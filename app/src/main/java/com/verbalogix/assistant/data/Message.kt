package com.verbalogix.assistant.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Database(entities = [Message::class, Provider::class], version = 2, exportSchema = true)
abstract class LocalmindDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao

    companion object {
        const val NAME = "localmind.db"

        /**
         * v1 -> v2: the provider seam. Adds `providers`; touches nothing that already
         * exists, so an upgrade keeps every message.
         *
         * Purely structural -- it creates the table and inserts nothing. Seeding lives
         * in one place instead (ProviderRepository.ensureSeeded, on the count == 0
         * path), because a fresh install never runs a migration at all and would
         * otherwise need its own seeding code. One path means an upgraded install and
         * a fresh one cannot drift into different defaults.
         *
         * The SQL must match what Room generates for the entity exactly. When it does
         * not, Room throws on first open after upgrade -- which is what MigrationTest
         * exists to discover here rather than on a user's device.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `providers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`baseUrl` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, " +
                        "`isActive` INTEGER NOT NULL)",
                )
            }
        }
    }
}
