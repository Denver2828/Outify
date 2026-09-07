package cc.tomko.outify.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PcmWriterTest {

    /**
     * Sink with a scripted response per write call. A response >= 0 is the number of bytes
     * to accept (capped at the request), a negative one is returned as-is. When the script
     * runs out, everything is accepted. Writing after release fails the test.
     */
    private class FakeSink(val name: String = "sink") : AudioSink {
        val script = ArrayDeque<Int>()
        val received = ByteArrayOutputStream()
        val calls = AtomicInteger()
        @Volatile var released = false

        /** When set, write blocks here until the latch opens (for the close race test). */
        @Volatile var blockOn: CountDownLatch? = null
        val entered = CountDownLatch(1)

        override fun write(buffer: ByteBuffer, sizeInBytes: Int): Int {
            if (released) throw AssertionError("write after release on $name")
            calls.incrementAndGet()
            entered.countDown()
            blockOn?.await(5, TimeUnit.SECONDS)
            val response = if (script.isEmpty()) sizeInBytes else script.removeFirst()
            if (response < 0) return response
            val take = minOf(response, sizeInBytes, buffer.remaining())
            repeat(take) { received.write(buffer.get().toInt()) }
            return take
        }

        override fun release() {
            released = true
        }
    }

    private class Harness(
        maxZero: Int = 3,
        maxRebuilds: Int = 3,
        windowMs: Long = 30_000,
        pendingCapacity: Int = 1024,
    ) {
        val events = mutableListOf<PcmWriter.Event>()
        val sleeps = mutableListOf<Long>()
        var now = 0L
        val rebuilt = mutableListOf<FakeSink>()
        var rebuildReturnsNull = false

        val writer = PcmWriter(
            rebuildSink = {
                if (rebuildReturnsNull) null
                else FakeSink("rebuilt-${rebuilt.size + 1}").also { rebuilt += it }
            },
            listener = { events += it },
            sleep = { sleeps += it },
            clock = { now },
            maxPendingBytes = pendingCapacity,
            maxConsecutiveZeroWrites = maxZero,
            zeroWriteBackoffMs = 7,
            maxRebuilds = maxRebuilds,
            rebuildWindowMs = windowMs,
        )
    }

    private fun bytes(n: Int, start: Int = 0): ByteArray = ByteArray(n) { (start + it).toByte() }

    private fun direct(data: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(data.size).also { it.put(data); it.flip() }

    @Test
    fun `partial write keeps the remainder and completes it before new samples`() {
        val h = Harness()
        val sink = FakeSink()
        h.writer.attach(sink)

        // First call: accept 4 of 10, then stall (zeros) so the remainder is retained.
        sink.script.addAll(listOf(4, 0, 0, 0))
        val first = h.writer.write(direct(bytes(10)), 10)
        assertEquals(PcmWriter.Outcome.STALLED, first)
        assertEquals(6, h.writer.snapshot().pendingBytes)
        assertEquals(1, h.writer.snapshot().partialWrites)

        // Second call: sink accepts everything; remainder must land before the new chunk.
        val second = h.writer.write(direct(bytes(5, start = 100)), 5)
        assertEquals(PcmWriter.Outcome.COMPLETE, second)
        assertEquals(0, h.writer.snapshot().pendingBytes)
        val expected = bytes(10) + bytes(5, start = 100)
        assertEquals(expected.toList(), sink.received.toByteArray().toList())
        assertEquals(0L, h.writer.snapshot().droppedBytes)
    }

    @Test
    fun `zero writes back off and stall after the limit without spinning`() {
        val h = Harness(maxZero = 3)
        val sink = FakeSink()
        h.writer.attach(sink)
        sink.script.addAll(listOf(0, 0, 0, 0, 0, 0))

        val outcome = h.writer.write(direct(bytes(8)), 8)

        assertEquals(PcmWriter.Outcome.STALLED, outcome)
        assertEquals(3, sink.calls.get())
        assertEquals(listOf(7L, 7L), h.sleeps) // backoff between zeros, none after the last
        val snap = h.writer.snapshot()
        assertEquals(3L, snap.zeroWrites)
        assertEquals(1L, snap.stalls)
        assertEquals(8, snap.pendingBytes)
        assertTrue(h.events.any { it is PcmWriter.Event.Stalled })
    }

    @Test
    fun `dead object rebuilds the sink and the pending samples reach the new one`() {
        val h = Harness()
        val dead = FakeSink("dead")
        h.writer.attach(dead)
        dead.script.addAll(listOf(3, AudioSinkErrors.ERROR_DEAD_OBJECT))

        val outcome = h.writer.write(direct(bytes(10)), 10)

        assertEquals(PcmWriter.Outcome.COMPLETE, outcome)
        assertTrue(dead.released)
        assertEquals(1, h.rebuilt.size)
        assertEquals(bytes(10).drop(3), h.rebuilt[0].received.toByteArray().toList())
        assertEquals(1L, h.writer.snapshot().rebuilds)
        assertTrue(h.events.any { it is PcmWriter.Event.SinkRebuilt })
        assertFalse(h.events.any { it is PcmWriter.Event.OutputFailure })
    }

    @Test
    fun `rebuild limit is respected and the failure is reported once`() {
        val h = Harness(maxRebuilds = 2, windowMs = 30_000)
        val first = FakeSink("first")
        h.writer.attach(first)

        first.script.add(AudioSinkErrors.ERROR_DEAD_OBJECT)
        assertEquals(PcmWriter.Outcome.COMPLETE, h.writer.write(direct(bytes(4)), 4))
        h.rebuilt[0].script.add(AudioSinkErrors.ERROR_DEAD_OBJECT)
        assertEquals(PcmWriter.Outcome.COMPLETE, h.writer.write(direct(bytes(4)), 4))
        assertEquals(2, h.rebuilt.size)

        // Third death inside the window: budget spent, no more rebuilds.
        h.now = 10_000
        h.rebuilt[1].script.add(AudioSinkErrors.ERROR_DEAD_OBJECT)
        assertEquals(PcmWriter.Outcome.DROPPED, h.writer.write(direct(bytes(4)), 4))
        assertEquals(2, h.rebuilt.size)
        assertTrue(h.rebuilt[1].released)
        assertEquals(1, h.events.count { it is PcmWriter.Event.OutputFailure })

        // Subsequent frames are dropped and counted, without touching any sink.
        assertEquals(PcmWriter.Outcome.DROPPED, h.writer.write(direct(bytes(4)), 4))
        assertEquals(1, h.events.count { it is PcmWriter.Event.OutputFailure })
        assertTrue(h.writer.snapshot().gaveUp)
        assertEquals(8L, h.writer.snapshot().droppedBytes)

        // A fresh attach (new format / explicit recreate) resets the budget.
        val fresh = FakeSink("fresh")
        h.writer.attach(fresh)
        assertEquals(PcmWriter.Outcome.COMPLETE, h.writer.write(direct(bytes(4)), 4))
        assertFalse(h.writer.snapshot().gaveUp)
    }

    @Test
    fun `rebuild window slides so old deaths do not count`() {
        val h = Harness(maxRebuilds = 1, windowMs = 1_000)
        val s = FakeSink()
        h.writer.attach(s)
        s.script.add(AudioSinkErrors.ERROR_DEAD_OBJECT)
        assertEquals(PcmWriter.Outcome.COMPLETE, h.writer.write(direct(bytes(2)), 2))

        h.now = 5_000 // beyond the window
        h.rebuilt[0].script.add(AudioSinkErrors.ERROR_DEAD_OBJECT)
        assertEquals(PcmWriter.Outcome.COMPLETE, h.writer.write(direct(bytes(2)), 2))
        assertEquals(2, h.rebuilt.size)
    }

    @Test
    fun `generic error retries once then fails, bad value fails immediately`() {
        val h = Harness()
        val s = FakeSink()
        h.writer.attach(s)

        s.script.addAll(listOf(AudioSinkErrors.ERROR, 4))
        assertEquals(PcmWriter.Outcome.COMPLETE, h.writer.write(direct(bytes(4)), 4))

        s.script.addAll(listOf(AudioSinkErrors.ERROR, AudioSinkErrors.ERROR))
        assertEquals(PcmWriter.Outcome.FAILED, h.writer.write(direct(bytes(4)), 4))
        assertEquals(0, h.writer.snapshot().pendingBytes)

        s.script.add(AudioSinkErrors.ERROR_BAD_VALUE)
        assertEquals(PcmWriter.Outcome.FAILED, h.writer.write(direct(bytes(4)), 4))
        assertEquals(1, s.calls.get() - 4) // one call for the bad-value write
        assertEquals(4L, h.writer.snapshot().writeErrors)
        assertEquals(AudioSinkErrors.ERROR_BAD_VALUE, h.writer.snapshot().lastWriteResult)
    }

    @Test
    fun `write after close is refused without touching the released sink`() {
        val h = Harness()
        val s = FakeSink()
        h.writer.attach(s)
        h.writer.close()
        assertTrue(s.released)

        val outcome = h.writer.write(direct(bytes(4)), 4)

        assertEquals(PcmWriter.Outcome.CLOSED, outcome)
        assertEquals(0, s.calls.get())
        assertFalse(h.writer.hasSink())
    }

    @Test
    fun `write without a sink is dropped and counted`() {
        val h = Harness()
        assertEquals(PcmWriter.Outcome.DROPPED, h.writer.write(direct(bytes(6)), 6))
        assertEquals(6L, h.writer.snapshot().droppedBytes)
    }

    @Test
    fun `discardPending drops the remainder on explicit flush`() {
        val h = Harness(maxZero = 1)
        val s = FakeSink()
        h.writer.attach(s)
        s.script.addAll(listOf(2, 0))
        assertEquals(PcmWriter.Outcome.STALLED, h.writer.write(direct(bytes(6)), 6))
        assertEquals(4, h.writer.snapshot().pendingBytes)

        h.writer.discardPending()

        assertEquals(0, h.writer.snapshot().pendingBytes)
        assertEquals(PcmWriter.Outcome.COMPLETE, h.writer.write(direct(bytes(3, start = 50)), 3))
        assertEquals((bytes(2) + bytes(3, start = 50)).toList(), s.received.toByteArray().toList())
    }

    @Test
    fun `pending store is bounded and overflow is counted as dropped`() {
        val h = Harness(maxZero = 1, pendingCapacity = 5)
        val s = FakeSink()
        h.writer.attach(s)
        s.script.add(0)
        assertEquals(PcmWriter.Outcome.STALLED, h.writer.write(direct(bytes(8)), 8))
        val snap = h.writer.snapshot()
        assertEquals(5, snap.pendingBytes)
        assertEquals(3L, snap.droppedBytes)
    }

    @Test
    fun `close during an in-flight write never writes to the released sink`() {
        val h = Harness()
        val s = FakeSink()
        h.writer.attach(s)
        val gate = CountDownLatch(1)
        s.blockOn = gate
        // The blocked call accepts a short count so the writer would normally retry.
        s.script.add(2)

        var outcome: PcmWriter.Outcome? = null
        val writerThread = Thread {
            outcome = h.writer.write(direct(bytes(10)), 10)
        }
        writerThread.start()
        assertTrue(s.entered.await(5, TimeUnit.SECONDS))

        val closer = Thread { h.writer.close() }
        closer.start()
        // Give close() a moment to raise the flag and block on the lock.
        Thread.sleep(50)
        assertFalse(s.released) // must not release while the write holds the lock

        gate.countDown()
        writerThread.join(5_000)
        closer.join(5_000)

        assertEquals(PcmWriter.Outcome.CLOSED, outcome)
        assertTrue(s.released)
        assertEquals(1, s.calls.get()) // no retry after the flag was raised
        assertEquals(0, h.writer.snapshot().pendingBytes)
        assertFalse(h.writer.hasSink())
    }

    @Test
    fun `listener events are delivered outside the lock so a listener may re-enter`() {
        // A listener that calls back into the writer from another thread would deadlock if
        // events were dispatched under the lock; snapshot() takes the same lock.
        var snapFromListener: PcmWriter.Snapshot? = null
        lateinit var self: PcmWriter
        self = PcmWriter(
            rebuildSink = { FakeSink("rebuilt") },
            listener = {
                val t = Thread { snapFromListener = self.snapshot() }
                t.start()
                t.join(5_000)
            },
            sleep = { },
            clock = { 0L },
        )
        val dead = FakeSink("dead").also { it.script.add(AudioSinkErrors.ERROR_DEAD_OBJECT) }
        self.attach(dead)

        assertEquals(PcmWriter.Outcome.COMPLETE, self.write(direct(bytes(3)), 3))

        assertEquals(1L, snapFromListener?.rebuilds)
    }
}
