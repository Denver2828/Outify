package cc.tomko.outify.ui.viewmodel.bottomsheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.LyricsResult
import cc.tomko.outify.core.model.LyricsSource
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.repository.LyricsRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /** Nothing to show: no provider had lyrics, or every lookup failed. */
    data object Missing : LyricsUiState()
}

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val lyricsRepository: LyricsRepository,
    private val playbackStateHolder: PlaybackStateHolder,
    private val spirc: SpircWrapper,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

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

        val cached = lyricsRepository.cached(track)
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
        LyricsResult.NotFound, LyricsResult.Error -> LyricsUiState.Missing
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
}
