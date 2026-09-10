package cc.tomko.outify.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Badge
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.ui.components.SpotyBrand
import cc.tomko.outify.data.repository.DEFAULT_LYRICS_OFFSET_MS
import cc.tomko.outify.data.repository.PlaybackSettings
import cc.tomko.outify.playback.model.Bitrate
import cc.tomko.outify.playback.model.labelRes
import cc.tomko.outify.ui.components.DropdownOption
import cc.tomko.outify.ui.components.DropdownPreferenceEntry
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.PreferenceHeader
import cc.tomko.outify.ui.components.SwitchPreferenceEntry
import cc.tomko.outify.ui.components.TextInputPreferenceEntry
import cc.tomko.outify.ui.viewmodel.settings.PlaybackSettingViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val LYRICS_OFFSET_MIN_MS = -3000f
private const val LYRICS_OFFSET_MAX_MS = 3000f
private const val LYRICS_OFFSET_STEP_MS = 100

/**
 * Number of intermediate stops for a [Slider] that must land exactly on every `step` multiple
 */
private fun sliderSteps(min: Float, max: Float, step: Float): Int =
    ((max - min) / step).roundToInt() - 1

@Composable
private fun formatLeadTime(offsetMs: Int): String =
    if (offsetMs > 0) stringResource(R.string.settings_lyrics_lead_time_positive, offsetMs)
    else stringResource(R.string.settings_lyrics_lead_time_value, offsetMs)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingScreen(
    viewModel: PlaybackSettingViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState(initial = PlaybackSettings.Default)
    val restartNeeded by viewModel.needsRestart.collectAsState()
    val romanizeLyrics by viewModel.romanizeLyrics.collectAsState(initial = false)
    val lyricsOffsetEnabled by viewModel.lyricsOffsetEnabled.collectAsState(initial = false)
    val lyricsOffsetMs by viewModel.lyricsOffsetMs.collectAsState(initial = DEFAULT_LYRICS_OFFSET_MS)
    val lyricsSynced by viewModel.lyricsSynced.collectAsState(initial = true)
    val lyricsFallbackEnabled by viewModel.lyricsFallbackEnabled.collectAsState(initial = true)
    val savedClientId by viewModel.clientId.collectAsState(initial = null)
    val savedClientSecret by viewModel.clientSecret.collectAsState(initial = null)
    var showAdvancedSettings by rememberSaveable { mutableStateOf(false) }

    if (showAdvancedSettings) {
        AdvancedSettingsDialog(
            savedClientId = savedClientId,
            savedClientSecret = savedClientSecret,
            onClientIdChange = viewModel::setClientId,
            onClientSecretChange = viewModel::setClientSecret,
            onDismiss = { showAdvancedSettings = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_playback_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                actions = { SpotyBrand(modifier = Modifier.padding(end = 16.dp)) }
            )
        },
        modifier = modifier
    ) { innerPaddings ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPaddings.calculateTopPadding())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {
                PreferenceHeader(stringResource(R.string.settings_audio_section))

                ElevatedCard(
                    modifier = modifier.fillMaxWidth()
                ) {
                    Column {
                        DropdownPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_bitrate_title)) },
                            description = stringResource(R.string.settings_bitrate_description),
                            icon = { Icon(Icons.Default.HighQuality, contentDescription = null) },
                            options = listOf(
                                DropdownOption(
                                    Bitrate.KBPS320,
                                    stringResource(R.string.settings_bitrate_option_format, 320, stringResource(Bitrate.KBPS320.labelRes()))
                                ),
                                DropdownOption(
                                    Bitrate.KBPS160,
                                    stringResource(R.string.settings_bitrate_option_format, 160, stringResource(Bitrate.KBPS160.labelRes()))
                                ),
                                DropdownOption(
                                    Bitrate.KBPS96,
                                    stringResource(R.string.settings_bitrate_option_format, 96, stringResource(Bitrate.KBPS96.labelRes()))
                                ),
                            ),
                            selectedValue = settings.bitrate,
                            onValueChange = { viewModel.setBitrate(it) }
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_normalize_audio_title)) },
                            description = stringResource(R.string.settings_normalize_audio_description),
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeDown,
                                    contentDescription = null
                                )
                            },
                            onCheckedChange = { viewModel.setNormalizeAudio(it) },
                            isChecked = settings.normalizeAudio
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_gapless_title)) },
                            description = stringResource(R.string.settings_gapless_description),
                            icon = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                            onCheckedChange = { viewModel.setGaplessPlayback(it) },
                            isChecked = settings.gapless
                        )

                        ElevatedCard(
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (restartNeeded)
                                    MaterialTheme.colorScheme.tertiaryContainer
                                else
                                    MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                PreferenceEntry(
                                    title = { Text(stringResource(R.string.settings_restart_spirc_title)) },
                                    description = stringResource(R.string.settings_restart_spirc_description),
                                    icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                                    onClick = {
                                        viewModel.restartSpirc()
                                    },
                                    trailingContent = {
                                        AnimatedVisibility(
                                            visible = restartNeeded,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut()
                                        ) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.tertiary
                                            ) {
                                                Text("!")
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                PreferenceHeader(stringResource(R.string.settings_controls_section))

                ElevatedCard(
                    modifier = modifier.fillMaxWidth()
                ) {
                    Column {
                        var ffSeconds by remember(settings.forwardMilliseconds) {
                            mutableFloatStateOf(settings.forwardMilliseconds.toFloat() / 1000f)
                        }

                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.settings_fast_forward_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = pluralStringResource(R.plurals.settings_fast_forward_seconds, ffSeconds.roundToInt(), ffSeconds.roundToInt()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = ffSeconds,
                                onValueChange = { ffSeconds = it },
                                onValueChangeFinished = {
                                    viewModel.setFastForwardMs((ffSeconds * 1000).toLong())
                                },
                                valueRange = 0f..90f,
                                steps = 17
                            )
                        }

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_keepalive_title)) },
                            description = stringResource(R.string.settings_keepalive_description),
                            icon = {
                                Icon(
                                    Icons.Default.Healing,
                                    contentDescription = null
                                )
                            },
                            onCheckedChange = { viewModel.setKeepAlive(it) },
                            isChecked = settings.keepalive
                        )
                    }
                }
            }

            item {
                PreferenceHeader(stringResource(R.string.settings_lyrics_section))

                ElevatedCard(
                    modifier = modifier.fillMaxWidth()
                ) {
                    Column {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_lyrics_synced_title)) },
                            description = stringResource(R.string.settings_lyrics_synced_description),
                            icon = { Icon(Icons.Default.Lyrics, contentDescription = null) },
                            onCheckedChange = { viewModel.setLyricsSynced(it) },
                            isChecked = lyricsSynced
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_lyrics_fallback_title)) },
                            description = stringResource(R.string.settings_lyrics_fallback_description),
                            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                            onCheckedChange = { viewModel.setLyricsFallbackEnabled(it) },
                            isChecked = lyricsFallbackEnabled
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_romanize_lyrics_title)) },
                            description = stringResource(R.string.settings_romanize_lyrics_description),
                            icon = { Icon(Icons.Default.Translate, contentDescription = null) },
                            onCheckedChange = { viewModel.setRomanizeLyrics(it) },
                            isChecked = romanizeLyrics
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_lyrics_early_title)) },
                            description = stringResource(R.string.settings_lyrics_early_description),
                            icon = { Icon(Icons.Default.Timer, contentDescription = null) },
                            onCheckedChange = { viewModel.setLyricsOffsetEnabled(it) },
                            isChecked = lyricsOffsetEnabled
                        )

                        if (lyricsOffsetEnabled) {
                            // Local draft so dragging does not spam DataStore; committed on release
                            var leadTimeMs by remember(lyricsOffsetMs) {
                                mutableFloatStateOf(lyricsOffsetMs.toFloat())
                            }

                            PreferenceEntry(
                                title = { Text(stringResource(R.string.settings_lyrics_lead_time_title)) },
                                description = formatLeadTime(leadTimeMs.roundToInt()),
                                content = {
                                    Slider(
                                        value = leadTimeMs,
                                        onValueChange = { leadTimeMs = it },
                                        onValueChangeFinished = {
                                            viewModel.setLyricsOffsetMs(leadTimeMs.roundToInt())
                                        },
                                        valueRange = LYRICS_OFFSET_MIN_MS..LYRICS_OFFSET_MAX_MS,
                                        steps = sliderSteps(
                                            LYRICS_OFFSET_MIN_MS,
                                            LYRICS_OFFSET_MAX_MS,
                                            LYRICS_OFFSET_STEP_MS.toFloat()
                                        ),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                },
                                onClick = { },
                            )
                        }
                    }
                }
            }

            item {
                PreferenceHeader(stringResource(R.string.settings_spotify_connection_section))

                ElevatedCard(
                    modifier = modifier.fillMaxWidth()
                ) {
                    Column {
                        val defaultDeviceName = stringResource(R.string.settings_app_name)
                        var deviceName by remember(settings.deviceName) {
                            mutableStateOf(settings.deviceName)
                        }

                        LaunchedEffect(deviceName) {
                            delay(500)
                            val finalValue = deviceName.ifBlank { defaultDeviceName }
                            if (finalValue != settings.deviceName) {
                                viewModel.setDeviceName(finalValue)
                            }
                        }

                        TextInputPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_connect_name_title)) },
                            placeholder = stringResource(R.string.settings_app_name),
                            value = deviceName,
                            onValueChange = { deviceName = it },
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_auto_transfer_title)) },
                            description = stringResource(R.string.settings_auto_transfer_description),
                            icon = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                            onCheckedChange = { viewModel.setAutoTransfer(it) },
                            isChecked = settings.autoTransfer
                        )
                    }
                }
            }

            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_advanced_title)) },
                        onClick = { showAdvancedSettings = true }
                    )
                }
            }
        }
    }
}