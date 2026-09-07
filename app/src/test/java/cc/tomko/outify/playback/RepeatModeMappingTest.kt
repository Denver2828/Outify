package cc.tomko.outify.playback

import androidx.media3.common.Player
import cc.tomko.outify.playback.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Media3 <-> Spirc repeat mapping. Spirc models repeat as (repeat, repeatTrack):
 * OFF = (false, false), ALL = (true, false), ONE = (true, true).
 */
class RepeatModeMappingTest {

    @Test
    fun `media3 constants map to the spirc pair`() {
        assertEquals(RepeatMode.NONE, RepeatMode.fromMediaRepeatMode(Player.REPEAT_MODE_OFF))
        assertEquals(RepeatMode.ALL, RepeatMode.fromMediaRepeatMode(Player.REPEAT_MODE_ALL))
        assertEquals(RepeatMode.ONE, RepeatMode.fromMediaRepeatMode(Player.REPEAT_MODE_ONE))

        assertEquals(false to false, RepeatMode.NONE.repeat to RepeatMode.NONE.repeatTrack)
        assertEquals(true to false, RepeatMode.ALL.repeat to RepeatMode.ALL.repeatTrack)
        assertEquals(true to true, RepeatMode.ONE.repeat to RepeatMode.ONE.repeatTrack)
    }

    @Test
    fun `spirc pairs map back to the media3 constants`() {
        assertEquals(Player.REPEAT_MODE_OFF, RepeatMode.fromSettings(repeat = false, repeatTrack = false).toMediaRepeatMode())
        assertEquals(Player.REPEAT_MODE_ALL, RepeatMode.fromSettings(repeat = true, repeatTrack = false).toMediaRepeatMode())
        assertEquals(Player.REPEAT_MODE_ONE, RepeatMode.fromSettings(repeat = true, repeatTrack = true).toMediaRepeatMode())
    }

    @Test
    fun `round trip is the identity for every mode`() {
        RepeatMode.entries.forEach { mode ->
            assertEquals(mode, RepeatMode.fromMediaRepeatMode(mode.toMediaRepeatMode()))
        }
    }

    @Test
    fun `unknown media3 values fall back to off`() {
        assertEquals(RepeatMode.NONE, RepeatMode.fromMediaRepeatMode(42))
    }
}
