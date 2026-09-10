package cc.tomko.outify.ui.viewmodel.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One searchable category. [headerRes] is opaque to the orchestrator (a string resource id). */
data class SearchSection(val type: String, val headerRes: Int)

/** Why a section, or a whole search, failed. */
enum class SearchErrorKind {
    NETWORK, RATE_LIMITED, AUTH, MISSING_ACCOUNT, REJECTED, FORBIDDEN, BAD_REQUEST, SERVER, DECODING, OTHER
}

sealed class SectionStatus<out T> {
    data object Pending : SectionStatus<Nothing>()
    data class Done<T>(val items: List<T>) : SectionStatus<T>()
    data class Failed(val kind: SearchErrorKind) : SectionStatus<Nothing>()
}

data class SectionSnapshot<out T>(val section: SearchSection, val status: SectionStatus<T>)

/**
 * Search state tagged with the query that produced it. Every state except [Idle] carries the
 * immutable query so late publications from an older search can be recognised and dropped.
 */
sealed class SearchUiState<out T> {
    abstract val query: String

    data object Idle : SearchUiState<Nothing>() {
        override val query: String = ""
    }

    /** At least one section is still pending. */
    data class InProgress<T>(
        override val query: String,
        val sections: List<SectionSnapshot<T>>,
    ) : SearchUiState<T>()

    /** Every section finished; some may have failed while others returned items. */
    data class Results<T>(
        override val query: String,
        val sections: List<SectionSnapshot<T>>,
    ) : SearchUiState<T>() {
        val isEmpty: Boolean
            get() = sections.none { (it.status as? SectionStatus.Done)?.items?.isNotEmpty() == true }

        val failedKinds: Set<SearchErrorKind>
            get() = sections.mapNotNull { (it.status as? SectionStatus.Failed)?.kind }.toSet()
    }

    /** Nothing could be searched: every section failed, or the request was refused up front. */
    data class Error(
        override val query: String,
        val kind: SearchErrorKind,
    ) : SearchUiState<Nothing>()
}

/**
 * Latest-only search orchestration, free of Android types so it can be unit tested.
 *
 * Contract:
 * - [search] runs every section as a child of the caller's coroutine. Cancelling the caller
 *   (what `collectLatest` does when the query changes) cancels the fetches.
 * - Each publication is tagged with the query captured at launch. A publication whose query
 *   is no longer the active one is dropped, so a fetch that ignores cancellation cannot
 *   overwrite a newer search.
 * - [clear] resets to [SearchUiState.Idle] and, by the same rule, rejects late results.
 * - Cancellation is never swallowed: only real failures become [SectionStatus.Failed].
 * - "No matches" is a [SearchUiState.Results] whose sections are all empty, never an error.
 */
class SearchOrchestrator<T>(
    private val sections: List<SearchSection>,
    private val fetchSection: suspend (query: String, section: SearchSection) -> List<T>,
    private val isRateLimited: () -> Boolean = { false },
    private val classify: (Throwable) -> SearchErrorKind = { SearchErrorKind.OTHER },
) {
    private val _state = MutableStateFlow<SearchUiState<T>>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState<T>> = _state.asStateFlow()

    fun clear() {
        _state.value = SearchUiState.Idle
    }

    /**
     * Searches [query] across every section and suspends until all of them settled or the
     * caller was cancelled. A blank query is equivalent to [clear].
     */
    suspend fun search(query: String) {
        if (query.isBlank()) {
            clear()
            return
        }

        if (isRateLimited()) {
            _state.value = SearchUiState.Error(query, SearchErrorKind.RATE_LIMITED)
            return
        }

        _state.value = SearchUiState.InProgress(
            query = query,
            sections = sections.map { SectionSnapshot(it, SectionStatus.Pending) },
        )

        coroutineScope {
            for (section in sections) {
                launch {
                    val status: SectionStatus<T> = try {
                        SectionStatus.Done(fetchSection(query, section))
                    } catch (e: CancellationException) {
                        // Our own cancellation (query changed, screen left) must propagate
                        // untouched. A cancellation that surfaces while this section is still
                        // active came from inside the fetch (a timeout or a cancelled inner
                        // scope): settle the section so the search cannot hang on Pending.
                        if (!currentCoroutineContext().isActive) throw e
                        val kind = if (e is TimeoutCancellationException) {
                            SearchErrorKind.NETWORK
                        } else {
                            SearchErrorKind.OTHER
                        }
                        SectionStatus.Failed(kind)
                    } catch (e: Throwable) {
                        SectionStatus.Failed(classify(e))
                    }
                    publish(query, section, status)
                }
            }
        }
    }

    /** Applies [status] only while [query] is still the active search. */
    private fun publish(query: String, section: SearchSection, status: SectionStatus<T>) {
        _state.update { current ->
            val inProgress = current as? SearchUiState.InProgress<T> ?: return@update current
            if (inProgress.query != query) return@update current

            val updated = inProgress.sections.map { snapshot ->
                if (snapshot.section == section) snapshot.copy(status = status) else snapshot
            }
            if (updated.any { it.status is SectionStatus.Pending }) {
                inProgress.copy(sections = updated)
            } else {
                finalize(query, updated)
            }
        }
    }

    private fun finalize(query: String, snapshots: List<SectionSnapshot<T>>): SearchUiState<T> {
        val allFailed = snapshots.isNotEmpty() && snapshots.all { it.status is SectionStatus.Failed }
        if (!allFailed) return SearchUiState.Results(query, snapshots)

        val kinds = snapshots.map { (it.status as SectionStatus.Failed).kind }
        val kind = when {
            SearchErrorKind.REJECTED in kinds -> SearchErrorKind.REJECTED
            SearchErrorKind.MISSING_ACCOUNT in kinds -> SearchErrorKind.MISSING_ACCOUNT
            SearchErrorKind.AUTH in kinds -> SearchErrorKind.AUTH
            SearchErrorKind.RATE_LIMITED in kinds -> SearchErrorKind.RATE_LIMITED
            SearchErrorKind.NETWORK in kinds -> SearchErrorKind.NETWORK
            else -> kinds.first()
        }
        return SearchUiState.Error(query, kind)
    }
}
