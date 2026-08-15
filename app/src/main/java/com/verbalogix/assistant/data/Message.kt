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
 * The schema version is MIGRATION-SENSITIVE, not immutable -- a distinction worth
 * keeping straight. It CAN change; that is what migrations are for. Only two things
 * about an Android app have no migration path at all: the applicationId and the
 * signing certificate.
 *
 * What cannot be undone here is that the data is already on someone's device, so a
 * wrong migration destroys it rather than merely failing. Version 1 is deliberately
 * minimal for that reason: adding a column later is a simple migration, changing a
 * primary key is not.
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

@Database(entities = [Message::class, Provider::class], version = 3, exportSchema = true)
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
         * in one place instead (ProviderRepository.ensureDefaults), because a fresh
         * install never runs a migration at all and would otherwise need its own
         * seeding code. One path means an upgraded install and a fresh one cannot
         * drift into different defaults.
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

        /**
         * v2 -> v3: `providers.model`, so one endpoint can serve several models.
         *
         * A swap proxy (llama-swap) keys on the request's `model` field to decide which
         * llama-server to start, stopping the previous one first. Empty means the old
         * behaviour -- one server, one model, its own port -- which stays the baseline.
         *
         * DEFAULT '' is what makes this safe on an existing install: every row already
         * there keeps working exactly as before, unchanged and still direct. Nothing is
         * migrated onto the proxy behind the user's back.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `providers` ADD COLUMN `model` TEXT NOT NULL DEFAULT ''",
                )
            }
        }
    }
}
