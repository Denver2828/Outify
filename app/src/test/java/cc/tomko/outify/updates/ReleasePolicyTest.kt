package cc.tomko.outify.updates

import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReleasePolicyTest {
    private fun payload(tag: String = "v1.7.19") = """{
      "tag_name":"$tag","draft":false,"prerelease":false,"assets":[{
      "name":"app-arm64-v8a-release.apk","size":21821015,
      "browser_download_url":"https://github.com/Denver2828/Outify/releases/download/$tag/app-arm64-v8a-release.apk",
      "content_type":"application/vnd.android.package-archive","digest":"sha256:${"a".repeat(64)}"}]}"""
    private fun evaluate(body: String) = ReleasePolicy.evaluate(body, "1.7.18", 20718)

    @Test fun acceptsExactAssetAndComparesNumericVersions() {
        val result = evaluate(payload()) as UpdateCheck.Available
        assertEquals(20719, result.release.versionCode)
        assertEquals("1.7.19", result.release.versionName)
        assertEquals(21821015, result.release.bytes.toInt())
        assertTrue(evaluate(payload("v1.10.0")) is UpdateCheck.Available)
        assertEquals(UpdateCheck.NoUpdate, evaluate(payload("v1.7.18")))
        assertEquals(UpdateCheck.NoUpdate, evaluate(payload("v1.6.99")))
    }

    @Test fun rejectsMalformedOverflowAndAmbiguousVersions() {
        listOf("1.7.19", "v01.7.19", "v1.7.19-beta", "v1.7.19+build", "v1.100.0",
            "v1.0.100", "v999999999999999999999.0.0", "v214747.0.0").forEach {
            assertEquals(it, UpdateCheck.Invalid, evaluate(payload(it)))
        }
        assertEquals(UpdateCheck.Invalid, ReleasePolicy.evaluate(payload(), "1.7.18", 42))
    }

    @Test fun rejectsNonPublicAndWrongOrUnboundedAssets() {
        val original = payload()
        listOf(original.replace("\"draft\":false", "\"draft\":true"),
            original.replace("\"prerelease\":false", "\"prerelease\":true"),
            original.replace("app-arm64-v8a-release.apk", "app-armeabi-v7a-release.apk"),
            original.replace("21821015", "0"), original.replace("21821015", "104857601"),
            original.replace("application/vnd.android.package-archive", "text/html"),
            original.replace("sha256:", "md5:"), original.replace("\"draft\":false,", ""),
            "{}").forEach { assertEquals(UpdateCheck.Invalid, evaluate(it)) }
    }

    @Test fun rejectsUntrustedAssetLocations() {
        listOf("http://github.com", "https://github.com.evil.test", "https://evil@github.com",
            "https://github.com:444").forEach {
            assertEquals(UpdateCheck.Invalid, evaluate(payload().replace("https://github.com", it)))
        }
        assertEquals(UpdateCheck.Invalid, evaluate(payload().replace("Denver2828/Outify", "other/Outify")))
        assertEquals(UpdateCheck.Invalid, evaluate(payload().replace(".apk\",", ".apk?token=x\",")))
    }

    @Test fun dedicatedTransportHasBoundedCredentialFreePolicy() {
        val client = GitHubReleaseClient.transport()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(20000, client.callTimeoutMillis)
        assertTrue(client.interceptors.isEmpty())
    }

    private fun check(code: Int, body: String = payload(), reset: String? = null,
        remaining: String? = null): UpdateCheck = runBlocking {
        val transport = GitHubReleaseClient.transport().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(GitHubReleaseClient.ENDPOINT, request.url.toString())
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            assertEquals("Spoty-Update-Checker", request.header("User-Agent"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .apply { reset?.let { header("X-RateLimit-Reset", it) }; remaining?.let { header("X-RateLimit-Remaining", it) } }
                .body(body.toResponseBody()).build()
        }.build()
        GitHubReleaseClient(transport).check("1.7.18", 20718)
    }

    @Test fun realClientParsesBoundedResponseAndSafeStatuses() {
        assertTrue(check(200) is UpdateCheck.Available)
        assertEquals(UpdateCheck.Invalid, check(200, "x".repeat(262145)))
        assertEquals(UpdateCheck.Invalid, check(200, "not JSON"))
        assertEquals(UpdateCheck.Service, check(302, "sensitive server response"))
        assertEquals(UpdateCheck.Service, check(503))
        assertEquals(UpdateCheck.Service, check(403))
        assertEquals(UpdateCheck.RateLimited(123456), check(403, reset = "123456", remaining = "0"))
        assertEquals(UpdateCheck.RateLimited(null), check(429, reset = "not a time"))
        assertEquals(UpdateCheck.RateLimited(null), check(429, reset = "-1"))
    }

    @Test fun networkFailureDoesNotExposeExceptionText() = runBlocking {
        val client = GitHubReleaseClient.transport().newBuilder().addInterceptor {
            throw IOException("sensitive internal message")
        }.build()
        assertEquals(UpdateCheck.Network, GitHubReleaseClient(client).check("1.7.18", 20718))
    }
}
