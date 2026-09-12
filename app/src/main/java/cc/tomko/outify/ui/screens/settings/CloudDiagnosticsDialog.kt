package cc.tomko.outify.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.tomko.outify.R
import cc.tomko.outify.diagnostics.CloudFailure
import cc.tomko.outify.diagnostics.CloudReport
import cc.tomko.outify.diagnostics.CloudResult
import cc.tomko.outify.diagnostics.CloudUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudDiagnosticsDialog(snapshot: CloudReport?, onClose: () -> Unit) {
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CloudResult?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val close = onClose
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = { TopAppBar(title = { Text(stringResource(R.string.cloud_title)) }) },
            bottomBar = {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = close) {
                        Text(stringResource(if (sending) R.string.cloud_cancel else R.string.cloud_close))
                    }
                    Button(enabled = snapshot != null && CloudUploader.isConfigured && !sending && result?.receipt == null,
                        onClick = {
                            if (!sending && snapshot != null && CloudUploader.isConfigured) {
                                sending = true
                                result = null
                                scope.launch {
                                    try {
                                        result = withContext(Dispatchers.IO) { CloudUploader.upload(snapshot) }
                                    } finally { sending = false }
                                }
                            }
                        }) {
                        Text(stringResource(if (sending) R.string.cloud_sending else R.string.cloud_send))
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.cloud_notice))
                Text(CloudUploader.DESTINATION, style = MaterialTheme.typography.bodySmall)
                if (snapshot == null) Text(stringResource(R.string.cloud_invalid))
                else Text(snapshot.text, style = MaterialTheme.typography.bodySmall)
                if (!CloudUploader.isConfigured) Text(stringResource(R.string.cloud_not_configured))
                result?.failure?.let { failure ->
                    Text(stringResource(when (failure) {
                        CloudFailure.AUTH -> R.string.cloud_auth
                        CloudFailure.SIZE -> R.string.cloud_size
                        CloudFailure.CAPACITY -> R.string.cloud_capacity
                        CloudFailure.UNAVAILABLE -> R.string.cloud_unavailable
                        CloudFailure.NETWORK -> R.string.cloud_network
                    }), color = MaterialTheme.colorScheme.error)
                }
                result?.receipt?.let { receipt ->
                    Text(stringResource(R.string.cloud_success, receipt.id,
                        DateFormat.getDateTimeInstance().format(Date(receipt.expiresAt * 1000))))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(receipt.id)) }) {
                        Text(stringResource(R.string.cloud_copy_id))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
