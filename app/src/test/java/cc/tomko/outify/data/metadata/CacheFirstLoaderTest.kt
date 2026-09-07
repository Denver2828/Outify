package cc.tomko.outify.data.metadata

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CacheFirstLoaderTest {

    private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private class Harness(
        val clock: FakeClock = FakeClock(),
        ttlMs: Long = TTL,
        var cached: CacheEntry<String>? = null,
        var remote: () -> String = { "fresh" },
    ) {
        val loader = CacheFirstLoader<String>(ttlMs = ttlMs, clock = clock)
        var fetchCalls = 0
        var cachedSeenByRefresh: String? = null

        fun load(force: Boolean = false): List<CacheFirstResult<String>> = runBlocking {
            loader.load(
                force = force,
                readCache = { cached },
                refresh = { seen ->
                    fetchCalls++
                    cachedSeenByRefresh = seen
                    remote()
                },
                classify = { RefreshFailure.classify(it, gateLimited = false) },
            ).toList()
        }
    }

    @Test
    fun `fresh cache is emitted first and no refresh happens`() {
        val h = Harness(cached = CacheEntry("cached", storedAtMs = 1_000_000L - TTL / 2))

        val results = h.load()

        assertEquals(listOf(CacheFirstResult.Cached("cached", stale = false)), results)
        assertEquals(0, h.fetchCalls)
    }

    @Test
    fun `stale cache is emitted first then replaced by the refresh`() {
        val h = Harness(cached = CacheEntry("cached", storedAtMs = 1_000_000L - TTL - 1))

        val results = h.load()

        assertEquals(
            listOf(
                CacheFirstResult.Cached("cached", stale = true),
                CacheFirstResult.Fresh("fresh"),
            ),
            results,
        )
        assertEquals(1, h.fetchCalls)
        assertEquals("cached", h.cachedSeenByRefresh)
    }

    @Test
    fun `failed refresh keeps the cached value and reports the kind`() {
        val h = Harness(
            cached = CacheEntry("cached", storedAtMs = 0L),
            remote = { throw IOException("unreachable") },
        )

        val results = h.load()

        assertEquals(
            listOf(
                CacheFirstResult.Cached("cached", stale = true),
                CacheFirstResult.RefreshFailed(RefreshFailure.NETWORK, "cached"),
            ),
            results,
        )
    }

    @Test
    fun `rate limited refresh is reported as such`() {
        val h = Harness(
            cached = CacheEntry("cached", storedAtMs = 0L),
            remote = { throw RateLimitException("429", 30) },
        )

        val last = h.load().last()

        assertEquals(CacheFirstResult.RefreshFailed(RefreshFailure.RATE_LIMITED, "cached"), last)
    }

    @Test
    fun `no cache and failed refresh reports failure without a value`() {
        val h = Harness(cached = null, remote = { throw IllegalStateException("boom") })

        val results = h.load()

        assertEquals(listOf(CacheFirstResult.RefreshFailed(RefreshFailure.OTHER, null)), results)
    }

    @Test
    fun `no cache and successful refresh emits only fresh`() {
        val h = Harness(cached = null)

        val results = h.load()

        assertEquals(listOf(CacheFirstResult.Fresh("fresh")), results)
    }

    @Test
    fun `ttl boundary - exactly ttl old is stale, one ms younger is fresh`() {
        val atTtl = Harness(cached = CacheEntry("cached", storedAtMs = 1_000_000L - TTL))
        val underTtl = Harness(cached = CacheEntry("cached", storedAtMs = 1_000_000L - TTL + 1))

        assertTrue((atTtl.load().first() as CacheFirstResult.Cached).stale)
        assertEquals(1, atTtl.fetchCalls)

        assertTrue(!(underTtl.load().first() as CacheFirstResult.Cached).stale)
        assertEquals(0, underTtl.fetchCalls)
    }

    @Test
    fun `manual refresh bypasses the ttl`() {
        val h = Harness(cached = CacheEntry("cached", storedAtMs = 1_000_000L))

        val results = h.load(force = true)

        assertEquals(
            listOf(
                CacheFirstResult.Cached("cached", stale = false),
                CacheFirstResult.Fresh("fresh"),
            ),
            results,
        )
        assertEquals(1, h.fetchCalls)
    }

    private companion object {
        const val TTL = 15 * 60 * 1000L
    }
}
