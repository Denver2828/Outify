package cc.tomko.outify.core.spirc

import cc.tomko.outify.core.model.OutifyUri

interface ISpircWrapper {
    fun shutdown()
    /**
     * Resolves the radio for [track] and loads it. Main-safe; suspends until the radio is
     * resolved (bounded) and the load command was issued.
     * @return `false` when Spotify has no radio for the track or the request failed.
     */
    suspend fun startRadio(track: OutifyUri, shuffle: Boolean = true): Boolean
    fun load(context: OutifyUri? = null, playingTrackUri: OutifyUri? = null): Boolean
    fun localLoad(uri: String): Boolean
    fun shuffle(enabled: Boolean): Boolean
    fun repeat(repeat: Boolean, repeatTrack: Boolean): Boolean
    fun shuffleLoad(uri: String? = null): Boolean
    fun addToQueue(uri: String?): Boolean
    fun setQueue(uris: Array<String>, playingTrackUri: String? = null): Boolean
    fun activate(): Boolean
    fun transfer(): Boolean
    fun smartTransfer(): Boolean
    fun setVolume(volume: Int): Boolean
    suspend fun hasActiveDevice(): Boolean
    suspend fun seekTo(positionMs: Long): Boolean
    fun playerPlay(): Boolean
    fun playerPause(): Boolean
    fun playerPlayPause(): Boolean
    fun playerNext(): Boolean
    fun playerPrevious(): Boolean
    fun previousTracks(): String
    fun nextTracks(): String
    fun playNext(trackUri: String): Boolean
}
