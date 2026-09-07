package cc.tomko.outify.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitGateTest {

    private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun `fresh gate is not limited`() {
        val gate = RateLimitGate(clock = FakeClock())

        assertFalse(gate.isLimited())
        assertEquals(0, gate.remainingSeconds())
        assertEquals(0L, gate.effectiveUntilMs())
    }

    @Test
    fun `noteRateLimited arms the window for the given seconds`() {
        val clock = FakeClock()
        val gate = RateLimitGate(clock = clock)

        gate.noteRateLimited(10)

        assertTrue(gate.isLimited())
        assertEquals(10, gate.remainingSeconds())
        assertEquals(clock.now + 10_000L, gate.effectiveUntilMs())
    }

    @Test
    fun `window expires with the clock`() {
        val clock = FakeClock()
        val gate = RateLimitGate(clock = clock)
        gate.noteRateLimited(5)

        clock.now += 4_999L
        assertTrue(gate.isLimited())
        assertEquals(1, gate.remainingSeconds())

        clock.now += 1L
        assertFalse(gate.isLimited())
        assertEquals(0, gate.remainingSeconds())
    }

    @Test
    fun `null retry-after falls back to the default and never shrinks an active window`() {
        val clock = FakeClock()
        val gate = RateLimitGate(clock = clock)

        gate.noteRateLimited(120)
        gate.noteRateLimited(null)

        assertEquals(120, gate.remainingSeconds())

        val fresh = RateLimitGate(clock = clock)
        fresh.noteRateLimited(null)
        assertEquals(RateLimitGate.DEFAULT_RETRY_AFTER_SECONDS.toInt(), fresh.remainingSeconds())
    }

    @Test
    fun `native window is honoured when it is later than the local one`() {
        val clock = FakeClock()
        var native = 0L
        val gate = RateLimitGate(clock = clock, nativeUntilMs = { native })

        assertFalse(gate.isLimited())

        native = clock.now + 45_000L
        assertTrue(gate.isLimited())
        assertEquals(45, gate.remainingSeconds())

        // A shorter local note does not shrink the native window.
        gate.noteRateLimited(3)
        assertEquals(45, gate.remainingSeconds())
    }

    @Test
    fun `a throwing native reader is treated as no native window`() {
        val gate = RateLimitGate(clock = FakeClock(), nativeUntilMs = { throw UnsatisfiedLinkError("old .so") })

        assertFalse(gate.isLimited())
    }

    @Test
    fun `reset clears the window`() {
        val gate = RateLimitGate(clock = FakeClock())
        gate.noteRateLimited(30)

        gate.reset()

        assertFalse(gate.isLimited())
    }

    @Test
    fun `remainingSecondsFlow counts down to zero and dedupes`() = runBlocking {
        val clock = FakeClock()
        val gate = RateLimitGate(clock = clock)
        gate.noteRateLimited(2)

        // Every tick moves the clock forward one second, so the flow sees 2, 1, 0.
        val seen = gate.remainingSecondsFlow(tickMs = 1L)
            .onEach { clock.now += 1_000L }
            .take(3)
            .toList()

        assertEquals(listOf(2, 1, 0), seen)
    }

    @Test
    fun `remainingSecondsFlow starts at zero when not limited`() = runBlocking {
        val gate = RateLimitGate(clock = FakeClock())

        assertEquals(0, gate.remainingSecondsFlow(tickMs = 1L).first())
    }
}
