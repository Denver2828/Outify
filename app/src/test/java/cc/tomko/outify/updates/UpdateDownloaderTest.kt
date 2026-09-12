package cc.tomko.outify.updates

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class UpdateDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "synthetic APK fixture".toByteArray()
    private fun release() = UpdateRelease("1.7.19", 20719,
        "https://github.com/Denver2828/Outify/releases/download/v1.7.19/${ReleasePolicy.ASSET}",
        bytes.size.toLong(), sha256(bytes))
    private val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1356439055/d8eb804c-2ddd-4e20-999b-e6b2b5e582aa?sig=test"

    private fun downloader(payload: ByteArray = bytes, verify: Boolean = true, redirect: String? = cdn, unknownLength: Boolean = false, files: UpdateFiles = UpdateFiles()): UpdateDownloader {
        val client = GitHubReleaseClient.transport().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            val first = request.url.host == "github.com" && redirect != null
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (first) 302 else 200).message("test")
                .apply { if (first) header("Location", redirect) }
                .body(if (!first && unknownLength) object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = -1L
                    private val buffer = Buffer().write(payload)
                    override fun source() = buffer
                } else (if (first) byteArrayOf() else payload).toResponseBody()).build()
        }.build()
        return UpdateDownloader(temporary.root, ApkVerifier { file, _ ->
            assertArrayEquals(payload, file.readBytes())
            verify
        }, client, files)
    }

    @Test fun defaultDownloaderTransportDoesNotBypassRedirectValidation() {
        val client = UpdateDownloader.transport()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(120000, client.callTimeoutMillis)
    }

    @Test fun localIoFailuresRemainStorageFailures() = runBlocking {
        for (failure in listOf("create", "open", "write", "sync", "close", "publish")) {
            val files = object : UpdateFiles() {
                private fun check(operation: String) { if (failure == operation) throw IOException("local failure") }
                override fun create(directory: File): File { check("create"); return super.create(directory) }
                override fun open(file: File): FileOutputStream { check("open"); return super.open(file) }
                override fun write(output: FileOutputStream, bytes: ByteArray, size: Int) {
                    check("write"); super.write(output, bytes, size)
                }
                override fun sync(output: FileOutputStream) { check("sync"); super.sync(output) }
                override fun close(output: FileOutputStream) { super.close(output); check("close") }
                override fun publish(part: File, destination: File) { check("publish"); super.publish(part, destination) }
            }
            assertEquals(failure, UpdateDownload.Storage, downloader(files = files).download(release()))
            assertTrue(File(temporary.root, "updates").listFiles()!!.isEmpty())
        }
    }

    @Test fun transportIoFailureRemainsNetworkFailure() = runBlocking {
        val client = UpdateDownloader.transport().newBuilder().addInterceptor { throw IOException("socket failure") }.build()
        val downloader = UpdateDownloader(temporary.root, ApkVerifier { _, _ -> true }, client)
        assertEquals(UpdateDownload.Network, downloader.download(release()))
        assertTrue(File(temporary.root, "updates").listFiles()!!.isEmpty())
    }

    @Test fun writesOnlyVerifiedFileAfterAllowedRedirect() = runBlocking {
        var received = 0L
        val result = downloader().download(release()) { count, _ -> received = count } as UpdateDownload.Ready
        assertArrayEquals(bytes, result.file.readBytes())
        assertEquals(bytes.size.toLong(), received)
        assertFalse(File(temporary.root, "updates").listFiles()!!.any { it.extension == "part" })
    }

    @Test fun rejectsHashSizeSignerAndHostWithoutReplacingCompletedFile() = runBlocking {
        val ready = downloader().download(release()) as UpdateDownload.Ready
        assertEquals(UpdateDownload.Invalid, downloader(verify = false).download(release()))
        assertArrayEquals(bytes, ready.file.readBytes())
        assertEquals(UpdateDownload.Invalid, downloader().download(release().copy(sha256 = "0".repeat(64))))
        assertEquals(UpdateDownload.Invalid, downloader(payload = bytes + 1.toByte()).download(release()))
        assertEquals(UpdateDownload.Invalid, downloader(redirect = "https://evil.test/file").download(release()))
        assertFalse(File(temporary.root, "updates").listFiles()!!.any { it.extension == "part" })
    }

    @Test fun boundsUnknownLengthStreamAndCleansCancelledPartial() = runBlocking {
        assertEquals(UpdateDownload.Invalid,
            downloader(payload = bytes + 1.toByte(), unknownLength = true).download(release()))
        val task = launch {
            val job = currentCoroutineContext()[Job]!!
            downloader(unknownLength = true).download(release()) { _, _ -> job.cancel() }
        }
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(File(temporary.root, "updates").listFiles()!!.isEmpty())
    }

    @Test fun redirectRulesRejectLookalikesUserInfoPortsAndLoops() {
        val initial = release().url.toHttpUrl()
        assertTrue(UpdateDownloader.allowedRedirect(initial, cdn.toHttpUrl()))
        listOf(cdn.replace("https:", "http:"), cdn.replace(".com/", ".com.evil/"),
            cdn.replace("https://", "https://user@"), cdn.replace(".com/", ".com:444/"),
            "https://github.com/other/file").forEach {
            assertFalse(UpdateDownloader.allowedRedirect(initial, it.toHttpUrl()))
        }
        assertFalse(UpdateDownloader.allowedRedirect(cdn.toHttpUrl(), cdn.toHttpUrl()))
        assertFalse(UpdateDownloader.allowedInitial(initial.newBuilder().addQueryParameter("x", "y").build(), release()))
    }

    @Test fun verifiesExactPackageVersionAndCurrentSignerSet() {
        val installed = ApkIdentity("cc.tomko.outify", "1.7.18", 20718, setOf("certificate"))
        val candidate = installed.copy(versionName = "1.7.19", versionCode = 20719)
        assertTrue(AndroidApkVerifier.matches(installed, candidate, release()))
        listOf(candidate.copy(packageName = "other"), candidate.copy(versionCode = 20718),
            candidate.copy(versionName = "1.7.20"), candidate.copy(signers = emptySet()),
            candidate.copy(signers = setOf("rotated")), candidate.copy(signers = setOf("certificate", "other")))
            .forEach { assertFalse(AndroidApkVerifier.matches(installed, it, release())) }
        assertFalse(AndroidApkVerifier.matches(installed.copy(signers = emptySet()), candidate, release()))
    }
}
