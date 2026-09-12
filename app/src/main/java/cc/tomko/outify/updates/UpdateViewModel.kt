package cc.tomko.outify.updates

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject

@HiltViewModel
internal class UpdateViewModel @Inject constructor(@ApplicationContext context: Context,
    private val store: UpdateStore, clock: Clock) : ViewModel() {
    private val verifier = AndroidApkVerifier(context)
    private val downloader = UpdateDownloader(context.cacheDir, verifier)
    private val session = UpdateSession(CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
        store, clock::millis, { GitHubReleaseClient().check() }, downloader::download, context.cacheDir, verifier)
    val state = session.state

    init { session.start(store.started.compareAndSet(false, true), store.dismissed) }
    fun retry() { store.dismissed = false; session.start(true, retry = true) }
    fun download() = session.download()
    fun later() { store.dismissed = true; session.dismiss() }
    suspend fun installPlan(allowed: Boolean, resumed: Boolean = false): InstallPlan = withContext(Dispatchers.IO) {
        try { session.installPlan(allowed, resumed) }
        catch (_: SecurityException) { InstallPlan.Invalid }
    }
    fun installFailed() = session.installFailed()
}
