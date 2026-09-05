package cc.tomko.outify.diagnostics

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Two ways of making noise that bypass librespot entirely.
 *
 * If the AudioTrack tone is heard, the device routes our PCM fine and the problem is the
 * data librespot hands us. If only the system tone is heard, the device ignores
 * AudioTrack streams with media attributes from this app. If neither is heard, the device
 * is not routing this app's audio at all.
 */
object AudioTestTones {
    private const val SAMPLE_RATE = 44_100
    private const val FREQUENCY_HZ = 440.0
    private const val DURATION_MS = 1_500

    /** Same attributes, format and transfer mode as the playback engine. */
    fun playAudioTrackTone(): String {
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelMask, encoding)
        if (minBuffer <= 0) {
            val message = "AudioTrack tone: invalid min buffer size $minBuffer"
            AudioDiagnostics.record("AudioTestTones", message)
            return message
        }

        val frames = SAMPLE_RATE * DURATION_MS / 1000
        val pcm = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            val fade = minOf(1.0, frame / (SAMPLE_RATE * 0.02), (frames - frame) / (SAMPLE_RATE * 0.02))
            val sample = (sin(2.0 * PI * FREQUENCY_HZ * frame / SAMPLE_RATE) * 0.6 * fade * Short.MAX_VALUE).toInt().toShort()
            pcm[frame * 2] = sample
            pcm[frame * 2 + 1] = sample
        }

        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(encoding)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, pcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                val message = "AudioTrack tone: track not initialized (state=${track.state})"
                AudioDiagnostics.record("AudioTestTones", message)
                return message
            }

            val written = track.write(pcm, 0, pcm.size)
            track.play()
            val route = AudioDiagnostics.describeDevice(track.routedDevice)
            Thread {
                Thread.sleep(DURATION_MS + 200L)
                runCatching { track.stop() }
                track.release()
            }.start()

            val message = "AudioTrack tone: wrote $written/${pcm.size} samples, playState=${track.playState}, route=$route"
            AudioDiagnostics.record("AudioTestTones", message)
            message
        } catch (t: Throwable) {
            val message = "AudioTrack tone failed: $t"
            AudioDiagnostics.record("AudioTestTones", message)
            message
        }
    }

    /** ToneGenerator on STREAM_MUSIC: a different, older path through the audio stack. */
    fun playSystemTone(): String {
        return try {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
            val started = generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, DURATION_MS)
            Thread {
                Thread.sleep(DURATION_MS + 200L)
                generator.release()
            }.start()
            val message = "System tone: started=$started"
            AudioDiagnostics.record("AudioTestTones", message)
            message
        } catch (t: Throwable) {
            val message = "System tone failed: $t"
            AudioDiagnostics.record("AudioTestTones", message)
            message
        }
    }
}
