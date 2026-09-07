package cc.tomko.outify.playback

import android.app.Application
import android.content.Context
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.audio.AudioFocusManager
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.R
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Episode
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.diagnostics.AudioDiagnostics
import cc.tomko.outify.playback.callbacks.PlayerEventCallback
import cc.tomko.outify.playback.model.PlayState
import cc.tomko.outify.playback.model.RepeatMode
import cc.tomko.outify.services.PlaybackService
import coil3.Bitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.google.common.util.concurrent.SettableFuture
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Singleton
@UnstableApi
class Player @Inject constructor(
    application: Application,
    val stateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    val json: Json,
    val imageLoader: ImageLoader,
    private val modeController: PlaybackModeController,
) : SimpleBasePlayer(application.mainLooper) {

    private val appContext: Context = application.applicationContext

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    init {
        // Repeat/shuffle can change from the in-app controls, the notification buttons or a
        // remote controller; every writer lands in the state holder, so this is the single
        // place that turns those changes into Media3 listener events.
        // The current value is read directly by getState(); only later changes need an
        // invalidation, and skipping the first emission keeps invalidateState() out of
        // the constructor.
        scope.launch {
            stateHolder.state
                .map { it.repeatMode to it.shuffleEnabled }
                .distinctUntilChanged()
                .drop(1)
                .collect { invalidateState() }
        }
    }
    @Volatile
    var currentArtworkBitmap: Bitmap? = null
    @Volatile
    private var currentArtworkBytes: ByteArray? = null
    @Volatile
    private var currentArtworkUri: String? = null

    private var artworkJob: Job? = null

    var engine: AudioEngine =
        AudioEngine(
            application.applicationContext,
            object : PlayerEventCallback {
                override fun onTrackChange(spotify_uri: String, json_str: String) {
                AudioDiagnostics.record("Player", "track change: $spotify_uri")
                scope.launch {
                    val audio = if (spotify_uri.startsWith("spotify:episode:")) {
                        val episode: Episode = try {
                            json.decodeFromString(json_str)
                        } catch (e: Exception) {
                            Log.w("Player", "Failed to decode episode JSON", e)
                            return@launch
                        }
                        episode.toPlayableAudio()
                    } else {
                        val track: Track = try {
                            json.decodeFromString(json_str)
                        } catch (e: Exception) {
                            Log.w("Player", "Failed to decode track JSON", e)
                            return@launch
                        }
                        track.toPlayableAudio()
                    }
                    stateHolder.setAudio(audio)

                    val cover = if (audio.isEpisode()) {
                        audio.covers.firstOrNull()
                    } else {
                        audio.sourceTrack?.album?.getCover(CoverSize.LARGE)
                    }
                    val artworkUrl = cover?.let { ALBUM_COVER_URL + it.uri }
                    currentArtworkUri = artworkUrl

                    invalidateState()

                    artworkJob?.cancel()

                    if (artworkUrl == null) return@launch

                    artworkJob = scope.launch {
                        val loadResult = withContext(Dispatchers.IO) {
                            try {
                                val request = ImageRequest.Builder(application)
                                    .data(artworkUrl)
                                    .allowHardware(false)
                                    .build()

                                val result = imageLoader.execute(request)
                                val bmp = result.image?.toBitmap()

                                val finalBmp = bmp?.let {
                                    val max = 1024
                                    if (it.width > max || it.height > max) {
                                        val ratio = minOf(
                                            max.toFloat() / it.width,
                                            max.toFloat() / it.height
                                        )
                                        it.scale(
                                            (it.width * ratio).toInt(),
                                            (it.height * ratio).toInt()
                                        )
                                    } else it
                                }

                                val bytes = finalBmp?.let { fb ->
                                    ByteArrayOutputStream().use { stream ->
                                        fb.compress(
                                            android.graphics.Bitmap.CompressFormat.PNG,
                                            100,
                                            stream
                                        )
                                        stream.toByteArray()
                                    }
                                }

                                Pair(finalBmp, bytes)
                            } catch (e: Exception) {
                                Log.w("Player", "artwork load failed", e)
                                null
                            }
                        }

                        if (loadResult == null) return@launch

                        val (loadedBitmap, loadedBytes) = loadResult

                        val currentTrackId = stateHolder.state.value.currentAudio?.id
                        if (currentTrackId != audio.id) {
                            return@launch
                        }

                        withContext(Dispatchers.Main) {
                            currentArtworkBitmap = loadedBitmap
                            currentArtworkBytes = loadedBytes

                            invalidateState()
                        }
                    }
                }
            }

            override fun onPositionUpdate(
                spotify_uri: String,
                position_ms: Long,
                json_raw: String
            ) {
                scope.launch {
                    stateHolder.seekTo(position_ms.toDuration(DurationUnit.MILLISECONDS))

                    val currentAudio = stateHolder.state.value.currentAudio
                    if (spotify_uri.startsWith("spotify:episode:") && currentAudio?.isEpisode() != true) {
                        val episode: Episode? = try {
                            json.decodeFromString(json_raw)
                        } catch (e: Exception) {
                            Log.w("Player", "Failed to decode episode JSON in position update", e)
                            null
                        }
                        episode?.let { stateHolder.setAudio(it.toPlayableAudio()) }
                    } else if (spotify_uri.startsWith("spotify:track:") && currentAudio?.isTrack() != true) {
                        val track: Track? = try {
                            json.decodeFromString(json_raw)
                        } catch (e: Exception) {
                            Log.w("Player", "Failed to decode track JSON in position update", e)
                            null
                        }
                        track?.let { stateHolder.setAudio(it.toPlayableAudio()) }
                    }

                    invalidateState()
                }
            }

            override fun onPlayingStatus(playing: Boolean) {
                AudioDiagnostics.record("Player", "librespot playing=$playing")
                scope.launch {
                    stateHolder.setPlaying(playing)
                    invalidateState()
                    syncAudioFocus(playing)
                }
            }
        }, stateHolder)

    /**
     * Keeps Android audio focus in step with what librespot reports.
     *
     * Playback started through `spirc.load()` (the UI, Android Auto, the tile) never goes
     * through [handleSetPlayWhenReady], so without this the app plays without holding focus.
     * Besides being bad citizenship, some car head units and Android boxes only open their
     * media audio channel for the app that holds focus, which surfaces as silent playback
     * with a moving progress bar.
     */
    private fun syncAudioFocus(playing: Boolean) {
        val command = audioFocusManager.updateAudioFocus(playing, STATE_READY)
        AudioDiagnostics.record("Player", "audio focus sync: playing=$playing -> command=$command")
        if (!playing) return

        when (command) {
            AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY -> Unit

            AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK -> {
                Log.i("Player", "Audio focus delayed, pausing until it is granted")
                scope.launch(Dispatchers.IO) { spirc.playerPause() }
            }

            AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY -> {
                Log.w("Player", "Audio focus denied, pausing")
                scope.launch(Dispatchers.IO) { spirc.playerPause() }
            }
        }
    }

    private val audioFocusManager = AudioFocusManager(
        application.applicationContext,
        application.mainLooper,
        object : AudioFocusManager.PlayerControl {
            override fun setVolumeMultiplier(volume: Float) {
                AudioDiagnostics.record("Player", "focus volume multiplier $volume")
                engine.setVolume(volume)
            }

            override fun executePlayerCommand(command: Int) {
                AudioDiagnostics.record("Player", "focus callback command=$command")
                when (command) {
                    AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK,
                    AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY -> {
                        scope.launch(Dispatchers.IO) {
                            spirc.playerPause()
                        }
                        scope.launch {
                            stateHolder.setPlaying(false)
                            invalidateState()
                        }
                    }

                    AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY -> {
                        scope.launch(Dispatchers.IO) {
                            spirc.playerPlay()
                        }
                        scope.launch {
                            stateHolder.setPlaying(true)
                            invalidateState()
                        }
                    }
                }
            }
        }
    )

    init {
        audioFocusManager.setAudioAttributes(PlaybackService.AUDIO_ATTRIBUTES)
    }

    override fun getState(): State {
        val ps = stateHolder.state.value
        val audio = ps.currentAudio ?: return State.Builder()
            .setPlaybackState(STATE_IDLE)
            .setAvailableCommands(determineCommands())
            .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(ps.repeatMode.toMediaRepeatMode())
            .setShuffleModeEnabled(ps.shuffleEnabled)
            .setPlaylist(emptyList())
            .build()

        val subtitle = audio.artists?.joinToString { it.name }
            ?: audio.showName
            ?: appContext.getString(R.string.sys_player_unknown_source)

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(audio.name)
            .setDisplayTitle(audio.name)
            .setArtist(subtitle)
            .setMediaType(if (audio.isEpisode()) MediaMetadata.MEDIA_TYPE_PODCAST else MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                currentArtworkUri?.let { setArtworkUri(it.toUri()) }
                currentArtworkBytes?.let { bytes ->
                    setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(audio.id)
            .setUri(audio.uri)
            .setMediaMetadata(mediaMetadata)
            .build()

        val playlist = listOf(
            MediaItemData.Builder(audio.id)
                .setMediaItem(mediaItem)
                .setDurationUs(audio.duration * 1000L)
                .setDefaultPositionUs(0)
                .setIsSeekable(true)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .build()
        )

        val playbackState = when {
            ps.state == PlayState.BUFFERING -> STATE_BUFFERING
            playlist.isEmpty() -> STATE_IDLE
            ps.isPlaying -> STATE_READY
            else -> STATE_READY
        }

        return State.Builder()
            .setAudioAttributes(PlaybackService.AUDIO_ATTRIBUTES)
            .setPlaybackState(playbackState)
            .setAvailableCommands(determineCommands())
            .setPlayWhenReady(ps.isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(ps.playbackSpeed))
            .setRepeatMode(ps.repeatMode.toMediaRepeatMode())
            .setShuffleModeEnabled(ps.shuffleEnabled)
            .setCurrentMediaItemIndex(if (playlist.isNotEmpty()) 0 else C.INDEX_UNSET)
            .setContentPositionMs(ps.position.active.inWholeMilliseconds)
            .setIsLoading(ps.state == PlayState.BUFFERING)
            .setPlaylist(playlist)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val currentMedia3State = if (stateHolder.state.value.currentAudio == null) STATE_IDLE else STATE_READY

        val playerCommand = audioFocusManager.updateAudioFocus(playWhenReady, currentMedia3State)

        scope.launch(Dispatchers.IO) {
            when (playerCommand) {
                AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY -> {
                    if (playWhenReady) spirc.playerPlay() else spirc.playerPause()
                }

                AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY,
                AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK -> {
                    spirc.playerPause()
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
//        spirc.seekTo(mediaItemIndex, positionMs)
        when (seekCommand) {
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> scope.launch(Dispatchers.IO) { spirc.playerPrevious() }
            COMMAND_SEEK_TO_PREVIOUS -> scope.launch(Dispatchers.IO) { spirc.playerPrevious() }

            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> scope.launch(Dispatchers.IO) { spirc.playerNext() }
            COMMAND_SEEK_TO_NEXT -> scope.launch(Dispatchers.IO) { spirc.playerNext() }

            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> scope.launch(Dispatchers.IO) {
                spirc.seekTo(positionMs)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        scope.launch(Dispatchers.IO) {
            spirc.playerPause() //TODO: Implement playerStop
        }
        audioFocusManager.updateAudioFocus(false, STATE_IDLE)
        return Futures.immediateVoidFuture()
    }

    /**
     * Standard Media3 repeat command (Android Auto, Bluetooth AVRCP, Wear, media buttons).
     * Shares the exact path of the in-app control and the notification button, so the
     * mode reported back through [getState] is the one Spirc is actually running.
     */
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> =
        modeOperation("repeat=$repeatMode") {
            modeController.setRepeatMode(RepeatMode.fromMediaRepeatMode(repeatMode))
        }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        modeOperation("shuffle=$shuffleModeEnabled") {
            modeController.setShuffleEnabled(shuffleModeEnabled)
        }

    /**
     * Runs a mode change off the main thread and completes the future once Spirc has
     * answered, so controllers do not see success before the change is issued. The JNI
     * call is not cancellable: on timeout the future completes (the placeholder state is
     * dropped and [getState] keeps reporting the last confirmed mode) while the native
     * call finishes in the background and reconciles through the state holder.
     */
    private fun modeOperation(label: String, block: suspend () -> Boolean): ListenableFuture<*> {
        val future = SettableFuture.create<Unit>()
        scope.launch {
            val accepted = withTimeoutOrNull(MODE_COMMAND_TIMEOUT_MS) { block() }
            when (accepted) {
                null -> Log.w("Player", "Mode change timed out: $label")
                false -> Log.w("Player", "Mode change rejected by Spirc: $label")
                true -> Unit
            }
            future.set(Unit)
        }
        return future
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    fun setSpeed(speed: Float) {
        scope.launch {
            stateHolder.setSpeed(speed)
            invalidateState()
        }
    }

    override fun handlePrepare(): ListenableFuture<*> {
        scope.launch(Dispatchers.IO) {
            spirc.ensureUsable()
        }

        return super.handlePrepare()
    }

    public override fun handleRelease(): ListenableFuture<*> {
        engine.releaseAudioTrack()
        engine.releaseNative()

        audioFocusManager.release()
        return Futures.immediateVoidFuture()
    }

    private fun determineCommands(): Player.Commands {
        val builder = Player.Commands.Builder()
            .add(COMMAND_PLAY_PAUSE)
            .add(COMMAND_PREPARE)
            .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(COMMAND_GET_METADATA)
            .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_NEXT)
            .add(COMMAND_SEEK_TO_PREVIOUS)
            .add(COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(COMMAND_SET_REPEAT_MODE)
            .add(COMMAND_SET_SHUFFLE_MODE)
            .add(COMMAND_STOP)

        return builder.build()
    }

    private companion object {
        /** Upper bound for a repeat/shuffle change before the controller is released. */
        const val MODE_COMMAND_TIMEOUT_MS = 5_000L
    }
}
