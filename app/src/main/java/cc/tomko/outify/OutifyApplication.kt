package cc.tomko.outify

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.media3.common.util.UnstableApi
import cc.tomko.outify.R
import cc.tomko.outify.core.RateLimitGate
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.data.database.AppDatabase
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.diagnostics.ProcessExitDiagnostics
import cc.tomko.outify.ui.viewmodel.detail.DetailViewModelStore
import cc.tomko.outify.ui.viewmodel.detail.setDetailViewModelStore
import cc.tomko.outify.utils.ExceptionCollector
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

const val ALBUM_COVER_URL: String = "https://i.scdn.co/image/"
fun widgetMediaPreference(id: GlanceId) =
    stringPreferencesKey("widget_media_$id")

@HiltAndroidApp
class OutifyApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    @Inject
    lateinit var spircController: SpircController

    @Inject
    lateinit var spircWrapper: SpircWrapper

    @Inject
    lateinit var detailViewModelStore: DetailViewModelStore

    @Inject
    lateinit var exceptionCollector: ExceptionCollector

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        exceptionCollector.install()

        setDetailViewModelStore(detailViewModelStore)

        val libraryLoaded = try {
            System.loadLibrary("librespot_ffi")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e("OutifyApplication", "Failed to load librespot_ffi", e)
            false
        }

				if(!libraryLoaded) {
					Toast.makeText(this, getString(R.string.sys_error_librespot_load_failed), Toast.LENGTH_LONG).show()
				}

        val spotifySecret = BuildConfig.SPOTIFY_CLIENT_SECRET
        val spotifyId = BuildConfig.SPOTIFY_CLIENT_ID

        if (spotifySecret.isEmpty() || spotifyId.isEmpty()) {
            Toast.makeText(this, getString(R.string.sys_error_missing_credentials), Toast.LENGTH_LONG).show()
            throw Exception("No Spotify credentials were supplied during build! spotify.playback.clientId is${if (spotifyId.isEmpty()) "" else " not"} empty; spotify.playback.clientSecret is${if (spotifySecret.isEmpty()) "" else " not"} empty")
        }

        appScope.launch { ProcessExitDiagnostics.logLastExit(applicationContext) }

        // The shared gate exists before Hilt does; give it durable storage so a Spotify 429
        // window survives the frequent restarts that otherwise re-trigger it.
        RateLimitGate.shared.attachPersistence { untilMs ->
            appScope.launch { settingsRepository.setRateLimitUntilMs(untilMs) }
        }
        appScope.launch {
            RateLimitGate.shared.restoreFrom(settingsRepository.rateLimitUntilMs.first())
        }

        appScope.launch {
            // A custom Client ID/Secret saved in settings must survive a process restart;
            // otherwise the app silently falls back to the build-time credentials.
            val customId = settingsRepository.clientId.first()?.trim().orEmpty()
            val customSecret = settingsRepository.clientSecret.first()?.trim().orEmpty()
            val useCustom = customId.isNotEmpty() && customSecret.isNotEmpty()
            val effectiveId = if (useCustom) customId else spotifyId
            val effectiveSecret = if (useCustom) customSecret else spotifySecret
            Log.i("OutifyApplication", "Spotify client credentials: ${if (useCustom) "custom" else "build-time"}")
            LibrespotFfi.libInit(applicationContext, effectiveId, effectiveSecret)

            spircController.start()
            spircWrapper.setRestartCallback { spircController.restart() }
        }
    }
}
