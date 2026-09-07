package cc.tomko.outify.core

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet
import kotlin.math.max

/**
 * Process-wide record of the last Spotify Web API 429.
 *
 * Every caller that is about to hit api.spotify.com asks [isLimited] first and stays quiet
 * until the window expires. The window is fed from two sides: the native layer arms it the
 * moment it sees a 429 (read back through [nativeUntilMs]) and Kotlin arms it when an error
 * payload with `retry_after_seconds` is parsed ([noteRateLimited]). Whichever expires later
 * wins.
 *
 * The clock and the native reader are injectable so the logic is unit-testable.
 */
class RateLimitGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val nativeUntilMs: () -> Long = { 0L },
    persist: (Long) -> Unit = {},
    restore: () -> Long? = { null },
) {
    private val _untilMs = MutableStateFlow(0L)

    @Volatile
    private var persist: (Long) -> Unit = persist

    @Volatile
    private var restore: () -> Long? = restore

    @Volatile
    private var restored = false

    /**
     * Attaches durable storage after construction. [shared] is created before Hilt exists,
     * so the application wires the DataStore-backed writer here once; [write] receives every
     * new window end and 0 on [reset]. The saved value is handed back through [restoreFrom]
     * from a background coroutine, so no lookup ever blocks on disk.
     */
    fun attachPersistence(write: (Long) -> Unit) {
        persist = write
    }

    /** Adopts a window end read from storage, when it is still in the future and later than ours. */
    fun restoreFrom(savedUntilMs: Long) {
        restored = true
        if (savedUntilMs <= clock()) return
        val previous = _untilMs.getAndUpdate { max(it, savedUntilMs) }
        if (savedUntilMs > previous) {
            Log.i(TAG, "Restored a Spotify rate-limit window ending in ${(savedUntilMs - clock()) / 1000L} s")
        }
    }

    private fun restoreOnce() {
        if (restored) return
        val saved = runCatching { restore() }.getOrNull()
        if (saved == null) {
            restored = true
            return
        }
        restoreFrom(saved)
    }

    /** Wall-clock timestamp (ms) until which the Web API is off limits, 0 when it is not. */
    val untilMs: StateFlow<Long> = _untilMs.asStateFlow()

    /** Records a 429 whose Retry-After is [retryAfterSeconds] (defaults to 30 s). */
    fun noteRateLimited(retryAfterSeconds: Long?) {
        val seconds = (retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceAtLeast(1L)
        val until = clock() + seconds * 1000L
        restoreOnce()
        val previous = _untilMs.getAndUpdate { max(it, until) }
        if (until > previous) {
            runCatching { persist(until) }
            Log.w(TAG, "Spotify rate limited, holding requests for $seconds s")
        }
    }

    /** Latest of the Kotlin-side and native-side windows, 0 when neither is active. */
    fun effectiveUntilMs(): Long {
        restoreOnce()
        val native = runCatching { nativeUntilMs() }.getOrDefault(0L)
        val until = _untilMs.updateAndGet { max(it, native) }
        return if (until > clock()) until else 0L
    }

    fun isLimited(): Boolean = effectiveUntilMs() > 0L

    /** Whole seconds left in the window, 0 when not limited. */
    fun remainingSeconds(): Int {
        val until = effectiveUntilMs()
        if (until == 0L) return 0
        return max(1L, (until - clock() + 999L) / 1000L).toInt()
    }

    /** Clears the window; used by tests and after a logout. */
    fun reset() {
        restored = true
        _untilMs.value = 0L
        runCatching { persist(0L) }
    }

    /**
     * [remainingSeconds] sampled every [tickMs], deduplicated. Screens turn it into a
     * countdown next to their "rate limited" notice; it emits 0 as soon as the window closes.
     */
    fun remainingSecondsFlow(tickMs: Long = 1_000L): Flow<Int> = flow {
        while (true) {
            emit(remainingSeconds())
            delay(tickMs)
        }
    }.distinctUntilChanged()

    companion object {
        private const val TAG = "RateLimitGate"
        const val DEFAULT_RETRY_AFTER_SECONDS = 30L

        /**
         * Single shared instance. [cc.tomko.outify.data.metadata.NativeErrorHandler] is an
         * `object` without injection, so it reaches the gate through here; Hilt hands out the
         * same instance to everything else.
         */
        val shared: RateLimitGate by lazy {
            RateLimitGate(nativeUntilMs = SpClient::rateLimitUntilMsOrZero)
        }
    }
}
