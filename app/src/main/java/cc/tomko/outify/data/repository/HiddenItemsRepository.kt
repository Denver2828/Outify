package cc.tomko.outify.data.repository

import cc.tomko.outify.data.dao.HiddenItemsDao
import cc.tomko.outify.data.database.HiddenItemsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Hide forever": tracks and albums the user never wants to see or hear again. Unlike
 * [LikedRepository] this is purely local (there is no server-side hide concept in the
 * Spotify Web API for arbitrary items), so there is no sync path here.
 */
@Singleton
class HiddenItemsRepository @Inject constructor(
    private val hiddenItemsDao: HiddenItemsDao,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val TYPE_TRACK = "track"
        const val TYPE_ALBUM = "album"
    }

    /**
     * Every hidden uri (tracks and albums mixed), kept hot so callers on the playback path
     * can check it synchronously without a suspend call.
     */
    val hiddenUris: StateFlow<Set<String>> = hiddenItemsDao.observeUris()
        .map { it.toHashSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun observeHidden(): Flow<List<HiddenItemsEntity>> = hiddenItemsDao.observeAll()

    suspend fun isHidden(uri: String): Boolean = hiddenItemsDao.contains(uri)

    suspend fun hideTrack(uri: String) {
        hiddenItemsDao.insert(HiddenItemsEntity(uri = uri, type = TYPE_TRACK))
    }

    suspend fun hideAlbum(uri: String) {
        hiddenItemsDao.insert(HiddenItemsEntity(uri = uri, type = TYPE_ALBUM))
    }

    suspend fun unhide(uri: String) {
        hiddenItemsDao.delete(uri)
    }

    suspend fun unhideAll() {
        hiddenItemsDao.clearAll()
    }
}
