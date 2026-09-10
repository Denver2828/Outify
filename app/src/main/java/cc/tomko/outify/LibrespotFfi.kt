package cc.tomko.outify

import android.content.Context

fun interface RateLimitCallback {
    fun onRateLimit(untilMs: Long)
}

object LibrespotFfi {

    @JvmStatic
    external fun libInit(
        context: Context,
        clientId: String,
        clientSecret: String,
        rateLimitUntilMs: Long,
        rateLimitCallback: RateLimitCallback,
    )

    @JvmStatic
    external fun updateClientCredentials(clientId: String, clientSecret: String)
}
