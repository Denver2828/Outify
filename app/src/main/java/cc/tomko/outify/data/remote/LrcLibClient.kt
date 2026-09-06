package cc.tomko.outify.data.remote

import android.util.Log
import cc.tomko.outify.BuildConfig
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.LyricsResult
import cc.tomko.outify.core.model.LyricsSource
import cc.tomko.outify.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "LrcLibClient"
private const val LRCLIB_HOST = "lrclib.net"
private const val CALL_TIMEOUT_SECONDS = 6L

/** Candidates whose duration differs more than this from the track are discarded. */
internal const val LRCLIB_DURATION_TOLERANCE_SECONDS = 5

/**
 * One row of the LRCLIB API (https://lrclib.net/docs).
 */
@Serializable
data class LrcLibTrack(
    val id: Long = 0,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
) {
    val hasLyrics: Boolean
        get() = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank()
}

/**
 * Fallback lyrics provider used when Spotify has no lyrics for a track.
 *
 * Lookup order: exact match on `/api/get` (title, artist, album, duration), then a
 * `/api/search` by title and artist filtered by duration. Sends only track metadata.
 */
@Singleton
class LrcLibClient @Inject constructor(
    okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val userAgent = "Spoty/${BuildConfig.VERSION_NAME} (https://github.com/Denver2828/Outify)"

    suspend fun getLyrics(track: Track): LyricsResult = withContext(Dispatchers.IO) {
        val title = track.name.trim()
        val artist = track.artists.firstOrNull()?.name?.trim().orEmpty()
        if (title.isEmpty()) return@withContext LyricsResult.NotFound

        val durationSeconds = (track.duration / 1000.0).roundToInt()

        try {
            val exact = fetchExact(title, artist, track.album?.name, durationSeconds)
            if (exact != null) return@withContext exact.toResult()

            val candidates = search(title, artist)
            val best = pickBestMatch(candidates, durationSeconds)
                ?: return@withContext LyricsResult.NotFound
            best.toResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "LRCLIB request failed: ${e.message}")
            LyricsResult.Error
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB lookup failed", e)
            LyricsResult.Error
        }
    }

    /** `null` means the API answered 404 (no exact match). Network failures throw. */
    private fun fetchExact(title: String, artist: String, album: String?, durationSeconds: Int): LrcLibTrack? {
        val url = baseUrl("api/get")
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (!album.isNullOrBlank()) addQueryParameter("album_name", album) }
            .addQueryParameter("duration", durationSeconds.toString())
            .build()

        client.newCall(request(url)).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("LRCLIB get returned HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("LRCLIB get returned no body")
            return json.decodeFromString<LrcLibTrack>(body)
        }
    }

    private fun search(title: String, artist: String): List<LrcLibTrack> {
        val url = baseUrl("api/search")
            .addQueryParameter("track_name", title)
            .apply { if (artist.isNotEmpty()) addQueryParameter("artist_name", artist) }
            .build()

        client.newCall(request(url)).execute().use { response ->
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) throw IOException("LRCLIB search returned HTTP ${response.code}")
            val body = response.body?.string() ?: return emptyList()
            return json.decodeFromString<List<LrcLibTrack>>(body)
        }
    }

    private fun baseUrl(path: String): HttpUrl.Builder =
        HttpUrl.Builder()
            .scheme("https")
            .host(LRCLIB_HOST)
            .addPathSegments(path)

    private fun request(url: HttpUrl): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .get()
            .build()
}

/**
 * Picks the search result closest in duration, within [LRCLIB_DURATION_TOLERANCE_SECONDS],
 * preferring synced lyrics over plain text. Instrumental and empty rows are skipped.
 */
internal fun pickBestMatch(candidates: List<LrcLibTrack>, durationSeconds: Int): LrcLibTrack? =
    candidates
        .asSequence()
        .filter { it.hasLyrics && !it.instrumental }
        .map { it to abs((it.duration ?: 0.0).roundToInt() - durationSeconds) }
        .filter { (_, diff) -> diff <= LRCLIB_DURATION_TOLERANCE_SECONDS }
        .sortedWith(
            compareBy<Pair<LrcLibTrack, Int>>({ (track, _) -> track.syncedLyrics.isNullOrBlank() })
                .thenBy { (_, diff) -> diff }
        )
        .firstOrNull()
        ?.first

internal fun LrcLibTrack.toResult(): LyricsResult {
    if (instrumental || !hasLyrics) return LyricsResult.NotFound

    val synced = syncedLyrics?.takeIf { it.isNotBlank() }?.let(LrcParser::parseSynced)
    if (!synced.isNullOrEmpty()) {
        return LyricsResult.Found(synced, LyricsSource.LRCLIB, synced = true)
    }

    val plain = plainLyrics?.takeIf { it.isNotBlank() }?.let(LrcParser::parsePlain)
    if (!plain.isNullOrEmpty()) {
        return LyricsResult.Found(plain, LyricsSource.LRCLIB, synced = false)
    }

    return LyricsResult.NotFound
}

/**
 * Parses LRC text (`[mm:ss.xx]` or `[mm:ss.xxx]` prefixes, several per line allowed).
 */
object LrcParser {
    private val timestamp = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parseSynced(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        text.lineSequence().forEach { rawLine ->
            val stamps = timestamp.findAll(rawLine).toList()
            if (stamps.isEmpty()) return@forEach

            val words = rawLine.substring(stamps.last().range.last + 1).trim()
            if (words.isEmpty()) return@forEach

            stamps.forEach { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                lines += LyricLine(
                    timestampMs = minutes * 60_000 + seconds * 1_000 + millis,
                    text = words,
                )
            }
        }
        return lines.sortedBy { it.timestampMs }
    }

    fun parsePlain(text: String): List<LyricLine> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricLine(timestampMs = 0L, text = it) }
            .toList()
}
