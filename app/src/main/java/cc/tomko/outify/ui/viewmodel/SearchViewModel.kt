package cc.tomko.outify.ui.viewmodel

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.R
import cc.tomko.outify.core.RateLimitGate
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.model.*
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.repository.SearchRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.data.repository.SyncErrorClassifier
import cc.tomko.outify.data.repository.SyncFailure
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.reccobeats.RecommendationConfig
import cc.tomko.outify.reccobeats.Recommendations
import cc.tomko.outify.ui.model.search.SearchHistoryItem
import cc.tomko.outify.ui.model.search.SearchResultType
import cc.tomko.outify.ui.viewmodel.search.SearchErrorKind
import cc.tomko.outify.ui.viewmodel.search.SearchOrchestrator
import cc.tomko.outify.ui.viewmodel.search.SearchSection
import cc.tomko.outify.ui.viewmodel.search.SearchUiState
import cc.tomko.outify.ui.viewmodel.search.SectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

private val SEARCH_SECTIONS = listOf(
    SearchSection("track", R.string.search_section_tracks),
    SearchSection("artist", R.string.search_section_artists),
    SearchSection("album", R.string.search_section_albums),
    SearchSection("playlist", R.string.search_section_playlists),
    SearchSection("show", R.string.search_section_shows),
    SearchSection("episode", R.string.search_section_episodes),
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    val metadata: Metadata,
    val spirc: SpircWrapper,
    val spClient: SpClient,
    private val repository: SearchRepository,
    private val playbackStateHolder: PlaybackStateHolder,
    private val settingsRepository: SettingsRepository,
    private val recommendations: Recommendations,
    private val likedDao: LikedDao,
    private val json: Json,
    private val userProfile: UserProfile,
    private val rateLimitGate: RateLimitGate,
) : ViewModel() {
    /** A query plus an attempt counter so [retry] can re-issue an unchanged query. */
    private data class SearchRequest(val query: String = "", val attempt: Int = 0)

    private val searchRequests = MutableStateFlow(SearchRequest())

    private val _results = MutableStateFlow<List<SearchUiModel>>(emptyList())
    val results: StateFlow<List<SearchUiModel>> = _results

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private val _isRecommendationMode = MutableStateFlow(false)
    val isRecommendationMode: StateFlow<Boolean> = _isRecommendationMode

    val currentAudio: StateFlow<PlayableAudio?> = playbackStateHolder.state
        .map { it.currentAudio }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val isPlaying: StateFlow<Boolean> = playbackStateHolder.state
        .map { it.isPlaying }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    val searchHistory: StateFlow<List<SearchHistoryItem>> = settingsRepository.searchHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _historyResults = MutableStateFlow<List<SearchUiModel>>(emptyList())
    val historyResults: StateFlow<List<SearchUiModel>> = _historyResults

    private val authorsCache = mutableMapOf<String, List<Profile>>()
    private val _authors = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val authors: StateFlow<Map<String, Profile>> = _authors

    /**
     * Latest-only search. The orchestrator tags every publication with the query captured at
     * launch and drops anything older, so a slow response can never overwrite a newer search.
     */
    private val orchestrator = SearchOrchestrator<SearchUiModel>(
        sections = SEARCH_SECTIONS,
        fetchSection = ::fetchSection,
        isRateLimited = rateLimitGate::isLimited,
        classify = ::classifySearchError,
    )

    val searchState: StateFlow<SearchUiState<SearchUiModel>> = orchestrator.state

    /** Seconds left on the Spotify rate-limit window; 0 when searching is allowed. */
    val rateLimitRemainingSeconds: StateFlow<Int> = rateLimitGate.remainingSecondsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), rateLimitGate.remainingSeconds())

    init {
        _isLoggedIn.value = spClient.isOAuthAuthenticated()

        viewModelScope.launch {
            searchRequests
                .debounce(500)
                .distinctUntilChanged()
                // collectLatest cancels the previous search (and its section fetches, which are
                // children of this block) as soon as a new request arrives.
                .collectLatest { request -> orchestrator.search(request.query) }
        }

        viewModelScope.launch {
            orchestrator.state.collect { state -> _results.value = state.toUiList() }
        }

        viewModelScope.launch {
            // Items already resolved by this ViewModel are reused; only new uris hit the
            // metadata helpers (network for artists, albums and shows). Keyed by uri.
            val resolved = mutableMapOf<String, SearchUiModel>()
            settingsRepository.searchHistory.collect { items ->
                if (items.isEmpty()) {
                    _historyResults.value = emptyList()
                    return@collect
                }
                val results = withContext(Dispatchers.IO) {
                    items.mapNotNull { item ->
                        resolved[item.uri]?.let { return@mapNotNull it }
                        try {
                            when (item.type) {
                                SearchResultType.TRACK -> {
                                    val tracks = metadata.getTrackMetadata(listOf(item.uri))
                                    tracks.firstOrNull()?.let { track ->
                                        SearchUiModel.TrackItem(item.uri, track)
                                    }
                                }

                                SearchResultType.ARTIST -> {
                                    val artist = metadata.getArtistMetadata(item.uri)
                                    artist?.let { SearchUiModel.ArtistItem(item.uri, it) }
                                }

                                SearchResultType.ALBUM -> {
                                    val album = metadata.getAlbumMetadata(item.uri)
                                    album?.let { SearchUiModel.AlbumItem(item.uri, it) }
                                }

                                SearchResultType.PLAYLIST -> {
                                    val playlist = metadata.getPlaylistMetadata(item.uri, true)
                                    playlist?.let { SearchUiModel.PlaylistItem(item.uri, it) }
                                }

                                SearchResultType.SHOW -> {
                                    val show = metadata.getShowMetadata(item.uri)
                                    show?.let { SearchUiModel.ShowItem(item.uri, it) }
                                }

                                SearchResultType.EPISODE -> {
                                    val episode = metadata.getEpisodeMetadata(item.uri)
                                    episode?.let { SearchUiModel.EpisodeItem(item.uri, it) }
                                }
                            }?.also { resolved[item.uri] = it }
                        } catch (e: Exception) {
                            Log.w(
                                "SearchViewModel",
                                "Failed to load history metadata for ${item.uri}",
                                e
                            )
                            null
                        }
                    }
                }
                _historyResults.value = results
            }
        }
    }

    /**
     * Resolves one section for an immutable [query]. Runs as a child of the search, so a query
     * change cancels it; per-item metadata misses are skipped, a failed search or a failed
     * batch lookup surfaces as a section failure.
     */
    private suspend fun fetchSection(query: String, section: SearchSection): List<SearchUiModel> {
        val uris = repository.searchByType(query, section.type).map { it.uri }
        if (uris.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            when (section.type) {
                "track" -> metadata.getTrackMetadata(uris).map { track ->
                    SearchUiModel.TrackItem(track.uri, track)
                }

                "artist" -> uris.mapNotNull { uri ->
                    lookup { metadata.getArtistMetadata(uri) }?.let { SearchUiModel.ArtistItem(uri, it) }
                }

                "album" -> uris.mapNotNull { uri ->
                    lookup { metadata.getAlbumMetadata(uri) }?.let { SearchUiModel.AlbumItem(uri, it) }
                }

                "playlist" -> uris.mapNotNull { uri ->
                    lookup { metadata.getPlaylistMetadata(uri, true) }
                        ?.let { SearchUiModel.PlaylistItem(uri, it) }
                }

                "show" -> uris.mapNotNull { uri ->
                    lookup { metadata.getShowMetadata(uri) }?.let { SearchUiModel.ShowItem(uri, it) }
                }

                "episode" -> metadata.getEpisodeMetadata(uris).mapIndexed { index, episode ->
                    SearchUiModel.EpisodeItem(uris[index], episode)
                }

                else -> emptyList()
            }
        }
    }

    /** A single missing item must not fail the section; cancellation still propagates. */
    private inline fun <R> lookup(block: () -> R?): R? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun classifySearchError(error: Throwable): SearchErrorKind {
        Log.w("SearchViewModel", "search section failed", error)
        return when (SyncErrorClassifier.classify(error, rateLimitGate.isLimited())) {
            SyncFailure.RATE_LIMITED -> SearchErrorKind.RATE_LIMITED
            SyncFailure.TRANSIENT -> SearchErrorKind.NETWORK
            SyncFailure.FATAL -> SearchErrorKind.OTHER
        }
    }

    /**
     * Renders the sectioned list the screen already understands: a header per section, a
     * skeleton while it is pending, its items once done, nothing when empty or failed.
     */
    private fun SearchUiState<SearchUiModel>.toUiList(): List<SearchUiModel> {
        val sections = when (this) {
            is SearchUiState.InProgress -> sections
            is SearchUiState.Results -> sections
            SearchUiState.Idle, is SearchUiState.Error -> return emptyList()
        }
        return buildList {
            sections.forEachIndexed { index, snapshot ->
                when (val status = snapshot.status) {
                    SectionStatus.Pending -> {
                        add(SearchUiModel.SectionHeader(snapshot.section.headerRes))
                        add(SearchUiModel.SkeletonItem(index))
                    }

                    is SectionStatus.Done -> if (status.items.isNotEmpty()) {
                        add(SearchUiModel.SectionHeader(snapshot.section.headerRes))
                        addAll(status.items)
                    }

                    is SectionStatus.Failed -> Unit
                }
            }
        }
    }

    fun onQueryChange(query: String) {
        searchRequests.update { it.copy(query = query) }
    }

    /** Re-runs the current query after an error; a no-op while the query is blank. */
    fun retry() {
        searchRequests.update { current ->
            if (current.query.isBlank()) current else current.copy(attempt = current.attempt + 1)
        }
    }

    fun fetchRecommendations(seedIds: List<String>, config: RecommendationConfig) {
        viewModelScope.launch {
            _isLoading.value = true
            val trackIds = recommendations.fetchRecommendations(50, seedIds.toTypedArray(), config)
            if (trackIds.isEmpty()) {
                _isLoading.value = false
                return@launch
            }
            val uris = trackIds.map { "spotify:track:$it" }
            val tracks = withContext(Dispatchers.IO) {
                metadata.getTrackMetadata(uris)
            }
            loadTrackResults(tracks)
        }
    }

    fun loadTrackResults(tracks: List<Track>) {
        _results.value = tracks.map { SearchUiModel.TrackItem(it.uri, it) }
        _isRecommendationMode.value = true
        _isLoading.value = false
    }

    suspend fun getArtworkUrl(playlist: Playlist): String? {
        return playlist.getCover(metadata)
    }

    suspend fun getAuthors(playlist: Playlist): List<Profile> = coroutineScope {
        authorsCache[playlist.uri]?.let { return@coroutineScope it }

        val ids = playlist.contents
            .map { it.attributes.addedBy }
            .distinct()

        val profiles = ids.map { id ->
            async(Dispatchers.IO) {
                _authors.value[id]?.let { return@async it }

                val jsonRaw = try {
                    userProfile.getUserProfile(id)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                } ?: return@async null

                val profile = try {
                    json.decodeFromString<Profile>(jsonRaw)
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@async null
                }

                _authors.update { current -> current + (id to profile) }
                profile
            }
        }.awaitAll()
            .filterNotNull()

        authorsCache[playlist.uri] = profiles
        profiles
    }

    fun saveItem(uri: String) {
        viewModelScope.launch {
            // Blocking JNI network call: keep it off the main thread.
            val saved = withContext(Dispatchers.IO) { spClient.saveItems(arrayOf(uri)) }
            if (!saved) {
                Log.w("SearchViewModel", "saveItem failed")
            }
        }
    }

    fun isLiked(uri: OutifyUri): Flow<Boolean> {
        val id = uri.id
        return if (uri.isTrack) {
            likedDao.observeIsTrackLiked(id)
        } else {
            likedDao.observeIsEpisodeLiked(id)
        }
    }

    fun setAudio(audio: PlayableAudio) {
        playbackStateHolder.setAudio(audio)
    }

    fun addToHistory(item: SearchUiModel) {
        val historyItem = when (item) {
            is SearchUiModel.TrackItem -> SearchHistoryItem(item.uri, SearchResultType.TRACK)
            is SearchUiModel.ArtistItem -> SearchHistoryItem(item.uri, SearchResultType.ARTIST)
            is SearchUiModel.AlbumItem -> SearchHistoryItem(item.uri, SearchResultType.ALBUM)
            is SearchUiModel.PlaylistItem -> SearchHistoryItem(item.uri, SearchResultType.PLAYLIST)
            is SearchUiModel.ShowItem -> SearchHistoryItem(item.uri, SearchResultType.SHOW)
            is SearchUiModel.EpisodeItem -> SearchHistoryItem(item.uri, SearchResultType.EPISODE)
            else -> return
        }
        viewModelScope.launch {
            settingsRepository.addSearchHistoryItems(listOf(historyItem))
        }
    }

    fun removeFromHistory(uri: String) {
        viewModelScope.launch {
            settingsRepository.removeSearchHistoryItem(uri)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            settingsRepository.clearSearchHistory()
        }
    }
}

sealed class SearchUiModel {
    abstract val uri: String

    data class SectionHeader(
        @StringRes val titleRes: Int
    ) : SearchUiModel() {
        override val uri: String = "header_$titleRes"
    }

    data class SkeletonItem(
        val id: Int
    ) : SearchUiModel() {
        override val uri: String = "skeleton_$id"
    }

    data class TrackItem(
        override val uri: String,
        val track: Track
    ) : SearchUiModel()

    data class ArtistItem(
        override val uri: String,
        val artist: Artist
    ) : SearchUiModel()

    data class AlbumItem(
        override val uri: String,
        val album: Album
    ) : SearchUiModel()

    data class PlaylistItem(
        override val uri: String,
        val playlist: Playlist
    ) : SearchUiModel()

    data class ShowItem(
        override val uri: String,
        val show: Show
    ) : SearchUiModel()

    data class EpisodeItem(
        override val uri: String,
        val episode: Episode
    ) : SearchUiModel()
}
