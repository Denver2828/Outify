package cc.tomko.outify.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OptimisticLikeToggleTest {

    /** Minimal stand-in for the liked table: one flag per id. */
    private class FakeTable {
        val liked = mutableSetOf<String>()
        var localWrites = 0
        fun add(id: String) { liked += id; localWrites++ }
        fun remove(id: String) { liked -= id; localWrites++ }
    }

    private class RemoteLog {
        val calls = mutableListOf<Pair<String, Boolean>>() // uri to "wasLiked"
    }

    private suspend fun toggle(
        table: FakeTable,
        id: String,
        remoteResult: suspend (wasLiked: Boolean) -> Boolean,
        onLocal: suspend () -> Unit = {},
    ): LikeToggleResult = optimisticLikeToggle(
        isLiked = { id in table.liked },
        add = { table.add(id) },
        remove = { table.remove(id) },
        remote = remoteResult,
        onLocalStateChanged = onLocal,
    )

    @Test
    fun `adding a like writes locally first and keeps it when remote succeeds`() = runBlocking {
        val table = FakeTable()
        val remote = RemoteLog()
        var likedWhenRemoteRan = false

        val result = toggle(table, "t1", remoteResult = { wasLiked ->
            remote.calls += "t1" to wasLiked
            likedWhenRemoteRan = "t1" in table.liked
            true
        })

        assertEquals(LikeToggleResult(liked = true, succeeded = true), result)
        assertTrue("t1" in table.liked)
        assertTrue("local flip must happen before the remote call", likedWhenRemoteRan)
        assertEquals(listOf("t1" to false), remote.calls)
        assertEquals(1, table.localWrites)
    }

    @Test
    fun `removing a like calls remote with wasLiked=true and clears the row`() = runBlocking {
        val table = FakeTable().apply { add("t1"); localWrites = 0 }
        val remote = RemoteLog()

        val result = toggle(table, "t1", remoteResult = { wasLiked ->
            remote.calls += "t1" to wasLiked
            true
        })

        assertEquals(LikeToggleResult(liked = false, succeeded = true), result)
        assertFalse("t1" in table.liked)
        assertEquals(listOf("t1" to true), remote.calls)
    }

    @Test
    fun `remote failure rolls the local state back and reports it`() = runBlocking {
        val table = FakeTable()
        var localNotifications = 0

        val result = toggle(table, "t1", remoteResult = { false }, onLocal = { localNotifications++ })

        assertEquals(LikeToggleResult(liked = false, succeeded = false), result)
        assertFalse("t1" in table.liked)
        assertEquals("flip + rollback", 2, table.localWrites)
        assertEquals("caller is told about both local writes", 2, localNotifications)
    }

    @Test
    fun `remote exception is treated as a failure and rolled back`() = runBlocking {
        val table = FakeTable().apply { add("t1"); localWrites = 0 }

        val result = toggle(table, "t1", remoteResult = { throw IllegalStateException("boom") })

        assertEquals(LikeToggleResult(liked = true, succeeded = false), result)
        assertTrue("t1" in table.liked)
        assertEquals(2, table.localWrites)
    }

    @Test
    fun `cancellation is not swallowed`() = runBlocking {
        val table = FakeTable()
        try {
            toggle(table, "t1", remoteResult = { throw CancellationException("cancelled") })
            fail("CancellationException must propagate")
        } catch (_: CancellationException) {
            // expected: the optimistic write is left in place, the caller decides what to do
        }
    }

    @Test
    fun `rapid repeated toggles serialized per id converge on the right state`() = runBlocking {
        val table = FakeTable()
        val lock = Mutex()
        val gate = CompletableDeferred<Unit>()
        val remoteOrder = mutableListOf<Boolean>()

        coroutineScope {
            // Two taps in quick succession: like, then unlike, on the same id.
            val first = async {
                lock.withLock {
                    toggle(table, "t1", remoteResult = { wasLiked ->
                        remoteOrder += wasLiked
                        gate.await() // slow network
                        true
                    })
                }
            }
            yield()
            val second = async {
                lock.withLock {
                    toggle(table, "t1", remoteResult = { wasLiked ->
                        remoteOrder += wasLiked
                        true
                    })
                }
            }
            yield()
            gate.complete(Unit)
            assertEquals(LikeToggleResult(liked = true, succeeded = true), first.await())
            assertEquals(LikeToggleResult(liked = false, succeeded = true), second.await())
        }

        assertFalse("like then unlike ends unliked", "t1" in table.liked)
        assertEquals("remote sees save then delete, in order", listOf(false, true), remoteOrder)
    }
}
