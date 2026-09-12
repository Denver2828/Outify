package cc.tomko.outify.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val bytes: Long) : UpdateState
    data class Ready(val release: UpdateRelease, val file: File) : UpdateState
    data class Error(val reason: String, val retryAt: Long = 0) : UpdateState
}

/** Lifecycle-owned coroutine work; all network actions are injected and no download starts in init. */
internal class UpdateSession(private val scope: CoroutineScope, private val store: UpdatePersistence,
    private val now: () -> Long, private val check: suspend () -> UpdateCheck,
    private val download: suspend (UpdateRelease, (Long, Long) -> Unit) -> UpdateDownload,
    private val cacheDir: File, private val verifier: ApkVerifier) {
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var cached = UpdateCache()
    private val installer = UpdateInstaller(verifier)

    fun start(checkThisProcess: Boolean, dismissed: Boolean = false, retry: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                mutable.value = UpdateState.Checking
                cached = store.load()
                val time = now()
                // Bound persisted timestamps after clock changes/corruption to avoid permanent suppression.
                val rate = cached.rateUntil.coerceIn(0, time + 24 * 60 * 60_000L)
                val due = cached.nextCheck.coerceIn(0, time + 6 * 60 * 60_000L)
                if (rate != cached.rateUntil || due != cached.nextCheck) {
                    cached = cached.copy(rateUntil = rate, nextCheck = due)
                    store.save(cached)
                }
                if (checkThisProcess && time >= rate && (retry || time >= due)) {
                    store.save(cached.copy(nextCheck = time + 6 * 60 * 60_000L))
                    when (val result = check()) {
                        is UpdateCheck.Available -> cached = UpdateCache(time + 6 * 60 * 60_000L, 0, result.release)
                        UpdateCheck.NoUpdate -> cached = UpdateCache(time + 6 * 60 * 60_000L)
                        is UpdateCheck.RateLimited -> cached = cached.copy(rateUntil =
                            ((result.resetAtSeconds?.times(1000)) ?: (time + 60 * 60_000L))
                                .coerceIn(time + 60_000L, time + 24 * 60 * 60_000L))
                        else -> { mutable.value = UpdateState.Error("check"); return@launch }
                    }
                    store.save(cached)
                }
                val release = cached.release
                val pending = release?.let { File(cacheDir, "updates/${it.sha256}.apk") }
                if (checkThisProcess) File(cacheDir, "updates").listFiles()?.forEach {
                    if (it != pending && (it.extension == "part" || time - it.lastModified() > 24 * 60 * 60_000L)) it.delete()
                }
                mutable.value = when {
                    dismissed -> UpdateState.Idle
                    release == null && cached.rateUntil > time -> UpdateState.Error("rate", cached.rateUntil)
                    release == null -> UpdateState.Idle
                    pending != null && pending.isFile && verifier.verify(pending, release) -> UpdateState.Ready(release, pending)
                    else -> UpdateState.Available(release)
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { mutable.value = UpdateState.Error("storage") }
        }
    }

    fun download() {
        val release = (mutable.value as? UpdateState.Available)?.release ?: return
        if (job?.isActive == true) return
        mutable.value = UpdateState.Downloading(release, 0)
        job = scope.launch {
            try {
                mutable.value = when (val result = download(release) { bytes, _ ->
                    mutable.value = UpdateState.Downloading(release, bytes.coerceIn(0, release.bytes))
                }) {
                    is UpdateDownload.Ready -> UpdateState.Ready(release, result.file)
                    else -> UpdateState.Error(when (result) {
                        UpdateDownload.Storage -> "storage"
                        UpdateDownload.Invalid -> "invalid"
                        else -> "download"
                    })
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { mutable.value = UpdateState.Error("download") }
        }
    }

    fun dismiss() { job?.cancel(); mutable.value = UpdateState.Idle }
    fun installPlan(allowed: Boolean, resumed: Boolean): InstallPlan {
        val ready = mutable.value as? UpdateState.Ready ?: return InstallPlan.None
        return installer.plan(ready.file, ready.release, allowed, resumed)
    }
    fun installFailed() { mutable.value = UpdateState.Error("install") }
}
