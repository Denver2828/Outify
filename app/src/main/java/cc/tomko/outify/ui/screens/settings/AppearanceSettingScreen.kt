package cc.tomko.outify.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Houseboat
import androidx.compose.material.icons.filled.MonochromePhotos
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.tomko.outify.R
import cc.tomko.outify.ui.components.SpotyBrand
import cc.tomko.outify.data.repository.DarkModeSetting
import cc.tomko.outify.data.repository.InterfaceSettings
import cc.tomko.outify.data.repository.LandscapeLayout
import cc.tomko.outify.ui.components.ColorPreferenceEntry
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.PreferenceSectionHeader
import cc.tomko.outify.ui.components.SwitchPreferenceEntry
import cc.tomko.outify.ui.components.bottomsheet.LYRIC_LINE_BASE_FONT_SIZE_SP
import cc.tomko.outify.ui.components.player.LyricsFontFamily
import cc.tomko.outify.ui.resolveDarkTheme
import cc.tomko.outify.ui.viewmodel.settings.AppearanceViewModel
import kotlin.math.roundToInt

private val darkModeOptions = listOf(
    DarkModeSetting.SYSTEM to R.string.settings_theme_system,
    DarkModeSetting.LIGHT to R.string.settings_theme_light,
    DarkModeSetting.DARK to R.string.settings_theme_dark,
)

private val landscapeLayoutOptions = listOf(
    LandscapeLayout.FULLSCREEN_LYRICS to R.string.settings_landscape_fullscreen_lyrics,
    LandscapeLayout.PLAYER_AND_CONTENT to R.string.settings_landscape_player_and_content,
)

private val lyricsFontFamilyOptions = listOf(
    LyricsFontFamily.SANS to R.string.settings_lyrics_typeface_sans,
    LyricsFontFamily.SERIF to R.string.settings_lyrics_typeface_serif,
    LyricsFontFamily.MONO to R.string.settings_lyrics_typeface_mono,
)

private const val LYRICS_FONT_SCALE_MIN = 0.7f
private const val LYRICS_FONT_SCALE_MAX = 1.6f
private const val LYRICS_FONT_SCALE_STEP = 0.1f

/**
 * Number of intermediate stops for a [Slider] that must land exactly on every `step` multiple.
 */
private fun lyricsSliderSteps(min: Float, max: Float, step: Float): Int =
    ((max - min) / step).roundToInt() - 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingScreen(
    viewModel: AppearanceViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = InterfaceSettings())
    val isDarkTheme = settings.darkMode.resolveDarkTheme()

    val lyricsFontScale by viewModel.lyricsFontScale.collectAsStateWithLifecycle(initialValue = 1.0f)
    val lyricsFontBold by viewModel.lyricsFontBold.collectAsStateWithLifecycle(initialValue = false)
    val lyricsFontFamily by viewModel.lyricsFontFamily.collectAsStateWithLifecycle(initialValue = "sans")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_appearance_title)) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                PreferenceSectionHeader(stringResource(R.string.settings_theme_title))

                ElevatedCard {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_theme_title)) },
                        description = stringResource(R.string.settings_theme_description),
                        icon = { Icon(Icons.Default.BrightnessMedium, contentDescription = null) },
                        content = {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                darkModeOptions.forEachIndexed { index, (mode, labelRes) ->
                                    SegmentedButton(
                                        selected = settings.darkMode == mode,
                                        onClick = { viewModel.setDarkMode(mode) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = darkModeOptions.size
                                        ),
                                        label = { Text(stringResource(labelRes)) }
                                    )
                                }
                            }
                        },
                        onClick = { },
                    )
                }
            }

            item {
                PreferenceSectionHeader(stringResource(R.string.settings_landscape_header))

                ElevatedCard {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_landscape_header)) },
                        description = stringResource(R.string.settings_landscape_description),
                        icon = { Icon(Icons.Default.StayCurrentLandscape, contentDescription = null) },
                        content = {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                landscapeLayoutOptions.forEachIndexed { index, (layout, labelRes) ->
                                    SegmentedButton(
                                        selected = settings.landscapeLayout == layout,
                                        onClick = { viewModel.setLandscapeLayout(layout) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = landscapeLayoutOptions.size
                                        ),
                                        label = { Text(stringResource(labelRes)) }
                                    )
                                }
                            }
                        },
                        onClick = { },
                    )
                }
            }

            item {
                PreferenceSectionHeader(stringResource(R.string.settings_dynamic_section))

                ElevatedCard {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_dynamic_theme_title)) },
                        description = stringResource(R.string.settings_dynamic_theme_description),
                        icon = { Icon(Icons.Default.DesignServices, contentDescription = null) },
                        isChecked = settings.dynamicTheme,
                        onCheckedChange = { enabled ->
                            viewModel.setDynamicTheme(enabled)
                        }
                    )

                    if (!settings.dynamicTheme) {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_dynamic_system_title)) },
                            description = stringResource(R.string.settings_dynamic_system_description),
                            icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                            isChecked = settings.dynamicSystem,
                            onCheckedChange = { enabled ->
                                viewModel.setDynamicSystem(enabled)
                            }
                        )

                        if (!settings.dynamicSystem) {
                            ColorPreferenceEntry(
                                title = { Text(stringResource(R.string.settings_accent_color_title)) },
                                description = stringResource(R.string.settings_accent_color_description),
                                icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                value = settings.accentColor,
                                onValueChange = { viewModel.setAccentColor(it) }
                            )
                        }
                    }
                }
            }

            item {
                ElevatedCard {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_pure_black_title)) },
                        description = if (isDarkTheme) stringResource(R.string.settings_pure_black_description) else stringResource(R.string.settings_pure_black_description_dark_only),
                        icon = { Icon(Icons.Default.DarkMode, contentDescription = null) },
                        isChecked = settings.pureBlack,
                        // Only has an effect on the dark palette
                        isEnabled = isDarkTheme,
                        onCheckedChange = { enabled ->
                            viewModel.setPureBlack(enabled)
                        }
                    )

                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_high_contrast_title)) },
                        icon = { Icon(Icons.Default.Contrast, contentDescription = null) },
                        isChecked = settings.highContrastCompat,
                        onCheckedChange = { enabled ->
                            viewModel.setHighContrastCompat(enabled)
                        }
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth(),
                ) {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_monochrome_artwork_title)) },
                        description = stringResource(R.string.settings_monochrome_artwork_description),
                        icon = { Icon(Icons.Default.MonochromePhotos, contentDescription = null) },
                        isChecked = settings.monochromeImages,
                        onCheckedChange = { enabled ->
                            viewModel.setMonochromeImages(enabled)
                        }
                    )
                }

            }
            item {
                if (settings.monochromeImages) {
                    PreferenceSectionHeader(stringResource(R.string.settings_monochrome_section))

                    ElevatedCard {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_monochrome_albums_title)) },
                            description = stringResource(R.string.settings_monochrome_albums_description),
                            icon = { Icon(Icons.Default.Album, contentDescription = null) },
                            isChecked = settings.monochromeAlbums,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromeAlbums(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_monochrome_artists_title)) },
                            description = stringResource(R.string.settings_monochrome_artists_description),
                            icon = { Icon(Icons.Default.Person, contentDescription = null) },
                            isChecked = settings.monochromeArtists,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromeArtists(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_monochrome_playlists_title)) },
                            description = stringResource(R.string.settings_monochrome_playlists_description),
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.PlaylistPlay,
                                    contentDescription = null
                                )
                            },
                            isChecked = settings.monochromePlaylists,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromePlaylists(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_monochrome_tracks_title)) },
                            description = stringResource(R.string.settings_monochrome_tracks_description),
                            icon = { Icon(Icons.Default.Audiotrack, contentDescription = null) },
                            isChecked = settings.monochromeTracks,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromeTracks(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_monochrome_player_title)) },
                            description = stringResource(R.string.settings_monochrome_player_description),
                            icon = {
                                Icon(
                                    Icons.Default.PlayCircleOutline,
                                    contentDescription = null
                                )
                            },
                            isChecked = settings.monochromePlayer,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromePlayer(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_monochrome_headers_title)) },
                            description = stringResource(R.string.settings_monochrome_headers_description),
                            icon = { Icon(Icons.Default.Topic, contentDescription = null) },
                            isChecked = settings.monochromeHeaders,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromeHeaders(enabled)
                            }
                        )
                    }
                }
            }

            item {
                PreferenceSectionHeader(stringResource(R.string.settings_font_section))

                ElevatedCard {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_font_scale_title)) },
                        description = stringResource(R.string.settings_font_scale_value, settings.fontScale),
                        icon = { Icon(Icons.Default.DesignServices, contentDescription = null) },
                        content = {
                            Slider(
                                value = settings.fontScale,
                                onValueChange = { viewModel.setFontScale(it) },
                                valueRange = 0.5f..2.0f,
                                steps = 14,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        },
                        onClick = { },
                    )
                }
            }

            item {
                PreferenceSectionHeader(stringResource(R.string.settings_lyrics_appearance_section))

                ElevatedCard {
                    // Lyrics-only text size. Local draft so dragging does not spam DataStore.
                    var lyricsFontScaleDraft by remember(lyricsFontScale) {
                        mutableFloatStateOf(lyricsFontScale)
                    }

                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_lyrics_text_size_title)) },
                        description = stringResource(
                            R.string.settings_percent_format,
                            (lyricsFontScaleDraft * 100).roundToInt()
                        ),
                        icon = { Icon(Icons.Default.FormatSize, contentDescription = null) },
                        content = {
                            Slider(
                                value = lyricsFontScaleDraft,
                                onValueChange = { lyricsFontScaleDraft = it },
                                onValueChangeFinished = {
                                    // Snap to one decimal so stored values match the slider stops
                                    val snapped = (lyricsFontScaleDraft * 10).roundToInt() / 10f
                                    viewModel.setLyricsFontScale(snapped)
                                },
                                valueRange = LYRICS_FONT_SCALE_MIN..LYRICS_FONT_SCALE_MAX,
                                steps = lyricsSliderSteps(
                                    LYRICS_FONT_SCALE_MIN,
                                    LYRICS_FONT_SCALE_MAX,
                                    LYRICS_FONT_SCALE_STEP
                                ),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        },
                        onClick = { },
                    )

                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_lyrics_bold_title)) },
                        icon = { Icon(Icons.Default.FormatBold, contentDescription = null) },
                        isChecked = lyricsFontBold,
                        onCheckedChange = { viewModel.setLyricsFontBold(it) },
                    )

                    val selectedFamily = LyricsFontFamily.fromId(lyricsFontFamily)

                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_lyrics_typeface_title)) },
                        icon = { Icon(Icons.Default.FontDownload, contentDescription = null) },
                        content = {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                lyricsFontFamilyOptions.forEachIndexed { index, (family, labelRes) ->
                                    SegmentedButton(
                                        selected = selectedFamily == family,
                                        onClick = { viewModel.setLyricsFontFamily(family.id) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = lyricsFontFamilyOptions.size
                                        ),
                                        label = { Text(stringResource(labelRes)) }
                                    )
                                }
                            }
                        },
                        onClick = { },
                    )

                    // Single live preview reflecting size + bold + family together
                    Text(
                        text = stringResource(R.string.settings_lyrics_preview),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = (LYRIC_LINE_BASE_FONT_SIZE_SP * lyricsFontScaleDraft).sp,
                            fontFamily = selectedFamily.fontFamily,
                            fontWeight = if (lyricsFontBold) FontWeight.Bold else null
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            item {
                PreferenceSectionHeader(stringResource(R.string.settings_experimental_section))

                ElevatedCard {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_floating_navbar_title)) },
                        description = stringResource(R.string.settings_floating_navbar_description),
                        icon = { Icon(Icons.Default.Houseboat, contentDescription = null) },
                        isChecked = settings.experimentalFloatingNav,
                        onCheckedChange = { viewModel.setExperimentalFloatingNav(it) },
                    )
                }

                if(settings.experimentalFloatingNav) {
                    ElevatedCard {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_navbar_show_label_title)) },
                            description = stringResource(R.string.settings_navbar_show_label_description),
                            icon = { Icon(Icons.Default.Title, contentDescription = null) },
                            isChecked = settings.navbarShowLabel,
                            onCheckedChange = { viewModel.setNavbarShowLabel(it) },
                        )
                    }
                }
            }
        }
    }
}
