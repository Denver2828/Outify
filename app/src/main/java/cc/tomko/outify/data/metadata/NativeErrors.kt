package cc.tomko.outify.data.metadata

import android.util.Log
import cc.tomko.outify.core.RateLimitGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

sealed class NativeError {
    abstract val message: String
    abstract val type: String

    data class RateLimited(
        override val message: String,
        val retryAfterSeconds: Long? = null
    ) : NativeError() {
        override val type: String = "rate_limit"
    }

    data class AuthenticationError(
        override val message: String
    ) : NativeError() {
        override val type: String = "authentication_error"
    }

    data class ServiceUnavailable(
        override val message: String
    ) : NativeError() {
        override val type: String = "service_unavailable"
    }

    data class Unknown(
        override val message: String,
        override val type: String = "unknown"
    ) : NativeError()

    companion object {
        private val retryAfterPattern = Regex("""rate limited, retry after (\d+) s""", RegexOption.IGNORE_CASE)

        /**
         * Classifies a plain exception message coming out of the native layer. JNI functions
         * that throw (saved items, episode details, ...) carry the Rust error's Display text,
         * which for a 429 reads "rate limited, retry after N s".
         */
        fun fromMessage(message: String): NativeError {
            retryAfterPattern.find(message)?.let { match ->
                return RateLimited(message, match.groupValues[1].toLongOrNull())
            }
            return fromJson("unknown", message)
        }

        fun fromJson(type: String, message: String, retryAfterSeconds: Long? = null): NativeError {
            return when (type) {
                "rate_limit", "rate_limited" -> RateLimited(message, retryAfterSeconds)
                "authentication_error", "authentication" -> AuthenticationError(message)
                "service_unavailable", "unavailable" -> ServiceUnavailable(message)
                "unknown" -> {
                    val lowerMessage = message.lowercase()
                    when {
                        lowerMessage.contains("token") && lowerMessage.contains("credentials") ->
                            AuthenticationError(message)

                        lowerMessage.contains("service unavailable") ->
                            ServiceUnavailable(message)

                        else -> Unknown(message, type)
                    }
                }

                else -> Unknown(message, type)
            }
        }
    }
}

object NativeErrorHandler {
    private const val TAG = "NativeErrorHandler"
    private val scope = CoroutineScope(Dispatchers.Main)

    interface ErrorCallback {
        fun onError(error: NativeError)
    }

    private var callbacks = mutableListOf<ErrorCallback>()

    fun registerCallback(callback: ErrorCallback) {
        callbacks.add(callback)
    }

    fun unregisterCallback(callback: ErrorCallback) {
        callbacks.remove(callback)
    }

    fun handleError(error: NativeError, context: String = "") {
        val fullMessage = if (context.isNotEmpty()) "$context: ${error.message}" else error.message

        Log.e(TAG, "Native error [${error.type}]: $fullMessage")

        scope.launch {
            callbacks.forEach { it.onError(error) }
        }

        when (error) {
            is NativeError.AuthenticationError -> handleAuthError(error)
            is NativeError.RateLimited -> handleRateLimit(error)
            is NativeError.ServiceUnavailable -> handleServiceUnavailable(error)
            is NativeError.Unknown -> handleUnknownError(error)
        }
    }

    fun handleErrorJson(jsonString: String, context: String = ""): NativeError? {
        return try {
            val obj = org.json.JSONObject(jsonString)
            if (obj.has("error")) {
                val err = obj.getJSONObject("error")
                val type = err.optString("type", "unknown")
                val message = err.optString("message", "Unknown error")
                val retryAfter =
                    if (err.has("retry_after_seconds")) err.getLong("retry_after_seconds") else null

                val error = NativeError.fromJson(type, message, retryAfter)
                handleError(error, context)
                error
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse error JSON: $jsonString", e)
            null
        }
    }

    private fun handleAuthError(error: NativeError.AuthenticationError) {
        Log.w(TAG, "Authentication error: ${error.message}")
    }

    private fun handleRateLimit(error: NativeError.RateLimited) {
        Log.w(TAG, "Rate limited: ${error.message}, retry after ${error.retryAfterSeconds}s")
        RateLimitGate.shared.noteRateLimited(error.retryAfterSeconds)
    }

    private fun handleServiceUnavailable(error: NativeError.ServiceUnavailable) {
        Log.w(TAG, "Service unavailable: ${error.message}")
    }

    private fun handleUnknownError(error: NativeError.Unknown) {
        Log.w(TAG, "Unknown native error: ${error.message}")
    }
}
