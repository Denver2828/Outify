package cc.tomko.outify.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

internal data class ApkIdentity(val packageName: String, val versionName: String?,
    val versionCode: Long, val signers: Set<String>)

internal fun interface ApkVerifier {
    fun verify(file: File, release: UpdateRelease): Boolean
}

internal class AndroidApkVerifier(private val context: Context) : ApkVerifier {
    @Suppress("DEPRECATION")
    override fun verify(file: File, release: UpdateRelease): Boolean = try {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags)
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, flags)
        candidate != null && matches(identity(installed), identity(candidate), release)
    } catch (_: Exception) { false }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
            else info.signatures
        return ApkIdentity(info.packageName, info.versionName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            certificates.orEmpty().map { sha256(it.toByteArray()) }.toSet())
    }

    companion object {
        fun matches(installed: ApkIdentity, candidate: ApkIdentity, release: UpdateRelease): Boolean =
            installed.packageName == "cc.tomko.outify" && candidate.packageName == installed.packageName &&
                candidate.versionName == release.versionName && candidate.versionCode == release.versionCode.toLong() &&
                candidate.versionCode > installed.versionCode && installed.signers.isNotEmpty() &&
                candidate.signers == installed.signers
    }
}

internal fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
