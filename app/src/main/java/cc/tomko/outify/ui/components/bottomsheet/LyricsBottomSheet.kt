package cc.tomko.outify.ui.components.bottomsheet

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.tomko.outify.R
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.LyricsSource
import cc.tomko.outify.ui.components.WavyMusicSlider
import cc.tomko.outify.ui.viewmodel.bottomsheet.LyricsViewModel

/**
 * Lyric line size at 100% text scale. Shared with the settings preview.
 */
const val LYRIC_LINE_BASE_FONT_SIZE_SP = 22f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsBottomSheet(
    viewModel: LyricsViewModel,
    onDismissRequest: () -> Unit,
    onSeekToTimestamp: (Long) -> Unit,
    isSyncedMode: Boolean = true,
    onPlayPause: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val lyrics by viewModel.lyrics.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val effectivePositionMs by viewModel.effectivePositionMs.collectAsState()
    val lyricsFontScale by viewModel.lyricsFontScale.collectAsState()
    val isCurrentTrack by viewModel.isCurrentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val displayedTrack by viewModel.displayedTrack.collectAsState()
    val isEpisode by viewModel.isEpisode.collectAsState()
    val hasSyncedContent by viewModel.hasSyncedContent.collectAsState()
    val lyricsSource by viewModel.lyricsSource.collectAsState()
    val isLoadingLyrics by viewModel.isLoading.collectAsState()

    val showPlaybackControls = hasSyncedContent && isCurrentTrack

    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val activeLineColor = MaterialTheme.colorScheme.primary
    val inactiveTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    val isSynced = isSyncedMode && showPlaybackControls

    fun formatTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0L)
        return "%01d:%02d".format(s / 60, s % 60)
    }

    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(positionMs, durationMs, isDragging) {
        if (!isDragging && durationMs > 0) {
            sliderPosition = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // Material caps sheets at 640.dp; in landscape that left the player visible at both sides
        sheetMaxWidth = Dp.Unspecified,
        containerColor = backgroundColor,
        contentColor = MaterialTheme.colorScheme.onBackground,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(16.dp))

            // Top bar: close button + track info
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .background(surfaceVariant, CircleShape)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.sheet_action_close),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 56.dp)
                ) {
                    Text(
                        text = displayedTrack?.name ?: stringResource(R.string.sheet_lyrics_unknown_track),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = displayedTrack?.artists?.joinToString { it.name } ?: stringResource(R.string.sheet_lyrics_unknown_artist),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    LyricsSourceBadge(source = lyricsSource)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content area
            if (isEpisode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.sheet_lyrics_no_lyrics_episodes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = inactiveTextColor,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (isLoadingLyrics || lyrics.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(
                            if (isLoadingLyrics) R.string.sheet_lyrics_loading else R.string.sheet_lyrics_not_found
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = inactiveTextColor,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LyricsList(
                    lyrics = lyrics,
                    // Offset-adjusted so lines can light up ahead of the vocals
                    currentPositionMs = effectivePositionMs,
                    fontScale = lyricsFontScale,
                    isSynced = isSynced,
                    activeLineColor = activeLineColor,
                    inactiveTextColor = inactiveTextColor,
                    onLineClick = if (showPlaybackControls) onSeekToTimestamp else { _ -> },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                )
            }

            // Compact controls laid out after the list, so lyrics are never drawn underneath
            if (showPlaybackControls) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onSkipPrevious() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.sheet_previous_cd),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    FilledIconButton(
                        onClick = { onPlayPause() },
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) stringResource(R.string.sheet_pause_cd) else stringResource(R.string.sheet_play_cd),
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    IconButton(
                        onClick = { onSkipNext() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.sheet_next_cd),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = formatTime(positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    WavyMusicSlider(
                        value = sliderPosition,
                        onValueChange = {
                            isDragging = true
                            sliderPosition = it.coerceIn(0f, 1f)
                        },
                        onValueChangeFinished = {
                            onSeek((sliderPosition * durationMs).toLong().coerceIn(0L, durationMs))
                            isDragging = false
                        },
                        inactiveTrackColor = MaterialTheme.colorScheme.secondary,
                        isPlaying = isPlaying,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )

                    Text(
                        text = formatTime(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Auto-centering lyric list. Shared with the landscape fullscreen lyrics screen.
 */
/**
 * Small caption naming the provider when the lyrics did not come from Spotify.
 * Renders nothing for Spotify lyrics or while there are none.
 */
@Composable
internal fun LyricsSourceBadge(
    source: LyricsSource?,
    modifier: Modifier = Modifier,
) {
    if (source == null || source == LyricsSource.SPOTIFY) return
    Text(
        text = stringResource(R.string.sheet_lyrics_provided_by, source.displayName),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = modifier.padding(top = 2.dp)
    )
}

@Composable
internal fun LyricsList(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    fontScale: Float,
    isSynced: Boolean,
    activeLineColor: Color,
    inactiveTextColor: Color,
    onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    val activeIndex = if (isSynced) {
        lyrics.indexOfLast { it.timestampMs <= currentPositionMs }.coerceAtLeast(0)
    } else {
        -1
    }

    LaunchedEffect(activeIndex, isSynced) {
        if (!isSynced || lyrics.isEmpty()) return@LaunchedEffect

        val layoutInfo = listState.layoutInfo
        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val visibleItem = layoutInfo.visibleItemsInfo.find { it.index == activeIndex }

        if (visibleItem != null) {
            val itemCenter = visibleItem.offset + visibleItem.size / 2
            val viewportCenter = viewportHeight / 2
            val delta = (itemCenter - viewportCenter).toFloat()

            listState.animateScrollBy(
                value = delta,
                animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing)
            )
        } else {
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -(viewportHeight / 2)
            )
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        itemsIndexed(lyrics) { index, line ->
            val isActive = index <= activeIndex || !isSynced

            val textColor by animateColorAsState(
                targetValue = if (isActive) activeLineColor else inactiveTextColor.copy(alpha = 0.45f),
                animationSpec = tween(durationMillis = 200),
                label = "textColor"
            )

            val scale by animateFloatAsState(
                targetValue = if (isActive) 1f else 20f / 22f,
                animationSpec = tween(durationMillis = 250),
                label = "lineScale"
            )

            val fontWeight by remember(isActive) {
                mutableStateOf(if (isActive) FontWeight.Bold else FontWeight.Medium)
            }

            Text(
                text = line.text,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = fontWeight,
                    // always measured at the largest size so auto-scroll centering stays correct
                    fontSize = (LYRIC_LINE_BASE_FONT_SIZE_SP * fontScale).sp
                ),
                color = textColor,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLineClick(line.timestampMs) }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
            )
        }
    }
}
