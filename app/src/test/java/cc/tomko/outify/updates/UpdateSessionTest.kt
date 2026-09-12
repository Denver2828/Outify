package cc.tomko.outify.updates

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val release = UpdateRelease("1.7.19", 20719,
        "https://github.com/Denver2828/Outify/releases/download/v1.7.19/${ReleasePolicy.ASSET}", 10, "a".repeat(64))
    private class MemoryStore(var value: UpdateCache = UpdateCache()) : UpdatePersistence {
        override suspend fun load() = value
        override suspend fun save(value: UpdateCache) { this.value = value }
    }

    @Test fun cachedMetadataUsesTheSameStrictReleasePolicy() {
        assertEquals(release, ReleasePolicy.validateCached(release, "1.7.18", 20718))
        assertNull(ReleasePolicy.validateCached(release.copy(url = "https://evil.test/app.apk"), "1.7.18", 20718))
        assertNull(ReleasePolicy.validateCached(release.copy(versionCode = 1), "1.7.18", 20718))
        assertNull(ReleasePolicy.validateCached(release, "1.7.19", 20719))
    }

    @Test fun restoringAtStartupNeverChecksTheNetwork() = runBlocking {
        val store = MemoryStore(UpdateCache(nextCheck = 10000))
        var checks = 0
        val session = UpdateSession(this, store, { 1000 }, { checks++; UpdateCheck.NoUpdate },
            { _, _ -> error("No automatic download") }, temporary.root, ApkVerifier { _, _ -> true })
        session.restore(); yield()
        assertEquals(0, checks)
        assertEquals(UpdateState.Idle, session.state.value)
    }

    @Test fun manualCheckBypassesNextCheckAndReportsNoUpdate() = runBlocking {
        val store = MemoryStore(UpdateCache(nextCheck = 10000))
        var checks = 0
        val session = UpdateSession(this, store, { 1000 }, { checks++; UpdateCheck.NoUpdate },
            { _, _ -> error("No automatic download") }, temporary.root, ApkVerifier { _, _ -> true })
        session.checkNow(); yield()
        assertEquals(1, checks)
        assertEquals(UpdateState.UpToDate, session.state.value)
        assertEquals(1000 + 6 * 60 * 60_000L, store.value.nextCheck)
    }

    @Test fun persistedRateLimitBlocksManualCheckAndReportsDeadline() = runBlocking {
        val store = MemoryStore(UpdateCache(rateUntil = 5000))
        var checks = 0
        val session = UpdateSession(this, store, { 1000 }, { checks++; UpdateCheck.NoUpdate },
            { _, _ -> error("No download") }, temporary.root, ApkVerifier { _, _ -> false })
        session.checkNow(); yield()
        assertEquals(0, checks)
        assertEquals(UpdateState.Error("rate", 5000), session.state.value)
    }

    @Test fun serverRateLimitIsPersistedAndReported() = runBlocking {
        val store = MemoryStore()
        val session = UpdateSession(this, store, { 1000 }, { UpdateCheck.RateLimited(Long.MAX_VALUE) },
            { _, _ -> error("No download") }, temporary.root, ApkVerifier { _, _ -> false })
        session.checkNow(); yield()
        assertTrue(store.value.rateUntil in 61000..86401000)
        assertEquals(UpdateState.Error("rate", store.value.rateUntil), session.state.value)
    }

    @Test fun explicitDownloadCancellationPropagatesAndProgressIsBounded() = runBlocking {
        val store = MemoryStore(UpdateCache(nextCheck = 10000, release = release))
        var cancelled = false
        val session = UpdateSession(this, store, { 1000 }, { error("No check") }, { _, progress ->
            try { progress(9999, 10); awaitCancellation() } finally { cancelled = true }
        }, temporary.root, ApkVerifier { _, _ -> false })
        session.restore(); yield()
        session.download(); yield()
        assertEquals(UpdateState.Downloading(release, 10), session.state.value)
        session.dismiss(); yield()
        assertTrue(cancelled)
        assertEquals(UpdateState.Idle, session.state.value)
    }

    @Test fun permissionRoundTripReverifiesWithoutRepeatedPrompts() {
        val file = temporary.newFile()
        var verified = true
        val installer = UpdateInstaller(ApkVerifier { _, _ -> verified })
        assertEquals(InstallPlan.Permission, installer.plan(file, release, false, false))
        assertEquals(InstallPlan.None, installer.plan(file, release, false, true))
        assertEquals(InstallPlan.Launch(file), installer.plan(file, release, true, true))
        assertEquals(InstallPlan.None, installer.plan(file, release, true, true))
        verified = false
        assertEquals(InstallPlan.Invalid, installer.plan(file, release, true, false))
    }

    @Test fun startupCleanupPreservesVerifiedPendingFile() = runBlocking {
        val directory = File(temporary.root, "updates").apply { mkdirs() }
        val pending = File(directory, "${release.sha256}.apk").apply { writeText("fixture"); setLastModified(1) }
        File(directory, "stale.part").writeText("partial")
        val session = UpdateSession(this, MemoryStore(UpdateCache(Long.MAX_VALUE, release = release)),
            { 100000000 }, { error("No check") }, { _, _ -> error("No download") },
            temporary.root, ApkVerifier { _, _ -> true })
        session.restore(); yield()
        assertEquals(listOf(pending), directory.listFiles()!!.toList())
        assertEquals(UpdateState.Ready(release, pending), session.state.value)
    }
}
