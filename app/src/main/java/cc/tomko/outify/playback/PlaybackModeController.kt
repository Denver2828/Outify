package cc.tomko.outify.playback

import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.model.RepeatMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the repeat and shuffle modes.
 *
 * Every entry point (in-app player controls, the notification / Android Auto custom
 * buttons and the standard Media3 `setRepeatMode` / `setShuffleModeEnabled` commands)
 * goes through here, so they all issue the same Spirc call, persist the same settings
 * and converge on the same [PlaybackStateHolder] state.
 *
 * The persisted settings are the source of truth (that is what the in-app controls
 * observe and what [cc.tomko.outify.core.spirc.SpircController] re-applies to Spirc on
 * every session start); this controller mirrors them into the state holder so the
 * Media3 player can report the effective mode to controllers.
 */
@Singleton
class PlaybackModeController internal constructor(
    private val stateHolder: PlaybackStateHolder,
    private val repeatModeFlow: Flow<RepeatMode>,
    private val shuffleFlow: Flow<Boolean>,
    private val persistRepeat: suspend (RepeatMode) -> Unit,
    private val persistShuffle: suspend (Boolean) -> Unit,
    private val applyRepeat: (repeat: Boolean, repeatTrack: Boolean) -> Boolean,
    private val applyShuffle: (Boolean) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    scope: CoroutineScope,
) {
    @Inject
    constructor(
        stateHolder: PlaybackStateHolder,
        settingsRepository: SettingsRepository,
        spirc: SpircWrapper,
    ) : this(
        stateHolder = stateHolder,
        repeatModeFlow = settingsRepository.repeatMode,
        shuffleFlow = settingsRepository.shuffleEnabled,
        persistRepeat = { mode ->
            settingsRepository.setRepeat(mode.repeat)
            settingsRepository.setRepeatTrack(mode.repeatTrack)
        },
        persistShuffle = { enabled -> settingsRepository.setShuffle(enabled) },
        applyRepeat = { repeat, repeatTrack -> spirc.repeat(repeat, repeatTrack) },
        applyShuffle = { enabled -> spirc.shuffle(enabled) },
        ioDispatcher = Dispatchers.IO,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** Serializes mode changes so a burst of toggles cannot interleave persist/apply. */
    private val mutex = Mutex()

    init {
        // Mirror the persisted modes into the state holder so any writer converges.
        scope.launch {
            repeatModeFlow.distinctUntilChanged().collect { stateHolder.setRepeatMode(it) }
        }
        scope.launch {
            shuffleFlow.distinctUntilChanged().collect { stateHolder.setShuffleEnabled(it) }
        }
    }

    /**
     * Applies [mode] to Spirc and, on success, persists it. The Spirc call goes through
     * JNI and is not cancellable; callers that need a bound should wrap this in a timeout.
     *
     * @return `true` when Spirc accepted the change.
     */
    suspend fun setRepeatMode(mode: RepeatMode): Boolean = mutex.withLock {
        val accepted = withContext(ioDispatcher) { applyRepeat(mode.repeat, mode.repeatTrack) }
        if (accepted) {
            persistRepeat(mode)
            stateHolder.setRepeatMode(mode)
        }
        accepted
    }

    /** Cycles NONE → ALL → ONE → NONE from the persisted mode and applies the result. */
    suspend fun toggleRepeatMode(): RepeatMode {
        val next = repeatModeFlow.first().next()
        setRepeatMode(next)
        return next
    }

    /** Same contract as [setRepeatMode] for shuffle. */
    suspend fun setShuffleEnabled(enabled: Boolean): Boolean = mutex.withLock {
        val accepted = withContext(ioDispatcher) { applyShuffle(enabled) }
        if (accepted) {
            persistShuffle(enabled)
            stateHolder.setShuffleEnabled(enabled)
        }
        accepted
    }

    /** Flips the persisted shuffle flag and applies the result. */
    suspend fun toggleShuffle(): Boolean {
        val next = !shuffleFlow.first()
        setShuffleEnabled(next)
        return next
    }
}
