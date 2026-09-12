package cc.tomko.outify.updates

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import cc.tomko.outify.R
import java.io.File

class UpdatePromptTest {
    @get:Rule val compose = createComposeRule()
    private val release = UpdateRelease("1.7.19", 20719, "", 100, "")
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun availablePromptDispatchesOnlyExplicitDownload() {
        var downloads = 0
        var dismissed = 0
        compose.setContent { MaterialTheme {
            UpdatePrompt(UpdateState.Available(release), { downloads++ }, { dismissed++ }, {})
        } }
        compose.runOnIdle { assertEquals(0, downloads) }
        compose.onNodeWithText(text(R.string.update_download)).performClick()
        compose.onNodeWithText(text(R.string.update_later)).performClick()
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(1, dismissed) }
    }

    @Test fun downloadProgressIsAccessibleAndCancellable() {
        var cancelled = 0
        compose.setContent { MaterialTheme {
            UpdatePrompt(UpdateState.Downloading(release, 50), {}, { cancelled++ }, {})
        } }
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f))).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.update_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun readyPromptRequiresInstallTap() {
        var installs = 0
        compose.setContent { MaterialTheme {
            UpdatePrompt(UpdateState.Ready(release, File("fixture.apk")), {}, {}, { installs++ })
        } }
        compose.runOnIdle { assertEquals(0, installs) }
        compose.onNodeWithText(text(R.string.update_install)).performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }
}
