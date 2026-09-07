package cc.tomko.outify.playback

/**
 * Wall-clock time of the last PCM frame the audio engine received from librespot.
 *
 * The UI position is extrapolated from the last position librespot reported, which keeps
 * advancing while the stream is stalled (no network, session reconnecting) even though no
 * audio is playing. Reading this stamp lets the estimate freeze at the moment audio stopped.
 */
object PcmActivity {
    /** Milliseconds without a PCM frame after which playback is considered stalled. */
    const val STALL_AFTER_MS = 1_500L

    @Volatile
    var lastFrameAtMs: Long = 0L

    fun noteFrame(nowMs: Long = System.currentTimeMillis()) {
        lastFrameAtMs = nowMs
    }

    /**
     * Upper bound of the wall-clock interval the position may be extrapolated over: [nowMs]
     * while audio flows, the last frame time once the stream stalled. Pure for tests.
     */
    fun extrapolationEndMs(nowMs: Long, lastFrameAtMs: Long, stallAfterMs: Long = STALL_AFTER_MS): Long {
        if (lastFrameAtMs <= 0L) return nowMs
        return if (nowMs - lastFrameAtMs > stallAfterMs) lastFrameAtMs else nowMs
    }
}
