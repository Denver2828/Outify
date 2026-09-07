package cc.tomko.outify.data.repository

import cc.tomko.outify.core.SpClientException
import cc.tomko.outify.data.metadata.NativeError
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException

/** What a failed sync step means for the caller. */
enum class SyncFailure {
    /** Spotify said 429: stop everything now, do not retry, wait for the window to pass. */
    RATE_LIMITED,

    /** Network hiccup or timeout: retrying shortly is reasonable. */
    TRANSIENT,

    /** Anything else (bad payload, session gone, logic error): fail fast. */
    FATAL,
}

/**
 * Pure classification of a sync exception. Kept free of Android types so it can be unit
 * tested.
 *
 * @param gateLimited whether the rate-limit gate is armed right now; a failure that lands
 *   while the gate is armed is treated as rate-limited even when the message says nothing.
 */
object SyncErrorClassifier {
    private val transientHints = listOf(
        "timeout", "timed out", "connection", "network", "reset", "unreachable",
        "broken pipe", "no address associated", "software caused", "eof",
    )

    private val rateLimitHints = listOf("rate limit", "rate limited", "too many requests")

    /**
     * A bare "429" is not enough: track ids, URIs and byte counts contain it all the time.
     * Only an HTTP status phrasing counts, e.g. "status 429", "status code: 429", "http 429".
     */
    private val status429 = Regex("""\b(?:status(?: code)?|http|code)\s*[:=]?\s*429\b""")

    fun classify(error: Throwable, gateLimited: Boolean): SyncFailure {
        if (error is SpClientException && error.error is NativeError.RateLimited) {
            return SyncFailure.RATE_LIMITED
        }
        val message = messageChain(error).lowercase()
        if (rateLimitHints.any { message.contains(it) } || status429.containsMatchIn(message)) {
            return SyncFailure.RATE_LIMITED
        }
        if (gateLimited) return SyncFailure.RATE_LIMITED

        if (error is TimeoutCancellationException) return SyncFailure.TRANSIENT
        if (error is IOException) return SyncFailure.TRANSIENT
        if (transientHints.any { message.contains(it) }) return SyncFailure.TRANSIENT

        return SyncFailure.FATAL
    }

    private fun messageChain(error: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            current.message?.let { parts.add(it) }
            current = current.cause
            depth++
        }
        return parts.joinToString(" | ")
    }
}
