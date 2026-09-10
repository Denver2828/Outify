package cc.tomko.outify.core.model

import kotlin.jvm.JvmName

/**
 * True when this track is hidden either directly (its own uri was hidden) or indirectly
 * (its album was hidden). [hidden] is the full set of hidden uris (tracks and albums mixed).
 */
fun Track.isHiddenIn(hidden: Set<String>): Boolean {
    if (hidden.isEmpty()) return false
    if (uri in hidden) return true
    val albumUri = album?.uri
    return albumUri != null && albumUri in hidden
}

@JvmName("dropHiddenTracks")
fun List<Track>.dropHidden(hidden: Set<String>): List<Track> {
    if (hidden.isEmpty()) return this
    return filterNot { it.isHiddenIn(hidden) }
}

fun Album.isHiddenIn(hidden: Set<String>): Boolean = hidden.isNotEmpty() && uri in hidden

@JvmName("dropHiddenAlbums")
fun List<Album>.dropHidden(hidden: Set<String>): List<Album> {
    if (hidden.isEmpty()) return this
    return filterNot { it.isHiddenIn(hidden) }
}
