package cc.tomko.outify.ui.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.repository.HiddenItemsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HiddenItemsUiState(
    val hiddenTracks: List<Track> = emptyList(),
    val hiddenAlbums: List<Album> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = !isLoading && hiddenTracks.isEmpty() && hiddenAlbums.isEmpty()
}

@HiltViewModel
class HiddenItemsViewModel @Inject constructor(
    private val hiddenItemsRepository: HiddenItemsRepository,
    private val metadata: Metadata,
) : ViewModel() {

    private val hiddenEntities = hiddenItemsRepository.observeHidden()

    private val hiddenTrackUris = hiddenEntities
        .map { list -> list.filter { it.type == HiddenItemsRepository.TYPE_TRACK }.map { it.uri } }

    private val hiddenAlbumUris = hiddenEntities
        .map { list -> list.filter { it.type == HiddenItemsRepository.TYPE_ALBUM }.map { it.uri } }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val hiddenTracks = hiddenTrackUris
        .flatMapLatest { uris -> if (uris.isEmpty()) flowOf(emptyList()) else metadata.observeTracks(uris) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val hiddenAlbums = hiddenAlbumUris
        .flatMapLatest { uris -> if (uris.isEmpty()) flowOf(emptyList()) else metadata.observeAlbums(uris) }

    val uiState: StateFlow<HiddenItemsUiState> = combine(
        hiddenTracks,
        hiddenAlbums,
    ) { tracks, albums ->
        HiddenItemsUiState(hiddenTracks = tracks, hiddenAlbums = albums, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HiddenItemsUiState())

    fun restore(uri: String) {
        viewModelScope.launch {
            hiddenItemsRepository.unhide(uri)
        }
    }

    fun restoreAll() {
        viewModelScope.launch {
            hiddenItemsRepository.unhideAll()
        }
    }
}
