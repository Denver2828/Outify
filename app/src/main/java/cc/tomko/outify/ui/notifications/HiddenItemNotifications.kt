package cc.tomko.outify.ui.notifications

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import cc.tomko.outify.R

/**
 * In-app notice shown after a track was hidden without a confirmation dialog, with an Undo
 * action. Shared by every hide entry point (player, lyrics, track detail, track sheet) so the
 * wording and timing stay identical.
 */
fun showTrackHiddenNotification(context: Context, onUndo: () -> Unit) {
    InAppNotificationController.show(
        NotificationSpec(
            message = context.getString(R.string.ui_notif_track_hidden),
            durationMillis = TRACK_HIDDEN_UNDO_WINDOW_MS,
            actions = {
                TextButton(onClick = onUndo) {
                    Text(stringResource(R.string.ui_notif_undo))
                }
            },
        )
    )
}

/** Long enough to read the notice and tap Undo, including on a car screen. */
private const val TRACK_HIDDEN_UNDO_WINDOW_MS = 4_000L
