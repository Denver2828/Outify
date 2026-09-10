package cc.tomko.outify.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cc.tomko.outify.data.database.HiddenItemsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HiddenItemsDao {
    @Query("SELECT * FROM hidden_items ORDER BY hiddenAt DESC")
    fun observeAll(): Flow<List<HiddenItemsEntity>>

    @Query("SELECT uri FROM hidden_items")
    fun observeUris(): Flow<List<String>>

    @Query("SELECT uri FROM hidden_items WHERE type = :type")
    fun observeUrisByType(type: String): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM hidden_items WHERE uri = :uri)")
    suspend fun contains(uri: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: HiddenItemsEntity)

    @Query("DELETE FROM hidden_items WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM hidden_items")
    suspend fun clearAll()
}
