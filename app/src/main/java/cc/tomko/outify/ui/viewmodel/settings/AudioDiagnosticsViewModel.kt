package cc.tomko.outify.ui.viewmodel.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.RateLimitGate
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.diagnostics.AudioDiagnostics
import cc.tomko.outify.diagnostics.AudioTestTones
import cc.tomko.outify.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class AudioDiagnosticsViewModel @Inject constructor(
    private val playbackStateHolder: PlaybackStateHolder,
    @ApplicationContext private val context: Context,
    private val spClient: SpClient,
    private val rateLimitGate: RateLimitGate,
) : ViewModel() {
    private val _report = MutableStateFlow("")
    val report: StateFlow<String> = _report.asStateFlow()

    private val _lastToneResult = MutableStateFlow<String?>(null)
    val lastToneResult: StateFlow<String?> = _lastToneResult.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            _report.value = withContext(Dispatchers.IO) {
                AudioDiagnostics.buildReport(context, playbackStateHolder.state.value)
            }
            _busy.value = false
        }
    }

    fun playAudioTrackTone() {
        viewModelScope.launch(Dispatchers.IO) {
            _lastToneResult.value = AudioTestTones.playAudioTrackTone()
        }
    }

    fun playSystemTone() {
        viewModelScope.launch(Dispatchers.IO) {
            _lastToneResult.value = AudioTestTones.playSystemTone()
        }
    }

    private val _lastProbeResult = MutableStateFlow<String?>(null)
    val lastProbeResult: StateFlow<String?> = _lastProbeResult.asStateFlow()

    /**
     * Calls a few Web API endpoints once each, on purpose ignoring the rate-limit gate, and
     * records what Spotify answered. Tells a per-account/IP block (everything fails) apart
     * from an endpoint-specific one (only some fail). Each line also lands in the report.
     */
    fun probeWebApi() {
        viewModelScope.launch(Dispatchers.IO) {
            _lastProbeResult.value = "probing…"
            val lines = listOf(
                // The probe natives bypass the native rate-limit window; the regular helpers
                // would answer "request skipped" without reaching Spotify.
                "/me" to { spClient.probeCurrentUserProfile() },
                "/me/top/artists" to { spClient.probeUserTop("artists", "medium_term") },
                "/me/top/tracks" to { spClient.probeUserTop("tracks", "medium_term") },
            ).map { (name, call) ->
                val summary = summarizeProbe(runCatching { call() })
                AudioDiagnostics.record("WebApiProbe", "$name -> $summary")
                "$name -> $summary"
            }
            _lastProbeResult.value = lines.joinToString("\n")
        }
    }

    private fun summarizeProbe(result: Result<String?>): String {
        val raw = result.getOrElse { return "exception: ${it.message ?: it}" }
            ?: return "null (no answer: no token or native failure)"
        return try {
            val obj = org.json.JSONObject(raw)
            if (obj.has("error")) {
                val err = obj.getJSONObject("error")
                val type = err.optString("type", "unknown")
                val retryAfter = if (err.has("retry_after_seconds")) err.getLong("retry_after_seconds") else null
                if (type == "rate_limit" || type == "rate_limited") {
                    rateLimitGate.noteRateLimited(retryAfter)
                }
                "error $type" + (retryAfter?.let { " retry_after=${it}s" } ?: "") +
                    ": " + err.optString("message").take(120).replace('\n', ' ')
            } else {
                "ok (${raw.length} chars)"
            }
        } catch (e: Exception) {
            "ok? non-JSON answer (${raw.length} chars)"
        }
    }

    /**
     * Writes the current report to the cache dir as two files (summary, logcat) and returns
     * a share intent chooser for both. The summary alone fits in a chat message; the logcat
     * is the part that used to push everything else past the paste limit.
     */
    suspend fun buildShareIntent(subject: String, chooserTitle: String): Intent = withContext(Dispatchers.IO) {
        val summary = AudioDiagnostics.buildSummary(context, playbackStateHolder.state.value)
        val logcat = AudioDiagnostics.buildLogcat(context)
        _report.value = summary + "\n" + logcat

        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val files = listOf(
            File(dir, "spoty-audio-$stamp-1-summary.txt").apply { writeText(summary) },
            File(dir, "spoty-audio-$stamp-2-logcat.txt").apply { writeText(logcat) },
        )

        val uris = ArrayList(
            files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
        )
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
