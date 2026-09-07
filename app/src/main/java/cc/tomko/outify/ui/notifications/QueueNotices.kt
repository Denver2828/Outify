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
        InsertNextResult.INSERTED -> R.string.ui_notif_inserted_to_queue
        InsertNextResult.NOTHING_PLAYING -> R.string.ui_notif_play_next_nothing_playing
        InsertNextResult.FAILED -> R.string.ui_notif_play_next_failed
    }
}
