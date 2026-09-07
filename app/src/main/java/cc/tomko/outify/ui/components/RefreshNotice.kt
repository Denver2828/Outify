package cc.tomko.outify.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.data.metadata.RefreshFailure

/** Message for a failed refresh; the rate-limited variant carries the countdown. */
@Composable
fun refreshFailureMessage(kind: RefreshFailure, rateLimitRemainingSeconds: Int): String =
    when (kind) {
        RefreshFailure.NETWORK -> stringResource(R.string.ui_notice_refresh_failed_network)
        RefreshFailure.RATE_LIMITED -> stringResource(
            R.string.settings_sync_error_rate_limited,
            rateLimitRemainingSeconds.coerceAtLeast(1),
        )
        RefreshFailure.OTHER -> stringResource(R.string.ui_notice_refresh_failed_generic)
    }

/** Message for a load that produced nothing to show; sits inside [ErrorScreen]. */
@Composable
fun loadFailureMessage(kind: RefreshFailure, rateLimitRemainingSeconds: Int, fallback: String): String =
    when (kind) {
        RefreshFailure.NETWORK -> stringResource(R.string.ui_error_load_network)
        RefreshFailure.RATE_LIMITED -> stringResource(
            R.string.settings_sync_error_rate_limited,
            rateLimitRemainingSeconds.coerceAtLeast(1),
        )
        RefreshFailure.OTHER -> fallback
    }

/**
 * One-line, non-blocking notice shown above content that is still valid: the refresh behind
 * it failed. Retry is disabled while Spotify's rate-limit window is still open.
 */
@Composable
fun RefreshNotice(
    kind: RefreshFailure,
    rateLimitRemainingSeconds: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val waiting = kind == RefreshFailure.RATE_LIMITED && rateLimitRemainingSeconds > 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = refreshFailureMessage(kind, rateLimitRemainingSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, enabled = !waiting) {
            Text(stringResource(R.string.ui_action_retry))
        }
    }
}
