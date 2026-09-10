package cc.tomko.outify.core

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RateLimitPersistenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `storage read precedes initialization and one writer preserves latest state`() = runBlocking {
        withTimeout(5_000) {
            val read = CompletableDeferred<Long>()
            val initialized = CompletableDeferred<(Long) -> Unit>()
            val writes = Channel<Long>(Channel.UNLIMITED)
            val releaseWrite = CompletableDeferred<Unit>()
            val gate = RateLimitGate(clock = { 1L })
            val job = launch {
                RateLimitPersistence.run(gate, { read.await() }, {
                    writes.send(it)
                    if (it == 100L) releaseWrite.await()
                }) { restored, notify ->
                    assertEquals(100L, restored)
                    initialized.complete(notify)
                }
            }
            yield()
            assertFalse(initialized.isCompleted)
            assertTrue(writes.tryReceive().isFailure)
            read.complete(100L)
            val notify = initialized.await()
            assertEquals(100L, writes.receive())
            notify(300L)
            notify(200L)
            releaseWrite.complete(Unit)
            assertEquals(300L, writes.receive())
            gate.reset()
            assertEquals(0L, writes.receive())
            job.cancelAndJoin()
        }
    }

    @Test
    fun `polling a native deadline also reaches the persistence writer`() = runBlocking {
        withTimeout(5_000) {
            var native = 0L
            val gate = RateLimitGate(clock = { 1L }, nativeUntilMs = { native })
            val writes = Channel<Long>(Channel.UNLIMITED)
            val job = launch {
                RateLimitPersistence.run(gate, { 0L }, { writes.send(it) }) { _, _ -> }
            }
            assertEquals(0L, writes.receive())
            native = 200L
            assertEquals(200L, gate.effectiveUntilMs())
            assertEquals(200L, writes.receive())
            job.cancelAndJoin()
        }
    }

    @Test
    fun `failed startup read never initializes or overwrites storage`() = runBlocking {
        var initialized = false
        var wrote = false
        val failure = runCatching {
            RateLimitPersistence.run(RateLimitGate(), { error("unreadable") }, { wrote = true }) { _, _ ->
                initialized = true
            }
        }
        assertTrue(failure.isFailure)
        assertFalse(initialized)
        assertFalse(wrote)
    }

    @Test
    fun `native notification is committed and restored through real DataStore`() = runBlocking {
        withTimeout(5_000) {
            val file = temporaryFolder.newFolder().resolve("cooldown.preferences_pb")
            val key = longPreferencesKey("rate_limit_until_ms")
            val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            // Use real JVM filesystem replacement, not Android FileStorage's stub-SDK fallback.
            fun storage() = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file.toOkioPath() }
            val store = PreferenceDataStoreFactory.create(storage = storage(), scope = storeScope)
            store.edit { it[key] = 100L }
            val notify = CompletableDeferred<(Long) -> Unit>()
            val committed = CompletableDeferred<Unit>()
            val job = launch {
                RateLimitPersistence.run(RateLimitGate(clock = { 1L }),
                    { store.data.first()[key] ?: 0L }, { value ->
                        store.edit { it[key] = value }
                        if (value == 500L) committed.complete(Unit)
                    },
                ) { restored, callback ->
                    assertEquals(100L, restored)
                    notify.complete(callback)
                }
            }
            notify.await()(500L)
            // Flow publication can precede the storage transaction finishing its file move.
            committed.await()
            job.cancelAndJoin()
            storeScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
            val reopenedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val reopened = PreferenceDataStoreFactory.create(storage = storage(), scope = reopenedScope)
                assertEquals(500L, reopened.data.first()[key])
            } finally {
                reopenedScope.cancel()
            }
        }
    }
}
