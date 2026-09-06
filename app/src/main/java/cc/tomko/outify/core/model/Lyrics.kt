package cc.tomko.outify.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LyricsResponse(
    val lyrics: Lyrics
)

@Serializable
data class Lyrics(
    val syncType: String,
    val lines: List<RawLyricLine>
)

@Serializable
data class RawLyricLine(
    val startTimeMs: String,
    val words: String,
    val endTimeMs: String? = null
)

data class LyricLine(
    val timestampMs: Long,
    val text: String
)

/**
 * Where a set of lyrics came from. Shown to the user when it is not Spotify.
 */
enum class LyricsSource(val displayName: String) {
    SPOTIFY("Spotify"),
    LRCLIB("LRCLIB"),
}

/**
 * Outcome of a lyrics lookup. [NotFound] is a definitive "this track has no lyrics"
 * answer and can be cached; [Error] is transient (timeout, network, parse) and must not.
 */
sealed class LyricsResult {
    data class Found(
        val lines: List<LyricLine>,
        val source: LyricsSource,
        /** False when only plain text exists and every timestamp is 0. */
        val synced: Boolean,
    ) : LyricsResult()

    data object NotFound : LyricsResult()

    data object Error : LyricsResult()

    val linesOrEmpty: List<LyricLine>
        get() = (this as? Found)?.lines ?: emptyList()
}
