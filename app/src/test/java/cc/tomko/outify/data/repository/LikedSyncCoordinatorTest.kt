package cc.tomko.outify.data.repository

import cc.tomko.outify.core.RateLimitGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LikedSyncCoordinatorTest {

    private class FakeClock(var now: Long = 10_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private class CountingRunner(private val result: Boolean = true) : LikedSyncRunner {
        var runs = 0
        var lastForce = false
        override suspend fun run(force: Boolean, onProgress: (Int, Int) -> Unit): Boolean {
            runs++
            lastForce = force
            onProgress(1, 1)
            return result
        }
    }

    private fun coordinator(
        runner: LikedSyncRunner,
        clock: FakeClock,
        gate: RateLimitGate = RateLimitGate(clock = clock),
    ) = LikedSyncCoordinator(runner, gate, clock, debounceMs = 60_000L)

    @Test
    fun `first request runs and reports the runner result`() = runBlocking {
        val runner = CountingRunner(result = true)
        val outcome = coordinator(runner, FakeClock()).requestSync("test")

        assertEquals(SyncOutcome.Ran(tracksSynced = true), outcome)
        assertEquals(1, runner.runs)
    }

    @Test
    fun `second request inside the debounce window is skipped`() = runBlocking {
        val clock = FakeClock()
        val runner = CountingRunner()
        val coordinator = coordinator(runner, clock)

        coordinator.requestSync("first")
        clock.now += 18_000L
        val outcome = coordinator.requestSync("second")

        assertEquals(SyncOutcome.SkippedDebounce(remainingSeconds = 42), outcome)
        assertEquals(1, runner.runs)
    }

    @Test
    fun `request after the debounce window runs again`() = runBlocking {
        val clock = FakeClock()
        val runner = CountingRunner()
        val coordinator = coordinator(runner, clock)

        coordinator.requestSync("first")
        clock.now += 60_000L
        val outcome = coordinator.requestSync("second")

        assertTrue(outcome is SyncOutcome.Ran)
        assertEquals(2, runner.runs)
    }

    @Test
    fun `forced request ignores the debounce and passes force to the runner`() = runBlocking {
        val clock = FakeClock()
        val runner = CountingRunner()
        val coordinator = coordinator(runner, clock)

        coordinator.requestSync("first")
        clock.now += 1_000L
        val outcome = coordinator.requestSync("forced", force = true)

        assertTrue(outcome is SyncOutcome.Ran)
        assertEquals(2, runner.runs)
        assertTrue(runner.lastForce)
    }

    @Test
    fun `rate limited gate skips even a forced request and never calls the runner`() = runBlocking {
        val clock = FakeClock()
        val gate = RateLimitGate(clock = clock)
        gate.noteRateLimited(25)
        val runner = CountingRunner()
        val coordinator = coordinator(runner, clock, gate)

        val outcome = coordinator.requestSync("forced", force = true)

        assertEquals(SyncOutcome.SkippedRateLimited(remainingSeconds = 25), outcome)
        assertEquals(0, runner.runs)
    }

    @Test
    fun `concurrent request coalesces into the running one`() = runBlocking {
        val clock = FakeClock()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val runner = LikedSyncRunner { _, _ ->
            runs++
            release.await()
            true
        }
        val coordinator = coordinator(runner, clock)

        coroutineScope {
            val first = async { coordinator.requestSync("first") }
            // Let the first request take the lock and block inside the runner.
            while (runs == 0) yield()
            val second = async { coordinator.requestSync("second") }
            yield()
            release.complete(Unit)

            assertEquals(SyncOutcome.Ran(tracksSynced = true), first.await())
            assertEquals(SyncOutcome.Coalesced, second.await())
        }
        assertEquals(1, runs)
    }
}
