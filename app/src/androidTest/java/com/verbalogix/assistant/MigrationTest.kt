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
        // job, deliberately not the migration's. If seeding ever moves into the
        // migration, this assertion fails and says so.
        migrated.query("SELECT COUNT(*) FROM providers").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("providers seeded by the migration", 0, c.getInt(0))
        }
        migrated.close()
    }

    /** v2 -> v3 alone: the ALTER produces exactly the schema Room expects. */
    @Test
    fun migrate2To3_matchesExportedSchema() {
        helper.createDatabase(TEST_DB, 2).close()
        helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            LocalmindDatabase.MIGRATION_2_3,
        ).close()
    }

    /**
     * The whole chain, v1 straight through to v3 -- which is what an installed copy of
     * v0.0.4 actually runs on upgrade.
     *
     * Testing each step in isolation is not sufficient. Room applies migrations in
     * sequence, and a pair that each validate alone can still fail in series.
     *
     * It also asserts the property that matters to someone who already has the app
     * installed: their rows survive with `model` defaulted to empty, so their providers
     * stay DIRECT. Nothing is silently repointed at a swap proxy they may not be
     * running -- which would turn a working app into one that cannot reach a server.
     */
    @Test
    fun migrate1To3_preservesRowsAndLeavesProvidersDirect() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO messages (role, content, createdAt) VALUES ('user', 'kept', 1)",
            )
        }

        // v1 has no providers table, so the row goes in after the first migration --
        // exactly the state a v0.0.5 install is in before upgrading.
        helper.runMigrationsAndValidate(TEST_DB, 2, true, LocalmindDatabase.MIGRATION_1_2)
            .use { db ->
                db.execSQL(
                    "INSERT INTO providers (name, baseUrl, mode, isActive) " +
                        "VALUES ('LFM2.5 8B', 'http://127.0.0.1:8080', 'direct', 1)",
                )
            }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            LocalmindDatabase.MIGRATION_2_3,
        )

        migrated.query("SELECT content FROM messages").use { c ->
            assertEquals("messages after two migrations", 1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("kept", c.getString(0))
        }
        migrated.query("SELECT name, baseUrl, model FROM providers").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("LFM2.5 8B", c.getString(0))
            assertEquals("http://127.0.0.1:8080", c.getString(1))
            // The entire point of DEFAULT '': an existing provider stays direct.
            assertEquals("model on an upgraded row", "", c.getString(2))
        }
        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
