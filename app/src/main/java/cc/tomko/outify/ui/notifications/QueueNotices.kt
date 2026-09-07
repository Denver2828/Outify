package cc.tomko.outify.ui.notifications

import androidx.annotation.StringRes
import cc.tomko.outify.R
import cc.tomko.outify.core.spirc.InsertNextResult

/**
 * Maps queue operation outcomes to the in-app notice shown to the user. Kept free of Android
 * runtime dependencies so the mapping can be unit tested.
 */
object QueueNotices {
    @StringRes
    fun forInsertNext(result: InsertNextResult): Int = when (result) {
        // Both insert paths leave the current track alone; the history difference is not
        // something the user acted on, so both read as a successful insertion.
        InsertNextResult.INSERTED,
        InsertNextResult.INSERTED_HISTORY_CLEARED -> R.string.ui_notif_inserted_to_queue
        InsertNextResult.NOTHING_PLAYING -> R.string.ui_notif_play_next_nothing_playing
        InsertNextResult.FAILED -> R.string.ui_notif_play_next_failed
    }
}
