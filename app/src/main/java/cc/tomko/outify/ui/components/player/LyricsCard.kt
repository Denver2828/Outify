package cc.tomko.outify.ui.components.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.core.model.LyricLine
import kotlinx.coroutines.flow.first

private val LyricsCardCornerRadius = 24.dp
private val LyricsCardPadding = 20.dp
private val LyricsBodyHeight = 320.dp
private val LyricsEdgeFadeHeight = 24.dp
private val LyricsLineSpacing = 14.dp
private const val InactiveLineAlpha = 0.55f

/**
 * Spotify-style lyrics card shown below the full player. Auto-scrolls to keep the
 * active line vertically centered when [isSynced]; otherwise renders the lines statically.
 *
 * Tapping the card body calls [onExpand]. Tapping a line calls [onSeek] only when synced.
 */
@Composable
fun LyricsCard(
    lines: List<LyricLine>,
    activeIndex: Int,
    isSynced: Boolean,
    fontScale: Float,
    onExpand: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val listState = rememberLazyListState()

    // Same centering logic as LyricsBottomSheet's LyricsList: nudge a visible active line
    // to the viewport center, or jump to it with a half-viewport offset when it is not laid out.
    LaunchedEffect(activeIndex, isSynced) {
        if (!isSynced || lines.isEmpty()) return@LaunchedEffect

        // On first composition the list has no layout yet; wait for a real viewport.
        snapshotFlow { listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset }
            .first { it > 0 }

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
            val itemHeight = layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -(viewportHeight / 2 - itemHeight / 2)
            )
        }
    }

    Surface(
        onClick = onExpand,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LyricsCardCornerRadius),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(modifier = Modifier.padding(LyricsCardPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ui_lyrics_card_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = contentColor,
                )

                FilledTonalIconButton(
                    onClick = onExpand,
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = stringResource(R.string.ui_lyrics_card_expand),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LyricsBodyHeight)
            ) {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = false,
                    verticalArrangement = Arrangement.spacedBy(LyricsLineSpacing),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(lines) { index, line ->
                        LyricsCardLine(
                            line = line,
                            isActive = isSynced && index == activeIndex,
                            isSynced = isSynced,
                            fontScale = fontScale,
                            onSeek = onSeek,
                        )
                    }
                }

                EdgeFade(
                    color = containerColor,
                    height = LyricsEdgeFadeHeight,
                    modifier = Modifier.align(Alignment.TopCenter),
                    topToBottom = true,
                )
                EdgeFade(
                    color = containerColor,
                    height = LyricsEdgeFadeHeight,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    topToBottom = false,
                )
            }
        }
    }
}

@Composable
private fun LyricsCardLine(
    line: LyricLine,
    isActive: Boolean,
    isSynced: Boolean,
    fontScale: Float,
    onSeek: (Long) -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    // Unsynced lyrics have no highlight: every line reads at full opacity.
    val highlighted = isActive || !isSynced

    val textColor by animateColorAsState(
        targetValue = if (highlighted) contentColor else contentColor.copy(alpha = InactiveLineAlpha),
        animationSpec = tween(durationMillis = 200),
        label = "lyricsCardLineColor"
    )

    val style = if (isActive) {
        MaterialTheme.typography.headlineSmall.let {
            it.copy(fontWeight = FontWeight.Bold, fontSize = it.fontSize * fontScale)
        }
    } else {
        MaterialTheme.typography.titleLarge.let {
            it.copy(fontWeight = FontWeight.Normal, fontSize = it.fontSize * fontScale)
        }
    }

    Text(
        text = line.text,
        style = style,
        color = textColor,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSynced) Modifier.clickable { onSeek(line.timestampMs) } else Modifier
            )
    )
}

/**
 * Gradient overlay that fades list edges into the card background.
 * It carries no pointer input, so taps pass through to the lines beneath.
 */
@Composable
private fun EdgeFade(
    color: Color,
    height: Dp,
    topToBottom: Boolean,
    modifier: Modifier = Modifier,
) {
    val transparent = color.copy(alpha = 0f)
    val colors = if (topToBottom) listOf(color, transparent) else listOf(transparent, color)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Brush.verticalGradient(colors))
    )
}
