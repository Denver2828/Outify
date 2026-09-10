package cc.tomko.outify.ui.screens.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.tomko.outify.R
import cc.tomko.outify.ui.components.SpotyBrand
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.PreferenceHeader
import cc.tomko.outify.ui.viewmodel.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    openInterfaceSettings: (() -> Unit),
    openPlaybackSettings: (() -> Unit),
    openMiscSettings: (() -> Unit),
    openChangelog: (() -> Unit),
    openAudioDiagnostics: (() -> Unit),
    openAboutSettings: (() -> Unit),
    openAccountSettings: (() -> Unit),
    openHiddenItems: (() -> Unit),
) {
    val context = LocalContext.current

    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var batteryOptimizationDisabled by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = hasNotificationPermission(context)
        // Once the user denied twice, Android stops showing the dialog: open the app's
        // notification settings instead so the button still leads somewhere.
        val activity = context.findActivity()
        if (!granted && activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
    }

    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOptimizationDisabled = isIgnoringBatteryOptimizations(context)
    }

    // The system settings screens leave the app, so the state is refreshed on every resume
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsGranted = hasNotificationPermission(context)
        batteryOptimizationDisabled = isIgnoringBatteryOptimizations(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                actions = { SpotyBrand(modifier = Modifier.padding(end = 16.dp)) }
            )
        },
        modifier = modifier
    ) { innerPaddings ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPaddings.calculateTopPadding())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_interface_title)) },
                        description = stringResource(R.string.settings_interface_description),
                        icon = { Icon(Icons.Default.Interests, contentDescription = null) },
                        onClick = openInterfaceSettings,
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_playback_title)) },
                        description = stringResource(R.string.settings_playback_description),
                        icon = { Icon(Icons.Default.Headphones, contentDescription = null) },
                        onClick = openPlaybackSettings,
                    )
                }
            }

            item {
                PreferenceHeader(stringResource(R.string.settings_permissions_header))

                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_permissions_notifications_title)) },
                        description = if (notificationsGranted) {
                            stringResource(R.string.settings_permissions_granted)
                        } else {
                            stringResource(R.string.settings_permissions_notifications_description)
                        },
                        icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                        isEnabled = !notificationsGranted,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            notificationsGranted = hasNotificationPermission(context)
                        },
                    )

                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_permissions_battery_title)) },
                        description = if (batteryOptimizationDisabled) {
                            stringResource(R.string.settings_permissions_granted)
                        } else {
                            stringResource(R.string.settings_permissions_battery_description)
                        },
                        icon = { Icon(Icons.Default.BatteryFull, contentDescription = null) },
                        isEnabled = !batteryOptimizationDisabled,
                        onClick = {
                            batteryOptimizationLauncher.launch(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                            )
                        },
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_misc_title)) },
                        description = stringResource(R.string.settings_misc_description),
                        icon = { Icon(Icons.Default.DeveloperMode, contentDescription = null) },
                        onClick = openMiscSettings,
                    )

                    PreferenceEntry(
                        title = { Text(stringResource(R.string.changelog_title)) },
                        description = stringResource(R.string.changelog_settings_description),
                        icon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = openChangelog,
                    )

                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_audio_diagnostics_title)) },
                        description = stringResource(R.string.settings_audio_diagnostics_description),
                        icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                        onClick = openAudioDiagnostics,
                    )

                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_about_title)) },
                        description = stringResource(R.string.settings_about_description),
                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = openAboutSettings,
                    )

                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_hidden_title)) },
                        description = stringResource(R.string.settings_hidden_description),
                        icon = { Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = null) },
                        onClick = openHiddenItems,
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_accounts_title)) },
                        description = stringResource(R.string.settings_accounts_description),
                        icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                        onClick = openAccountSettings,
                    )
                }
            }
        }
    }
}

/**
 * POST_NOTIFICATIONS only exists from API 33 on; below that the permission is implicitly granted.
 */
private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
