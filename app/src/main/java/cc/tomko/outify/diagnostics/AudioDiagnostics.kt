package cc.tomko.outify.diagnostics

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import cc.tomko.outify.BuildConfig
import cc.tomko.outify.playback.model.PlaybackState
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-app audio event log plus a snapshot of the device audio state.
 *
 * Exists because the audio path can fail silently: librespot keeps decoding while the
 * PCM never reaches the speaker, and on devices without adb (car boxes) there is no other
 * way to see what happened. Everything recorded here is also written to logcat.
 */
object AudioDiagnostics {
    private const val TAG = "AudioDiagnostics"
    private const val MAX_EVENTS = 400
    private const val LOGCAT_LINES = 800
    private const val PREVIOUS_LOGCAT_LINES = 400

    private val events = ArrayDeque<String>(MAX_EVENTS)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Callbacks that describe live objects (the AudioTrack) when a report is built. */
    private val snapshotProviders = mutableListOf<() -> String>()

    fun record(tag: String, message: String) {
        Log.i(tag, message)
        val line = "${timeFormat.format(Date())} $tag: $message"
        synchronized(events) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(line)
        }
    }

    fun registerSnapshotProvider(provider: () -> String) {
        synchronized(snapshotProviders) { snapshotProviders += provider }
    }

    fun buildReport(context: Context, playbackState: PlaybackState): String = buildString {
        appendLine("=== Spoty audio diagnostics ===")
        appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.PRODUCT})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()

        appendLine("--- Playback state ---")
        appendLine("state=${playbackState.state} playing=${playbackState.isPlaying} buffering=${playbackState.isBuffering}")
        appendLine("activeDevice=${playbackState.isActiveDevice} spotifyVolume=${playbackState.volume}/65535 speed=${playbackState.playbackSpeed}")
        appendLine("track=${playbackState.currentAudio?.name ?: "none"} (${playbackState.currentAudio?.id ?: "-"})")
        appendLine()

        appendLine("--- AudioManager ---")
        append(describeAudioManager(context))
        appendLine()

        appendLine("--- Audio engine ---")
        val providers = synchronized(snapshotProviders) { snapshotProviders.toList() }
        if (providers.isEmpty()) appendLine("no engine registered")
        providers.forEach { provider ->
            appendLine(runCatching(provider).getOrElse { "snapshot failed: $it" })
        }
        appendLine()

        appendLine("--- Recorded events (oldest first) ---")
        val recorded = synchronized(events) { events.toList() }
        if (recorded.isEmpty()) appendLine("none")
        recorded.forEach { appendLine(it) }
        appendLine()

        appendLine("--- Process exits (last 10) ---")
        append(ProcessExitDiagnostics.describe(context))
        appendLine()

        // The logcat buffer is system-wide, and an app may read every line its own UID wrote,
        // so the lines of a process that just crashed or ANR'd are still there. That is where
        // the Rust panic hook and the last actions before the death live.
        ProcessExitDiagnostics.abnormalExitPids(context).forEach { pid ->
            appendLine("--- logcat (previous process $pid, last $PREVIOUS_LOGCAT_LINES lines) ---")
            appendLine(readLogcat(pid, PREVIOUS_LOGCAT_LINES))
            appendLine()
        }

        appendLine("--- logcat (this process, last $LOGCAT_LINES lines) ---")
        appendLine(readLogcat(android.os.Process.myPid(), LOGCAT_LINES))
    }

    private fun describeAudioManager(context: Context): String = buildString {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            appendLine("unavailable")
            return@buildString
        }

        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        appendLine("STREAM_MUSIC volume=$volume/$maxVolume fixedVolume=${audioManager.isVolumeFixed}")
        appendLine("mode=${audioManager.mode} musicActive=${audioManager.isMusicActive} ringerMode=${audioManager.ringerMode}")
        appendLine(
            "bluetoothA2dp=${audioManager.isBluetoothA2dpOn} bluetoothSco=${audioManager.isBluetoothScoOn} " +
                "speakerphone=${audioManager.isSpeakerphoneOn} wiredHeadset=${audioManager.isWiredHeadsetOn}"
        )
        appendLine("outputSampleRate=${audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} " +
            "framesPerBuffer=${audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}")

        appendLine("output devices:")
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outputs.isEmpty()) appendLine("  none")
        outputs.forEach { device ->
            appendLine("  ${describeDevice(device)}")
        }
    }

    fun describeDevice(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        val rates = device.sampleRates.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "any"
        val channels = device.channelCounts.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "any"
        return "id=${device.id} type=${deviceTypeName(device.type)} name=\"${device.productName}\" " +
            "sink=${device.isSink} rates=$rates channels=$channels"
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_FM -> "FM"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_UNKNOWN -> "UNKNOWN"
        else -> "TYPE_$type"
    }

    /** Reads the logcat lines of [pid]; no permission needed for pids of the caller's own UID. */
    private fun readLogcat(pid: Int, lines: Int): String {
        return try {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "threadtime", "-t", lines.toString(),
                "--pid=$pid",
            ).redirectErrorStream(true).start()
            val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            text.ifBlank { "(empty)" }
        } catch (e: Exception) {
            Log.w(TAG, "logcat read failed", e)
            "logcat unavailable: $e"
        }
    }
}
