package cc.tomko.outify.updates

import cc.tomko.outify.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** No authentication, automatic redirects, replay, or dependency on Spotify initialization. */
internal class GitHubReleaseClient(private val calls: Call.Factory = transport()) {
    companion object {
        const val ENDPOINT = "https://api.github.com/repos/Denver2828/Outify/releases/latest"
        const val MAX_BODY_BYTES = 256L * 1024
        internal fun transport() = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()
    }

    suspend fun check(installedName: String = BuildConfig.VERSION_NAME,
        installedCode: Int = BuildConfig.VERSION_CODE): UpdateCheck = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(ENDPOINT)
            .header("User-Agent", "Spoty-Update-Checker")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").build()
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(UpdateCheck.Network)
            }
            override fun onResponse(call: Call, response: Response) {
                val outcome = response.use {
                    try {
                        when {
                            it.code == 429 || it.code == 403 && it.header("X-RateLimit-Remaining") == "0" -> {
                                val reset = it.header("X-RateLimit-Reset")?.toLongOrNull()
                                    ?.takeIf { value -> value in 1..253402300799L }
                                UpdateCheck.RateLimited(reset)
                            }
                            it.code != 200 -> UpdateCheck.Service
                            else -> {
                                val source = it.body?.source()
                                if (source == null || source.request(MAX_BODY_BYTES + 1)) UpdateCheck.Invalid
                                else ReleasePolicy.evaluate(source.readUtf8(), installedName, installedCode)
                            }
                        }
                    } catch (_: IOException) { UpdateCheck.Network }
                      catch (_: Exception) { UpdateCheck.Invalid }
                }
                continuation.resume(outcome)
            }
        })
    }
}
