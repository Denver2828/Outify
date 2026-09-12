package cc.tomko.outify.updates

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.tomko.outify.R
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun UpdateHost(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var planning by remember { mutableStateOf(false) }
    fun install(resumed: Boolean, permission: () -> Unit = {}) {
        if (planning) return
        planning = true
        scope.launch {
            try {
                when (val plan = viewModel.installPlan(context.packageManager.canRequestPackageInstalls(), resumed)) {
                    InstallPlan.None -> Unit
                    InstallPlan.Invalid -> viewModel.installFailed()
                    InstallPlan.Permission -> permission()
                    is InstallPlan.Launch -> {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", plan.file)
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                            "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            } catch (_: ActivityNotFoundException) { viewModel.installFailed() }
              catch (_: SecurityException) { viewModel.installFailed() }
              catch (_: IllegalArgumentException) { viewModel.installFailed() }
              finally { planning = false }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        install(resumed = true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { install(resumed = true) }
    UpdatePrompt(state, viewModel::download, viewModel::later, viewModel::retry, {
        install(resumed = false) {
            permissions.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")))
        }
    }, planning)
}

@Composable
internal fun UpdatePrompt(state: UpdateState, download: () -> Unit, later: () -> Unit,
    retry: () -> Unit, install: () -> Unit, planning: Boolean = false) {
    if (state == UpdateState.Idle || state == UpdateState.Checking) return
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        else -> null
    }
    AlertDialog(onDismissRequest = later,
        title = { Text(stringResource(R.string.update_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                release?.let { Text(stringResource(R.string.update_version, it.versionName, it.bytes / 1048576.0)) }
                when (state) {
                    is UpdateState.Available -> Text(stringResource(R.string.update_download_notice))
                    is UpdateState.Downloading -> {
                        val fraction = (state.bytes.toFloat() / state.release.bytes).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.update_progress, (fraction * 100).toInt()))
                    }
                    is UpdateState.Ready -> Text(stringResource(R.string.update_install_notice))
                    is UpdateState.Error -> {
                        Text(stringResource(when (state.reason) {
                            "storage" -> R.string.update_storage_error
                            "invalid" -> R.string.update_invalid_error
                            "install" -> R.string.update_install_error
                            "rate" -> R.string.update_rate_error
                            else -> R.string.update_network_error
                        }))
                        if (state.retryAt > 0) Text(stringResource(R.string.update_retry_time,
                            DateFormat.getDateTimeInstance().format(Date(state.retryAt))))
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = download) { Text(stringResource(R.string.update_download)) }
                is UpdateState.Ready -> TextButton(onClick = install, enabled = !planning) { Text(stringResource(R.string.update_install)) }
                is UpdateState.Error -> TextButton(onClick = retry) { Text(stringResource(R.string.update_retry)) }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = later) {
            Text(stringResource(if (state is UpdateState.Downloading) R.string.update_cancel else R.string.update_later))
        } },
    )
}
