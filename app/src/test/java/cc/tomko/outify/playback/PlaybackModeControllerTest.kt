package cc.tomko.outify.playback

import cc.tomko.outify.playback.model.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModeControllerTest {

    /** Persisted settings and the Spirc side, both faked; the state holder is the real one. */
    private class Harness(acceptRepeat: Boolean = true, acceptShuffle: Boolean = true) {
        val stateHolder = PlaybackStateHolder()
        val persistedRepeat = MutableStateFlow(RepeatMode.NONE)
        val persistedShuffle = MutableStateFlow(false)
        val spircRepeatCalls = mutableListOf<Pair<Boolean, Boolean>>()
        val spircShuffleCalls = mutableListOf<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val controller = PlaybackModeController(
            stateHolder = stateHolder,
            repeatModeFlow = persistedRepeat,
            shuffleFlow = persistedShuffle,
            persistRepeat = { persistedRepeat.value = it },
            persistShuffle = { persistedShuffle.value = it },
            applyRepeat = { repeat, repeatTrack ->
                spircRepeatCalls += repeat to repeatTrack
                acceptRepeat
            },
            applyShuffle = { enabled ->
                spircShuffleCalls += enabled
                acceptShuffle
            },
            ioDispatcher = Dispatchers.Default,
            scope = scope,
        )

        suspend fun awaitReported(repeat: RepeatMode, shuffle: Boolean) = withTimeout(2_000) {
            while (stateHolder.state.value.repeatMode != repeat ||
                stateHolder.state.value.shuffleEnabled != shuffle
            ) yield()
        }

        fun close() = scope.cancel()
    }

    @Test
    fun `setRepeatMode issues the spirc call, persists and updates the reported mode`() = runBlocking {
        val h = Harness()
        try {
            val accepted = h.controller.setRepeatMode(RepeatMode.ONE)

            assertTrue(accepted)
            assertEquals(listOf(true to true), h.spircRepeatCalls)
            assertEquals(RepeatMode.ONE, h.persistedRepeat.value)
            h.awaitReported(RepeatMode.ONE, shuffle = false)
        } finally {
            h.close()
        }
    }

    @Test
    fun `toggleRepeatMode cycles from the persisted mode`() = runBlocking {
        val h = Harness()
        try {
            h.persistedRepeat.value = RepeatMode.ALL

            assertEquals(RepeatMode.ONE, h.controller.toggleRepeatMode())
            assertEquals(RepeatMode.NONE, h.controller.toggleRepeatMode())
            assertEquals(RepeatMode.ALL, h.controller.toggleRepeatMode())
            assertEquals(listOf(true to true, false to false, true to false), h.spircRepeatCalls)
            h.awaitReported(RepeatMode.ALL, shuffle = false)
        } finally {
            h.close()
        }
    }

    @Test
    fun `setShuffleEnabled issues the spirc call, persists and updates the reported state`() = runBlocking {
        val h = Harness()
        try {
            assertTrue(h.controller.setShuffleEnabled(true))
            assertEquals(listOf(true), h.spircShuffleCalls)
            assertTrue(h.persistedShuffle.value)
            h.awaitReported(RepeatMode.NONE, shuffle = true)

            assertTrue(h.controller.toggleShuffle().not())
            assertEquals(listOf(true, false), h.spircShuffleCalls)
            h.awaitReported(RepeatMode.NONE, shuffle = false)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a rejected spirc call neither persists nor changes the reported mode`() = runBlocking {
        val h = Harness(acceptRepeat = false, acceptShuffle = false)
        try {
            assertFalse(h.controller.setRepeatMode(RepeatMode.ALL))
            assertFalse(h.controller.setShuffleEnabled(true))

            assertEquals(listOf(true to false), h.spircRepeatCalls)
            assertEquals(RepeatMode.NONE, h.persistedRepeat.value)
            assertFalse(h.persistedShuffle.value)
            yield()
            assertEquals(RepeatMode.NONE, h.stateHolder.state.value.repeatMode)
            assertFalse(h.stateHolder.state.value.shuffleEnabled)
        } finally {
            h.close()
        }
    }

    @Test
    fun `modes written by another owner are mirrored into the reported state`() = runBlocking {
        val h = Harness()
        try {
            // e.g. SpircController re-applying persisted settings on session start
            h.persistedRepeat.value = RepeatMode.ALL
            h.persistedShuffle.value = true

            h.awaitReported(RepeatMode.ALL, shuffle = true)
            assertTrue(h.spircRepeatCalls.isEmpty())
        } finally {
            h.close()
        }
    }
}
