package cc.tomko.outify.ui.components.bottomsheet

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.tomko.outify.R
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.LyricsSource
import cc.tomko.outify.ui.components.WavyMusicSlider
import cc.tomko.outify.ui.components.player.LyricsFontFamily
import cc.tomko.outify.ui.components.player.EmphasizedLyricText
import cc.tomko.outify.ui.components.player.currentLyricIndex
import cc.tomko.outify.ui.components.player.lyricLineScale
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
    val lyricsFontBold by viewModel.lyricsFontBold.collectAsState()
    val lyricsFontFamily by viewModel.lyricsFontFamily.collectAsState()
    val isCurrentTrack by viewModel.isCurrentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isShuffling by viewModel.isShuffling.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val displayedTrack by viewModel.displayedTrack.collectAsState()
    val isEpisode by viewModel.isEpisode.collectAsState()
    val hasSyncedContent by viewModel.hasSyncedContent.collectAsState()
    val lyricsSource by viewModel.lyricsSource.collectAsState()
    val isLoadingLyrics by viewModel.isLoading.collectAsState()
    val lyricsError by viewModel.hasError.collectAsState()
    val isLiked by viewModel.isLiked.collectAsState()
    val hiddenUris by viewModel.hiddenUris.collectAsState()
    val isHidden = displayedTrack?.uri in hiddenUris

    // Transport controls follow the playing track, not whether it has lyrics. Gating them
    // on hasSyncedContent hid play/pause/skip whenever a song had no (synced) lyrics.
    val showPlaybackControls = isCurrentTrack
    // Line highlighting and tap-to-seek still require timestamps on the current track.
    val canSeekLines = hasSyncedContent && isCurrentTrack

    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val activeLineColor = MaterialTheme.colorScheme.primary
    val inactiveTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    val isSynced = isSyncedMode && canSeekLines

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

                if (!isEpisode) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.toggleLiked() },
                            modifier = Modifier
                                .background(surfaceVariant, CircleShape)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = stringResource(
                                    if (isLiked) R.string.sys_gesture_action_remove_from_favorites
                                    else R.string.sys_gesture_action_add_to_favorites
                                ),
                                tint = if (isLiked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = { viewModel.toggleHideTrack() },
                            modifier = Modifier
                                .background(surfaceVariant, CircleShape)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isHidden) Icons.Rounded.RemoveCircle else Icons.Rounded.RemoveCircleOutline,
                                contentDescription = stringResource(
                                    if (isHidden) R.string.unhide_item else R.string.hide_track
                                ),
                                tint = if (isHidden) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                    LyricsStatusMessage(
                        isLoading = isLoadingLyrics,
                        isError = lyricsError,
                        color = inactiveTextColor,
                        onRetry = viewModel::retryLyrics
                    )
                }
            } else {
                LyricsList(
                    lyrics = lyrics,
                    // Offset-adjusted so lines can light up ahead of the vocals
                    currentPositionMs = effectivePositionMs,
                    fontScale = lyricsFontScale,
                    fontFamily = LyricsFontFamily.fromId(lyricsFontFamily).fontFamily,
                    bold = lyricsFontBold,
                    isSynced = isSynced,
                    activeLineColor = activeLineColor,
                    inactiveTextColor = inactiveTextColor,
                    onLineClick = if (canSeekLines) onSeekToTimestamp else { _ -> },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                )
            }

            // Reserve separate rows for seeking and transport; neither overlays the lyrics.
            if (showPlaybackControls) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                    LyricsPlaybackControls(
                        isPlaying = isPlaying,
                        isShuffling = isShuffling,
                        onShuffle = viewModel::toggleShuffle,
                        onPrevious = onSkipPrevious,
                        onPlayPause = onPlayPause,
                        onNext = onSkipNext,
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

/**
 * Placeholder drawn where the lyrics list would be: looking up, definitive "no lyrics",
 * or a transient failure with a retry action. Only [isError] offers the retry.
 */
@Composable
internal fun LyricsStatusMessage(
    isLoading: Boolean,
    isError: Boolean,
    color: Color,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(
                when {
                    isLoading -> R.string.sheet_lyrics_loading
                    isError -> R.string.sheet_lyrics_error
                    else -> R.string.sheet_lyrics_not_found
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            textAlign = TextAlign.Center
        )
        if (isError && !isLoading) {
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.ui_action_retry))
            }
        }
    }
}

@Composable
internal fun LyricsList(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    fontScale: Float,
    fontFamily: FontFamily,
    bold: Boolean,
    isSynced: Boolean,
    activeLineColor: Color,
    inactiveTextColor: Color,
    onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    val activeIndex = currentLyricIndex(lyrics, currentPositionMs, isSynced)

    LaunchedEffect(activeIndex, isSynced) {
        if (activeIndex !in lyrics.indices) return@LaunchedEffect

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
            EmphasizedLyricText(
                text = line.text,
                visualScale = lyricLineScale(index, activeIndex, isSynced),
                baseStyle = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
                    fontFamily = fontFamily,
                    fontSize = (LYRIC_LINE_BASE_FONT_SIZE_SP * fontScale).sp,
                ),
                activeColor = activeLineColor,
                inactiveColor = if (isSynced) inactiveTextColor.copy(alpha = 0.45f) else inactiveTextColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLineClick(line.timestampMs) },
            )
        }
    }
}
