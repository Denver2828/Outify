package cc.tomko.outify.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import cc.tomko.outify.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AdvancedSettingsDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun toolbarCloseSubmitsPendingEditsBeforeDismissal() = assertPendingEditsSubmitted(false)

    @Test
    fun systemBackSubmitsPendingEditsBeforeDismissal() = assertPendingEditsSubmitted(true)

    private fun assertPendingEditsSubmitted(systemBack: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val visible = mutableStateOf(true)
        val submissions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                if (visible.value) AdvancedSettingsDialog(
                    "original-id", "original-secret",
                    { submissions.add("id:$it") },
                    { submissions.add("secret:$it") },
                    {
                        assertEquals(listOf("id:fixture-id", "secret:"), submissions)
                        visible.value = false
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        listOf("advanced-client-id" to "fixture-id", "advanced-client-secret" to "")
            .forEach { (tag, value) ->
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
                compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))
                    .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(value)) }
                compose.mainClock.advanceTimeByFrame()
            }
        compose.runOnIdle { assertEquals(emptyList<String>(), submissions) }
        // Frozen virtual time keeps both writes inside the 500 ms debounce window.
        if (systemBack) Espresso.pressBack()
        else compose.onNodeWithContentDescription(context.getString(R.string.settings_back)).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(false, visible.value)
            assertEquals(listOf("id:fixture-id", "secret:"), submissions)
        }
    }

    @Test
    fun credentialFieldsRemainReachableWithLargeTextAndToolbarClosesWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val visible = mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 3f)) {
                MaterialTheme {
                    if (visible.value) AdvancedSettingsDialog(
                        null, null, {}, {}, { visible.value = false },
                    )
                }
            }
        }
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("advanced-client-id")))
            .performClick().performTextInput("fixture-id")
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag("advanced-client-secret"))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("advanced-client-secret")))
            .assertIsDisplayed().performClick().performTextInput("fixture-value")
        compose.onNodeWithContentDescription(context.getString(R.string.settings_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_advanced_title)).assertDoesNotExist()
    }

    @Test
    fun systemBackDismissesIndependentWindow() {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) AdvancedSettingsDialog(null, null, {}, {}, { visible.value = false })
            }
        }
        compose.waitForIdle()
        Espresso.pressBack()
        compose.onNode(hasScrollToNodeAction()).assertDoesNotExist()
    }
}
