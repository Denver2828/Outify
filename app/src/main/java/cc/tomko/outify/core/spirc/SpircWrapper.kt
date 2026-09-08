package cc.tomko.outify.core.spirc

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import cc.tomko.outify.core.RateLimitGate
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.DevicesResponse
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.core.spirc.ISpircWrapper
import cc.tomko.outify.core.spirc.Spirc
import cc.tomko.outify.data.metadata.NativeErrorHandler
import cc.tomko.outify.data.repository.PlayerRepository
import cc.tomko.outify.data.repository.SavedQueueRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.services.PlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.Volatile
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Singleton
class SpircWrapper @Inject constructor(
    @ApplicationContext val context: Context,
    private val playbackStateHolder: PlaybackStateHolder,
    private val spClient: SpClient,
    private val settingsRepository: SettingsRepository,
    private val savedQueueRepository: SavedQueueRepository,
    private val playerRepository: PlayerRepository,
    private val json: Json,
    private val rateLimitGate: RateLimitGate,
) : ISpircWrapper {
    private companion object {
        const val TAG = "SpircWrapper"
    }

    val scope = CoroutineScope(
        Dispatchers.Main.immediate + SupervisorJob()
    )

    private var restartCallback: (() -> Unit)? = null

    fun setRestartCallback(callback: () -> Unit) {
        this.restartCallback = callback
    }

    fun ensureUsable() {
        if (!isUsable) {
            scope.launch(Dispatchers.IO) {
                restartCallback?.invoke()
            }
        }
    }

    @Volatile
    var isUsable = false
        private set

    fun setUsable(usable: Boolean) {
        isUsable = usable
    }

    fun restart() {
        setUsable(false)
        scope.launch(Dispatchers.IO) {
            restartCallback?.invoke()
        }
    }

    override fun shutdown() {
        setUsable(false)
        Spirc.shutdown()
    }

    override suspend fun startRadio(trackUri: OutifyUri, shuffle: Boolean): Boolean {
        // Network resolution happens off the main thread inside the repository.
        val playlistUri = playerRepository.getRadioPlaylistUri(trackUri.toUriString())
            ?: return false
        val uri = OutifyUri.fromUriString(playlistUri)

        if (shuffle) {
            shuffleLoad(playlistUri)
        } else {
            load(uri, trackUri)
        }

        return true
    }

    @OptIn(UnstableApi::class)
    fun startPlaybackService() {
        // A live service needs no new start request. Re-issuing startForegroundService()
        // on every playback command obliged the service to call startForeground() again
        // within the system timeout, which nobody did while the player was paused (ANR).
        if (PlaybackService.isRunning) return
        val intent = Intent(context, PlaybackService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            // Background start not allowed (API 31+ ForegroundServiceStartNotAllowedException).
            Log.w("SpircWrapper", "Could not start PlaybackService in the foreground", e)
        }
    }

    @OptIn(UnstableApi::class)
    private fun ensureServiceRunning() {
        ensureUsable()
        startPlaybackService()
    }


    /**
     * Loads a SpotifyURI
     * @param context valid form of URI, that will get loaded. Leave empty for liked tracks
     * @param playingTrackUri from which to start playing in this context. Leave empty for first/random
     * @return `true` if loaded successfully
     */
    override fun load(context: OutifyUri?, playingTrackUri: OutifyUri?): Boolean {
        scope.launch {
            savedQueueRepository.setActiveQueueId(null)

            val currentPosition = playbackStateHolder.estimatePosition().inWholeMilliseconds
            settingsRepository.saveLastPlayback(
                trackUri = playingTrackUri?.toUriString(),
                contextUri = context?.toUriString(),
                positionMs = if (currentPosition > 0) currentPosition else null
            )
        }

        ensureServiceRunning()
        return Spirc.load(context?.toUriString(), playingTrackUri?.toUriString())
    }

    override fun setQueue(uris: Array<String>, playingTrackUri: String?): Boolean {
        ensureServiceRunning()
        return Spirc.setQueue(uris, playingTrackUri)
    }

    /**
     * Inserts [uris] ahead of the next tracks. The current track, its position and (when nothing
     * is queued ahead) the history are preserved; the native side reports which path it took.
     * Duplicates are allowed, as in Spotify. When nothing is playing there is no "next" to insert
     * ahead of, so the request is refused instead of silently starting playback.
     */
    override fun insertNext(uris: List<String>): InsertNextResult {
        if (uris.isEmpty()) return InsertNextResult.FAILED
        if (playbackStateHolder.state.value.currentAudio == null) {
            return InsertNextResult.NOTHING_PLAYING
        }
        return try {
            InsertNextResult.fromNative(Spirc.insertNext(uris.toTypedArray()))
        } catch (e: UnsatisfiedLinkError) {
            // Native library predates insertNext; never fall back to setQueue with a playing
            // track, which would replace the current track.
            InsertNextResult.FAILED
        }
    }

    override fun localLoad(uri: String): Boolean {
        scope.launch {
            savedQueueRepository.setActiveQueueId(null)
        }

        ensureServiceRunning()
        return Spirc.localLoad(uri)
    }

    /**
     * Shuffles the playback
     * @return <code>true</code> if success
     */
    override fun shuffle(enabled: Boolean): Boolean {
        scope.launch {
            savedQueueRepository.setActiveQueueId(null)
            settingsRepository.setShuffle(enabled)
        }

        return Spirc.shuffle(enabled)
    }

    /**
     * Repeats the playback
		 * @param repeat whether to even repeat
		 * @param repeatTrack whether to repeat current track
     * @return <code>true</code> if success
     */
    override fun repeat(repeat: Boolean, repeatTrack: Boolean): Boolean {
        scope.launch {
            settingsRepository.setRepeat(repeat)
            settingsRepository.setRepeatTrack(repeatTrack)
        }
        return Spirc.repeat(repeat, repeatTrack)
    }

    /**
     * Loads the context URI and starts playing randomly within it
     */
    override fun shuffleLoad(uri: String?): Boolean {
        scope.launch {
            savedQueueRepository.setActiveQueueId(null)
            settingsRepository.saveLastPlayback(
                trackUri = null,
                contextUri = uri,
                positionMs = null
            )
        }

        ensureServiceRunning()
        return Spirc.shuffleLoad(uri)
    }

    /**
     * Adds a SpotifyURI to queue
     * @param spotifyUri valid form of URI, that will get loaded
     * @return `true` if loaded successfully
     */
    override fun addToQueue(spotifyUri: String?): Boolean {
        // TODO: cache in kotlin, so we can have faster UX
        return Spirc.addToQueue(spotifyUri)
    }

    /**
     * Activates current Spirc session
     * @return `true` if success
     */
    override fun activate(): Boolean {
        return Spirc.activate()
    }

    /**
     * Transfers current Spirc session
     * @return `true` if success
     */
    override fun transfer(): Boolean {
        return Spirc.transfer()
    }

    /**
     * Transfers current Spirc session only if no other session is streaming.
     */
    override fun smartTransfer(): Boolean {
        if (rateLimitGate.isLimited()) {
            Log.w(TAG, "smartTransfer: skipped, Spotify rate limited for ${rateLimitGate.remainingSeconds()} s")
            return false
        }

        // Any failure here (no answer, error payload, bad shape) means "do not transfer":
        // this runs on every Spirc init and must never throw inside the wrapper scope.
        val devices = try {
            val json = spClient.getDevices()
            if (json == null) {
                Log.w(TAG, "smartTransfer: no device list, not transferring")
                return false
            }
            if (NativeErrorHandler.handleErrorJson(json, "smartTransfer devices") != null) {
                Log.w(TAG, "smartTransfer: device list failed, not transferring")
                return false
            }
            Json.decodeFromString<DevicesResponse>(json)
        } catch (e: Exception) {
            Log.w(TAG, "smartTransfer: device list unavailable, not transferring", e)
            return false
        }

        for (device in devices.devices) {
            if (device.isActive) return false
        }

        return Spirc.transfer()
    }

    /**
     * Sets the volume for the current Spotify Connect session.
     */
    override fun setVolume(volume: Int): Boolean {
        return Spirc.setVolume(volume)
    }

    /**
     * Seeks the current track to given position
     * @return `true` if success
     */
    override suspend fun seekTo(positionMs: Long): Boolean {
        if (positionMs < 0) {
            return false
        }

        // Assuming it went successfully - pre-updating the position
        playbackStateHolder.seekTo(positionMs.toDuration(DurationUnit.MILLISECONDS))
        playbackStateHolder.updatePosition(positionMs)

        settingsRepository.saveLastPlayback(
            trackUri = null,
            contextUri = null,
            positionMs = positionMs
        )

        return Spirc.seekTo(positionMs)
    }

    /**
     * Tells the player to start playing
     */
    override fun playerPlay(): Boolean {
        ensureServiceRunning()
        if (!isUsable) return false
        return Spirc.playerPlay()
    }

    /**
     * Tells the player to pause playing
     */
    override fun playerPause(): Boolean {
        ensureServiceRunning()
        if (!isUsable) return false
        return Spirc.playerPause()
    }

    /**
     * Tells the player to toggle play status
     */
    override fun playerPlayPause(): Boolean {
        ensureServiceRunning()
        if (!isUsable) return false
        return Spirc.playerPlayPause()
    }

    /**
     * Tells the player to skip to the next track
     */
    override fun playerNext(): Boolean {
        return Spirc.playerNext()
    }

    /**
     * Tells the player to play the previous track, or return to the start of current track
     */
    override fun playerPrevious(): Boolean {
        return Spirc.playerPrevious()
    }

    /**
     * Gets the previous tracks from queue
     */
    override fun previousTracks(): String {
        return Spirc.previousTracks()
    }

    /**
     * Gets the next tracks from queue
     */
    override fun nextTracks(): String {
        return Spirc.nextTracks()
    }

    /**
     * Adds a track to play next (inserts at the beginning of the next tracks).
     * @param trackUri the track URI to play next
     */
    override fun playNext(trackUri: String): InsertNextResult = insertNext(listOf(trackUri))
}
