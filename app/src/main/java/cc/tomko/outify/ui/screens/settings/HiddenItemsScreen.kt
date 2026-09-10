package cc.tomko.outify.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.R
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.SpotyBrand
import cc.tomko.outify.ui.viewmodel.settings.HiddenItemsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenItemsScreen(
    viewModel: HiddenItemsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showRestoreAllConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_hidden_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                actions = {
                    if (!uiState.isEmpty) {
                        IconButton(onClick = { showRestoreAllConfirm = true }) {
                            Icon(
                                imageVector = Icons.Default.Restore,
                                contentDescription = stringResource(R.string.settings_hidden_restore_all)
                            )
                        }
                    }
                    SpotyBrand(modifier = Modifier.padding(end = 16.dp))
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (uiState.isEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_hidden_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (uiState.hiddenTracks.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.settings_hidden_section_tracks),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(uiState.hiddenTracks, key = { "track_${it.uri}" }) { track ->
                        HiddenTrackRow(track = track, onRestore = { viewModel.restore(track.uri) })
                    }
                }

                if (uiState.hiddenAlbums.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.settings_hidden_section_albums),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(uiState.hiddenAlbums, key = { "album_${it.uri}" }) { album ->
                        HiddenAlbumRow(album = album, onRestore = { viewModel.restore(album.uri) })
                    }
                }
            }
        }
    }

    if (showRestoreAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreAllConfirm = false },
            title = { Text(stringResource(R.string.settings_hidden_restore_all)) },
            text = { Text(stringResource(R.string.settings_hidden_restore_all_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreAllConfirm = false
                    viewModel.restoreAll()
                }) {
                    Text(stringResource(R.string.settings_hidden_restore_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreAllConfirm = false }) {
                    Text(stringResource(R.string.sheet_action_cancel))
                }
            }
        )
    }
}

@Composable
private fun HiddenTrackRow(track: Track, onRestore: () -> Unit) {
    val artworkUrl = ALBUM_COVER_URL + (track.album?.getCover(CoverSize.SMALL)?.uri ?: "")
    HiddenItemRow(
        artworkUrl = artworkUrl,
        title = track.name,
        subtitle = track.artists.joinToString { it.name },
        onRestore = onRestore,
    )
}

@Composable
private fun HiddenAlbumRow(album: Album, onRestore: () -> Unit) {
    val artworkUrl = ALBUM_COVER_URL + (album.getCover(CoverSize.SMALL)?.uri ?: "")
    HiddenItemRow(
        artworkUrl = artworkUrl,
        title = album.name,
        subtitle = album.artists.joinToString { it.name },
        onRestore = onRestore,
    )
}

@Composable
private fun HiddenItemRow(
    artworkUrl: String,
    title: String,
    subtitle: String,
    onRestore: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp)
            ) {
                SmartImage(
                    url = artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onRestore) {
                Icon(
                    imageVector = Icons.Default.Undo,
                    contentDescription = stringResource(R.string.unhide_item)
                )
            }
        }
    }
}
