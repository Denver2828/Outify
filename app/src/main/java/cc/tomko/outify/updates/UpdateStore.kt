package cc.tomko.outify.updates

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cc.tomko.outify.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

internal data class UpdateCache(val nextCheck: Long = 0, val rateUntil: Long = 0, val release: UpdateRelease? = null)
internal interface UpdatePersistence {
    suspend fun load(): UpdateCache
    suspend fun save(value: UpdateCache)
}

@Singleton
internal class UpdateStore @Inject constructor(private val data: DataStore<Preferences>) : UpdatePersistence {
    var dismissed = false
    private val next = longPreferencesKey("update_next_check")
    private val rate = longPreferencesKey("update_rate_until")
    private val candidate = stringPreferencesKey("update_candidate")

    override suspend fun load(): UpdateCache {
        val values = data.data.first()
        val release = values[candidate]?.let {
            runCatching { Json.decodeFromString<UpdateRelease>(it) }.getOrNull()
        }?.let { ReleasePolicy.validateCached(it, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) }
        return UpdateCache(values[next] ?: 0, values[rate] ?: 0, release)
    }

    override suspend fun save(value: UpdateCache) {
        data.edit {
            it[next] = value.nextCheck
            it[rate] = value.rateUntil
            if (value.release == null) it.remove(candidate)
            else it[candidate] = Json.encodeToString(UpdateRelease.serializer(), value.release)
        }
    }
}
