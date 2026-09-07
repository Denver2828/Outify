package cc.tomko.outify.data.metadata

import cc.tomko.outify.data.repository.SyncErrorClassifier
import cc.tomko.outify.data.repository.SyncFailure

/**
 * Why a background refresh did not produce fresh data. The cached content, when there is
 * some, stays on screen in every case; this only drives the notice shown next to it.
 */
enum class RefreshFailure {
    /** Connection problem or timeout: the remote may simply be unreachable right now. */
    NETWORK,

    /** Spotify answered 429; [cc.tomko.outify.core.RateLimitGate] knows for how long. */
    RATE_LIMITED,

    /** Anything else (bad payload, session gone, logic error). */
    OTHER;

    companion object {
        /**
         * Maps a refresh exception to the notice kind. [gateLimited] is the rate-limit gate
         * state at the time of the failure; a failure that lands while it is armed is
         * reported as rate limited even when the exception itself says nothing.
         */
        fun classify(error: Throwable, gateLimited: Boolean): RefreshFailure {
            if (error is RateLimitException) return RATE_LIMITED
            if (error is NativeOperationException) {
                when (error.error) {
                    is NativeError.RateLimited -> return RATE_LIMITED
                    is NativeError.ServiceUnavailable -> return NETWORK
                    else -> Unit
                }
            }
            return when (SyncErrorClassifier.classify(error, gateLimited)) {
                SyncFailure.RATE_LIMITED -> RATE_LIMITED
                SyncFailure.TRANSIENT -> NETWORK
                SyncFailure.FATAL -> OTHER
            }
        }
    }
}
