package cc.tomko.outify.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val _untilMs = MutableStateFlow(0L)

    /** Wall-clock timestamp (ms) until which the Web API is off limits, 0 when it is not. */
    val untilMs: StateFlow<Long> = _untilMs.asStateFlow()

    /** Records a 429 whose Retry-After is [retryAfterSeconds] (defaults to 30 s). */
    fun noteRateLimited(retryAfterSeconds: Long?) {
        val seconds = (retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceAtLeast(1L)
        val until = clock() + seconds * 1000L
        if (until > _untilMs.value) {
            _untilMs.value = until
            Log.w(TAG, "Spotify rate limited, holding requests for $seconds s")
        }
    }

    /** Latest of the Kotlin-side and native-side windows, 0 when neither is active. */
    fun effectiveUntilMs(): Long {
        val native = runCatching { nativeUntilMs() }.getOrDefault(0L)
        if (native > _untilMs.value) _untilMs.value = native
        val until = _untilMs.value
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
        _untilMs.value = 0L
    }

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
