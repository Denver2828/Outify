package cc.tomko.outify.core.spirc

import cc.tomko.outify.R
import cc.tomko.outify.ui.notifications.QueueNotices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertNextResultTest {

    @Test
    fun `native codes map to results and unknown codes are failures`() {
        assertEquals(InsertNextResult.FAILED, InsertNextResult.fromNative(0))
        assertEquals(InsertNextResult.INSERTED, InsertNextResult.fromNative(1))
        // Code 2 ("inserted, history cleared") was retired once librespot gained play_next.
        assertEquals(InsertNextResult.FAILED, InsertNextResult.fromNative(2))
        assertEquals(InsertNextResult.FAILED, InsertNextResult.fromNative(-1))
        assertEquals(InsertNextResult.FAILED, InsertNextResult.fromNative(99))
    }

    @Test
    fun `only insert outcomes count as success`() {
        assertTrue(InsertNextResult.INSERTED.succeeded)
        assertFalse(InsertNextResult.NOTHING_PLAYING.succeeded)
        assertFalse(InsertNextResult.FAILED.succeeded)
    }

    @Test
    fun `failures and refusals never show the inserted notice`() {
        assertEquals(R.string.ui_notif_inserted_to_queue, QueueNotices.forInsertNext(InsertNextResult.INSERTED))
        assertEquals(
            R.string.ui_notif_play_next_nothing_playing,
            QueueNotices.forInsertNext(InsertNextResult.NOTHING_PLAYING),
        )
        assertEquals(R.string.ui_notif_play_next_failed, QueueNotices.forInsertNext(InsertNextResult.FAILED))
    }
}
