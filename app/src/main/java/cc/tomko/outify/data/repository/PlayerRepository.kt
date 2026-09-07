package cc.tomko.outify.data.repository

import android.util.Log
import cc.tomko.outify.core.RadioResult
import cc.tomko.outify.core.RateLimitGate
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.LyricsResponse
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.LyricsResult
import cc.tomko.outify.core.model.LyricsSource
import cc.tomko.outify.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject

class PlayerRepository @Inject constructor(
    private val spClient: SpClient,
    private val json: Json,
    private val rateLimitGate: RateLimitGate,
) {
    /**
     * Resolves the radio playlist Spotify builds for [trackUri].
     *
     * Main-safe: the blocking JNI request runs on [Dispatchers.IO] and is bounded by
     * [timeoutMs]. The native request itself is not cancellable, so a timeout only stops
     * waiting for it; the result is dropped when it eventually arrives. Skipped while
     * Spotify is rate limiting us.
     *
     * @return the playlist uri, or `null` when there is no radio, the request failed,
     * timed out, or we are rate limited.
     */
    suspend fun getRadioPlaylistUri(trackUri: String, timeoutMs: Long = 8_000L): String? =
        withContext(Dispatchers.IO) {
            if (rateLimitGate.isLimited()) return@withContext null
            val raw = try {
                withTimeoutOrNull(timeoutMs) { spClient.getRadioForTrack(trackUri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "getRadioForTrack failed for $trackUri", e)
                null
            } ?: return@withContext null

            try {
                val result: RadioResult = json.decodeFromString(raw)
                if (result.total == 0 || result.mediaItems.isEmpty()) null
                else result.mediaItems.first().uri
            } catch (e: Exception) {
                Log.w(TAG, "radio payload could not be decoded for $trackUri", e)
                null
            }
        }

    private companion object {
        const val TAG = "PlayerRepository"
    }

    /**
     * Spotify's own lyrics for [track].
     *
     * The native call returns `null` both when Spotify answers 404 and when the request
     * itself fails (the JNI layer logs the cause and collapses both to `null`). We treat
     * `null` as [LyricsResult.NotFound]: the only cost of guessing wrong is caching a
     * "no lyrics" answer for a track during an outage, and the fallback provider is
     * consulted either way. Timeouts and parse failures are [LyricsResult.Error].
     */
    suspend fun getSpotifyLyrics(track: Track?, timeoutMs: Long = 2000L): LyricsResult =
        withContext(Dispatchers.IO) {
            val id = track?.id ?: return@withContext LyricsResult.NotFound

            val raw: String = try {
                withTimeout(timeoutMs) {
                    spClient.getLyrics(id)
                } ?: return@withContext LyricsResult.NotFound
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext LyricsResult.Error
            }

            return@withContext try {
                val response: LyricsResponse = json.decodeFromString(raw)
                val lines = response.lyrics.lines
                    .filter { it.words.isNotBlank() }
                    .map {
                        LyricLine(
                            timestampMs = it.startTimeMs.toLong(),
                            text = it.words
                        )
                    }
                if (lines.isEmpty()) {
                    LyricsResult.NotFound
                } else {
                    LyricsResult.Found(
                        lines = lines,
                        source = LyricsSource.SPOTIFY,
                        synced = lines.any { it.timestampMs > 0L },
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                LyricsResult.Error
            }
        }
}
