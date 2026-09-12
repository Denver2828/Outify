package cc.tomko.outify.updates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal sealed interface UpdateCheck {
    data object NoUpdate : UpdateCheck
    data class Available(val release: UpdateRelease) : UpdateCheck
    data class RateLimited(val resetAtSeconds: Long?) : UpdateCheck
    data object Network : UpdateCheck
    data object Service : UpdateCheck
    data object Invalid : UpdateCheck
}

@Serializable
internal data class UpdateRelease(val versionName: String, val versionCode: Int,
    val url: String, val bytes: Long, val sha256: String)

@Serializable
private data class ReleasePayload(val tag_name: String, val draft: Boolean,
    val prerelease: Boolean, val assets: List<AssetPayload>)

@Serializable
private data class AssetPayload(val name: String, val browser_download_url: String,
    val size: Long, val digest: String? = null, val content_type: String)

internal object ReleasePolicy {
    const val ASSET = "app-arm64-v8a-release.apk"
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    fun validateCached(value: UpdateRelease, installedName: String, installedCode: Int): UpdateRelease? {
        if (versionCode(value.versionName) != value.versionCode) return null
        val payload = ReleasePayload("v${value.versionName}", false, false, listOf(
            AssetPayload(ASSET, value.url, value.bytes, "sha256:${value.sha256}", "application/octet-stream")))
        return (evaluate(json.encodeToString(ReleasePayload.serializer(), payload), installedName, installedCode)
            as? UpdateCheck.Available)?.release
    }
    private val versionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

    // Minor/patch are limited to two decimal digits by the app's version-code scheme.
    internal fun versionCode(name: String): Int? {
        val match = versionPattern.matchEntire(name) ?: return null
        val parts = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        if (parts[0] > 214746 || parts[1] > 99 || parts[2] > 99) return null
        val code = 10_000L + parts[0] * 10_000 + parts[1] * 100 + parts[2]
        return code.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    fun evaluate(body: String, installedName: String, installedCode: Int): UpdateCheck = try {
        val release = json.decodeFromString<ReleasePayload>(body)
        require(versionCode(installedName) == installedCode)
        require(!release.draft && !release.prerelease && release.tag_name.startsWith("v"))
        val name = release.tag_name.removePrefix("v")
        val code = versionCode(name) ?: error("Invalid version")
        if (code <= installedCode) UpdateCheck.NoUpdate else {
            val asset = release.assets.filter { it.name == ASSET }.single()
            require(asset.size in 1..MAX_APK_BYTES)
            require(asset.content_type in setOf("application/vnd.android.package-archive", "application/octet-stream"))
            val url = asset.browser_download_url.toHttpUrlOrNull() ?: error("Invalid URL")
            require(url.scheme == "https" && url.host == "github.com" && url.port == 443)
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
            require(url.encodedPath == "/Denver2828/Outify/releases/download/${release.tag_name}/$ASSET")
            val digest = asset.digest ?: error("Missing digest")
            require(Regex("sha256:[0-9a-fA-F]{64}").matches(digest))
            UpdateCheck.Available(UpdateRelease(name, code, url.toString(), asset.size, digest.substring(7).lowercase()))
        }
    } catch (_: Exception) { UpdateCheck.Invalid }
}
