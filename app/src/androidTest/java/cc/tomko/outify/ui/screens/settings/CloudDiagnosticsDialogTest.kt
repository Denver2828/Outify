package cc.tomko.outify.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cc.tomko.outify.R
import cc.tomko.outify.diagnostics.CloudUploader
import cc.tomko.outify.diagnostics.CloudReport
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudDiagnosticsDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun successfulUploadShowsSentAndPreventsDuplicate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = CloudReport.prepare("--- Audio engine ---\naudioTrack: headPosition=10 underruns=2")!!
        var calls = 0
        compose.setContent {
            MaterialTheme {
                CloudDiagnosticsDialog(snapshot, configured = true, upload = {
                    calls++
                    cc.tomko.outify.diagnostics.CloudResult(
                        receipt = cc.tomko.outify.diagnostics.CloudReceipt("test-receipt", 2_000_000_000),
                    )
                }) {}
            }
        }
        compose.onNodeWithText(context.getString(R.string.cloud_send)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.cloud_sent)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.cloud_sent)).assertIsNotEnabled().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, calls) }
    }

    @Test fun previewNeedsNoManualKeyAndCloseDoesNotUpload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = CloudReport.prepare("--- Audio engine ---\naudioTrack: headPosition=10 underruns=2")!!
        var closed = false
        compose.setContent { MaterialTheme { CloudDiagnosticsDialog(snapshot) { closed = true } } }
        compose.onNodeWithText(snapshot.text).assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        val send = compose.onNodeWithText(context.getString(R.string.cloud_send))
        if (CloudUploader.isConfigured) send.assertIsEnabled() else send.assertIsNotEnabled()
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
