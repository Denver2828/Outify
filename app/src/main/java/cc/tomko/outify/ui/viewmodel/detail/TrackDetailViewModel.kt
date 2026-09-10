package cc.tomko.outify.ui.viewmodel.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.repository.HiddenItemsRepository
import cc.tomko.outify.ui.notifications.showTrackHiddenNotification
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.data.repository.LyricsRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.ui.screens.library.track.TrackUiState
import cc.tomko.outify.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadata: Metadata,
    private val playbackStateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    private val lyricsRepository: LyricsRepository,
    private val likedRepository: LikedRepository,
    private val likedDao: LikedDao,
    private val hiddenItemsRepository: HiddenItemsRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val hiddenUris: StateFlow<Set<String>> = hiddenItemsRepository.hiddenUris

    private val _uiState = MutableStateFlow(TrackUiState())
    val uiState: StateFlow<TrackUiState> = _uiState

    /**
     * Multiplier applied to lyric lines, shared with the lyrics sheet setting
     */
    val lyricsFontScale: StateFlow<Float> = settingsRepository.lyricsFontScale
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 1.0f
        )

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

    private var _lastTrackUri: String? = null

    fun retry() {
        val uri = _lastTrackUri ?: return
        viewModelScope.launch {
            spirc.restart()
            _uiState.value = TrackUiState(isLoading = true, error = null)
            loadTrack(uri)
        }
    }

    fun loadTrack(trackUri: String) {
        _lastTrackUri = trackUri
        viewModelScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) {
                    metadata.getTrackMetadata(listOf(trackUri))
                }
                val track = tracks.firstOrNull()
                if (track == null) {
                    _uiState.value = TrackUiState(isLoading = false, error = context.getString(R.string.screen_error_track_not_found))
                    return@launch
                }

                val lyrics = withContext(Dispatchers.IO) {
                    lyricsRepository.getLyrics(track).linesOrEmpty
                }

                _uiState.value = TrackUiState(
                    isLoading = false,
                    track = track,
                    lyrics = lyrics,
                )
            } catch (e: Exception) {
                _uiState.value = TrackUiState(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun toggleLike(trackUri: String) {
        viewModelScope.launch {
            // Shared optimistic toggle: main-safe, rolled back if Spotify rejects it.
            likedRepository.toggleTrackLiked(trackUri.substringAfterLast(":"))
        }
    }

    fun toggleHideTrack(trackUri: String) {
        viewModelScope.launch {
            if (hiddenUris.value.contains(trackUri)) {
                hiddenItemsRepository.unhide(trackUri)
            } else {
                hiddenItemsRepository.hideTrack(trackUri)
                if (currentAudio.value?.uri == trackUri) {
                    withContext(Dispatchers.IO) { spirc.playerNext() }
                }
                showTrackHiddenNotification(context) {
                    viewModelScope.launch { hiddenItemsRepository.unhide(trackUri) }
                }
            }
        }
    }

    fun setTrack(track: Track) {
        playbackStateHolder.setAudio(track.toPlayableAudio())
    }
}
