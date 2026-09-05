package cc.tomko.outify.ui.viewmodel.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    /** Writes the current report to the cache dir and returns a share intent chooser for it. */
    suspend fun buildShareIntent(subject: String, chooserTitle: String): Intent = withContext(Dispatchers.IO) {
        val text = AudioDiagnostics.buildReport(context, playbackStateHolder.state.value)
        _report.value = text

        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "spoty-audio-$stamp.txt")
        file.writeText(text)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
