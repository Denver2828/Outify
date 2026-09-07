package cc.tomko.outify.ui.viewmodel.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SearchOrchestratorTest {

    private val tracks = SearchSection("track", 1)
    private val artists = SearchSection("artist", 2)

    private fun classify(error: Throwable): SearchErrorKind = when (error) {
        is IOException -> SearchErrorKind.NETWORK
        is RateLimitedForTest -> SearchErrorKind.RATE_LIMITED
        else -> SearchErrorKind.OTHER
    }

    private class RateLimitedForTest : RuntimeException("429")

    private fun doneItems(state: SearchUiState<String>, section: SearchSection): List<String>? =
        (state as? SearchUiState.Results)
            ?.sections?.first { it.section == section }
            ?.status?.let { (it as? SectionStatus.Done)?.items }

    @Test
    fun `late response of an older query never replaces the newer results`() = runBlocking {
        val startedA = CompletableDeferred<Unit>()
        val responseA = CompletableDeferred<List<String>>()
        val responseB = CompletableDeferred<List<String>>()
        val orchestrator = SearchOrchestrator<String>(
            sections = listOf(tracks),
            fetchSection = { query, _ ->
                when (query) {
                    // A does not cooperate with cancellation: it finishes whenever it finishes.
                    "A" -> {
                        startedA.complete(Unit)
                        withContext(NonCancellable) { responseA.await() }
                    }
                    else -> responseB.await()
                }
            },
            classify = ::classify,
        )

        val jobA = launch { orchestrator.search("A") }
        startedA.await()
        assertEquals("A", orchestrator.state.value.query)

        // What collectLatest does on a new query: cancel the previous search, start the next.
        jobA.cancel()
        val jobB = launch { orchestrator.search("B") }
        yield()
        assertEquals("B", orchestrator.state.value.query)

        responseB.complete(listOf("b1", "b2"))
        jobB.join()
        assertEquals(listOf("b1", "b2"), doneItems(orchestrator.state.value, tracks))

        // A finishes last and must be dropped.
        responseA.complete(listOf("a1"))
        jobA.join()
        assertEquals("B", orchestrator.state.value.query)
        assertEquals(listOf("b1", "b2"), doneItems(orchestrator.state.value, tracks))
    }

    @Test
    fun `clearing the query rejects results that arrive afterwards`() = runBlocking {
        val startedA = CompletableDeferred<Unit>()
        val responseA = CompletableDeferred<List<String>>()
        val orchestrator = SearchOrchestrator<String>(
            sections = listOf(tracks),
            fetchSection = { _, _ ->
                startedA.complete(Unit)
                withContext(NonCancellable) { responseA.await() }
            },
        )

        val jobA = launch { orchestrator.search("A") }
        startedA.await()
        assertTrue(orchestrator.state.value is SearchUiState.InProgress)

        jobA.cancel()
        orchestrator.search("")
        assertEquals(SearchUiState.Idle, orchestrator.state.value)

        responseA.complete(listOf("a1"))
        jobA.join()
        assertEquals(SearchUiState.Idle, orchestrator.state.value)
    }

    @Test
    fun `no matches is an empty result, not an error`() = runBlocking {
        val orchestrator = SearchOrchestrator<String>(
            sections = listOf(tracks, artists),
            fetchSection = { _, _ -> emptyList() },
            classify = ::classify,
        )

        orchestrator.search("nothing")

        val state = orchestrator.state.value
        assertTrue(state is SearchUiState.Results)
        assertTrue((state as SearchUiState.Results).isEmpty)
        assertTrue(state.failedKinds.isEmpty())
    }

    @Test
    fun `network failure in every section becomes a network error`() = runBlocking {
        val orchestrator = SearchOrchestrator<String>(
            sections = listOf(tracks, artists),
            fetchSection = { _, _ -> throw IOException("connection reset") },
            classify = ::classify,
        )

        orchestrator.search("q")

        assertEquals(SearchUiState.Error("q", SearchErrorKind.NETWORK), orchestrator.state.value)
    }

    @Test
    fun `rate limit gate refuses the search before any fetch`() = runBlocking {
        var fetches = 0
        val orchestrator = SearchOrchestrator<String>(
            sections = listOf(tracks),
            fetchSection = { _, _ -> fetches++; emptyList() },
            isRateLimited = { true },
            classify = ::classify,
        )

        orchestrator.search("q")

        assertEquals(SearchUiState.Error("q", SearchErrorKind.RATE_LIMITED), orchestrator.state.value)
        assertEquals(0, fetches)
    }

    @Test
    fun `rate limit reported by a fetch outranks other failures`() = runBlocking {
        val orchestrator = SearchOrchestrator<String>(
            sections = listOf(tracks, artists),
            fetchSection = { _, section ->
                if (section == tracks) throw RateLimitedForTest() else throw IllegalStateException("boom")
            },
            classify = ::classify,
        )

        orchestrator.search("q")

        assertEquals(SearchUiState.Error("q", SearchErrorKind.RATE_LIMITED), orchestrator.state.value)
    }

    @Test
    fun `a failed section does not hide the sections that succeeded`() = runBlocking {
        val orchestrator = SearchOrchestrator<String>(
            sections = listOf(tracks, artists),
            fetchSection = { _, section ->
                if (section == tracks) listOf("t1") else throw IOException("timeout")
            },
            classify = ::classify,
        )

        orchestrator.search("q")

        val state = orchestrator.state.value as SearchUiState.Results
        assertEquals(listOf("t1"), doneItems(state, tracks))
        assertFalse(state.isEmpty)
        assertEquals(setOf(SearchErrorKind.NETWORK), state.failedKinds)
    }

    @Test
    fun `cancelling the caller cancels the fetch and is not swallowed`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var observedCancellation = false
        val orchestrator = SearchOrchestrator<String>(
            sections = listOf(tracks),
            fetchSection = { _, _ ->
                started.complete(Unit)
                try {
                    gate.await()
                    listOf("never")
                } catch (e: CancellationException) {
                    observedCancellation = true
                    throw e
                }
            },
            classify = ::classify,
        )

        val job = launch { orchestrator.search("A") }
        started.await()
        job.cancel()
        job.join()

        assertTrue(observedCancellation)
        assertTrue(job.isCancelled)
        // Cancellation left the search unsettled instead of faking a failure.
        val state = orchestrator.state.value
        assertTrue(state is SearchUiState.InProgress)
        assertTrue((state as SearchUiState.InProgress).sections.all { it.status is SectionStatus.Pending })
    }

    @Test
    fun `a timeout inside a fetch settles the section as a network failure`() = runBlocking {
        val orchestrator = SearchOrchestrator<String>(
            sections = listOf(tracks),
            fetchSection = { _, _ -> withTimeout(1) { awaitCancellation() } },
            classify = ::classify,
        )

        val job = launch { orchestrator.search("A") }
        job.join()

        assertFalse(job.isCancelled)
        assertEquals(SearchUiState.Error("A", SearchErrorKind.NETWORK), orchestrator.state.value)
    }

    @Test
    fun `a cancelled inner scope settles the section instead of hanging on pending`() = runBlocking {
        val orchestrator = SearchOrchestrator<String>(
            sections = listOf(tracks),
            fetchSection = { _, _ -> throw CancellationException("inner scope cancelled") },
            classify = ::classify,
        )

        val job = launch { orchestrator.search("A") }
        job.join()

        assertFalse(job.isCancelled)
        assertEquals(SearchUiState.Error("A", SearchErrorKind.OTHER), orchestrator.state.value)
    }
}
