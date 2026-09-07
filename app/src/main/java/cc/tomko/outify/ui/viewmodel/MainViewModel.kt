package cc.tomko.outify.ui.viewmodel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.EpisodeDetails
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.core.model.toSpotifyUri
import cc.tomko.outify.data.repository.InterfaceSettings
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.data.repository.PlayerRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.data.setting.EpisodeSwipeActionHandler
import cc.tomko.outify.data.setting.GestureSetting
import cc.tomko.outify.data.setting.SwipeActionHandler
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.PopupSpec
import cc.tomko.outify.ui.notifications.InAppNotificationController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import android.content.Context
import androidx.compose.ui.res.stringResource
import cc.tomko.outify.R
import dagger.hilt.android.qualifiers.ApplicationContext

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackStateHolder: PlaybackStateHolder,
    private val spirc: SpircWrapper,
    private val settingsRepository: SettingsRepository,
    private val spClient: SpClient,
    private val likedRepository: LikedRepository,
    private val playerRepository: PlayerRepository,
    private val json: Json,
) : ViewModel() {
    val swipeSettings: Flow<List<GestureSetting>> =
        settingsRepository.interfaceSettings.map { it.gestureSettings }

    val interfaceSettings: Flow<InterfaceSettings> =
        settingsRepository.interfaceSettings

    val episodeSwipeActionHandler = object : EpisodeSwipeActionHandler {
        override fun addToQueue(uri: String) {
            this@MainViewModel.addToQueue(uri)
        }

        override fun playNext(uri: String) {
            this@MainViewModel.playNext(uri)
        }

        override fun favorite(episodeUri: String) {
            this@MainViewModel.favorite(episodeUri)
        }
    }

    val swipeActionHandler = object : SwipeActionHandler {
        override fun addToQueue(uri: String) {
            this@MainViewModel.addToQueue(uri)
        }

        override fun playNext(uri: String) {
            this@MainViewModel.playNext(uri)
        }

        override fun startRadio(track: Track) {
            this@MainViewModel.startRadio(track)
        }

        override fun favorite(trackUri: String) {
            this@MainViewModel.favorite(trackUri)
        }

        override fun addToPlaylist(track: Track) {
            this@MainViewModel.addToPlaylist(track)
        }

        override fun trackInfo(track: Track) {
            openTrackInfo(track)
        }
    }

    val currentAudio: StateFlow<PlayableAudio?> = playbackStateHolder.state
        .map { it.currentAudio }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    fun addToQueue(uri: String) {
        spirc.addToQueue(uri)
        InAppNotificationController.show(
            context.getString(R.string.ui_notif_added_to_queue),
            { Icon(Icons.Default.Queue, contentDescription = stringResource(R.string.ui_notif_added_to_queue)) },
            1000L
        )
    }

    fun playNext(uri: String) {
        spirc.playNext(uri)
        InAppNotificationController.show(
            context.getString(R.string.ui_notif_inserted_to_queue),
            { Icon(Icons.Default.Queue, contentDescription = stringResource(R.string.ui_notif_inserted_to_queue)) },
            1000L
        )
    }

    /**
     * Resolves and starts the radio for [track]. The network round trip happens off the
     * main thread; the "radio started" notice is only shown once the radio actually loaded.
     */
    fun startRadio(track: Track) {
        viewModelScope.launch {
            val started = spirc.startRadio(track.toSpotifyUri(), false)
            if (!started) {
                InAppNotificationController.show(
                    context.getString(R.string.ui_notif_radio_failed),
                    durationMillis = 2000L
                )
                return@launch
            }
            playbackStateHolder.setAudio(track.toPlayableAudio())
            InAppNotificationController.show(
                context.getString(R.string.ui_notif_radio_started),
                { Icon(Icons.Default.Radio, contentDescription = stringResource(R.string.ui_notif_radio_started)) },
                1000L
            )
        }
    }

    /**
     * Resolves the radio playlist uri for [track] off the main thread and hands it to
     * [onResolved] on the main thread. A `null` means Spotify has no radio for it or the
     * request failed; the user is told in that case.
     */
    fun resolveRadioUri(track: Track, onResolved: (String?) -> Unit) {
        viewModelScope.launch {
            val uri = playerRepository.getRadioPlaylistUri(track.uri)
            if (uri == null) {
                InAppNotificationController.show(
                    context.getString(R.string.ui_notif_radio_failed),
                    durationMillis = 2000L
                )
            }
            onResolved(uri)
        }
    }

    fun addToPlaylist(track: Track) {
        GlobalPopupController.show(PopupSpec.AddToPlaylist(listOf(track)))
    }

    fun addToPlaylist(tracks: List<Track>) {
        GlobalPopupController.show(PopupSpec.AddToPlaylist(tracks))
    }

    /**
     * Flips the liked state of a track or episode through the shared repository toggle,
     * so this control converges on the same state as every other like button.
     */
    fun favorite(rawUri: String) {
        viewModelScope.launch {
            val result = likedRepository.toggleLikedByUri(rawUri) ?: return@launch
            if (!result.succeeded) {
                InAppNotificationController.show(
                    context.getString(R.string.ui_notif_favorite_failed),
                    durationMillis = 2000L
                )
            }
        }
    }

    fun openTrackInfo(track: Track) {
        viewModelScope.launch {
            val likedIndex = try {
                likedRepository.getTrackIndex(track.uri)
            } catch (_: Exception) {
                -1
            }

            val isLiked = likedRepository.isLiked(track.id)

            GlobalPopupController.show(
                PopupSpec.TrackInfo(
                    track,
                    likedTrackIndex = if (likedIndex >= 0) likedIndex else null,
                    isLiked = isLiked
                )
            )
        }
    }

    fun getEpisodeDetails(episodeId: String): EpisodeDetails {
        val raw = spClient.getEpisodeDetails(episodeId)
        return json.decodeFromString<EpisodeDetails>(raw)
    }
}