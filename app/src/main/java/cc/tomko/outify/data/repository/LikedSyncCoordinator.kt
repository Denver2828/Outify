package cc.tomko.outify.data.repository

import android.util.Log
import cc.tomko.outify.core.RateLimitGate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs one full liked sync (tracks, then episodes, then shows). */
fun interface LikedSyncRunner {
    /** @return true when the track sync completed (the caller's success signal). */
    suspend fun run(force: Boolean, onProgress: (current: Int, total: Int) -> Unit): Boolean
}

/** Outcome of a [LikedSyncCoordinator.requestSync] call. */
sealed class SyncOutcome {
    /** The sync ran; [tracksSynced] mirrors the runner's result. */
    data class Ran(val tracksSynced: Boolean) : SyncOutcome()

    /** Another request was already running; this call waited for it and reused its work. */
    data object Coalesced : SyncOutcome()

    /** Skipped because a non-forced sync ran less than the debounce window ago. */
    data class SkippedDebounce(val remainingSeconds: Int) : SyncOutcome()

    /** Skipped because Spotify is rate limiting us; [remainingSeconds] until it lifts. */
    data class SkippedRateLimited(val remainingSeconds: Int) : SyncOutcome()
}

/**
 * The single entry point for liked-library syncs.
 *
 * Every screen and service that used to call the repository directly goes through here, so:
 * - concurrent requests coalesce into one run (a second caller waits for the first),
 * - non-forced requests are debounced (default 15 min between runs),
 * - nothing touches the network while [RateLimitGate] is armed, forced or not.
 *
 * Clock is injectable for tests. The last start time is handed to [persist] and read back
 * through [restore] on the first request, so a process restart does not re-sync a library
 * that was synced minutes ago: every launch used to pay three Web API bursts.
 */
class LikedSyncCoordinator(
    private val runner: LikedSyncRunner,
    private val gate: RateLimitGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val persist: suspend (Long) -> Unit = {},
    private val restore: suspend () -> Long? = { null },
) {
    private val mutex = Mutex()

    @Volatile
    private var lastSyncStartedMs: Long = 0L

    @Volatile
    private var restored = false

    private suspend fun restoreOnce() {
        if (restored) return
        restored = true
        val saved = runCatching { restore() }.getOrNull() ?: return
        if (saved > lastSyncStartedMs && saved <= clock()) lastSyncStartedMs = saved
    }

    suspend fun requestSync(
        reason: String,
        force: Boolean = false,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncOutcome {
        if (mutex.isLocked) {
            Log.i(TAG, "coalesced ($reason): waiting for the sync already running")
            mutex.withLock { }
            return SyncOutcome.Coalesced
        }

        return mutex.withLock {
            if (gate.isLimited()) {
                val left = gate.remainingSeconds()
                Log.i(TAG, "skipped ($reason): rate limited, ${left}s left")
                return@withLock SyncOutcome.SkippedRateLimited(left)
            }

            restoreOnce()
            val sinceLast = clock() - lastSyncStartedMs
            if (!force && lastSyncStartedMs != 0L && sinceLast < debounceMs) {
                val left = ((debounceMs - sinceLast + 999L) / 1000L).toInt()
                Log.i(TAG, "skipped ($reason): debounce, ${left}s left")
                return@withLock SyncOutcome.SkippedDebounce(left)
            }

            lastSyncStartedMs = clock()
            runCatching { persist(lastSyncStartedMs) }
            Log.i(TAG, "running ($reason, force=$force)")
            val tracksSynced = runner.run(force, onProgress)
            SyncOutcome.Ran(tracksSynced)
        }
    }

    companion object {
        private const val TAG = "LikedSync"
        const val SYNC_DEBOUNCE_MS = 15L * 60_000L
        const val DEFAULT_DEBOUNCE_MS = SYNC_DEBOUNCE_MS
    }
}
