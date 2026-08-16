package com.verbalogix.assistant.data.memory

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Can Kotlin open a store.db built by the Python side, and does FTS5 work?
 *
 * The whole memory-system composition rests on this being true. Python writes
 * the SQLite file (project-graph-memory template + NLKE adapter, see
 * ~/projects/localmind-memory); Android reads it via the system SQLite. If
 * FTS5 MATCH does not return the expected rows on the emulator, the
 * composition is broken and every later evening builds on sand.
 *
 * FIXTURE SHAPE
 *
 * androidTest/assets/memory-fixture.db is a deterministic 5-episode SQLite
 * file built by:
 *
 *   cd ~/projects/localmind-memory/project-graph-memory
 *   python3 -m py.build_fixture memory-fixture.db
 *
 * The five episodes each stress a specific FTS5 behavior:
 *
 *   fx-01  single-keyword lexical hit         ('BM25 ranking')
 *   fx-02  multi-word phrase                  ('PoisonedRAG', 'ingestion')
 *   fx-03  technical hyphenated term          ('llama.cpp', 'KV cache')
 *   fx-04  negation / contrast                ('no embeddings')
 *   fx-05  unique keyword                     ('Localmind on-device')
 *
 * If any of these behaviors changes, the fixture is the first place to look.
 *
 * WHY the skip below API 30
 *
 * SQLite's FTS5 module is only guaranteed on Android's system SQLite from
 * API 30. Same floor as the embedded engine (NativeEngineTest also skips
 * below 30). Removing the skip on API 28 would silently regress users on
 * Android 9/10 -- they would install and the FTS query would return an
 * empty cursor with no error.
 */
@RunWith(AndroidJUnit4::class)
class MemoryStoreTest {

    private lateinit var dbFile: File

    @Before
    fun copyFixtureFromAssets() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        dbFile = File(ctx.filesDir, "memory-fixture.db")
        if (dbFile.exists()) dbFile.delete()
        ctx.assets.open("memory-fixture.db").use { input ->
            dbFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Test
    fun theFixtureOpensAndHasFiveEpisodes() {
        assumeTrue(
            "MemoryStore requires API 30; this device is ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= 30,
        )
        MemoryStore.open(dbFile.absolutePath).use { store ->
            assertEquals(5, store.countEpisodes())
        }
    }

    @Test
    fun fts5MatchOnUniqueKeywordReturnsTheRightDocFirst() {
        assumeTrue(
            "MemoryStore requires API 30; this device is ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= 30,
        )
        MemoryStore.open(dbFile.absolutePath).use { store ->
            // 'Localmind' appears in fx-05's content and fx-03's tags -- but the
            // content match must rank first, or BM25 is not doing what it says.
            val hits = store.searchEpisodes("Localmind", limit = 4)
            assertTrue("expected at least one hit for 'Localmind'; got $hits", hits.isNotEmpty())
            assertEquals(
                "expected fx-05 (content match) to rank above fx-03 (tag match); got ${hits.map { it.id }}",
                "fx-05",
                hits.first().id,
            )
        }
    }

    @Test
    fun fts5MatchOnTechnicalTermFindsItInContent() {
        assumeTrue(
            "MemoryStore requires API 30; this device is ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= 30,
        )
        MemoryStore.open(dbFile.absolutePath).use { store ->
            // 'cache' only appears in fx-03. If porter tokenization loses it, this
            // fails loudly rather than silently returning empty results.
            val hits = store.searchEpisodes("cache", limit = 4)
            assertTrue(
                "expected fx-03 among hits for 'cache'; got ${hits.map { it.id }}",
                hits.any { it.id == "fx-03" },
            )
        }
    }

    @Test
    fun fts5MatchOnMissingTermReturnsEmpty() {
        assumeTrue(
            "MemoryStore requires API 30; this device is ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= 30,
        )
        MemoryStore.open(dbFile.absolutePath).use { store ->
            // Distinguish 'FTS5 works, no match' from 'FTS5 broken, silent failure'.
            // If a genuinely absent term returns 25 rows, something in the query
            // path is wrong -- probably a bad MATCH argument or a swapped table.
            val hits = store.searchEpisodes("xylophone", limit = 25)
            assertTrue(
                "expected zero hits for 'xylophone'; got ${hits.map { it.id }}",
                hits.isEmpty(),
            )
        }
    }
}
