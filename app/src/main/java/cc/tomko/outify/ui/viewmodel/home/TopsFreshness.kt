package cc.tomko.outify.ui.viewmodel.home

/** What Home should do with the cached top artists/tracks on load. */
enum class TopsDecision {
    /** Cache is fresh: show it and make no network call. */
    SERVE_CACHE,

    /** Cache exists but is stale: show it, then refresh in the background. */
    REFRESH_IN_BACKGROUND,

    /** Nothing usable cached: fetch, with the loading state on screen. */
    FETCH,
}

/**
 * Freshness rule for the cached tops. Every launch used to refresh them, which on a
 * development-mode Spotify quota is enough to earn a 429 by itself.
 */
object TopsFreshness {
    const val TOPS_TTL_MS = 30L * 60_000L

    /**
     * @param hasCache whether a usable cached entry exists for the selected duration
     * @param savedAtMs when the cache was written, 0 when unknown (treated as stale)
     * @param forced pull-to-refresh / retry: always refresh, keeping the cache on screen
     */
    fun decide(hasCache: Boolean, savedAtMs: Long, nowMs: Long, forced: Boolean): TopsDecision {
        if (!hasCache) return TopsDecision.FETCH
        if (forced) return TopsDecision.REFRESH_IN_BACKGROUND
        val age = nowMs - savedAtMs
        val fresh = savedAtMs > 0L && age in 0 until TOPS_TTL_MS
        return if (fresh) TopsDecision.SERVE_CACHE else TopsDecision.REFRESH_IN_BACKGROUND
    }
}
