package cc.tomko.outify.playback.audio

import android.media.AudioTrack
import java.nio.ByteBuffer

/**
 * Adapts a live [AudioTrack] to [AudioSink]. Blocking writes: on a playing track the call
 * returns once everything is queued; on a stopped/paused track or after an I/O problem it
 * returns a short count, which [PcmWriter] turns into retained samples instead of loss.
 */
class AudioTrackSink(val track: AudioTrack) : AudioSink {

    override fun write(buffer: ByteBuffer, sizeInBytes: Int): Int =
        track.write(buffer, sizeInBytes, AudioTrack.WRITE_BLOCKING)

    override fun release() {
        try {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                try {
                    track.stop()
                } catch (ignored: IllegalStateException) {
                    // already stopped or never started
                }
            }
        } catch (ignored: Exception) {
        } finally {
            try {
                track.release()
            } catch (ignored: Exception) {
            }
        }
    }
}
