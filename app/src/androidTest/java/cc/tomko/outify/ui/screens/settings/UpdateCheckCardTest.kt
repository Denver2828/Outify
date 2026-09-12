package cc.tomko.outify.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cc.tomko.outify.R
import cc.tomko.outify.updates.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpdateCheckCardTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun manualActionInitiatesCheck() {
        var checks = 0
        compose.setContent { MaterialTheme {
            UpdateCheckCard(UpdateState.Idle, { checks++ })
        } }

        compose.runOnIdle { assertEquals(0, checks) }
        compose.onNodeWithText(text(R.string.settings_update_check)).performClick()
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun noUpdateFeedbackIsVisible() {
        compose.setContent { MaterialTheme {
            UpdateCheckCard(UpdateState.UpToDate, {})
        } }

        compose.onNodeWithText(text(R.string.settings_update_up_to_date)).assertExists()
    }
}
