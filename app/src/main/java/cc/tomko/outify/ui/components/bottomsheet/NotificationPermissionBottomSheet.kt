package cc.tomko.outify.ui.components.bottomsheet

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cc.tomko.outify.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPermissionBottomSheet(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    SystemPromptBottomSheet(
        icon = Icons.Filled.NotificationsActive,
        title = stringResource(R.string.sheet_notification_title),
        description = stringResource(R.string.sheet_notification_description),
        confirmLabel = stringResource(R.string.sheet_notification_allow),
        onConfirm = onAllow,
        onDismiss = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
    )
}
