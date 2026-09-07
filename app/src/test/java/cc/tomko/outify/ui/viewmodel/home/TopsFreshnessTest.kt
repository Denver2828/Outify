package cc.tomko.outify.ui.viewmodel.home

import org.junit.Assert.assertEquals
import org.junit.Test

class TopsFreshnessTest {

    private val now = 10_000_000L

    @Test
    fun `no cache fetches`() {
        assertEquals(TopsDecision.FETCH, TopsFreshness.decide(hasCache = false, savedAtMs = now, nowMs = now, forced = false))
        assertEquals(TopsDecision.FETCH, TopsFreshness.decide(hasCache = false, savedAtMs = 0L, nowMs = now, forced = true))
    }

    @Test
    fun `fresh cache is served without network`() {
        val savedAt = now - (TopsFreshness.TOPS_TTL_MS - 1L)
        assertEquals(TopsDecision.SERVE_CACHE, TopsFreshness.decide(true, savedAt, now, forced = false))
    }

    @Test
    fun `cache at the ttl boundary refreshes in the background`() {
        val savedAt = now - TopsFreshness.TOPS_TTL_MS
        assertEquals(TopsDecision.REFRESH_IN_BACKGROUND, TopsFreshness.decide(true, savedAt, now, forced = false))
    }

    @Test
    fun `legacy cache without a stamp refreshes in the background`() {
        assertEquals(TopsDecision.REFRESH_IN_BACKGROUND, TopsFreshness.decide(true, 0L, now, forced = false))
    }

    @Test
    fun `forced load refreshes even a fresh cache`() {
        assertEquals(TopsDecision.REFRESH_IN_BACKGROUND, TopsFreshness.decide(true, now, now, forced = true))
    }

    @Test
    fun `a stamp from the future counts as stale`() {
        assertEquals(TopsDecision.REFRESH_IN_BACKGROUND, TopsFreshness.decide(true, now + 1L, now, forced = false))
    }
}
