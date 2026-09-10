package cc.tomko.outify.ui.viewmodel.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

enum class SearchAuthState { CHECKING, CONNECTED, DISCONNECTED, ERROR }
/** Revision preserves a completed recheck even when StateFlow conflates the checking state. */
data class SearchAccessState(val auth: SearchAuthState, val revision: Int)

/** Rechecks local account presence without refreshing tokens or initiating login. */
class SearchAccess(private val readAccount: suspend () -> Boolean) {
    private val _state = MutableStateFlow(SearchAccessState(SearchAuthState.CHECKING, 0))
    val state = _state.asStateFlow()

    suspend fun refresh() {
        val revision = _state.value.revision + 1
        _state.value = SearchAccessState(SearchAuthState.CHECKING, revision)
        val auth = try {
            if (readAccount()) SearchAuthState.CONNECTED else SearchAuthState.DISCONNECTED
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SearchAuthState.ERROR
        }
        _state.value = SearchAccessState(auth, revision)
    }

    /** Clearing cancels obsolete work; a successful recheck evaluates the retained query once. */
    suspend fun collect(queries: Flow<String>, search: suspend (String) -> Unit) {
        combine(queries, state) { query, auth ->
            if (auth.auth == SearchAuthState.CONNECTED) query else ""
        }.collectLatest(search)
    }
}
