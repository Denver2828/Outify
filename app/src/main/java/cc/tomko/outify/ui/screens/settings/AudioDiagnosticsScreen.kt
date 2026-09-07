package cc.tomko.outify.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.ui.viewmodel.settings.AudioDiagnosticsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDiagnosticsScreen(
    viewModel: AudioDiagnosticsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report by viewModel.report.collectAsState()
    val toneResult by viewModel.lastToneResult.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val shareSubject = stringResource(R.string.settings_audio_diagnostics_share_subject)
    val shareTitle = stringResource(R.string.settings_audio_diagnostics_share)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_audio_diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_audio_diagnostics_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = viewModel::playAudioTrackTone) {
                    Text(stringResource(R.string.settings_audio_diagnostics_tone_track))
                }
                FilledTonalButton(onClick = viewModel::playSystemTone) {
                    Text(stringResource(R.string.settings_audio_diagnostics_tone_system))
                }
            }

            toneResult?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            val probeResult by viewModel.lastProbeResult.collectAsState()
            FilledTonalButton(onClick = viewModel::probeWebApi) {
                Text(stringResource(R.string.settings_audio_diagnostics_probe_web_api))
            }
            probeResult?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            context.startActivity(viewModel.buildShareIntent(shareSubject, shareTitle))
                        }
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.settings_audio_diagnostics_share))
                }
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(report)) },
                    enabled = report.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.settings_audio_diagnostics_copy))
                }
                OutlinedButton(onClick = viewModel::refresh, enabled = !busy) {
                    Text(stringResource(R.string.settings_audio_diagnostics_refresh))
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (busy && report.isEmpty()) stringResource(R.string.settings_audio_diagnostics_loading) else report,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}
