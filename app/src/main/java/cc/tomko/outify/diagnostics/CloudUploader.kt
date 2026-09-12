package cc.tomko.outify.diagnostics

import cc.tomko.outify.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal enum class CloudFailure { AUTH, SIZE, CAPACITY, UNAVAILABLE, NETWORK }
internal data class CloudReceipt(val id: String, val expiresAt: Long)
internal data class CloudResult(val receipt: CloudReceipt? = null, val failure: CloudFailure? = null)

internal object CloudUploader {
    val isConfigured: Boolean get() = validToken(BuildConfig.DIAGNOSTICS_UPLOAD_TOKEN)
    internal fun validToken(token: String) = Regex("[0-9a-fA-F]{64}").matches(token)
    const val DESTINATION = "https://spoty-diagnostics.hdarioburgos38.workers.dev"
    // Dedicated client: no interceptors, shared cookies, redirects or automatic replay.
    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()

    fun failure(code: Int) = when (code) {
        401 -> CloudFailure.AUTH
        413 -> CloudFailure.SIZE
        429 -> CloudFailure.CAPACITY
        else -> CloudFailure.UNAVAILABLE
    }

    fun receipt(body: String): CloudReceipt {
        val json = Json.parseToJsonElement(body).jsonObject
        val id = json.getValue("id").jsonPrimitive.content
        val expiry = json.getValue("expires_at").jsonPrimitive.long
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id))
        require(expiry in 1..253402300799L)
        return CloudReceipt(id, expiry)
    }

    suspend fun upload(report: CloudReport): CloudResult {
        if (!isConfigured) return CloudResult(failure = CloudFailure.UNAVAILABLE)
        val request = Request.Builder().url("$DESTINATION/reports")
            .header("Authorization", "Bearer ${BuildConfig.DIAGNOSTICS_UPLOAD_TOKEN}")
            .post(report.text.toRequestBody("text/plain; charset=utf-8".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(CloudResult(failure = CloudFailure.NETWORK))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        if (it.code != 201) CloudResult(failure = failure(it.code))
                        else try {
                            val source = it.body?.source() ?: throw IOException()
                            if (source.request(4097)) throw IOException()
                            CloudResult(receipt = receipt(source.readUtf8()))
                        } catch (_: Exception) {
                            CloudResult(failure = CloudFailure.UNAVAILABLE)
                        }
                    }
                    continuation.resume(result)
                }
            })
        }
    }
}
