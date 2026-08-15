package com.verbalogix.assistant.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The conversation, persisted.
 *
 * This is the architectural answer to Android killing backgrounded apps, and it is
 * the reason this app needs no foreground service. llama-server holds the weights in
 * Termux and never stops; the app is a client. If Android reclaims it while you are
 * elsewhere, reopening restores the conversation from here and reconnects to a server
 * that was never interrupted.
 *
 * Make a kill invisible rather than fight to stay resident. On a RedMagic device,
 * where background management is aggressive, that is the trade that actually works.
 *
 * The schema version is one of exactly three things about this app that can never
 * change after the first install. Version 1 is deliberately minimal: adding a column
 * later is a simple migration, changing a primary key is not.
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "user" or "assistant" -- matches the OpenAI-compatible wire role exactly. */
    val role: String,
    val content: String,
    val createdAt: Long,
)

@Dao
interface MessageDao {
    /** Flow, so the UI redraws when a reply lands without anyone polling. */
    @Query("SELECT * FROM messages ORDER BY id")
    fun observeAll(): Flow<List<Message>>

    @Query("SELECT * FROM messages ORDER BY id")
    suspend fun all(): List<Message>

    @Insert
    suspend fun insert(message: Message): Long

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Database(entities = [Message::class], version = 1, exportSchema = true)
abstract class LocalmindDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        const val NAME = "localmind.db"
    }
}
