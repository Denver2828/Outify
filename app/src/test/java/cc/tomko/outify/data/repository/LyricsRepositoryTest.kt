package cc.tomko.outify.data.repository

import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.LyricsResult
import cc.tomko.outify.core.model.LyricsSource
import cc.tomko.outify.core.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private class Harness(
        spotify: LyricsResult = LyricsResult.NotFound,
        lrclib: LyricsResult = LyricsResult.NotFound,
        fallbackEnabled: Boolean = false,
    ) {
        val clock = FakeClock()
        val fallback = MutableStateFlow(fallbackEnabled)
        var spotifyResult = spotify
        var lrclibResult = lrclib
        @Volatile var spotifyCalls = 0
        @Volatile var lrclibCalls = 0

        /** When set, the LRCLIB source suspends until this completes (for concurrency tests). */
        var lrclibGate: CompletableDeferred<Unit>? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val repository = LyricsRepository(
            spotifySource = { spotifyCalls++; spotifyResult },
            fallbackSource = {
                lrclibCalls++
                lrclibGate?.await()
                lrclibResult
            },
            fallbackEnabledFlow = fallback,
            clock = clock,
            scope = scope,
        )
    }

    private val track = Track(id = "t1", uri = "spotify:track:t1", name = "Song", files = emptyList())

    private val found = LyricsResult.Found(
        lines = listOf(LyricLine(0L, "la la")),
        source = LyricsSource.LRCLIB,
        synced = true,
    )

    @Test
    fun `plain Spotify lyrics upgrade only when fallback enabled including cache changes`() = runBlocking {
        val plain = found.copy(source = LyricsSource.SPOTIFY, synced = false)
        val h = Harness(spotify = plain, lrclib = found)
        assertEquals(plain, h.repository.getLyrics(track))
        assertEquals(0, h.lrclibCalls)
        h.fallback.value = true
        assertNull(h.repository.peek(track))
        assertEquals(found, h.repository.getLyrics(track))
        assertEquals(1, h.lrclibCalls)
    }

    @Test
    fun `missing failed or plain fallback retains Spotify text`() = runBlocking {
        val plain = found.copy(source = LyricsSource.SPOTIFY, synced = false)
        for (fallback in listOf(LyricsResult.NotFound, LyricsResult.Error, found.copy(synced = false))) {
            val h = Harness(spotify = plain, lrclib = fallback, fallbackEnabled = true)
            assertEquals(plain, h.repository.getLyrics(track))
            assertEquals(1, h.lrclibCalls)
        }
    }

    @Test
    fun `synchronized Spotify lyrics never query fallback`() = runBlocking {
        val h = Harness(spotify = found, lrclib = found, fallbackEnabled = true)
        assertEquals(found, h.repository.getLyrics(track))
        assertEquals(0, h.lrclibCalls)
    }

    @Test
    fun `not found cached with fallback off is refetched through lrclib once fallback is on`() = runBlocking {
        val h = Harness(spotify = LyricsResult.NotFound, lrclib = found, fallbackEnabled = false)

        assertEquals(LyricsResult.NotFound, h.repository.getLyrics(track))
        assertEquals(1, h.spotifyCalls)
        assertEquals(0, h.lrclibCalls)
        assertEquals(LyricsResult.NotFound, h.repository.peek(track))

        h.fallback.value = true
        yield()

        // The stale negative answer must not be offered as a fast-path hit
        assertNull(h.repository.peek(track))

        assertEquals(found, h.repository.getLyrics(track))
        assertEquals(2, h.spotifyCalls)
        assertEquals(1, h.lrclibCalls)
        assertEquals(found, h.repository.peek(track))
    }

    @Test
    fun `not found older than the ttl is refetched and younger is served from cache`() = runBlocking {
        val h = Harness(spotify = LyricsResult.NotFound, fallbackEnabled = false)

        assertEquals(LyricsResult.NotFound, h.repository.getLyrics(track))
        assertEquals(1, h.spotifyCalls)

        h.clock.now += LyricsRepository.NEGATIVE_TTL_MS - 1
        assertEquals(LyricsResult.NotFound, h.repository.peek(track))
        assertEquals(LyricsResult.NotFound, h.repository.getLyrics(track))
        assertEquals(1, h.spotifyCalls)

        h.clock.now += 2
        assertNull(h.repository.peek(track))
        assertEquals(LyricsResult.NotFound, h.repository.getLyrics(track))
        assertEquals(2, h.spotifyCalls)
    }

    @Test
    fun `found is served from cache regardless of config change and age`() = runBlocking {
        val h = Harness(spotify = found, fallbackEnabled = false)

        assertEquals(found, h.repository.getLyrics(track))
        assertEquals(1, h.spotifyCalls)

        h.fallback.value = true
        yield()
        h.clock.now += LyricsRepository.NEGATIVE_TTL_MS * 10

        assertEquals(found, h.repository.peek(track))
        assertEquals(found, h.repository.getLyrics(track))
        assertEquals(1, h.spotifyCalls)
        assertEquals(0, h.lrclibCalls)
    }

    @Test
    fun `error is never cached so the next lookup fetches again`() = runBlocking {
        val h = Harness(spotify = LyricsResult.Error, fallbackEnabled = false)

        assertEquals(LyricsResult.Error, h.repository.getLyrics(track))
        assertNull(h.repository.peek(track))
        assertEquals(LyricsResult.Error, h.repository.getLyrics(track))
        assertEquals(2, h.spotifyCalls)
    }

    @Test
    fun `concurrent callers share one fetch and cancelling one does not fail the other`() = runBlocking {
        val h = Harness(spotify = LyricsResult.NotFound, lrclib = found, fallbackEnabled = true)
        h.lrclibGate = CompletableDeferred()

        val first = async { h.repository.getLyrics(track) }
        val second = async { h.repository.getLyrics(track) }
        // Let both callers reach the shared in-flight request
        while (h.lrclibCalls == 0) yield()
        yield()

        first.cancelAndJoin()
        assertTrue(first.isCancelled)

        h.lrclibGate!!.complete(Unit)
        val result = withTimeout(5_000) { second.await() }

        assertEquals(found, result)
        assertEquals(1, h.spotifyCalls)
        assertEquals(1, h.lrclibCalls)
        assertEquals(found, h.repository.peek(track))
    }
}
