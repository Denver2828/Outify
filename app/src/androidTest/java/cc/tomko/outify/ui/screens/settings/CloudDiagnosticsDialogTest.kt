package cc.tomko.outify.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cc.tomko.outify.R
import cc.tomko.outify.diagnostics.CloudReport
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudDiagnosticsDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewRequiresTokenAndCloseDoesNotUpload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = CloudReport.prepare("--- Audio engine ---\naudioTrack: headPosition=10 underruns=2")!!
        var closed = false
        compose.setContent { MaterialTheme { CloudDiagnosticsDialog(snapshot) { closed = true } } }
        compose.onNodeWithText(snapshot.text).assertExists()
        compose.onNodeWithText(context.getString(R.string.cloud_send)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.cloud_close)).performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun absentSnapshotCannotBeSent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { CloudDiagnosticsDialog(null) {} } }
        compose.onNodeWithText(context.getString(R.string.cloud_invalid)).assertExists()
        compose.onNodeWithText(context.getString(R.string.cloud_send)).assertIsNotEnabled()
    }
}
