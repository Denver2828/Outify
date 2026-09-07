package cc.tomko.outify.playback.audio

import java.nio.ByteBuffer

/**
 * Minimal PCM output abstraction so the write/recovery policy can be exercised without
 * an Android AudioTrack. Semantics mirror `AudioTrack.write(ByteBuffer, int, WRITE_BLOCKING)`.
 */
interface AudioSink {
    /**
     * Writes up to [sizeInBytes] from the buffer's current position, advancing it by the
     * amount written. Returns the number of bytes written (0..sizeInBytes) or one of the
     * negative codes in [AudioSinkErrors]. A short count means the sink was stopped, paused
     * or hit an I/O problem mid-write.
     */
    fun write(buffer: ByteBuffer, sizeInBytes: Int): Int

    /** Releases the underlying output. Calling [write] afterwards is a programming error. */
    fun release()
}

/**
 * Error codes as returned by `android.media.AudioTrack.write`, duplicated here so the policy
 * stays free of Android classes. Values must match the platform constants.
 */
object AudioSinkErrors {
    /** AudioTrack.ERROR: generic failure, may be transient. */
    const val ERROR = -1

    /** AudioTrack.ERROR_BAD_VALUE: invalid arguments, never transient. */
    const val ERROR_BAD_VALUE = -2

    /** AudioTrack.ERROR_INVALID_OPERATION: track not initialized/usable, never transient. */
    const val ERROR_INVALID_OPERATION = -3

    /** AudioTrack.ERROR_DEAD_OBJECT: the audio server side died; the track must be recreated. */
    const val ERROR_DEAD_OBJECT = -6
}
