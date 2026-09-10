package cc.tomko.outify.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.tomko.outify.R
import cc.tomko.outify.ui.components.TextInputPreferenceEntry
import kotlinx.coroutines.delay

/** A separate window keeps the form independent of the player and app navigation overlays. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsDialog(
    savedClientId: String?,
    savedClientSecret: String?,
    onClientIdChange: (String) -> Unit,
    onClientSecretChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Preserve the existing empty/default semantics and 500 ms debounced persistence.
    // Credentials remain in memory, never in rememberSaveable or diagnostics.
    var clientIdInput by remember(savedClientId) { mutableStateOf(savedClientId ?: "") }
    var clientSecretInput by remember(savedClientSecret) { mutableStateOf(savedClientSecret ?: "") }
    LaunchedEffect(clientIdInput) {
        delay(500)
        if (clientIdInput != (savedClientId ?: "")) onClientIdChange(clientIdInput)
    }
    LaunchedEffect(clientSecretInput) {
        delay(500)
        if (clientSecretInput != (savedClientSecret ?: "")) onClientSecretChange(clientSecretInput)
    }

    val flushAndDismiss: () -> Unit = {
        // Submit pending edits before dismissal cancels the debounced effects.
        if (clientIdInput != (savedClientId ?: "")) onClientIdChange(clientIdInput)
        if (clientSecretInput != (savedClientSecret ?: "")) onClientSecretChange(clientSecretInput)
        onDismiss()
    }

    Dialog(
        onDismissRequest = flushAndDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_advanced_title)) },
                    navigationIcon = {
                        IconButton(onClick = flushAndDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                item {
                    TextInputPreferenceEntry(
                        modifier = Modifier.testTag("advanced-client-id"),
                        title = { Text(stringResource(R.string.settings_client_id_title)) },
                        placeholder = stringResource(R.string.settings_leave_empty_default),
                        value = clientIdInput,
                        onValueChange = { clientIdInput = it },
                    )
                }
                item {
                    TextInputPreferenceEntry(
                        modifier = Modifier.testTag("advanced-client-secret"),
                        title = { Text(stringResource(R.string.settings_client_secret_title)) },
                        placeholder = stringResource(R.string.settings_leave_empty_default),
                        value = clientSecretInput,
                        onValueChange = { clientSecretInput = it },
                    )
                }
            }
        }
    }
}
