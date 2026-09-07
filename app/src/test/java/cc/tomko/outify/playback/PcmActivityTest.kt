package cc.tomko.outify.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmActivityTest {
    @Test
    fun `no frame seen yet keeps extrapolating to now`() {
        assertEquals(10_000L, PcmActivity.extrapolationEndMs(nowMs = 10_000L, lastFrameAtMs = 0L))
    }

    @Test
    fun `recent frame keeps extrapolating to now`() {
        assertEquals(10_000L, PcmActivity.extrapolationEndMs(nowMs = 10_000L, lastFrameAtMs = 9_000L))
    }

    @Test
    fun `stalled stream freezes the clock at the last frame`() {
        assertEquals(5_000L, PcmActivity.extrapolationEndMs(nowMs = 10_000L, lastFrameAtMs = 5_000L))
    }

    @Test
    fun `stall threshold is inclusive of the boundary`() {
        val boundary = 10_000L - PcmActivity.STALL_AFTER_MS
        assertEquals(10_000L, PcmActivity.extrapolationEndMs(nowMs = 10_000L, lastFrameAtMs = boundary))
        assertEquals(boundary - 1, PcmActivity.extrapolationEndMs(nowMs = 10_000L, lastFrameAtMs = boundary - 1))
    }
}
