package cc.tomko.outify.ui.viewmodel.detail

import androidx.lifecycle.SavedStateHandle
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.dropHidden
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.repository.HiddenItemsRepository
import cc.tomko.outify.diagnostics.AudioDiagnostics
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.ui.screens.library.album.AlbumUiState
import cc.tomko.outify.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

private const val ALBUM_STATE_KEY = "album_state"

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadata: Metadata,
    private val playbackStateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    val spClient: SpClient,
    val json: Json,
    val likedDao: LikedDao,
    private val hiddenItemsRepository: HiddenItemsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val rawUiState = MutableStateFlow(
        savedStateHandle.get<String>(ALBUM_STATE_KEY)?.let {
            try {
                json.decodeFromString<AlbumUiState>(it)
            } catch (e: Exception) {
                AlbumUiState()
            }
        } ?: AlbumUiState()
    )

    /** [rawUiState] with hidden tracks dropped, live against the hidden set. */
    val uiState: StateFlow<AlbumUiState> = combine(
        rawUiState,
        hiddenItemsRepository.hiddenUris,
    ) { state, hidden -> state.copy(tracks = state.tracks.dropHidden(hidden)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), rawUiState.value)

    val hiddenUris: StateFlow<Set<String>> = hiddenItemsRepository.hiddenUris

    fun toggleHideAlbum() {
        viewModelScope.launch {
            val album = rawUiState.value.album ?: return@launch
            val uri = album.uri
            if (hiddenUris.value.contains(uri)) {
                hiddenItemsRepository.unhide(uri)
            } else {
                hiddenItemsRepository.hideAlbum(uri)
                // If the hidden album is currently playing, skip away from it immediately.
                val playingAlbumUri = currentAudio.value?.sourceTrack?.album?.uri
                if (playingAlbumUri == uri) {
                    AudioDiagnostics.record("AlbumDetailViewModel", "hidden album was playing, skipping: $uri")
                    withContext(Dispatchers.IO) { spirc.playerNext() }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val likedTrackIds: StateFlow<Set<String>> =
        likedDao.observeLikedIds()
            .map { it.toHashSet() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptySet()
            )

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

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved

    fun toggleSave() {
        viewModelScope.launch {
            val album = rawUiState.value.album ?: return@launch
            val uri = album.uri
            if (_isSaved.value) {
                withContext(Dispatchers.IO) { spClient.deleteItems(arrayOf(uri)) }
                metadata.removeLikedAlbum(uri)
            } else {
                withContext(Dispatchers.IO) { spClient.saveItems(arrayOf(uri)) }
                metadata.addLikedAlbum(uri)
            }
            _isSaved.value = !_isSaved.value
        }
    }

    private var _lastAlbumUri: String? = null

    fun retry() {
        val uri = _lastAlbumUri ?: return
        viewModelScope.launch {
            spirc.restart()
            rawUiState.value = rawUiState.value.copy(isLoading = true, error = null)
            loadAlbum(uri)
        }
    }

    private fun checkIsSaved(albumUri: String) {
        viewModelScope.launch {
            _isSaved.value = metadata.isLikedAlbum(albumUri)
        }
    }

    private fun saveState(state: AlbumUiState) {
        savedStateHandle[ALBUM_STATE_KEY] = json.encodeToString(AlbumUiState.serializer(), state)
    }

    suspend fun loadAlbum(albumUri: String) {
        _lastAlbumUri = albumUri
        try {
            val album = withContext(Dispatchers.IO) {
                metadata.getAlbumMetadata(albumUri)
            }

            if (album == null) {
                val newState = AlbumUiState(
                    isLoading = false,
                    error = context.getString(R.string.screen_error_album_not_found)
                )
                rawUiState.value = newState
                saveState(newState)
                return
            }

            val trackUris: List<String> = album.tracks.map { it }

            val tracks: List<Track> = if (trackUris.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    metadata.getTrackMetadata(trackUris)
                }
            } else emptyList()

            val newState = AlbumUiState(
                isLoading = false,
                album = album,
                tracks = tracks,
            )
            rawUiState.value = newState
            _isSaved.value = false
            checkIsSaved(albumUri)
            saveState(newState)
        } catch (e: Exception) {
            val newState = AlbumUiState(
                isLoading = false,
                error = e.message
            )
            rawUiState.value = newState
            saveState(newState)
        }
    }

    suspend fun loadAlbumFromTrackUri(trackUri: String) {
        try {
            val albumId = withContext(Dispatchers.IO) {
                metadata.getTrackAlbumId(trackUri)
            }

            if (albumId == null) {
                val newState = AlbumUiState(
                    isLoading = false,
                    error = context.getString(R.string.screen_error_album_for_track_not_found)
                )
                rawUiState.value = newState
                saveState(newState)
                return
            }

            loadAlbum("spotify:album:$albumId")
        } catch (e: Exception) {
            val newState = AlbumUiState(
                isLoading = false,
                error = e.message
            )
            rawUiState.value = newState
            saveState(newState)
        }
    }

    fun setTrack(track: Track) {
        playbackStateHolder.setAudio(track.toPlayableAudio())
    }
}
