package cc.tomko.outify.ui.viewmodel.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SearchAccessTest {
    @Test
    fun `return after login evaluates retained query once and respects cooldown`() = runBlocking {
        withTimeout(5_000) {
            var answer = CompletableDeferred<Boolean>()
            var limited = true
            val fetched = mutableListOf<String>()
            val access = SearchAccess { answer.await() }
            val query = MutableStateFlow("")
            val orchestrator = SearchOrchestrator(
                listOf(SearchSection("track", 1)),
                fetchSection = { text, _ ->
                    fetched.add(text)
                    listOf(text)
                },
                isRateLimited = { limited },
            )
            val collector = launch { access.collect(query, orchestrator::search) }
            val check = launch { access.refresh() }
            query.value = "retained"
            yield()
            assertTrue(fetched.isEmpty())
            answer.complete(false)
            check.join()
            assertEquals(SearchAuthState.DISCONNECTED, access.state.value.auth)
            assertTrue(fetched.isEmpty())

            answer = CompletableDeferred(true)
            access.refresh()
            val blocked = orchestrator.state.first { it is SearchUiState.Error }
            assertEquals(SearchErrorKind.RATE_LIMITED, (blocked as SearchUiState.Error).kind)
            assertTrue(fetched.isEmpty())
            limited = false
            access.refresh()
            orchestrator.state.first { it is SearchUiState.Results }
            assertEquals(listOf("retained"), fetched)
            yield()
            assertEquals(1, fetched.size)
            query.value = ""
            orchestrator.state.first { it == SearchUiState.Idle }
            query.value = "next"
            orchestrator.state.first { it is SearchUiState.Results && it.query == "next" }
            assertEquals(listOf("retained", "next"), fetched)
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `local auth inspection failure is not reported as disconnected`() = runBlocking {
        val access = SearchAccess { error("local read failed") }
        access.refresh()
        assertEquals(SearchAuthState.ERROR, access.state.value.auth)
    }
}
