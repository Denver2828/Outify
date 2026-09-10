package cc.tomko.outify.ui.screens.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.tomko.outify.R
import cc.tomko.outify.ui.components.navigation.LocalGoHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LandscapePlayerLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun splitPlayerKeepsHomeAndInsetsWithoutReservingViewportForBrand() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var homeCalls = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalGoHome provides { homeCalls++ }) {
                    Column(Modifier.width(300.dp)) {
                        FullPlayerLandscapeContent(
                            paddingValues = PaddingValues(top = 24.dp, bottom = 16.dp),
                            coverSize = 80.dp,
                            albumCoverSection = { Box(it.testTag("cover")) },
                            trackMetadataSection = { Box(Modifier.height(32.dp).testTag("metadata")) },
                            playerProgressSection = { Box(Modifier.height(32.dp).testTag("progress")) },
                            playbackControlsSection = { Box(Modifier.height(it).testTag("playback")) },
                            controlsSection = { Box(Modifier.height(it).testTag("modes")) },
                            moreActions = { Box(Modifier.height(48.dp).testTag("actions")) },
                            isEpisode = false,
                        )
                        Box(Modifier.height(40.dp).testTag("lyrics"))
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.brand_name)).assertDoesNotExist()
        val home = compose.onNodeWithContentDescription(context.getString(R.string.go_home))
        home.assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, homeCalls) }
        val homeBounds = home.getUnclippedBoundsInRoot()
        assertEquals(32.dp, homeBounds.top)
        assertTrue(homeBounds.bottom - homeBounds.top >= 48.dp)
        val tags = listOf("cover", "metadata", "progress", "playback", "modes", "actions", "lyrics")
        val bounds = tags.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        bounds.zipWithNext().forEach { (above, below) -> assertTrue(above.bottom <= below.top) }
        // 80 + 32 + 32 + 50 + 50 + 48, five 4 dp gaps, 16 dp outer padding, 40 dp insets.
        assertEquals(368.dp, bounds.last().top)
    }
}
