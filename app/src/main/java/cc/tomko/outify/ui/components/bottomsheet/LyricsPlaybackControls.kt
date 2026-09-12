package cc.tomko.outify.ui.components.bottomsheet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R

/** Shared production row for the lyrics sheet, independent of its progress slider. */
@Composable
internal fun LyricsPlaybackControls(
    isPlaying: Boolean,
    isShuffling: Boolean,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val playSize = if (maxWidth >= 272.dp) 80.dp else 64.dp
        // Exceptionally narrow windows scroll instead of shrinking or overlapping targets.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LyricsShuffleButton(isShuffling, onShuffle)
            IconButton(onClick = onPrevious, modifier = Modifier.size(64.dp)) {
                Icon(
                    Icons.Default.SkipPrevious,
                    stringResource(R.string.sheet_previous_cd),
                    modifier = Modifier.size(40.dp),
                )
            }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(playSize)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (isPlaying) R.string.sheet_pause_cd else R.string.sheet_play_cd),
                    modifier = Modifier.size(48.dp),
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(64.dp)) {
                Icon(
                    Icons.Default.SkipNext,
                    stringResource(R.string.sheet_next_cd),
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

/** Checked state has a filled indicator as well as accessible toggle semantics. */
@Composable
internal fun LyricsShuffleButton(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    FilledIconToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        modifier = modifier.size(if (compact) 48.dp else 64.dp),
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(Icons.Default.Shuffle, stringResource(R.string.sheet_shuffle_cd), Modifier.size(if (compact) 24.dp else 32.dp))
    }
}

/** Shared stateful footer so every lyrics surface exposes the same progress and seek behavior. */
@Composable
internal fun LyricsPlaybackFooter(
    isPlaying: Boolean,
    isShuffling: Boolean,
    positionMs: Long,
    durationMs: Long,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(positionMs, durationMs, isDragging) {
        if (!isDragging && durationMs > 0L) {
            sliderPosition = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
    }

    CompactLyricsPlaybackControls(
        isPlaying = isPlaying,
        isShuffling = isShuffling,
        position = sliderPosition,
        elapsed = formatLyricsTime(positionMs),
        duration = formatLyricsTime(durationMs),
        onShuffle = onShuffle,
        onPrevious = onPrevious,
        onPlayPause = onPlayPause,
        onNext = onNext,
        onPositionChange = {
            isDragging = true
            sliderPosition = it.coerceIn(0f, 1f)
        },
        onSeekFinished = {
            onSeek((sliderPosition * durationMs).toLong().coerceIn(0L, durationMs))
            isDragging = false
        },
        modifier = modifier,
    )
}

private fun formatLyricsTime(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtLeast(0L)
    return "%01d:%02d".format(seconds / 60, seconds % 60)
}

/** Compact lyrics footer for every orientation, preserving lyric height and touch targets. */
@Composable
internal fun CompactLyricsPlaybackControls(
    isPlaying: Boolean,
    isShuffling: Boolean,
    position: Float,
    elapsed: String,
    duration: String,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPositionChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A very narrow window scrolls rather than overlapping the seek bar.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState())
                .width(maxOf(maxWidth, 400.dp)).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LyricsShuffleButton(isShuffling, onShuffle, compact = true)
            IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.sheet_previous_cd), Modifier.size(24.dp))
            }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(48.dp)) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (isPlaying) R.string.sheet_pause_cd else R.string.sheet_play_cd),
                    Modifier.size(28.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.sheet_next_cd), Modifier.size(24.dp))
            }
            Text(elapsed, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Slider(
                value = position,
                onValueChange = onPositionChange,
                onValueChangeFinished = onSeekFinished,
                modifier = Modifier.weight(1f).height(48.dp).padding(horizontal = 4.dp),
            )
            Text(duration, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
