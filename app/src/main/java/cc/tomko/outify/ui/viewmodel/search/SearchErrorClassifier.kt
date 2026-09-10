package cc.tomko.outify.ui.viewmodel.search

import java.io.IOException

/** Classifies transport evidence, never exposes a provider body as UI copy. */
object SearchErrorClassifier {
    private val status = Regex(
        """\b(?:failed with status|http(?: status(?: code)?)?)\s*[:=]?\s*(\d{3})\b"""
    )
    private val networkHints = listOf(
        "http request failed:", "timeout", "timed out", "connection", "network", "unreachable",
    )

    fun classify(error: Throwable, limited: Boolean): SearchErrorKind {
        val causes = generateSequence(error) { it.cause }.take(5).toList()
        val message = causes.joinToString(" | ") { it.message.orEmpty() }.lowercase()
        val renewalRejected = "refresh_token" in message &&
            listOf("invalid_client", "invalid_grant").any { it in message }
        if ("account token rejected, sign in again" in message || renewalRejected) {
            return SearchErrorKind.REJECTED
        }
        val code = status.find(message)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            code == 401 -> SearchErrorKind.AUTH
            "no account token present" in message || "token is none" in message ->
                SearchErrorKind.MISSING_ACCOUNT
            code == 403 -> SearchErrorKind.FORBIDDEN
            code == 400 -> SearchErrorKind.BAD_REQUEST
            code != null && code in 500..599 -> SearchErrorKind.SERVER
            code == 429 || "rate limited" in message || limited -> SearchErrorKind.RATE_LIMITED
            "json error:" in message || causes.any { it.javaClass.simpleName == "SerializationException" } ->
                SearchErrorKind.DECODING
            causes.any { it is IOException } || networkHints.any { it in message } ->
                SearchErrorKind.NETWORK
            else -> SearchErrorKind.OTHER
        }
    }
}
