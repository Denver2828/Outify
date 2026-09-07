package cc.tomko.outify.playback.audio

import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the [AudioSink] and decides what happens to every PCM chunk: partial writes keep
 * their remainder, zero-length writes back off instead of spinning, `ERROR_DEAD_OBJECT`
 * rebuilds the sink a bounded number of times, and a released sink is never written to.
 *
 * Pure Kotlin on purpose: [AudioEngine] adapts an AudioTrack into an [AudioSink] and
 * supplies [rebuildSink]; everything else is testable on the JVM.
 *
 * Threading: [write] is called from the native PCM thread; [close], [discardPending],
 * [attach] and [snapshot] from other threads. One lock guards the sink handle and the
 * pending buffer. Listener callbacks are collected under the lock and delivered after it
 * is released, so a listener may safely call back into the owner.
 *
 * Pending samples are only dropped on an explicit [discardPending] (flush/seek), on
 * [close] (stop/format change) or when the bounded pending buffer overflows, which is
 * counted in [Snapshot.droppedBytes].
 */
class PcmWriter(
    private val rebuildSink: () -> AudioSink?,
    private val listener: Listener,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPendingBytes: Int = DEFAULT_MAX_PENDING_BYTES,
    private val maxConsecutiveZeroWrites: Int = DEFAULT_MAX_CONSECUTIVE_ZERO_WRITES,
    private val zeroWriteBackoffMs: Long = DEFAULT_ZERO_WRITE_BACKOFF_MS,
    private val maxRebuilds: Int = DEFAULT_MAX_REBUILDS,
    private val rebuildWindowMs: Long = DEFAULT_REBUILD_WINDOW_MS,
) {
    companion object {
        const val DEFAULT_MAX_PENDING_BYTES = 256 * 1024
        const val DEFAULT_MAX_CONSECUTIVE_ZERO_WRITES = 20
        const val DEFAULT_ZERO_WRITE_BACKOFF_MS = 5L
        const val DEFAULT_MAX_REBUILDS = 3
        const val DEFAULT_REBUILD_WINDOW_MS = 30_000L
    }

    /** Result of one [write] call. Pending remainder is retained unless stated. */
    enum class Outcome {
        /** Every pending and incoming byte reached the sink. */
        COMPLETE,

        /** The sink kept returning zero; the remainder is retained for the next call. */
        STALLED,

        /** A non-recoverable error; the current chunk was dropped, pending cleared. */
        FAILED,

        /** No usable sink (none attached, rebuild budget exhausted); chunk dropped. */
        DROPPED,

        /** [close] raced the write; the remainder was discarded with the sink. */
        CLOSED,
    }

    sealed class Event {
        /** A partial or failing write; informational. */
        data class WriteProblem(val result: Int, val message: String) : Event()

        /** The sink was replaced after ERROR_DEAD_OBJECT. [attempt] is 1-based within the window. */
        data class SinkRebuilt(val attempt: Int) : Event()

        /** Recovery gave up; the owner should warn the user once. */
        data class OutputFailure(val reason: String) : Event()

        /** The sink stalled ([maxConsecutiveZeroWrites] zero writes in a row). */
        data class Stalled(val pendingBytes: Int) : Event()
    }

    fun interface Listener {
        fun onEvent(event: Event)
    }

    data class Snapshot(
        val bytesWritten: Long,
        val writeErrors: Long,
        val partialWrites: Long,
        val zeroWrites: Long,
        val stalls: Long,
        val rebuilds: Long,
        val droppedBytes: Long,
        val lastWriteResult: Int,
        val pendingBytes: Int,
        val gaveUp: Boolean,
        val hasSink: Boolean,
    )

    private val lock = ReentrantLock()

    @Volatile
    private var closed = false

    private var sink: AudioSink? = null

    /** Heap buffer holding samples the sink did not accept yet, in write order. */
    private val pending: ByteBuffer = ByteBuffer.allocate(maxPendingBytes).also { it.limit(0) }

    private val rebuildTimestamps = ArrayDeque<Long>()
    private var gaveUp = false

    @Volatile private var bytesWritten = 0L
    @Volatile private var writeErrors = 0L
    @Volatile private var partialWrites = 0L
    @Volatile private var zeroWrites = 0L
    @Volatile private var stalls = 0L
    @Volatile private var rebuilds = 0L
    @Volatile private var droppedBytes = 0L
    @Volatile private var lastWriteResult = 0

    /**
     * Installs [newSink] as the output, releasing any previous one. Pending samples are
     * kept: the caller decides via [discardPending] whether a format change invalidates them.
     * Reopens the writer after [close] and resets the rebuild budget.
     */
    fun attach(newSink: AudioSink) {
        var old: AudioSink? = null
        lock.withLock {
            old = sink
            sink = newSink
            closed = false
            gaveUp = false
            rebuildTimestamps.clear()
        }
        old?.let { runCatching { it.release() } }
    }

    /** Drops retained samples. Use on flush/seek, where continuing old audio is wrong. */
    fun discardPending() {
        lock.withLock { clearPending() }
    }

    /**
     * Releases the sink and drops pending samples (explicit stop). Safe to call while a
     * write is in flight: the flag is raised first so the writer stops touching the sink as
     * soon as its current call returns, and the sink is only released once the lock is held.
     */
    fun close() {
        closed = true
        var toRelease: AudioSink? = null
        lock.withLock {
            toRelease = sink
            sink = null
            clearPending()
        }
        toRelease?.let { runCatching { it.release() } }
    }

    fun hasSink(): Boolean = lock.withLock { sink != null }

    fun snapshot(): Snapshot = lock.withLock {
        Snapshot(
            bytesWritten = bytesWritten,
            writeErrors = writeErrors,
            partialWrites = partialWrites,
            zeroWrites = zeroWrites,
            stalls = stalls,
            rebuilds = rebuilds,
            droppedBytes = droppedBytes,
            lastWriteResult = lastWriteResult,
            pendingBytes = pending.remaining(),
            gaveUp = gaveUp,
            hasSink = sink != null,
        )
    }

    /**
     * Writes any retained remainder, then [size] bytes from [buffer]'s current position.
     * The buffer position is advanced by what the sink accepted; the caller may reuse the
     * buffer afterwards because unwritten bytes are copied into the pending store.
     */
    fun write(buffer: ByteBuffer, size: Int): Outcome {
        val events = ArrayList<Event>(2)
        val outcome = lock.withLock { writeLocked(buffer, size, events) }
        events.forEach(listener::onEvent)
        return outcome
    }

    private fun writeLocked(buffer: ByteBuffer, size: Int, events: MutableList<Event>): Outcome {
        val chunkLimit = minOf(buffer.position() + size, buffer.limit())
        val chunk = buffer.duplicate().also { it.limit(chunkLimit) }

        if (closed) {
            droppedBytes += chunk.remaining()
            return Outcome.CLOSED
        }
        if (sink == null || gaveUp) {
            droppedBytes += chunk.remaining()
            buffer.position(chunkLimit)
            return Outcome.DROPPED
        }

        if (pending.hasRemaining()) {
            val pendingResult = drain(pending, events)
            if (pendingResult != DrainResult.COMPLETE) {
                // Keep the still-pending part first, then queue the new chunk behind it.
                return settle(pendingResult, chunk, buffer, chunkLimit, events)
            }
            pending.limit(0)
        }

        val result = drain(chunk, events)
        return settle(result, chunk, buffer, chunkLimit, events)
    }

    private fun settle(
        result: DrainResult,
        chunk: ByteBuffer,
        buffer: ByteBuffer,
        chunkLimit: Int,
        events: MutableList<Event>,
    ): Outcome {
        val outcome = when (result) {
            DrainResult.COMPLETE -> {
                buffer.position(chunkLimit)
                Outcome.COMPLETE
            }
            DrainResult.STALLED -> {
                retain(chunk)
                buffer.position(chunkLimit)
                stalls++
                events += Event.Stalled(pending.remaining())
                Outcome.STALLED
            }
            DrainResult.FAILED -> {
                droppedBytes += chunk.remaining() + pending.remaining()
                clearPending()
                buffer.position(chunkLimit)
                Outcome.FAILED
            }
            DrainResult.GAVE_UP -> {
                droppedBytes += chunk.remaining() + pending.remaining()
                clearPending()
                buffer.position(chunkLimit)
                Outcome.DROPPED
            }
            DrainResult.CLOSED -> {
                droppedBytes += chunk.remaining() + pending.remaining()
                clearPending()
                buffer.position(chunkLimit)
                Outcome.CLOSED
            }
        }
        return outcome
    }

    private enum class DrainResult { COMPLETE, STALLED, FAILED, GAVE_UP, CLOSED }

    /**
     * Pushes [buf] into the sink until it is empty or the policy says stop. Retries a generic
     * ERROR once, rebuilds on DEAD_OBJECT within budget, backs off on zero writes.
     */
    private fun drain(buf: ByteBuffer, events: MutableList<Event>): DrainResult {
        var zeroStreak = 0
        var genericRetried = false
        while (buf.hasRemaining()) {
            if (closed) return DrainResult.CLOSED
            val current = sink ?: return DrainResult.GAVE_UP
            val requested = buf.remaining()
            val written = current.write(buf, requested)
            lastWriteResult = written
            when {
                written > 0 -> {
                    bytesWritten += written
                    zeroStreak = 0
                    if (written < requested) {
                        partialWrites++
                        events += Event.WriteProblem(written, "partial write $written/$requested")
                    }
                }
                written == 0 -> {
                    zeroWrites++
                    zeroStreak++
                    if (zeroStreak >= maxConsecutiveZeroWrites) return DrainResult.STALLED
                    sleep(zeroWriteBackoffMs)
                }
                written == AudioSinkErrors.ERROR_DEAD_OBJECT -> {
                    writeErrors++
                    events += Event.WriteProblem(written, "sink is dead, rebuilding")
                    if (!rebuild(events)) return DrainResult.GAVE_UP
                }
                written == AudioSinkErrors.ERROR -> {
                    writeErrors++
                    if (genericRetried) {
                        events += Event.WriteProblem(written, "generic error persisted after retry")
                        return DrainResult.FAILED
                    }
                    genericRetried = true
                    events += Event.WriteProblem(written, "generic error, retrying once")
                }
                else -> {
                    // ERROR_BAD_VALUE, ERROR_INVALID_OPERATION and anything unknown: not transient.
                    writeErrors++
                    events += Event.WriteProblem(written, "non-transient error $written")
                    return DrainResult.FAILED
                }
            }
        }
        return DrainResult.COMPLETE
    }

    /** Replaces the sink after ERROR_DEAD_OBJECT. Returns false once the budget is spent. */
    private fun rebuild(events: MutableList<Event>): Boolean {
        val now = clock()
        while (rebuildTimestamps.isNotEmpty() && now - rebuildTimestamps.peekFirst() > rebuildWindowMs) {
            rebuildTimestamps.removeFirst()
        }
        val dead = sink
        sink = null
        dead?.let { runCatching { it.release() } }

        if (rebuildTimestamps.size >= maxRebuilds) {
            gaveUp = true
            events += Event.OutputFailure("audio output died $maxRebuilds times within ${rebuildWindowMs / 1000}s")
            return false
        }
        rebuildTimestamps.addLast(now)
        val replacement = rebuildSink()
        if (replacement == null) {
            gaveUp = true
            events += Event.OutputFailure("could not recreate the audio output")
            return false
        }
        sink = replacement
        rebuilds++
        events += Event.SinkRebuilt(rebuildTimestamps.size)
        return true
    }

    /** Appends the unwritten part of [chunk] to the pending store, bounded by capacity. */
    private fun retain(chunk: ByteBuffer) {
        if (!chunk.hasRemaining()) return
        // Move the still-pending bytes to the front, then append.
        pending.compact()
        val room = pending.remaining()
        val take = minOf(room, chunk.remaining())
        if (take < chunk.remaining()) {
            droppedBytes += (chunk.remaining() - take).toLong()
        }
        val slice = chunk.duplicate().also { it.limit(chunk.position() + take) }
        pending.put(slice)
        pending.flip()
    }

    private fun clearPending() {
        pending.clear()
        pending.limit(0)
    }
}
