package cc.tomko.outify.data.repository

import cc.tomko.outify.core.model.LyricsResult
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.remote.LrcLibClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val FALLBACK_TIMEOUT_MS = 10_000L
private const val MAX_CACHE_ENTRIES = 64

/**
 * Single entry point for lyrics: Spotify first, then LRCLIB when the user allows it.
 *
 * Results are cached per track id so every screen (player card, sheet, track detail)
 * shares one lookup. [LyricsResult.Found] and [LyricsResult.NotFound] are cached;
 * [LyricsResult.Error] is not, so a later open retries.
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val lrcLibClient: LrcLibClient,
    private val settingsRepository: SettingsRepository,
) {
    private data class CacheEntry(val fallbackEnabled: Boolean, val result: LyricsResult)

    private val cache: MutableMap<String, CacheEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    fun cached(track: Track): LyricsResult? = cache[track.id]?.result

    suspend fun getLyrics(track: Track): LyricsResult {
        val fallbackEnabled = settingsRepository.lyricsFallbackEnabled.first()

        cache[track.id]?.let { entry ->
            // A "not found" answer is only valid for the provider set it was computed with
            if (entry.result is LyricsResult.Found || entry.fallbackEnabled == fallbackEnabled) {
                return entry.result
            }
        }

        val result = resolve(track, fallbackEnabled)
        if (result !is LyricsResult.Error) {
            cache[track.id] = CacheEntry(fallbackEnabled, result)
        }
        return result
    }

    private suspend fun resolve(track: Track, fallbackEnabled: Boolean): LyricsResult {
        val spotify = playerRepository.getSpotifyLyrics(track)
        if (spotify is LyricsResult.Found || !fallbackEnabled) return spotify

        val fallback = withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
            lrcLibClient.getLyrics(track)
        } ?: LyricsResult.Error

        return when (fallback) {
            is LyricsResult.Found -> fallback
            // Both providers definitively have nothing: safe to remember
            LyricsResult.NotFound -> LyricsResult.NotFound
            // Spotify said "no" but LRCLIB could not answer: retry next time
            LyricsResult.Error -> LyricsResult.Error
        }
    }
}
