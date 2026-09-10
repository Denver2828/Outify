package cc.tomko.outify.ui.viewmodel.bottomsheet

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.LyricsResult
import cc.tomko.outify.core.model.LyricsSource
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.repository.HiddenItemsRepository
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.data.repository.LyricsRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackModeController
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.ui.notifications.showTrackHiddenNotification
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * What the lyrics screens should draw for the displayed track.
 */
sealed class LyricsUiState {
    data object Loading : LyricsUiState()

    data class Found(
        val lines: List<LyricLine>,
        val source: LyricsSource,
        val synced: Boolean,
    ) : LyricsUiState()

    /** Definitive answer: no provider has lyrics for this track. */
    data object Missing : LyricsUiState()

    /** Transient failure (timeout, network); the lookup can be retried. */
    data object Error : LyricsUiState()
}

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val lyricsRepository: LyricsRepository,
    private val playbackStateHolder: PlaybackStateHolder,
    private val spirc: SpircWrapper,
    private val settingsRepository: SettingsRepository,
    private val likedDao: LikedDao,
    private val likedRepository: LikedRepository,
    private val modeController: PlaybackModeController,
    private val hiddenItemsRepository: HiddenItemsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val hiddenUris: StateFlow<Set<String>> = hiddenItemsRepository.hiddenUris

    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.Missing)
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()

    val lyrics: StateFlow<List<LyricLine>> = _lyricsState
        .map { (it as? LyricsUiState.Found)?.lines ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Provider that supplied the displayed lyrics; null while loading or when there are none.
     */
    val lyricsSource: StateFlow<LyricsSource?> = _lyricsState
        .map { (it as? LyricsUiState.Found)?.source }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isLoading: StateFlow<Boolean> = _lyricsState
        .map { it is LyricsUiState.Loading }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True when the last lookup failed for a transient reason and can be retried. */
    val hasError: StateFlow<Boolean> = _lyricsState
        .map { it is LyricsUiState.Error }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Real playback position. Drives the time label and the seek slider.
     */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /**
     * Lead time applied to the active-line computation; 0 when the offset is disabled.
     */
    private val lyricsOffsetMs: StateFlow<Long> = combine(
        settingsRepository.lyricsOffsetEnabled,
        settingsRepository.lyricsOffsetMs,
    ) { enabled, offsetMs ->
        if (enabled) offsetMs.toLong() else 0L
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * Position used to pick the active lyric line and to auto-scroll.
     * Positive offsets highlight a line before it is actually sung.
     */
    val effectivePositionMs: StateFlow<Long> = combine(
        _positionMs,
        lyricsOffsetMs,
    ) { position, offset ->
        position + offset
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * Multiplier applied to the lyric line font size (1.0 = default)
     */
    val lyricsFontScale: StateFlow<Float> = settingsRepository.lyricsFontScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1.0f)

    /**
     * When true, lyric lines on the lyrics screens are rendered bold.
     */
    val lyricsFontBold: StateFlow<Boolean> = settingsRepository.lyricsFontBold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Stored typeface id for the lyrics screens (see LyricsFontFamily); "sans" by default.
     */
    val lyricsFontFamily: StateFlow<String> = settingsRepository.lyricsFontFamily
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "sans")

    /**
     * When false the lyric text is shown static, without following the playback position.
     */
    val lyricsSynced: StateFlow<Boolean> = settingsRepository.lyricsSynced
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _isCurrentTrack = MutableStateFlow(false)
    val isCurrentTrack: StateFlow<Boolean> = _isCurrentTrack.asStateFlow()

    private val _displayedTrack = MutableStateFlow<Track?>(null)
    val displayedTrack: StateFlow<Track?> = _displayedTrack.asStateFlow()

    private val _isEpisode = MutableStateFlow(false)
    val isEpisode: StateFlow<Boolean> = _isEpisode.asStateFlow()

    /**
     * Liked state of the displayed track (not necessarily the one playing),
     * so the heart stays correct when the sheet was opened from a track detail.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isLiked: StateFlow<Boolean> = _displayedTrack
        .flatMapLatest { track ->
            if (track == null) flowOf(false) else likedDao.observeIsTrackLiked(track.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * True only when the displayed lyrics carry real timestamps; plain-text
     * lyrics from a fallback provider cannot follow the playback position.
     */
    val hasSyncedContent: StateFlow<Boolean> = _lyricsState
        .map { state -> state is LyricsUiState.Found && state.synced && state.lines.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var followCurrentTrack = false
    private var currentAudioObserver: Job? = null
    private var fetchJob: Job? = null

    val currentAudio: StateFlow<PlayableAudio?> = playbackStateHolder.state
        .map { it.currentAudio }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isPlaying: StateFlow<Boolean> = playbackStateHolder.state
        .map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Same persisted flag the player reads, so both screens agree on the shuffle state.
    val isShuffling: StateFlow<Boolean> = settingsRepository.shuffleEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val durationMs: StateFlow<Long> = playbackStateHolder.state
        .map { it.currentAudio?.duration ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    init {
        viewModelScope.launch {
            while (isActive) {
                _positionMs.value = playbackStateHolder.estimatePosition().inWholeMilliseconds
                delay(250L)
            }
        }
    }

    fun loadLyrics(track: Track, followCurrentTrack: Boolean = false) {
        this.followCurrentTrack = followCurrentTrack
        _displayedTrack.value = track
        _isEpisode.value = false

        _isCurrentTrack.value = playbackStateHolder.state.value.currentAudio?.id == track.id

        fetchLyrics(track)

        currentAudioObserver?.cancel()
        currentAudioObserver = viewModelScope.launch {
            currentAudio.collect { audio ->
                if (audio == null) return@collect

                val currentId = audio.id
                val displayedId = _displayedTrack.value?.id
                val matchesDisplayed = currentId == displayedId

                if (matchesDisplayed) {
                    _isCurrentTrack.value = true
                    return@collect
                }

                if (!this@LyricsViewModel.followCurrentTrack) {
                    _isCurrentTrack.value = false
                    return@collect
                }

                // following new audio track
                if (audio.isTrack()) {
                    val sourceTrack = audio.sourceTrack
                    if (sourceTrack != null) {
                        _displayedTrack.value = sourceTrack
                        _isEpisode.value = false
                        fetchLyrics(sourceTrack)
                        _isCurrentTrack.value = true
                    } else {
                        _isCurrentTrack.value = false
                    }
                } else {
                    fetchJob?.cancel()
                    _isEpisode.value = true
                    _lyricsState.value = LyricsUiState.Missing
                    _isCurrentTrack.value = false
                }
            }
        }
    }

    private fun fetchLyrics(track: Track) {
        fetchJob?.cancel()

        val cached = lyricsRepository.peek(track)
        if (cached != null) {
            _lyricsState.value = cached.toUiState()
            return
        }

        _lyricsState.value = LyricsUiState.Loading
        fetchJob = viewModelScope.launch {
            val result = try {
                lyricsRepository.getLyrics(track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LyricsResult.Error
            }
            // A newer track may have been requested while this lookup ran
            if (_displayedTrack.value?.id == track.id) {
                _lyricsState.value = result.toUiState()
            }
        }
    }

    private fun LyricsResult.toUiState(): LyricsUiState = when (this) {
        is LyricsResult.Found -> LyricsUiState.Found(lines, source, synced)
        LyricsResult.NotFound -> LyricsUiState.Missing
        LyricsResult.Error -> LyricsUiState.Error
    }

    /** Re-runs the lookup for the displayed track after a transient failure. */
    fun retryLyrics() {
        if (_isEpisode.value) return
        val track = _displayedTrack.value ?: return
        fetchLyrics(track)
    }

    fun toggleLiked() {
        if (_isEpisode.value) return
        val trackId = _displayedTrack.value?.id ?: return
        viewModelScope.launch {
            likedRepository.toggleTrackLiked(trackId)
        }
    }

    fun toggleHideTrack() {
        if (_isEpisode.value) return
        val trackUri = _displayedTrack.value?.uri ?: return
        viewModelScope.launch {
            if (hiddenUris.value.contains(trackUri)) {
                hiddenItemsRepository.unhide(trackUri)
            } else {
                hiddenItemsRepository.hideTrack(trackUri)
                if (isCurrentTrack.value) {
                    withContext(Dispatchers.IO) { spirc.playerNext() }
                }
                showTrackHiddenNotification(context) {
                    viewModelScope.launch { hiddenItemsRepository.unhide(trackUri) }
                }
            }
        }
    }

    fun seekTo(timestampMs: Long) {
        viewModelScope.launch {
            spirc.seekTo(timestampMs)
            playbackStateHolder.seekTo(timestampMs.toDuration(DurationUnit.MILLISECONDS))
        }
    }

    fun playPause() {
        viewModelScope.launch {
            spirc.playerPlayPause()
            playbackStateHolder.setPlaying(!playbackStateHolder.state.value.isPlaying)
        }
    }

    fun skipPrevious() {
        viewModelScope.launch {
            spirc.playerPrevious()
        }
    }

    fun skipNext() {
        viewModelScope.launch {
            spirc.playerNext()
        }
    }

    // Goes through the shared mode controller so the player, notification and this screen stay in sync.
    fun toggleShuffle() {
        viewModelScope.launch {
            modeController.toggleShuffle()
        }
    }
}
