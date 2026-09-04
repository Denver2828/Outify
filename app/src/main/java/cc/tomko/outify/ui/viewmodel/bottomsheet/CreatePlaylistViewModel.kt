package cc.tomko.outify.ui.viewmodel.bottomsheet

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.R
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.data.dao.PlaylistDao
import cc.tomko.outify.data.database.PlaylistEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class CreatePlaylistViewModel @Inject constructor(
    private val spClient: SpClient,
    private val playlistDao: PlaylistDao,
) : ViewModel() {

    private val _result = MutableSharedFlow<Result<String>>(extraBufferCapacity = 1)
    val result: SharedFlow<Result<String>> = _result

    fun createPlaylist(
        name: String,
        description: String?,
        isPublic: Boolean,
        isCollaborative: Boolean
    ) {
        viewModelScope.launch {
            try {
                val playlistId = withContext(Dispatchers.IO) {
                    spClient.createPlaylist(name, description ?: "", isPublic, isCollaborative)
                } ?: return@launch

                playlistDao.upsertPlaylist(
                    PlaylistEntity(
                        id = playlistId,
                        uri = "spotify:playlist:$playlistId",
                        ownerUsername = spClient.username() ?: "",
                        revision = "",
                        name = name,
                        description = description ?: "",
                        pictureId = "",
                        isCollaborative = isCollaborative,
                        isDeletedByOwner = false,
                        timestamp = System.currentTimeMillis(),
                    )
                )
                _result.tryEmit(Result.success(playlistId))
            } catch (e: Exception) {
                _result.tryEmit(Result.failure(e))
            }
        }
    }

    fun modifyPlaylist(
        playlistId: String,
        name: String,
        description: String?,
        isPublic: Boolean,
        isCollaborative: Boolean
    ) {
        viewModelScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    spClient.modifyPlaylist(
                        playlistId,
                        name,
                        description ?: "",
                        isPublic,
                        isCollaborative
                    )
                }
                if (status == 200) {
                    playlistDao.upsertPlaylist(
                        PlaylistEntity(
                            id = playlistId,
                            uri = "spotify:playlist:$playlistId",
                            ownerUsername = spClient.username() ?: "",
                            revision = "",
                            name = name,
                            description = description ?: "",
                            pictureId = "",
                            isCollaborative = isCollaborative,
                            isDeletedByOwner = false,
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    _result.tryEmit(Result.success(playlistId))
                } else {
                    Log.w("CreatePlaylistViewModel", "Failed to modify with status code: $status")
                    val messageRes = when (status) {
                        403 -> R.string.sheet_playlist_modify_forbidden
                        else -> R.string.sheet_playlist_modify_failed
                    }
                    _result.tryEmit(Result.failure(PlaylistModifyException(messageRes, status)))
                }
            } catch (e: Exception) {
                _result.tryEmit(Result.failure(e))
            }
        }
    }
}

/**
 * Raised when Spotify rejects a playlist create/modify request. The UI resolves
 * [messageRes] with [statusCode] as its single format argument.
 */
class PlaylistModifyException(
    @StringRes val messageRes: Int,
    val statusCode: Int,
) : RuntimeException("Failed to modify playlist (status: $statusCode)")
