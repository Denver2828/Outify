package cc.tomko.outify.data.remote

import cc.tomko.outify.core.model.LyricsResult
import cc.tomko.outify.core.model.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses two-digit fraction timestamps`() {
        val lines = LrcParser.parseSynced("[00:12.34] First line\n[01:02.50]Second line")

        assertEquals(2, lines.size)
        assertEquals(12_340L, lines[0].timestampMs)
        assertEquals("First line", lines[0].text)
        assertEquals(62_500L, lines[1].timestampMs)
        assertEquals("Second line", lines[1].text)
    }

    @Test
    fun `parses three-digit fraction and colon separators`() {
        val lines = LrcParser.parseSynced("[00:05.123]A\n[00:07:45]B")

        assertEquals(5_123L, lines[0].timestampMs)
        assertEquals(7_450L, lines[1].timestampMs)
    }

    @Test
    fun `skips blank lines and keeps output sorted`() {
        val lines = LrcParser.parseSynced("[00:20.00]Late\n[00:15.00]\n[00:10.00]Early\n\nplain junk")

        assertEquals(listOf(10_000L, 20_000L), lines.map { it.timestampMs })
        assertEquals(listOf("Early", "Late"), lines.map { it.text })
    }

    @Test
    fun `expands repeated timestamps on one line`() {
        val lines = LrcParser.parseSynced("[00:01.00][00:03.00]Chorus")

        assertEquals(listOf(1_000L, 3_000L), lines.map { it.timestampMs })
        assertTrue(lines.all { it.text == "Chorus" })
    }

    @Test
    fun `plain lyrics get zero timestamps`() {
        val lines = LrcParser.parsePlain("One\n\n  Two  \n")

        assertEquals(listOf("One", "Two"), lines.map { it.text })
        assertTrue(lines.all { it.timestampMs == 0L })
    }
}

class LrcLibMatchingTest {

    private fun track(
        duration: Double,
        synced: String? = null,
        plain: String? = null,
        instrumental: Boolean = false,
    ) = LrcLibTrack(
        id = duration.toLong(),
        trackName = "t",
        artistName = "a",
        duration = duration,
        instrumental = instrumental,
        plainLyrics = plain,
        syncedLyrics = synced,
    )

    @Test
    fun `prefers synced lyrics over a closer plain match`() {
        val plainExact = track(200.0, plain = "text")
        val syncedNear = track(203.0, synced = "[00:01.00]x")

        val best = pickBestMatch(listOf(plainExact, syncedNear), durationSeconds = 200)

        assertEquals(syncedNear, best)
    }

    @Test
    fun `discards candidates outside the duration tolerance`() {
        val tooLong = track(210.0, synced = "[00:01.00]x")
        val tooShort = track(190.0, plain = "text")

        assertNull(pickBestMatch(listOf(tooLong, tooShort), durationSeconds = 200))
    }

    @Test
    fun `among synced candidates picks the closest duration`() {
        val far = track(204.0, synced = "[00:01.00]x")
        val near = track(201.0, synced = "[00:01.00]y")

        assertEquals(near, pickBestMatch(listOf(far, near), durationSeconds = 200))
    }

    @Test
    fun `ignores instrumental and empty rows`() {
        val instrumental = track(200.0, synced = "[00:01.00]x", instrumental = true)
        val empty = track(200.0)

        assertNull(pickBestMatch(listOf(instrumental, empty), durationSeconds = 200))
    }

    @Test
    fun `synced row maps to a synced result from LRCLIB`() {
        val result = track(200.0, synced = "[00:01.00]x").toResult()

        assertTrue(result is LyricsResult.Found)
        result as LyricsResult.Found
        assertEquals(LyricsSource.LRCLIB, result.source)
        assertTrue(result.synced)
    }

    @Test
    fun `plain-only row maps to an unsynced result`() {
        val result = track(200.0, plain = "line one\nline two").toResult()

        result as LyricsResult.Found
        assertFalse(result.synced)
        assertEquals(2, result.lines.size)
    }

    @Test
    fun `instrumental row maps to not found`() {
        assertEquals(LyricsResult.NotFound, track(200.0, synced = "[00:01.00]x", instrumental = true).toResult())
    }
}

class LrcLibSelectionTest {
    private val plain = LrcLibTrack(duration = 200.0, plainLyrics = "Original")
    private val synced = LrcLibTrack(duration = 201.0, syncedLyrics = "[00:01.00]Timed")

    @Test fun `exact plain upgrades to synchronized search result`() = kotlinx.coroutines.runBlocking {
        val result = resolveLrcLib(plain, 200) { listOf(synced) } as LyricsResult.Found
        assertTrue(result.synced)
        assertEquals(1000L, result.lines.first().timestampMs)
    }

    @Test fun `exact synchronized lyrics never search`() = kotlinx.coroutines.runBlocking {
        assertEquals(synced.toResult(), resolveLrcLib(synced, 200) { error("unexpected search") })
    }

    @Test fun `missing or failed upgrade preserves exact plain`() = kotlinx.coroutines.runBlocking {
        assertEquals(plain.toResult(), resolveLrcLib(plain, 200) { emptyList() })
        assertEquals(plain.toResult(), resolveLrcLib(plain, 200) { throw java.io.IOException() })
        assertEquals(plain.toResult(), resolveLrcLib(plain, 200) { listOf(synced.copy(duration = 220.0)) })
    }

    @Test fun `malformed synchronized text cannot outrank usable timestamps`() {
        val malformed = plain.copy(syncedLyrics = "not timestamped")
        assertEquals(synced, pickBestMatch(listOf(malformed, synced), 200))
        assertEquals(plain, pickBestMatch(listOf(malformed.copy(plainLyrics = null), plain), 200))
    }

    @Test fun `upgrade cancellation propagates`() = kotlinx.coroutines.runBlocking {
        try {
            resolveLrcLib(plain, 200) { throw kotlinx.coroutines.CancellationException("cancel") }
            org.junit.Assert.fail("cancellation swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }
}
