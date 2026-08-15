package com.verbalogix.assistant

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.verbalogix.assistant.data.LocalmindDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The persisted schema is one of exactly three things about an Android app that can
 * never be changed after the first user installs. A wrong migration is therefore not a
 * bug you patch; it is data you have already destroyed on someone else's phone.
 *
 * Two distinct failures are possible and only one of them is loud:
 *
 *   the migration THROWS         -- loud, found on any upgrade
 *   the migration SUCCEEDS but   -- silent at migrate time, and Room then throws on
 *   builds the wrong table          first open with an identity-hash mismatch
 *
 * runMigrationsAndValidate covers both: it executes the migration against a real v1
 * database and then validates the result against the exported v2 schema JSON. That is
 * the only check that compares what the migration BUILT against what Room EXPECTS.
 *
 * This runs on the emulator rung (tags and the weekly schedule), not on every push.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LocalmindDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * The structural half: does v1 -> v2 produce exactly the schema Room expects?
     *
     * validateDroppedTables = true so a migration that drops something it should not
     * fails here rather than silently.
     */
    @Test
    fun migrate1To2_matchesExportedSchema() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            LocalmindDatabase.MIGRATION_1_2,
        ).close()
    }

    /**
     * The half that matters to the user: is the conversation still there afterwards?
     *
     * Schema validation would pass just as happily on a migration that recreated
     * `messages` empty. This is the assertion that a real upgrade does not quietly
     * cost someone their history, which is the actual promise being made.
     */
    @Test
    fun migrate1To2_preservesMessages() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO messages (role, content, createdAt) VALUES " +
                    "('user', 'does my history survive an upgrade?', 1)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            LocalmindDatabase.MIGRATION_1_2,
        )

        migrated.query("SELECT role, content FROM messages").use { c ->
            assertEquals("message count after migration", 1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("user", c.getString(0))
            assertEquals("does my history survive an upgrade?", c.getString(1))
        }

        // And the new table exists and starts empty -- seeding is ProviderRepository's
        // job on the count == 0 path, deliberately not the migration's. If seeding
        // ever moves into the migration, this assertion fails and says so.
        migrated.query("SELECT COUNT(*) FROM providers").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("providers seeded by the migration", 0, c.getInt(0))
        }
        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
