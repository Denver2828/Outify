package cc.tomko.outify.ui.components.bottomsheet

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.R
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.notifications.InAppNotificationController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TrackInfoBottomSheet(
    track: Track,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    likedTrackIndex: Int? = null,
    isLiked: Boolean = false,
    isTrackHidden: Boolean = false,
    isAlbumHidden: Boolean = false,
    onArtworkClick: (() -> Unit)? = null,
    onArtistClick: ((Artist) -> Unit)? = null,
    onOpenAlbum: (() -> Unit)? = null,
    onOpenArtist: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onToggleLike: (() -> Unit)? = null,
    onUseAsRecommendationSeed: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    onOpenRadio: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onCopyUri: (() -> Unit)? = null,
    onScrollToLiked: (() -> Unit)? = null,
    onAddToWidget: (() -> Unit)? = null,
    onToggleHideTrack: (() -> Unit)? = null,
    onToggleHideAlbum: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current

    var showHideAlbumConfirm by remember { mutableStateOf(false) }

    val imageSize = 96.dp

    val artworkUrl =
        remember(track) { ALBUM_COVER_URL + (track.album?.getCover(CoverSize.MEDIUM)?.uri ?: "") }

    val defaultShare: () -> Unit = {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/${track.id}")
            type = "text/plain"
        }
        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.sheet_track_share_chooser)))
    }

    val defaultCopy: () -> Unit = {
        scope.launch {
            val clipData = ClipData.newPlainText(
                context.getString(R.string.sheet_share_clip_label, track.name),
                "https://open.spotify.com/track/${track.id}"
            )
            clipboardManager.setClipEntry(ClipEntry(clipData))
            InAppNotificationController.show(context.getString(R.string.sheet_copied_to_clipboard))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: artwork + title + artists + share/copy icons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .size(imageSize)
                        .clickable {
                            onArtworkClick?.invoke()
                            onDismiss()
                        }
                ) {
                    SmartImage(
                        url = artworkUrl,
                        contentDescription = stringResource(R.string.sheet_artwork_cd),
                        modifier = Modifier.fillMaxSize(),
                        monochrome = LocalUiSettings.current.monochromeTracks
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    val artists = track.artists

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        artists.forEachIndexed { index, artist ->
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.primary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    onArtistClick?.invoke(artist)
                                    onDismiss()
                                }
                            )

                            if (index < artists.lastIndex) {
                                Text(
                                    text = ", ",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                // Header icons: like, share, copy
                Row {
                    IconButton(
                        onClick = {
                            onAddToWidget?.invoke()
                            onDismiss
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Widgets,
                            contentDescription = stringResource(R.string.sheet_track_add_to_widget_cd)
                        )
                    }

                    IconButton(
                        onClick = {
                            onToggleLike?.invoke()
                            onDismiss()
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = stringResource(R.string.sheet_like_cd),
                            tint = if (isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = { onShare?.invoke() ?: defaultShare() },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.sheet_action_share))
                    }

                    IconButton(
                        onClick = { onCopyUri?.invoke() ?: defaultCopy() },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.sheet_action_copy_link))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Open section
            Text(
                text = stringResource(R.string.sheet_action_open),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            ActionCard(
                icon = Icons.Default.Album,
                title = stringResource(R.string.sheet_track_album_title),
                subtitle = track.album?.name ?: "",
                onClick = {
                    onOpenAlbum?.invoke()
                    onDismiss()
                }
            )

            ActionCard(
                icon = Icons.Default.Person,
                title = stringResource(R.string.sheet_artist_label),
                subtitle = track.artists.joinToString { it.name },
                onClick = {
                    onOpenArtist?.invoke()
                    onDismiss()
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Queue section
            Text(
                text = stringResource(R.string.sheet_queue_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActionCard(
                    icon = Icons.Default.Queue,
                    title = stringResource(R.string.sheet_queue_add_title),
                    subtitle = stringResource(R.string.sheet_queue_add_subtitle),
                    onClick = {
                        onAddToQueue?.invoke()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )

                ActionCard(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = stringResource(R.string.sheet_queue_play_next_title),
                    subtitle = stringResource(R.string.sheet_queue_play_next_subtitle),
                    onClick = {
                        onPlayNext?.invoke()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            ActionCard(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                title = stringResource(R.string.sheet_track_add_to_playlist_title),
                subtitle = stringResource(R.string.sheet_track_add_to_playlist_subtitle),
                onClick = {
                    onAddToPlaylist?.invoke()
                    onDismiss()
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Playback section
            Text(
                text = stringResource(R.string.sheet_playback_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActionCard(
                    icon = Icons.Default.Favorite,
                    title = stringResource(R.string.sheet_like_cd),
                    subtitle = if (likedTrackIndex != null && likedTrackIndex >= 0) stringResource(R.string.sheet_track_in_liked_songs) else stringResource(R.string.sheet_track_add_to_liked_subtitle),
                    onClick = {
                        onToggleLike?.invoke()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    isHighlighted = true,
                    highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                )

                ActionCard(
                    icon = Icons.Default.Radio,
                    title = stringResource(R.string.sheet_track_start_radio_title),
                    subtitle = stringResource(R.string.sheet_track_start_radio_subtitle),
                    onClick = {
                        onStartRadio?.invoke()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    isHighlighted = true,
                    highlightColor = MaterialTheme.colorScheme.primaryContainer
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActionCard(
                    icon = if (isTrackHidden) Icons.Rounded.RemoveCircle else Icons.Rounded.RemoveCircleOutline,
                    title = stringResource(
                        if (isTrackHidden) R.string.unhide_item else R.string.hide_track
                    ),
                    subtitle = stringResource(R.string.sheet_track_hide_subtitle),
                    onClick = {
                        onToggleHideTrack?.invoke()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )

                // Only tracks that resolved an album can hide it; a card that silently did
                // nothing would be worse than no card.
                if (track.album != null) {
                    ActionCard(
                        icon = if (isAlbumHidden) Icons.Rounded.RemoveCircle else Icons.Rounded.RemoveCircleOutline,
                        title = stringResource(
                            if (isAlbumHidden) R.string.unhide_item else R.string.hide_album
                        ),
                        subtitle = stringResource(R.string.sheet_album_hide_subtitle),
                        onClick = {
                            if (isAlbumHidden) {
                                onToggleHideAlbum?.invoke()
                                onDismiss()
                            } else {
                                showHideAlbumConfirm = true
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (showHideAlbumConfirm) {
                AlertDialog(
                    onDismissRequest = { showHideAlbumConfirm = false },
                    title = { Text(stringResource(R.string.hide_album_confirm_title)) },
                    text = { Text(stringResource(R.string.hide_album_confirm_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showHideAlbumConfirm = false
                            onToggleHideAlbum?.invoke()
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.hide_album_confirm_action))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showHideAlbumConfirm = false }) {
                            Text(stringResource(R.string.sheet_action_cancel))
                        }
                    }
                )
            }

            ActionCard(
                icon = Icons.Default.AutoAwesome,
                title = stringResource(R.string.sheet_track_seed_title),
                subtitle = stringResource(R.string.sheet_track_seed_subtitle),
                onClick = {
                    onUseAsRecommendationSeed?.invoke()
                    onDismiss()
                }
            )

            ActionCard(
                icon = Icons.Default.Radio,
                title = stringResource(R.string.sheet_track_open_radio_title),
                subtitle = stringResource(R.string.sheet_track_open_radio_subtitle),
                onClick = {
                    onOpenRadio?.invoke()
                    onDismiss()
                }
            )

            // Show liked track index if available
            if (likedTrackIndex != null && likedTrackIndex >= 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                ActionCard(
                    icon = Icons.Default.Favorite,
                    title = stringResource(R.string.sheet_track_in_liked_songs),
                    subtitle = stringResource(R.string.sheet_track_liked_position, likedTrackIndex + 1),
                    onClick = {
                        onScrollToLiked?.invoke()
                        onDismiss()
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    highlightColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = highlightColor.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(10.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}