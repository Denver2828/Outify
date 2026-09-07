package cc.tomko.outify.services

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.MediaSessionConstants
import cc.tomko.outify.R
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.core.model.Playlist
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.metadata.NativeErrorHandler
import cc.tomko.outify.data.repository.SearchRepository
import cc.tomko.outify.ui.model.search.SearchResultType
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Collections

/**
 * Media library exposed to Android Auto and other media browsers.
 *
 * Browse tree (media ids):
 * - `root`
 *   - `recent`                 -> recently played tracks (playable, no context)
 *   - `liked`                  -> liked tracks (playable, context `outify:liked`)
 *   - `playlist`               -> user's playlists (browsable, id = `spotify:playlist:<id>`)
 *     - `spotify:playlist:<id>`-> playlist tracks (playable, context = playlist uri)
 *   - `artist`                 -> top artists (browsable, id = `spotify:artist:<id>`)
 *     - `spotify:artist:<id>`  -> artist top tracks (playable, context = artist uri)
 *
 * Playable items use the full Spotify track uri as media id and carry the playback context in
 * the `context_uri` extra. Playback is started through librespot ([SpircWrapper.load]) from
 * [onSetMediaItems]; the Media3 [cc.tomko.outify.playback.Player] mirrors librespot state and
 * does not own a playlist of its own.
 */
@UnstableApi
class MediaLibrarySessionCallback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spClient: SpClient,
    private val spircController: SpircController,
    private val spirc: SpircWrapper,
    private val metadata: Metadata,
    private val searchRepository: SearchRepository,
    private val json: Json,
) : MediaLibraryService.MediaLibrarySession.Callback {

    companion object {
        val TAG = MediaLibrarySessionCallback::class.simpleName.toString()

        /** Extra on playable items: the context uri playback should be started in. */
        const val EXTRA_CONTEXT_URI = "context_uri"

        private const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1
        private const val CONTENT_STYLE_GRID = 2

        private const val TRACK_URI_PREFIX = "spotify:track:"
        private const val EPISODE_URI_PREFIX = "spotify:episode:"
        private const val PLAYLIST_URI_PREFIX = "spotify:playlist:"
        private const val ARTIST_URI_PREFIX = "spotify:artist:"
        private const val ALBUM_URI_PREFIX = "spotify:album:"

        private const val MAX_PLAYLIST_TRACKS = 200
        private const val MAX_LIKED_TRACKS = 100
        private const val MAX_RECENT_TRACKS = 50
        private const val MAX_SEARCH_TRACKS = 20
        private const val MAX_SEARCH_CONTAINERS = 5
        private const val MAX_SEARCH_RESULTS = 50
        private const val FETCH_CONCURRENCY = 6
        private const val CONTEXT_CACHE_SIZE = 512

        /** Upper bound for reporting a like/radio command result back to a controller. */
        private const val CUSTOM_COMMAND_TIMEOUT_MS = 10_000L

        // Last-resort fallbacks when a native payload cannot be decoded as a model.
        private val TRACK_URI_REGEX = Regex("""spotify:track:[a-zA-Z0-9]+""")
        private val ARTIST_URI_REGEX = Regex("""spotify:artist:[a-zA-Z0-9]+""")
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    lateinit var service: PlaybackService

    /**
     * Like and radio are network operations: the service hands back a deferred that
     * completes with the real outcome (`null` = nothing to act on), so [onCustomCommand]
     * can report success only once the operation is confirmed.
     */
    var toggleLike: () -> Deferred<Boolean?> = { CompletableDeferred(null) }
    var toggleStartRadio: () -> Deferred<Boolean?> = { CompletableDeferred(null) }

    /** Repeat/shuffle reach Spirc through JNI; same confirmed-outcome contract as above. */
    var toggleRepeatMode: () -> Deferred<Boolean?> = { CompletableDeferred(null) }
    var toggleShuffle: () -> Deferred<Boolean?> = { CompletableDeferred(null) }

    /** Results of the most recent [onSearch], served back by [onGetSearchResult]. */
    @Volatile
    private var lastSearch: Pair<String, List<MediaItem>>? = null

    /**
     * Context uri of recently served playable items, keyed by media id.
     * Legacy browsers (Android Auto) are not guaranteed to echo item extras back in
     * `playFromMediaId`, so this lets [onSetMediaItems] recover the browse context.
     */
    private val contextByMediaId: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(CONTEXT_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > CONTEXT_CACHE_SIZE
        }
    )

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .build(),
            connectionResult.availablePlayerCommands
        )
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        scope.launch {
            spircController.restart()
        }
        return super.onPlaybackResumption(mediaSession, controller, isForPlayback)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        return when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> confirmedResult(toggleLike())
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> confirmedResult(toggleStartRadio())
            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> confirmedResult(toggleRepeatMode())
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> confirmedResult(toggleShuffle())
            else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    /**
     * Maps a network-backed command outcome to a [SessionResult] without holding the
     * session callback: the future completes when the operation is confirmed, or with an
     * error after [CUSTOM_COMMAND_TIMEOUT_MS]. A timeout does not cancel the operation
     * (the native request is not cancellable); it keeps running and reconciles state.
     */
    private fun confirmedResult(outcome: Deferred<Boolean?>): ListenableFuture<SessionResult> =
        scope.future {
            val code = when (withTimeoutOrNull(CUSTOM_COMMAND_TIMEOUT_MS) { outcome.await() }) {
                true -> SessionResult.RESULT_SUCCESS
                false -> SessionResult.RESULT_ERROR_UNKNOWN
                null -> if (outcome.isCompleted) SessionResult.RESULT_INFO_SKIPPED
                else SessionResult.RESULT_ERROR_UNKNOWN // still pending after the timeout
            }
            SessionResult(code)
        }

    // region Playback requests

    /**
     * Called when a browser asks to play something: a tap on a browse item, a voice
     * "play X" request (search query in request metadata) or a `playFromUri` request.
     *
     * Playback is delegated to librespot. The returned list is handed to the Media3 session,
     * which forwards it to [cc.tomko.outify.playback.Player.setMediaItems]; that call is a no-op
     * because the custom player does not advertise `COMMAND_SET_MEDIA_ITEM` /
     * `COMMAND_CHANGE_MEDIA_ITEMS`, so returning the items unchanged is safe and lets the session
     * still issue the follow-up `prepare()`/`play()`.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val selected = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
        if (selected != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    startPlayback(selected)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start playback for ${selected.mediaId}", e)
                }
            }
        } else {
            Log.w(TAG, "onSetMediaItems called without items")
        }

        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
        )
    }

    /** Items already carry a uri; nothing to resolve. */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> {
        return Futures.immediateFuture(mediaItems)
    }

    private suspend fun startPlayback(item: MediaItem) {
        val mediaId = item.mediaId
        val searchQuery = item.requestMetadata.searchQuery
        val requestUri = item.requestMetadata.mediaUri?.toString()

        when {
            mediaId.isNotBlank() && mediaId != MediaItem.DEFAULT_MEDIA_ID -> {
                val contextUri = item.mediaMetadata.extras?.getString(EXTRA_CONTEXT_URI)
                    ?: item.requestMetadata.extras?.getString(EXTRA_CONTEXT_URI)
                    ?: contextByMediaId[mediaId]
                playMediaId(mediaId, contextUri)
            }

            !searchQuery.isNullOrBlank() -> playFromSearch(searchQuery)

            requestUri != null && requestUri.startsWith("spotify:") -> {
                Log.i(TAG, "Playing from uri $requestUri")
                spirc.load(OutifyUri.fromUriString(requestUri), null)
            }

            else -> Log.w(TAG, "Nothing playable in request: $item")
        }
    }

    private fun playMediaId(mediaId: String, contextUri: String?) {
        val uri = mediaId.toSpotifyUriString()
        Log.i(TAG, "Playing $uri in context ${contextUri ?: "none"}")

        when {
            uri.startsWith(TRACK_URI_PREFIX) || uri.startsWith(EPISODE_URI_PREFIX) -> {
                val track = OutifyUri.fromUriString(uri)
                if (contextUri.isNullOrBlank()) {
                    // Same as SearchScreen/HomeViewModel: a lone track is its own context.
                    spirc.load(track, null)
                } else {
                    spirc.load(OutifyUri.fromUriString(contextUri), track)
                }
            }

            uri.startsWith(PLAYLIST_URI_PREFIX) ||
                uri.startsWith(ARTIST_URI_PREFIX) ||
                uri.startsWith(ALBUM_URI_PREFIX) -> {
                spirc.load(OutifyUri.fromUriString(uri), null)
            }

            mediaId == PlaybackService.LIKED -> spirc.load(OutifyUri.Liked, null)

            else -> Log.w(TAG, "Unsupported media id for playback: $mediaId")
        }
    }

    /** Voice search ("play X"): play the best match directly. */
    private suspend fun playFromSearch(query: String) {
        val results = try {
            searchRepository.search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Voice search failed for \"$query\"", e)
            return
        }

        if (results.isEmpty()) {
            Log.w(TAG, "Voice search returned nothing for \"$query\"")
            return
        }

        val first = results.first()
        val firstTrack = results.firstOrNull { it.type == SearchResultType.TRACK }

        val target = when (first.type) {
            SearchResultType.ARTIST,
            SearchResultType.PLAYLIST,
            SearchResultType.ALBUM -> first.uri

            else -> firstTrack?.uri ?: first.uri
        }

        Log.i(TAG, "Voice search \"$query\" -> $target")
        spirc.load(OutifyUri.fromUriString(target), null)
    }

    // endregion

    // region Browsing

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val rootItem = MediaItem.Builder()
            .setMediaId(PlaybackService.ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setTitle(context.getString(R.string.app_name))
                    .build()
            )
            .build()

        val libraryParams = MediaLibraryService.LibraryParams.Builder()
            .setExtras(Bundle().apply {
                putBoolean("androidx.media3.session.LIBRARY_PARAM_KEY_RECENT", true)
                putBoolean("androidx.media3.session.LIBRARY_PARAM_KEY_OFFLINE", false)
                putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
                putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST)
                putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
            })
            .build()

        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, libraryParams))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        try {
            val children = when {
                parentId == PlaybackService.ROOT -> getRootChildren()
                parentId == PlaybackService.PLAYLIST -> getPlaylists()
                parentId == PlaybackService.LIKED -> getLikedTracks(page, pageSize)
                parentId == PlaybackService.ARTIST -> getTopArtists()
                parentId == PlaybackService.RECENT -> getRecentTracks()
                parentId.startsWith(PLAYLIST_URI_PREFIX) -> getPlaylistTracks(parentId, page, pageSize)
                parentId.startsWith(ARTIST_URI_PREFIX) -> getArtistTracks(parentId)
                else -> {
                    Log.w(TAG, "Unknown parent id: $parentId")
                    emptyList<MediaItem>()
                }
            }
            rememberContexts(children)
            LibraryResult.ofItemList(children, params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "onGetChildren failed for $parentId", e)
            LibraryResult.ofError(SessionError.ERROR_IO)
        }
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
        try {
            val trackUri = mediaId.toSpotifyUriString()
            val item = when {
                mediaId == PlaybackService.ROOT ||
                    mediaId == PlaybackService.PLAYLIST ||
                    mediaId == PlaybackService.LIKED ||
                    mediaId == PlaybackService.ARTIST ||
                    mediaId == PlaybackService.RECENT ->
                    getRootChildren().firstOrNull { it.mediaId == mediaId }

                trackUri.startsWith(TRACK_URI_PREFIX) ->
                    metadata.getTrackMetadata(listOf(trackUri)).firstOrNull()
                        ?.toMediaItem(contextUri = contextByMediaId[mediaId])

                trackUri.startsWith(PLAYLIST_URI_PREFIX) ->
                    metadata.getPlaylistMetadata(trackUri, allowCached = true)?.let { it.toMediaItem(it.getCover(metadata)) }

                trackUri.startsWith(ARTIST_URI_PREFIX) ->
                    metadata.getArtistMetadata(trackUri)?.toMediaItem()

                else -> null
            } ?: currentPlayerItem()?.takeIf { it.mediaId == mediaId }

            if (item != null) {
                LibraryResult.ofItem(item, null)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "onGetItem failed for $mediaId", e)
            LibraryResult.ofError(SessionError.ERROR_IO)
        }
    }

    /** The player must be touched on its application thread. */
    private suspend fun currentPlayerItem(): MediaItem? = withContext(Dispatchers.Main) {
        if (::service.isInitialized) service.player.currentMediaItem else null
    }

    private fun getRootChildren(): List<MediaItem> = listOf(
        folderItem(
            mediaId = PlaybackService.RECENT,
            title = context.getString(R.string.sys_media_recently_played),
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            childrenStyle = CONTENT_STYLE_LIST,
            // No bundled history/clock icon in the project; Auto falls back to its default.
            iconRes = null,
        ),
        folderItem(
            mediaId = PlaybackService.LIKED,
            title = context.getString(R.string.sys_media_liked_songs),
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            childrenStyle = CONTENT_STYLE_LIST,
            iconRes = androidx.media3.session.R.drawable.media3_icon_heart_filled,
        ),
        folderItem(
            mediaId = PlaybackService.PLAYLIST,
            title = context.getString(R.string.sys_media_playlists),
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            childrenStyle = CONTENT_STYLE_GRID,
            iconRes = androidx.media3.session.R.drawable.media3_icon_playlist_add,
        ),
        folderItem(
            mediaId = PlaybackService.ARTIST,
            title = context.getString(R.string.sys_media_top_artists),
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            childrenStyle = CONTENT_STYLE_GRID,
            iconRes = androidx.media3.session.R.drawable.media3_icon_artist,
        ),
    )

    private fun folderItem(
        mediaId: String,
        title: String,
        mediaType: Int,
        childrenStyle: Int,
        @DrawableRes iconRes: Int?,
    ): MediaItem {
        val extras = Bundle().apply {
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, childrenStyle)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
        }
        val metadataBuilder = MediaMetadata.Builder()
            .setIsPlayable(false)
            .setIsBrowsable(true)
            .setMediaType(mediaType)
            .setTitle(title)
            .setExtras(extras)

        if (iconRes != null) {
            metadataBuilder.setArtworkUri("android.resource://${context.packageName}/$iconRes".toUri())
        }

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private suspend fun getPlaylists(): List<MediaItem> {
        val uris = metadata.getPlaylistUris()
            .filter { it.contains(":playlist:") }
            .map { it.normalizePlaylistUri() }
            .distinct()

        return fetchConcurrently(uris) { uri ->
            val playlist = metadata.getPlaylistMetadata(uri, allowCached = true) ?: return@fetchConcurrently null
            playlist.toMediaItem(cover = playlist.getCover(metadata, CoverSize.MEDIUM), mediaId = uri)
        }
    }

    private suspend fun getPlaylistTracks(playlistUri: String, page: Int, pageSize: Int): List<MediaItem> {
        val playlist = metadata.getPlaylistMetadata(playlistUri, allowCached = true)
        if (playlist == null) {
            Log.w(TAG, "Playlist $playlistUri could not be loaded")
            return emptyList()
        }

        val trackUris = playlist.contents
            .map { it.uri }
            .filter { it.startsWith(TRACK_URI_PREFIX) }
            .window(page, pageSize, MAX_PLAYLIST_TRACKS)

        return metadata.getTrackMetadata(trackUris).map { it.toMediaItem(contextUri = playlistUri) }
    }

    private suspend fun getTopArtists(): List<MediaItem> {
        val raw = spClient.getUserTop("artists") ?: return emptyList()
        if (NativeErrorHandler.handleErrorJson(raw, "android auto top artists") != null) return emptyList()

        // The Web API payload already carries name and portrait; use it and avoid one
        // native metadata call per artist.
        val parsed = try {
            json.decodeFromString<TopArtistsResponse>(raw).items
        } catch (e: Exception) {
            Log.w(TAG, "Top artists payload is not the expected shape, falling back to metadata", e)
            emptyList()
        }

        if (parsed.isNotEmpty()) {
            return parsed.mapNotNull { item ->
                val uri = item.uri?.takeIf { it.startsWith(ARTIST_URI_PREFIX) } ?: return@mapNotNull null
                artistItem(
                    uri = uri,
                    name = item.name.ifBlank { context.getString(R.string.sys_media_unknown_artist) },
                    imageUrl = item.images?.firstOrNull()?.url,
                )
            }
        }

        val uris = ARTIST_URI_REGEX.findAll(raw).map { it.value }.distinct().toList()
        return fetchConcurrently(uris) { uri -> metadata.getArtistMetadata(uri)?.toMediaItem() }
    }

    private suspend fun getArtistTracks(artistUri: String): List<MediaItem> {
        val artist = metadata.getArtistMetadata(artistUri)
        if (artist == null) {
            Log.w(TAG, "Artist $artistUri could not be loaded")
            return emptyList()
        }

        val trackUris = artist.tracks
            .map { it.toSpotifyUriString() }
            .filter { it.startsWith(TRACK_URI_PREFIX) }
            .distinct()

        return metadata.getTrackMetadata(trackUris).map { it.toMediaItem(contextUri = artistUri) }
    }

    private suspend fun getLikedTracks(page: Int, pageSize: Int): List<MediaItem> {
        // Collection order from the API is newest first (same source the liked sync uses).
        val trackUris = metadata.getLikedUris()
            .filter { it.startsWith(TRACK_URI_PREFIX) }
            .window(page, pageSize, MAX_LIKED_TRACKS)

        return metadata.getTrackMetadata(trackUris)
            .map { it.toMediaItem(contextUri = OutifyUri.Liked.toUriString()) }
    }

    private suspend fun getRecentTracks(): List<MediaItem> {
        val raw = spClient.getUserCollection("recent") ?: return emptyList()

        val decoded = try {
            json.decodeFromString<List<String>>(spClient.checkAndHandleError(raw, "media_recent"))
        } catch (e: Exception) {
            Log.w(TAG, "Recent payload is not a uri list, falling back to regex", e)
            TRACK_URI_REGEX.findAll(raw).map { it.value }.toList()
        }

        val trackUris = decoded
            .filter { it.startsWith(TRACK_URI_PREFIX) }
            .distinct()
            .take(MAX_RECENT_TRACKS)

        return metadata.getTrackMetadata(trackUris).map { it.toMediaItem() }
    }

    // endregion

    // region Search

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> = scope.future(Dispatchers.IO) {
        val items = try {
            searchLibrary(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for \"$query\"", e)
            emptyList()
        }
        lastSearch = query to items
        rememberContexts(items)

        withContext(Dispatchers.Main) {
            session.notifySearchResultChanged(browser, query, items.size, params)
        }
        LibraryResult.ofVoid(params)
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        try {
            val items = lastSearch?.takeIf { it.first == query }?.second
                ?: searchLibrary(query).also {
                    lastSearch = query to it
                    rememberContexts(it)
                }
            LibraryResult.ofItemList(items.window(page, pageSize, MAX_SEARCH_RESULTS), params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "onGetSearchResult failed for \"$query\"", e)
            LibraryResult.ofError(SessionError.ERROR_IO)
        }
    }

    /** Playable tracks first, then artists and playlists as browsable folders. */
    private suspend fun searchLibrary(query: String): List<MediaItem> = supervisorScope {
        val results = searchRepository.search(query)

        val trackUris = results.filter { it.type == SearchResultType.TRACK }.map { it.uri }.take(MAX_SEARCH_TRACKS)
        val artistUris = results.filter { it.type == SearchResultType.ARTIST }.map { it.uri }.take(MAX_SEARCH_CONTAINERS)
        val playlistUris = results.filter { it.type == SearchResultType.PLAYLIST }.map { it.uri }.take(MAX_SEARCH_CONTAINERS)

        val tracks = async {
            try {
                metadata.getTrackMetadata(trackUris).map { it.toMediaItem() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search: track metadata failed", e)
                emptyList()
            }
        }
        val artists = async {
            fetchConcurrently(artistUris) { uri -> metadata.getArtistMetadata(uri)?.toMediaItem() }
        }
        val playlists = async {
            fetchConcurrently(playlistUris) { uri ->
                metadata.getPlaylistMetadata(uri, allowCached = true)
                    ?.let { it.toMediaItem(cover = it.getCover(metadata, CoverSize.MEDIUM)) }
            }
        }

        tracks.await() + artists.await() + playlists.await()
    }

    // endregion

    // region Helpers

    /** Runs [block] for every uri with bounded concurrency; failures are logged and skipped. */
    private suspend fun <T : Any> fetchConcurrently(
        uris: List<String>,
        block: suspend (String) -> T?,
    ): List<T> = supervisorScope {
        if (uris.isEmpty()) return@supervisorScope emptyList()
        val semaphore = Semaphore(FETCH_CONCURRENCY)
        uris.map { uri ->
            async {
                semaphore.withPermit {
                    try {
                        block(uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load $uri", e)
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun rememberContexts(items: List<MediaItem>) {
        items.forEach { item ->
            val contextUri = item.mediaMetadata.extras?.getString(EXTRA_CONTEXT_URI) ?: return@forEach
            contextByMediaId[item.mediaId] = contextUri
        }
    }

    private fun <T> List<T>.window(page: Int, pageSize: Int, maxPageSize: Int): List<T> {
        val effectivePageSize = if (pageSize <= 0 || pageSize > maxPageSize) maxPageSize else pageSize
        val from = (page.coerceAtLeast(0).toLong() * effectivePageSize).coerceAtMost(size.toLong()).toInt()
        val to = (from.toLong() + effectivePageSize).coerceAtMost(size.toLong()).toInt()
        return subList(from, to)
    }

    private fun artistItem(uri: String, name: String, imageUrl: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setArtist(name)
                    .setArtworkUri(imageUrl?.toUri())
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                    .setExtras(Bundle().apply {
                        putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
                        putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
                    })
                    .build()
            )
            .build()

    private fun Artist.toMediaItem(): MediaItem =
        artistItem(
            uri = uri.ifBlank { "$ARTIST_URI_PREFIX$id" },
            name = name.ifBlank { context.getString(R.string.sys_media_unknown_artist) },
            imageUrl = getCover(CoverSize.MEDIUM)?.let { ALBUM_COVER_URL + it.uri }
                ?: portraits.firstOrNull()?.let { ALBUM_COVER_URL + it },
        )

    private fun Playlist.toMediaItem(cover: String?, mediaId: String = uri.normalizePlaylistUri()): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(attributes.name)
                    .setSubtitle(context.getString(R.string.sys_media_playlist_owner, ownerUsername))
                    .setArtworkUri(cover?.toUri())
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setExtras(Bundle().apply {
                        putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
                        putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
                    })
                    .build()
            )
            .build()

    private fun Track.toMediaItem(contextUri: String? = null): MediaItem {
        val artistNames = artists.joinToString { it.name }
            .ifBlank { context.getString(R.string.sys_media_unknown_artist) }

        return MediaItem.Builder()
            .setMediaId(uri)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setSubtitle(artistNames)
                    .setArtist(artistNames)
                    .setAlbumTitle(album?.name)
                    .setArtworkUri(album?.getCover(CoverSize.LARGE)?.let { (ALBUM_COVER_URL + it.uri).toUri() })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MEDIA_TYPE_MUSIC)
                    .setExtras(Bundle().apply {
                        if (contextUri != null) putString(EXTRA_CONTEXT_URI, contextUri)
                    })
                    .build()
            )
            .build()
    }

    /** `spotify:user:<u>:playlist:<id>` and `spotify:playlist:<id>` both map to the latter. */
    private fun String.normalizePlaylistUri(): String =
        if (contains(":playlist:")) "$PLAYLIST_URI_PREFIX${substringAfterLast(":")}" else this

    /**
     * Accepts a full Spotify uri, or a bare track id (as used by the current player item and
     * older browse ids) and returns a full Spotify uri.
     */
    private fun String.toSpotifyUriString(): String = when {
        startsWith("spotify:") || startsWith("outify:") -> this
        startsWith("playlist:") -> "$PLAYLIST_URI_PREFIX${substringAfter(':')}"
        startsWith("artist:") -> "$ARTIST_URI_PREFIX${substringAfter(':')}"
        matches(Regex("[a-zA-Z0-9]{22}")) -> "$TRACK_URI_PREFIX$this"
        else -> this
    }

    @Serializable
    private data class TopArtistsResponse(val items: List<TopArtistItem> = emptyList())

    @Serializable
    private data class TopArtistItem(
        val name: String = "",
        val uri: String? = null,
        val images: List<TopArtistImage>? = null,
    )

    @Serializable
    private data class TopArtistImage(val url: String)

    // endregion
}
