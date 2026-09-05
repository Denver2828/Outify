package cc.tomko.outify.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import cc.tomko.outify.R
import cc.tomko.outify.diagnostics.AudioDiagnostics
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import cc.tomko.outify.core.spirc.VolumeController.Companion.SPOTIFY_MAX_VOLUME
import cc.tomko.outify.playback.callbacks.PlayerEventCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

private const val TAG = "AudioEngine"

enum class PcmFormat {
    S16,
}

/**
 * Plays the received PCM audio using modern AudioAttributes/AudioFormat API.
 */
@UnstableApi
class AudioEngine(
    val context: Context,
    eventCallback: PlayerEventCallback,
    private val stateHolder: PlaybackStateHolder,
) {
    @Volatile
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = -1
    private var currentChannels = -1
    private var currentFormat: PcmFormat? = null

    // Diagnostics counters, read by AudioDiagnostics when a report is built.
    @Volatile private var framesReceived = 0L
    @Volatile private var bytesWritten = 0L
    @Volatile private var writeErrors = 0L
    @Volatile private var partialWrites = 0L
    @Volatile private var droppedFrames = 0L
    @Volatile private var lastWriteResult = 0
    @Volatile private var lastPcmSampleRate = -1
    @Volatile private var lastPcmChannels = -1
    @Volatile private var lastPcmSize = -1

    private val pcmBuffer = ByteBuffer.allocateDirect(4 * 8192)

    private val sonic = SonicAudioProcessor()
    private var sonicSampleRate = -1
    private var sonicChannels = -1
    private var sonicCommittedSpeed = 1f

    private val writeLock = ReentrantLock()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while the last AudioTrack creation failed, so the user is warned once per streak. */
    @Volatile
    private var outputFailureReported = false

    init {
        // Registers this class as the PCM callback.
        // Rust stores the GlobalRef and calls the onPcm method
        registerPcmCallback(this, pcmBuffer)

        // Registers callbacks to handle librespot events
        registerPlayerEventListener(eventCallback)

        AudioDiagnostics.registerSnapshotProvider(::diagnosticSnapshot)
    }

    private fun diagnosticSnapshot(): String = buildString {
        val track = audioTrack
        appendLine("pcm: frames=$framesReceived lastSize=$lastPcmSize lastRate=$lastPcmSampleRate lastChannels=$lastPcmChannels")
        appendLine("writes: bytes=$bytesWritten errors=$writeErrors partial=$partialWrites dropped=$droppedFrames lastResult=$lastWriteResult")
        appendLine("outputFailureReported=$outputFailureReported speed=${stateHolder.state.value.playbackSpeed}")
        if (track == null) {
            appendLine("audioTrack: none")
        } else {
            appendLine(
                "audioTrack: state=${track.state} playState=${track.playState} rate=${track.sampleRate} " +
                    "channels=${track.channelCount} format=${track.audioFormat} sessionId=${track.audioSessionId}"
            )
            appendLine("audioTrack: headPosition=${track.playbackHeadPosition} underruns=${track.underrunCount}")
            appendLine("audioTrack: route=${AudioDiagnostics.describeDevice(track.routedDevice)}")
            appendLine("audioTrack: attributes=${track.audioAttributes}")
        }
    }

    private fun ensureAudioTrack(sampleRate: Int, channels: Int, format: PcmFormat): Boolean {
        writeLock.withLock {
            val existing = audioTrack
            if (existing != null
                && sampleRate == currentSampleRate
                && channels == currentChannels
                && format == currentFormat
                && existing.state == AudioTrack.STATE_INITIALIZED
            ) {
                return true
            }

            // Otherwise recreate
            releaseAudioTrack()

            val channelMask = when (channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> {
                    // fallback to stereo for unknown channel counts
                    Log.w(TAG, "Unsupported channel count $channels, falling back to stereo")
                    AudioFormat.CHANNEL_OUT_STEREO
                }
            }

            val encoding = when (format) {
                PcmFormat.S16 -> AudioFormat.ENCODING_PCM_16BIT
            }

            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            if (minBufferSize <= 0) {
                Log.e(TAG, "Invalid min buffer size: $minBufferSize")
                reportOutputFailure("minBufferSize=$minBufferSize")
                return false
            }

            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
            val frameSize = bytesPerSample * max(1, channels)
            val bufferSize = max(minBufferSize, frameSize * 1024)

            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                val formatBuilder = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelMask)
                    .build()

                val newTrack = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(formatBuilder)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "Failed to initialize AudioTrack: state=${newTrack.state}")
                    newTrack.release()
                    reportOutputFailure("state=${newTrack.state}")
                    return false
                }

                newTrack.play()

                audioTrack = newTrack
                currentSampleRate = sampleRate
                currentChannels = channels
                currentFormat = format

                outputFailureReported = false
                AudioDiagnostics.record(
                    TAG,
                    "AudioTrack created: sampleRate=$sampleRate, channels=$channels, encoding=$encoding, " +
                        "buffer=$bufferSize, minBuffer=$minBufferSize, playState=${newTrack.playState}, " +
                        "route=${describeRoute(newTrack)}"
                )
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "Exception while creating AudioTrack", t)
                AudioDiagnostics.record(TAG, "AudioTrack creation threw $t")
                reportOutputFailure(t.javaClass.simpleName + ": " + (t.message ?: ""))
                return false
            }
        }
    }

    /**
     * Silence with a moving progress bar is the worst failure mode: librespot keeps decoding
     * while every frame is dropped here. Tell the user once per failure streak.
     */
    private fun reportOutputFailure(reason: String) {
        AudioDiagnostics.record(TAG, "audio output failure: $reason")
        if (outputFailureReported) return
        outputFailureReported = true
        mainHandler.post {
            Toast.makeText(
                context,
                context.getString(R.string.sys_audio_output_failed, reason),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun describeRoute(track: AudioTrack): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return "unknown"
        val device = track.routedDevice ?: return "none"
        return "${device.productName} (type=${device.type})"
    }

    fun releaseAudioTrack() {
        writeLock.withLock {
            drainSonic()
            val t = audioTrack ?: return
            try {
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        t.stop()
                    } catch (ignored: IllegalStateException) {
                        // ignore - may happen if already stopped
                    }
                } else if (t.playState == AudioTrack.PLAYSTATE_PAUSED) {
                    try {
                        t.stop()
                    } catch (ignored: IllegalStateException) {
                    }
                }
            } catch (ignored: Exception) {
            } finally {
                try {
                    t.release()
                } catch (ignored: Exception) {
                }
                audioTrack = null
                currentSampleRate = -1
                currentChannels = -1
                currentFormat = null
            }
        }
    }

    /**
     * Releases native resources — frees JNI GlobalRefs for PCM callback
     * and player event listener.
     */
    fun releaseNative() {
        unregisterPcmCallback()
        unregisterPlayerEventListener()
    }

    fun pause() {
        writeLock.withLock {
            audioTrack?.let {
                try {
                    it.pause()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "pause() failed", e)
                }
            }
        }
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(
            volume.coerceIn(0.0f, AudioTrack.getMaxVolume())
        )
    }

    fun flush() {
        writeLock.withLock {
            drainSonic()
            audioTrack?.let {
                try {
                    it.flush()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "flush() failed", e)
                }
            }
        }
    }

    /**
     * Marks this class as the one to receive onPcm data
     */
    private external fun registerPcmCallback(callbackPtr: AudioEngine?, buffer: ByteBuffer)

    /**
     * Called from rust trampoline when the PCMBuffer is filled with PCM.
     */
    fun onPcmReady(size: Int, sampleRate: Int, channels: Int) {
        writeLock.withLock {
            pcmBuffer.order(ByteOrder.nativeOrder())

            framesReceived++
            lastPcmSize = size
            lastPcmSampleRate = sampleRate
            lastPcmChannels = channels
            if (framesReceived == 1L || framesReceived % 500 == 0L) {
                AudioDiagnostics.record(
                    TAG,
                    "pcm frame #$framesReceived size=$size rate=$sampleRate channels=$channels " +
                        "written=$bytesWritten errors=$writeErrors dropped=$droppedFrames " +
                        "head=${audioTrack?.playbackHeadPosition ?: -1} underruns=${audioTrack?.underrunCount ?: -1}"
                )
            }

            if (!ensureAudioTrack(sampleRate, channels, PcmFormat.S16)) {
                droppedFrames++
                Log.w(TAG, "ensureAudioTrack failed - dropping frame")
                return
            }

            val cap = pcmBuffer.capacity()
            if (size > cap) {
                Log.w(TAG, "pcm size $size > buffer capacity $cap; dropping frame")
                return
            }

            pcmBuffer.position(0)
            pcmBuffer.limit(size)

            try {
                val track = audioTrack ?: run {
                    Log.w(TAG, "audioTrack is null in onPcmReady")
                    return
                }

                val speed = stateHolder.state.value.playbackSpeed.coerceAtLeast(0.1f)
                if (speed == 1f) {
                    writeToTrack(pcmBuffer, size, track)
                } else {
                    prepareSonic(sampleRate, channels, speed)
                    sonic.queueInput(pcmBuffer)
                    drainSonicTo(track)
                }
            } catch (ise: IllegalStateException) {
                Log.e(TAG, "AudioTrack write failed", ise)
            } finally {
                pcmBuffer.position(0)
                pcmBuffer.limit(pcmBuffer.capacity())
            }
        }
    }

    private fun prepareSonic(sampleRate: Int, channels: Int, speed: Float) {
        val formatChanged = sampleRate != sonicSampleRate || channels != sonicChannels
        val speedChanged = speed != sonicCommittedSpeed
        if (!formatChanged && !speedChanged) return

        drainSonic()

        if (formatChanged) {
            try {
                sonic.reset()
                val inputFormat = AudioProcessor.AudioFormat(
                    sampleRate, channels, C.ENCODING_PCM_16BIT
                )
                sonic.configure(inputFormat)
                sonicSampleRate = sampleRate
                sonicChannels = channels
            } catch (e: AudioProcessor.UnhandledAudioFormatException) {
                Log.e(TAG, "Sonic configure failed", e)
                sonicSampleRate = -1
                sonicChannels = -1
                sonicCommittedSpeed = 1f
                return
            }
        }

        sonic.setSpeed(speed)
        sonicCommittedSpeed = speed
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun drainSonicTo(track: AudioTrack) {
        var output = sonic.getOutput()
        while (output.hasRemaining()) {
            writeToTrack(output, output.remaining(), track)
            output = sonic.getOutput()
        }
    }

    private fun drainSonic() {
        if (sonicSampleRate < 0) return
        sonic.queueEndOfStream()
        val track = audioTrack
        var output = sonic.getOutput()
        while (track != null && output.hasRemaining()) {
            writeToTrack(output, output.remaining(), track)
            output = sonic.getOutput()
        }
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
        sonicCommittedSpeed = 1f
    }

    private fun writeToTrack(buffer: ByteBuffer, size: Int, track: AudioTrack) {
        val written = track.write(buffer, size, AudioTrack.WRITE_BLOCKING)
        lastWriteResult = written
        if (written < 0) {
            writeErrors++
            if (writeErrors <= 5 || writeErrors % 500 == 0L) {
                AudioDiagnostics.record(TAG, "AudioTrack.write returned error $written (playState=${track.playState})")
            }
        } else {
            bytesWritten += written
            if (written < size) {
                partialWrites++
                Log.w(TAG, "AudioTrack wrote $written / $size bytes (partial write)")
            }
        }
    }

    /**
     * Registers PlayerEvent listener to FFI.
     * FFI stores the GlobalRef of the callback
     */
    external fun registerPlayerEventListener(callback: PlayerEventCallback);

    /**
     * Unregisters the PCM callback, freeing its JNI GlobalRef
     */
    private external fun unregisterPcmCallback()

    /**
     * Unregisters the player event listener, freeing its JNI GlobalRef
     */
    private external fun unregisterPlayerEventListener()
}
