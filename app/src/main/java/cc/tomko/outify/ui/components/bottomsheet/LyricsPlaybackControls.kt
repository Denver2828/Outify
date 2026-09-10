package cc.tomko.outify.ui.components.bottomsheet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
            IconToggleButton(
                checked = isShuffling,
                onCheckedChange = { onShuffle() },
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    Icons.Default.Shuffle,
                    stringResource(R.string.sheet_shuffle_cd),
                    modifier = Modifier.size(32.dp),
                    tint = if (isShuffling) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
