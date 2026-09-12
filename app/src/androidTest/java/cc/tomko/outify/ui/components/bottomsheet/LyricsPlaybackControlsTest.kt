package cc.tomko.outify.ui.components.bottomsheet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.tomko.outify.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LyricsPlaybackControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun portraitControlsHaveSeparateLargeTargetsAndDispatchActions() = checkControls(288.dp)

    @Test
    fun landscapeControlsHaveSeparateLargeTargetsAndDispatchActions() = checkControls(600.dp)

    @Test
    fun compactLandscapeKeepsButtonsAndSeekBarOnOneLine() = checkCompactControls(600.dp)

    @Test
    fun compactPortraitKeepsButtonsAndSeekBarOnOneLine() = checkCompactControls(288.dp)

    @Test
    fun portraitFavoriteAndHideActionsHaveSeparatedTouchTargets() = checkTrackActions(288.dp)

    @Test
    fun landscapeFavoriteAndHideActionsHaveSeparatedTouchTargets() = checkTrackActions(600.dp)

    private fun checkTrackActions(width: Dp) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val liked = mutableStateOf(false)
        val hidden = mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(width)) {
                    LyricsTrackActions(
                        isLiked = liked.value,
                        isHidden = hidden.value,
                        onToggleLiked = {
                            liked.value = !liked.value
                            calls.add("favorite")
                        },
                        onToggleHidden = {
                            hidden.value = !hidden.value
                            calls.add("hide")
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }

        val favorite = compose.onNodeWithContentDescription(
            context.getString(R.string.sys_gesture_action_add_to_favorites),
        )
        val hide = compose.onNodeWithContentDescription(context.getString(R.string.hide_track))
        val favoriteBounds = favorite.getUnclippedBoundsInRoot()
        val hideBounds = hide.getUnclippedBoundsInRoot()
        val favoriteCenter = favoriteBounds.left + (favoriteBounds.right - favoriteBounds.left) / 2
        val hideCenter = hideBounds.left + (hideBounds.right - hideBounds.left) / 2

        assertEquals(48.dp, favoriteBounds.right - favoriteBounds.left)
        assertEquals(48.dp, favoriteBounds.bottom - favoriteBounds.top)
        assertEquals(48.dp, hideBounds.right - hideBounds.left)
        assertEquals(48.dp, hideBounds.bottom - hideBounds.top)
        assertTrue(hideBounds.left - favoriteBounds.right >= 16.dp)
        assertEquals(width, hideBounds.right)
        assertEquals(72.dp, hideCenter - favoriteCenter)
        assertEquals(96.dp, width - favoriteCenter)

        favorite.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription(
            context.getString(R.string.sys_gesture_action_remove_from_favorites),
        ).assertExists()
        hide.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription(context.getString(R.string.unhide_item)).assertExists()
        compose.runOnIdle { assertEquals(listOf("favorite", "hide"), calls) }
    }

    private fun checkCompactControls(width: Dp) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shuffled = mutableStateOf(false)
        val position = mutableStateOf(0.25f)
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(width)) {
                    CompactLyricsPlaybackControls(
                        false, shuffled.value, position.value, "1:00", "4:00",
                        { shuffled.value = !shuffled.value; calls.add("shuffle") },
                        { calls.add("previous") }, { calls.add("play") }, { calls.add("next") },
                        { position.value = it }, { calls.add("seek") },
                    )
                }
            }
        }
        val labels = listOf(R.string.sheet_shuffle_cd, R.string.sheet_previous_cd,
            R.string.sheet_play_cd, R.string.sheet_next_cd).map(context::getString)
        val buttons = labels.map { compose.onNodeWithContentDescription(it) }
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        val bounds = (buttons + slider).map { it.getUnclippedBoundsInRoot() }
        bounds.forEach {
            assertTrue(it.bottom - it.top >= 48.dp)
            assertTrue(it.right - it.left >= 48.dp)
            assertEquals(bounds.first().top, it.top)
            assertEquals(bounds.first().bottom, it.bottom)
        }
        bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
        compose.onNodeWithText("1:00").assertExists()
        compose.onNodeWithText("4:00").assertExists()
        buttons.forEach { it.performSemanticsAction(SemanticsActions.OnClick) { action -> action() } }
        buttons.first().assertIsOn()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle {
            assertEquals(0.5f, position.value)
            assertEquals(listOf("shuffle", "previous", "play", "next", "seek"), calls)
        }
    }

    private fun checkControls(width: Dp) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shuffled = mutableStateOf(false)
        val playing = mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(width)) {
                    LyricsPlaybackControls(
                        playing.value, shuffled.value,
                        {
                            shuffled.value = !shuffled.value
                            calls.add("shuffle")
                        },
                        { calls.add("previous") },
                        {
                            playing.value = !playing.value
                            calls.add("play")
                        },
                        { calls.add("next") },
                    )
                }
            }
        }
        val labels = listOf(R.string.sheet_shuffle_cd, R.string.sheet_previous_cd,
            R.string.sheet_play_cd, R.string.sheet_next_cd).map(context::getString)
        val nodes = labels.map { compose.onNodeWithContentDescription(it) }
        val bounds = nodes.map { it.getUnclippedBoundsInRoot() }
        bounds.forEachIndexed { index, rect ->
            val minimum = if (index == 2) 80.dp else 64.dp
            assertTrue(rect.right - rect.left >= minimum)
            assertTrue(rect.bottom - rect.top >= minimum)
        }
        bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
        // Invoke semantics directly so a wide fixture also works on a portrait test host.
        nodes.first().assertIsOff()
        val off = nodes.first().captureToImage().toPixelMap()
        val offFill = off[off.width / 2, 8]
        nodes.first().performSemanticsAction(SemanticsActions.OnClick) { it() }
        nodes.first().assertIsOn()
        val on = nodes.first().captureToImage().toPixelMap()
        org.junit.Assert.assertNotEquals(offFill, on[on.width / 2, 8])
        nodes.first().performSemanticsAction(SemanticsActions.OnClick) { it() }
        nodes.first().assertIsOff()
        nodes.drop(1).forEach { node -> node.performSemanticsAction(SemanticsActions.OnClick) { it() } }
        compose.onNodeWithContentDescription(context.getString(R.string.sheet_pause_cd)).assertExists()
        compose.runOnIdle { assertEquals(listOf("shuffle", "shuffle", "previous", "play", "next"), calls) }
    }
}
