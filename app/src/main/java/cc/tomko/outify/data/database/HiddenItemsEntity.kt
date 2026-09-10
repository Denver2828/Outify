package cc.tomko.outify.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hidden_items")
data class HiddenItemsEntity(
    @PrimaryKey
    val uri: String,
    val type: String, // "track", "album"
    val hiddenAt: Long = System.currentTimeMillis()
)
