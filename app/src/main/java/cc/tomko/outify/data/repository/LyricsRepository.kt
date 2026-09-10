package cc.tomko.outify.data.repository

import cc.tomko.outify.core.model.LyricsResult
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.remote.LrcLibClient
import cc.tomko.outify.diagnostics.AudioDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val FALLBACK_TIMEOUT_MS = 10_000L
private const val MAX_CACHE_ENTRIES = 64

/** Short label for the diagnostics report: a found result also says whether it is synced. */
private fun LyricsResult.kind(): String = when (this) {
    is LyricsResult.Found -> "found(synced=${synced}, source=$source)"
    LyricsResult.NotFound -> "notFound"
    LyricsResult.Error -> "error"
}

/**
 * Single entry point for lyrics: Spotify first, then LRCLIB when the user allows it.
 *
 * The repository owns the cache AND its validity, so no caller needs to know the rules:
 * - [LyricsResult.Found] is valid forever (the song has lyrics, whatever the settings are).
 * - [LyricsResult.NotFound] is valid only for the provider set it was computed with
 *   (the fallback toggle) and only for [NEGATIVE_TTL_MS]; after that it is looked up again.
 * - [LyricsResult.Error] is transient (timeout, network, parse) and is never stored.
 *
 * Concurrent lookups for the same track and provider set share one in-flight request that
 * runs in the repository's own scope, so cancelling one caller never fails the others.
 */
@Singleton
class LyricsRepository internal constructor(
    private val spotifySource: suspend (Track) -> LyricsResult,
    private val fallbackSource: suspend (Track) -> LyricsResult,
    private val fallbackEnabledFlow: Flow<Boolean>,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        playerRepository: PlayerRepository,
        lrcLibClient: LrcLibClient,
        settingsRepository: SettingsRepository,
    ) : this(
        spotifySource = { track -> playerRepository.getSpotifyLyrics(track) },
        fallbackSource = { track -> lrcLibClient.getLyrics(track) },
        fallbackEnabledFlow = settingsRepository.lyricsFallbackEnabled,
        clock = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    companion object {
        /** How long a "no lyrics anywhere" answer is trusted before asking the providers again. */
        const val NEGATIVE_TTL_MS = 6L * 60 * 60 * 1000
    }

    private data class CacheEntry(
        val fallbackEnabled: Boolean,
        val result: LyricsResult,
        val storedAtMs: Long,
    )

    private data class InFlightKey(val trackId: String, val fallbackEnabled: Boolean)

    private val cache: MutableMap<String, CacheEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    private val inFlight = HashMap<InFlightKey, Deferred<LyricsResult>>()

    /**
     * Last value seen on the settings flow; null until the first emission. Lets [peek] apply
     * the same validity rules synchronously without touching DataStore.
     */
    @Volatile
    private var observedFallbackEnabled: Boolean? = null

    init {
        scope.launch {
            fallbackEnabledFlow.collect { observedFallbackEnabled = it }
        }
    }

    /**
     * Synchronous cache lookup for callers that want to avoid a loading state on a hit.
     * Returns null on a miss, on a stale entry, or before the settings have been observed;
     * in every one of those cases the caller must fall through to [getLyrics].
     */
    fun peek(track: Track): LyricsResult? {
        val fallbackEnabled = (fallbackEnabledFlow as? StateFlow<Boolean>)?.value
            ?: observedFallbackEnabled
            ?: return null
        return validEntry(track.id, fallbackEnabled)?.result
    }

    suspend fun getLyrics(track: Track): LyricsResult {
        val fallbackEnabled = fallbackEnabledFlow.first()
        observedFallbackEnabled = fallbackEnabled

        validEntry(track.id, fallbackEnabled)?.let { return it.result }

        val key = InFlightKey(track.id, fallbackEnabled)
        val request = synchronized(inFlight) {
            inFlight[key] ?: scope.async { fetchAndStore(track, fallbackEnabled) }.also { started ->
                inFlight[key] = started
                started.invokeOnCompletion {
                    synchronized(inFlight) {
                        if (inFlight[key] === started) inFlight.remove(key)
                    }
                }
            }
        }
        return request.await()
    }

    private fun validEntry(trackId: String, fallbackEnabled: Boolean): CacheEntry? {
        val entry = cache[trackId] ?: return null
        val valid = when (entry.result) {
            is LyricsResult.Found -> true
            LyricsResult.NotFound ->
                entry.fallbackEnabled == fallbackEnabled &&
                    clock() - entry.storedAtMs <= NEGATIVE_TTL_MS
            LyricsResult.Error -> false
        }
        if (!valid) cache.remove(trackId)
        return entry.takeIf { valid }
    }

    private suspend fun fetchAndStore(track: Track, fallbackEnabled: Boolean): LyricsResult {
        val result = try {
            resolve(track, fallbackEnabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LyricsResult.Error
        }
        if (result !is LyricsResult.Error) {
            cache[track.id] = CacheEntry(fallbackEnabled, result, clock())
        }
        return result
    }

    private suspend fun resolve(track: Track, fallbackEnabled: Boolean): LyricsResult {
        val spotify = spotifySource(track)
        AudioDiagnostics.record(
            "Lyrics",
            "spotify=${spotify.kind()} fallbackEnabled=$fallbackEnabled track=${track.id}"
        )
        if (spotify is LyricsResult.Found || !fallbackEnabled) return spotify

        val fallback = withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
            fallbackSource(track)
        } ?: LyricsResult.Error
        AudioDiagnostics.record("Lyrics", "lrclib=${fallback.kind()} track=${track.id}")

        return when (fallback) {
            is LyricsResult.Found -> fallback
            // Both providers definitively have nothing: safe to remember
            LyricsResult.NotFound -> LyricsResult.NotFound
            // Spotify said "no" but LRCLIB could not answer: retry next time
            LyricsResult.Error -> LyricsResult.Error
        }
    }
}
