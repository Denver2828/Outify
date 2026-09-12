package cc.tomko.outify.updates

import java.io.File

internal sealed interface InstallPlan {
    data object None : InstallPlan
    data object Invalid : InstallPlan
    data object Permission : InstallPlan
    data class Launch(val file: File) : InstallPlan
}

/** The UI adapter opens Settings or the system installer; neither action is silent. */
internal class UpdateInstaller(private val verifier: ApkVerifier) {
    private var waitingForPermission = false
    fun plan(file: File, release: UpdateRelease, allowed: Boolean, resumed: Boolean): InstallPlan {
        if (resumed && !waitingForPermission) return InstallPlan.None
        if (!file.isFile || !verifier.verify(file, release)) {
            waitingForPermission = false
            return InstallPlan.Invalid
        }
        if (!allowed) {
            if (resumed) return InstallPlan.None
            waitingForPermission = true
            return InstallPlan.Permission
        }
        waitingForPermission = false
        return InstallPlan.Launch(file)
    }
}
