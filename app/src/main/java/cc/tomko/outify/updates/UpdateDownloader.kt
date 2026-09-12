package cc.tomko.outify.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal sealed interface UpdateDownload {
    data class Ready(val file: File) : UpdateDownload
    data object Invalid : UpdateDownload
    data object Network : UpdateDownload
    data object Storage : UpdateDownload
    data object Service : UpdateDownload
}

/** cacheRoot must be the application's private cacheDir. Never expose partial downloads. */
internal class UpdateDownloader(private val cacheRoot: File, private val verifier: ApkVerifier,
    private val calls: Call.Factory = transport(), private val files: UpdateFiles = UpdateFiles()) {
    companion object {
        internal fun transport() = GitHubReleaseClient.transport().newBuilder()
            .callTimeout(120, TimeUnit.SECONDS).build()

        internal fun allowedInitial(url: HttpUrl, release: UpdateRelease): Boolean =
            secure(url) && url.host == "github.com" && url.query == null &&
                url.encodedPath == "/Denver2828/Outify/releases/download/v${release.versionName}/${ReleasePolicy.ASSET}"

        // Observed official flow: exact repository asset -> one signed release-assets CDN URL.
        internal fun allowedRedirect(from: HttpUrl, to: HttpUrl): Boolean =
            from.host == "github.com" && secure(to) && to.host == "release-assets.githubusercontent.com" &&
                Regex("/github-production-release-asset/[0-9]+/[0-9a-f-]{36}").matches(to.encodedPath)

        private fun secure(url: HttpUrl) = url.scheme == "https" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null
    }

    suspend fun download(release: UpdateRelease, progress: (Long, Long) -> Unit = { _, _ -> }): UpdateDownload =
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            var part: File? = null
            try {
                var url = release.url.toHttpUrlOrNull() ?: return@withContext UpdateDownload.Invalid
                if (!allowedInitial(url, release) || release.bytes !in 1..ReleasePolicy.MAX_APK_BYTES ||
                    !Regex("[0-9a-f]{64}").matches(release.sha256)) return@withContext UpdateDownload.Invalid
                val directory = File(cacheRoot, "updates")
                if (!directory.isDirectory && !directory.mkdirs()) return@withContext UpdateDownload.Storage
                part = storage { files.create(directory) }
                for (hop in 0..1) {
                    context.ensureActive()
                    val call = calls.newCall(Request.Builder().url(url)
                        .header("User-Agent", "Spoty-Update-Downloader").header("Accept-Encoding", "identity").build())
                    // A child cancellation handler interrupts blocking socket reads immediately.
                    val cancellation = Job(context[Job])
                    cancellation.invokeOnCompletion { if (cancellation.isCancelled) call.cancel() }
                    try {
                        call.execute().use { response ->
                            if (response.code in setOf(301, 302, 303, 307, 308)) {
                                val next = response.header("Location")?.let(url::resolve)
                                if (hop != 0 || next == null || !allowedRedirect(url, next))
                                    return@withContext UpdateDownload.Invalid
                                url = next
                            } else {
                                if (response.code != 200) return@withContext UpdateDownload.Service
                                val body = response.body ?: return@withContext UpdateDownload.Invalid
                                if (body.contentLength() >= 0 && body.contentLength() != release.bytes)
                                    return@withContext UpdateDownload.Invalid
                                if (response.header("Content-Encoding")?.let { it != "identity" } == true)
                                    return@withContext UpdateDownload.Invalid
                                val digest = MessageDigest.getInstance("SHA-256")
                                var total = 0L
                                val output = storage { files.open(part) }
                                try {
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            context.ensureActive()
                                            val size = input.read(buffer)
                                            if (size == -1) break
                                            total += size
                                            if (total > release.bytes || total > ReleasePolicy.MAX_APK_BYTES)
                                                return@withContext UpdateDownload.Invalid
                                            storage { files.write(output, buffer, size) }
                                            digest.update(buffer, 0, size)
                                            progress(total, release.bytes)
                                        }
                                    }
                                    storage { files.sync(output) }
                                } finally { storage { files.close(output) } }
                                if (total != release.bytes || hex(digest.digest()) != release.sha256 ||
                                    !verifier.verify(part, release)) return@withContext UpdateDownload.Invalid
                                context.ensureActive()
                                val destination = File(directory, "${release.sha256}.apk")
                                storage { files.publish(part, destination) }
                                return@withContext UpdateDownload.Ready(destination)
                            }
                        }
                    } finally { cancellation.complete() }
                }
                UpdateDownload.Invalid
            } catch (_: StorageFailure) {
                context.ensureActive()
                UpdateDownload.Storage
            } catch (_: IOException) {
                context.ensureActive()
                UpdateDownload.Network
            } catch (_: SecurityException) {
                context.ensureActive()
                UpdateDownload.Storage
            }
              finally { part?.delete() }
        }
}

/** Individual local operations keep socket/read exceptions outside the storage boundary. */
internal open class UpdateFiles {
    open fun create(directory: File): File = File.createTempFile("update-", ".part", directory)
    open fun open(file: File): FileOutputStream = FileOutputStream(file)
    open fun write(output: FileOutputStream, bytes: ByteArray, size: Int) = output.write(bytes, 0, size)
    open fun sync(output: FileOutputStream) = output.fd.sync()
    open fun close(output: FileOutputStream) = output.close()
    open fun publish(part: File, destination: File) {
        Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }
}

private class StorageFailure : IOException()
private inline fun <T> storage(action: () -> T): T = try { action() }
    catch (_: IOException) { throw StorageFailure() }
