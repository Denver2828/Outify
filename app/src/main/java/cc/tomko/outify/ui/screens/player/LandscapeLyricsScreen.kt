package cc.tomko.outify.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cc.tomko.outify.R
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.ui.components.bottomsheet.LyricsList
import cc.tomko.outify.ui.components.bottomsheet.LyricsPlaybackFooter
import cc.tomko.outify.ui.components.bottomsheet.LyricsSourceBadge
import cc.tomko.outify.ui.components.bottomsheet.LyricsTrackActions
import cc.tomko.outify.ui.components.player.LyricsFontFamily
import cc.tomko.outify.ui.components.bottomsheet.LyricsStatusMessage
import cc.tomko.outify.ui.viewmodel.bottomsheet.LyricsViewModel

/**
 * Fullscreen lyrics shown instead of the split landscape layout.
 * Follows whatever is playing, so it stays useful on a car screen.
 */
@Composable
fun LandscapeLyricsScreen(
    track: Track,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LyricsViewModel = hiltViewModel(),
) {
    LaunchedEffect(track.id) {
        viewModel.loadLyrics(track, followCurrentTrack = true)
    }

    val lyrics by viewModel.lyrics.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val effectivePositionMs by viewModel.effectivePositionMs.collectAsState()
    val lyricsFontScale by viewModel.lyricsFontScale.collectAsState()
    val lyricsFontBold by viewModel.lyricsFontBold.collectAsState()
    val lyricsFontFamily by viewModel.lyricsFontFamily.collectAsState()
    val lyricsSynced by viewModel.lyricsSynced.collectAsState()
    val isCurrentTrack by viewModel.isCurrentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val displayedTrack by viewModel.displayedTrack.collectAsState()
    val isEpisode by viewModel.isEpisode.collectAsState()
    val hasSyncedContent by viewModel.hasSyncedContent.collectAsState()
    val lyricsSource by viewModel.lyricsSource.collectAsState()
    val isLoadingLyrics by viewModel.isLoading.collectAsState()
    val lyricsError by viewModel.hasError.collectAsState()
    val isLiked by viewModel.isLiked.collectAsState()
    val isShuffling by viewModel.isShuffling.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val hiddenUris by viewModel.hiddenUris.collectAsState()
    val isHidden = displayedTrack?.uri in hiddenUris

    val isSynced = lyricsSynced && hasSyncedContent && isCurrentTrack

    val activeLineColor = MaterialTheme.colorScheme.primary
    val inactiveTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconButton(
                onClick = onBrowse,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.ui_landscape_lyrics_browse),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayedTrack?.name ?: stringResource(R.string.sheet_lyrics_unknown_track),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = displayedTrack?.artists?.joinToString { it.name }
                        ?: stringResource(R.string.sheet_lyrics_unknown_artist),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LyricsSourceBadge(source = lyricsSource)
            }

            if (!isEpisode) {
                LyricsTrackActions(
                    isLiked = isLiked,
                    isHidden = isHidden,
                    onToggleLiked = viewModel::toggleLiked,
                    onToggleHidden = viewModel::toggleHideTrack,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (isEpisode) {
                Text(
                    text = stringResource(R.string.sheet_lyrics_no_lyrics_episodes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = inactiveTextColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            } else if (isLoadingLyrics || lyrics.isEmpty()) {
                LyricsStatusMessage(
                    isLoading = isLoadingLyrics,
                    isError = lyricsError,
                    color = inactiveTextColor,
                    onRetry = viewModel::retryLyrics,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
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
                    // Seeking only makes sense while the displayed track is the one playing
                    onLineClick = { timestampMs -> if (isCurrentTrack) viewModel.seekTo(timestampMs) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                )
            }
        }

        if (isCurrentTrack) {
            LyricsPlaybackFooter(
                isPlaying = isPlaying,
                isShuffling = isShuffling,
                positionMs = positionMs,
                durationMs = durationMs,
                onShuffle = viewModel::toggleShuffle,
                onPrevious = viewModel::skipPrevious,
                onPlayPause = viewModel::playPause,
                onNext = viewModel::skipNext,
                onSeek = viewModel::seekTo,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
