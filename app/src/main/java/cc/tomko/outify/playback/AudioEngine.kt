package cc.tomko.outify.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
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
import cc.tomko.outify.playback.audio.AudioSink
import cc.tomko.outify.playback.audio.AudioTrackSink
import cc.tomko.outify.playback.audio.PcmWriter
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
 *
 * Ownership: the AudioTrack lives inside a [PcmWriter], which is the only code that
 * writes to it. This class creates tracks (initially and on rebuild after
 * `ERROR_DEAD_OBJECT`), feeds PCM to the writer and reports what it sees to the
 * diagnostics log. [writeLock] serializes the PCM path against pause/flush/release from
 * other threads; the writer has its own lock for the sink handle.
 */
@UnstableApi
class AudioEngine(
    val context: Context,
    eventCallback: PlayerEventCallback,
    private val stateHolder: PlaybackStateHolder,
) {
    /** Current sink, kept here only for diagnostics and volume/pause/flush; writes go through [writer]. */
    @Volatile
    private var sink: AudioTrackSink? = null
    private var currentSampleRate = -1
    private var currentChannels = -1
    private var currentFormat: PcmFormat? = null

    /** Last volume requested by the player, reapplied to every newly created track. */
    @Volatile
    private var lastVolume: Float? = null

    // Diagnostics counters, read by AudioDiagnostics when a report is built.
    @Volatile private var framesReceived = 0L
    @Volatile private var droppedFrames = 0L
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

    /** When the writer last gave up on the output; gates how soon a fresh track is attempted. */
    @Volatile
    private var gaveUpAtMs = 0L

    private val writer = PcmWriter(
        rebuildSink = ::rebuildSinkForWriter,
        listener = ::onWriterEvent,
    )

    init {
        // Registers this class as the PCM callback.
        // Rust stores the GlobalRef and calls the onPcm method
        registerPcmCallback(this, pcmBuffer)

        // Registers callbacks to handle librespot events
        registerPlayerEventListener(eventCallback)

        AudioDiagnostics.registerSnapshotProvider(::diagnosticSnapshot)
    }

    private fun diagnosticSnapshot(): String = buildString {
        val track = sink?.track
        val w = writer.snapshot()
        appendLine("pcm: frames=$framesReceived lastSize=$lastPcmSize lastRate=$lastPcmSampleRate lastChannels=$lastPcmChannels")
        appendLine(
            "writes: bytes=${w.bytesWritten} errors=${w.writeErrors} partial=${w.partialWrites} " +
                "dropped=$droppedFrames lastResult=${w.lastWriteResult}"
        )
        appendLine(
            "recovery: rebuilds=${w.rebuilds} zeroWrites=${w.zeroWrites} stalls=${w.stalls} " +
                "pendingBytes=${w.pendingBytes} droppedBytes=${w.droppedBytes} gaveUp=${w.gaveUp}"
        )
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

    /**
     * Makes sure a track with this format exists and is attached to the writer.
     * A format change releases the old track and discards pending samples of the old format.
     */
    private fun ensureAudioTrack(sampleRate: Int, channels: Int, format: PcmFormat): Boolean {
        writeLock.withLock {
            val existing = sink
            if (existing != null
                && sampleRate == currentSampleRate
                && channels == currentChannels
                && format == currentFormat
                && existing.track.state == AudioTrack.STATE_INITIALIZED
                && writer.hasSink()
            ) {
                return true
            }

            // After the writer exhausted its rebuild budget, do not recreate the track on
            // every frame: wait one window before trying the output again.
            val sameFormat = sampleRate == currentSampleRate && channels == currentChannels && format == currentFormat
            if (sameFormat && writer.snapshot().gaveUp &&
                System.currentTimeMillis() - gaveUpAtMs < PcmWriter.DEFAULT_REBUILD_WINDOW_MS
            ) {
                return false
            }

            // Otherwise recreate; samples of a different format must not be replayed.
            releaseAudioTrack()

            val newSink = createTrack(sampleRate, channels, format) ?: return false
            sink = newSink
            currentSampleRate = sampleRate
            currentChannels = channels
            currentFormat = format
            writer.attach(newSink)
            outputFailureReported = false
            return true
        }
    }

    /** Builds and starts an AudioTrack for the given format; null (and a user warning) on failure. */
    private fun createTrack(sampleRate: Int, channels: Int, format: PcmFormat): AudioTrackSink? {
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
            return null
        }

        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        val frameSize = bytesPerSample * max(1, channels)
        val bufferSize = max(minBufferSize, frameSize * 1024)

        return try {
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
                return null
            }

            lastVolume?.let { newTrack.setVolume(it) }
            newTrack.play()

            AudioDiagnostics.record(
                TAG,
                "AudioTrack created: sampleRate=$sampleRate, channels=$channels, encoding=$encoding, " +
                    "buffer=$bufferSize, minBuffer=$minBufferSize, playState=${newTrack.playState}, " +
                    "route=${describeRoute(newTrack)}"
            )
            AudioTrackSink(newTrack)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception while creating AudioTrack", t)
            AudioDiagnostics.record(TAG, "AudioTrack creation threw $t")
            reportOutputFailure(t.javaClass.simpleName + ": " + (t.message ?: ""))
            null
        }
    }

    /**
     * Called by the writer (under its lock, on the PCM thread) after `ERROR_DEAD_OBJECT`.
     * The dead track was already released by the writer; recreate one with the same format.
     */
    private fun rebuildSinkForWriter(): AudioSink? {
        val sampleRate = currentSampleRate
        val channels = currentChannels
        val format = currentFormat
        if (sampleRate <= 0 || channels <= 0 || format == null) {
            sink = null
            return null
        }
        val replacement = createTrack(sampleRate, channels, format)
        sink = replacement
        return replacement
    }

    /** Writer events arrive outside the writer lock; they only log and warn. */
    private fun onWriterEvent(event: PcmWriter.Event) {
        when (event) {
            is PcmWriter.Event.WriteProblem -> {
                val w = writer.snapshot()
                if (w.writeErrors + w.partialWrites <= 5 || (w.writeErrors + w.partialWrites) % 500 == 0L) {
                    AudioDiagnostics.record(
                        TAG,
                        "AudioTrack.write ${event.message} (result=${event.result}, playState=${sink?.track?.playState})"
                    )
                }
            }
            is PcmWriter.Event.SinkRebuilt ->
                AudioDiagnostics.record(TAG, "audio output rebuilt after dead object (attempt ${event.attempt})")
            is PcmWriter.Event.Stalled -> {
                val w = writer.snapshot()
                if (w.stalls <= 5 || w.stalls % 100 == 0L) {
                    AudioDiagnostics.record(TAG, "audio output stalled, ${event.pendingBytes} bytes retained")
                }
            }
            is PcmWriter.Event.OutputFailure -> {
                gaveUpAtMs = System.currentTimeMillis()
                reportOutputFailure(event.reason)
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

    /** Explicit stop: releases the track and drops any samples still pending. */
    fun releaseAudioTrack() {
        writeLock.withLock {
            drainSonic()
            writer.close()
            sink = null
            currentSampleRate = -1
            currentChannels = -1
            currentFormat = null
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
            sink?.track?.let {
                try {
                    it.pause()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "pause() failed", e)
                }
            }
        }
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, AudioTrack.getMaxVolume())
        lastVolume = clamped
        sink?.track?.setVolume(clamped)
    }

    /** Explicit flush (seek): pending samples belong to the old position and are dropped. */
    fun flush() {
        writeLock.withLock {
            drainSonic()
            writer.discardPending()
            sink?.track?.let {
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
            PcmActivity.noteFrame()
            lastPcmSize = size
            lastPcmSampleRate = sampleRate
            lastPcmChannels = channels
            if (framesReceived == 1L || framesReceived % 500 == 0L) {
                val w = writer.snapshot()
                AudioDiagnostics.record(
                    TAG,
                    "pcm frame #$framesReceived size=$size rate=$sampleRate channels=$channels " +
                        "written=${w.bytesWritten} errors=${w.writeErrors} dropped=$droppedFrames " +
                        "rebuilds=${w.rebuilds} pending=${w.pendingBytes} " +
                        "head=${sink?.track?.playbackHeadPosition ?: -1} underruns=${sink?.track?.underrunCount ?: -1}"
                )
            }

            if (!ensureAudioTrack(sampleRate, channels, PcmFormat.S16)) {
                droppedFrames++
                Log.w(TAG, "ensureAudioTrack failed - dropping frame")
                return
            }

            val cap = pcmBuffer.capacity()
            if (size > cap) {
                droppedFrames++
                Log.w(TAG, "pcm size $size > buffer capacity $cap; dropping frame")
                return
            }

            pcmBuffer.position(0)
            pcmBuffer.limit(size)

            try {
                val speed = stateHolder.state.value.playbackSpeed.coerceAtLeast(0.1f)
                if (speed == 1f) {
                    writeToSink(pcmBuffer, size)
                } else {
                    prepareSonic(sampleRate, channels, speed)
                    sonic.queueInput(pcmBuffer)
                    drainSonicToSink()
                }
            } catch (ise: IllegalStateException) {
                Log.e(TAG, "AudioTrack write failed", ise)
            } finally {
                // Safe to reset: whatever the sink did not take was copied into the writer's
                // pending store, so nothing is lost here.
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

    private fun drainSonicToSink() {
        var output = sonic.getOutput()
        while (output.hasRemaining()) {
            writeToSink(output, output.remaining())
            output = sonic.getOutput()
        }
    }

    private fun drainSonic() {
        if (sonicSampleRate < 0) return
        sonic.queueEndOfStream()
        var output = sonic.getOutput()
        while (writer.hasSink() && output.hasRemaining()) {
            writeToSink(output, output.remaining())
            output = sonic.getOutput()
        }
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
        sonicCommittedSpeed = 1f
    }

    private fun writeToSink(buffer: ByteBuffer, size: Int) {
        when (writer.write(buffer, size)) {
            PcmWriter.Outcome.COMPLETE, PcmWriter.Outcome.STALLED -> Unit
            PcmWriter.Outcome.FAILED, PcmWriter.Outcome.DROPPED, PcmWriter.Outcome.CLOSED -> droppedFrames++
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
