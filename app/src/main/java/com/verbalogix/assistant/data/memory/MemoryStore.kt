package com.verbalogix.assistant.data.memory

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.json.JSONArray

/**
 * Read-only view into a project-graph-memory store.db (SQLite + FTS5),
 * built by the Python side (~/projects/localmind-memory) and consumed
 * here by Kotlin.
 *
 * The interchange IS the file. Python owns write; Kotlin owns read.
 *
 * WHY BUNDLED SQLITE, NOT ANDROID'S SYSTEM SQLITE
 *
 * Emulator run 31922969687 (2026-08-16, API 36) failed with
 *   android.database.sqlite.SQLiteException: no such module: fts5
 * against Android's system SQLite. FTS5 requires the compile-time flag
 * SQLITE_ENABLE_FTS5, and Android system SQLite ships without it on
 * enough builds -- past 30, past 34, and on the emulator at 36 -- that
 * relying on it is a live regression. androidx.sqlite:sqlite-bundled
 * packages a modern SQLite with FTS5 into the app, so the answer is the
 * same on every device.
 *
 * WHY THE NEW DRIVER API (SQLiteConnection), NOT SQLiteDatabase
 *
 * The bundled driver only exposes the new androidx.sqlite driver API.
 * The old android.database.sqlite.* interface routes through the system
 * SQLite and cannot see the bundled one.
 *
 * WHY BARE DRIVER, NOT ROOM
 *
 * The schema is Python-owned by design -- the same store.db could be
 * built on a laptop, in Termux, or (later) on-device. Room's identity
 * hash validation would reject any DB it did not create.
 *
 * @see ~/projects/localmind-memory/project-graph-memory/schema.sql
 */
class MemoryStore private constructor(private val conn: SQLiteConnection) : AutoCloseable {

    fun countEpisodes(): Int =
        conn.prepare("SELECT COUNT(*) FROM episodes").use { stmt ->
            check(stmt.step()) { "count query returned no rows" }
            stmt.getLong(0).toInt()
        }

    /**
     * FTS5 MATCH against `episodes_fts`, ordered by BM25.
     *
     * The FTS5 index covers content + kind + tags, so a hit can come from
     * any of those. Tokenization is porter + unicode61 (see schema.sql).
     * Version-number tokenization ("3.6" etc.) is a known limitation of
     * this tokenizer -- flag for query preprocessing later.
     */
    fun searchEpisodes(query: String, limit: Int = 25): List<Episode> {
        val sql = """
            SELECT e.id, e.content, e.kind, e.batch, e.tags, e.metadata, e.created_at,
                   bm25(episodes_fts) AS rank
            FROM episodes_fts
            JOIN episodes e ON e.rowid = episodes_fts.rowid
            WHERE episodes_fts MATCH ?
            ORDER BY rank
            LIMIT ?
        """.trimIndent()
        return conn.prepare(sql).use { stmt ->
            stmt.bindText(1, query)
            stmt.bindLong(2, limit.toLong())
            buildList {
                while (stmt.step()) {
                    add(
                        Episode(
                            id = stmt.getText(0),
                            content = stmt.getText(1),
                            kind = stmt.getText(2),
                            batch = stmt.getTextOrNull(3),
                            tags = parseTags(stmt.getTextOrNull(4)),
                            metadata = stmt.getTextOrNull(5).orEmpty(),
                            createdAt = stmt.getText(6),
                            rank = stmt.getDouble(7),
                        ),
                    )
                }
            }
        }
    }

    fun searchFacts(query: String, limit: Int = 25): List<Fact> {
        val sql = """
            SELECT f.id, f.claim, f.reason, f.status, f.superseded_by, f.tags,
                   f.created_at, f.updated_at, bm25(facts_fts) AS rank
            FROM facts_fts
            JOIN facts f ON f.rowid = facts_fts.rowid
            WHERE facts_fts MATCH ? AND f.status = 'active'
            ORDER BY rank
            LIMIT ?
        """.trimIndent()
        return conn.prepare(sql).use { stmt ->
            stmt.bindText(1, query)
            stmt.bindLong(2, limit.toLong())
            buildList {
                while (stmt.step()) {
                    add(
                        Fact(
                            id = stmt.getText(0),
                            claim = stmt.getText(1),
                            reason = stmt.getTextOrNull(2),
                            status = stmt.getText(3),
                            supersededBy = stmt.getTextOrNull(4),
                            tags = parseTags(stmt.getTextOrNull(5)),
                            createdAt = stmt.getText(6),
                            updatedAt = stmt.getText(7),
                            rank = stmt.getDouble(8),
                        ),
                    )
                }
            }
        }
    }

    fun searchDocs(query: String, limit: Int = 25): List<DocPointer> {
        val sql = """
            SELECT d.id, d.title, d.path, d.purpose, d.tags, d.last_updated,
                   bm25(doc_pointers_fts) AS rank
            FROM doc_pointers_fts
            JOIN doc_pointers d ON d.rowid = doc_pointers_fts.rowid
            WHERE doc_pointers_fts MATCH ?
            ORDER BY rank
            LIMIT ?
        """.trimIndent()
        return conn.prepare(sql).use { stmt ->
            stmt.bindText(1, query)
            stmt.bindLong(2, limit.toLong())
            buildList {
                while (stmt.step()) {
                    add(
                        DocPointer(
                            id = stmt.getText(0),
                            title = stmt.getText(1),
                            path = stmt.getText(2),
                            purpose = stmt.getTextOrNull(3),
                            tags = parseTags(stmt.getTextOrNull(4)),
                            lastUpdated = stmt.getTextOrNull(5),
                            rank = stmt.getDouble(6),
                        ),
                    )
                }
            }
        }
    }

    override fun close() {
        conn.close()
    }

    companion object {
        private val driver: BundledSQLiteDriver = BundledSQLiteDriver()

        /**
         * Open a store.db at [path]. Effectively read-only by discipline: this
         * class never calls execSQL and never binds against a write statement.
         * The bundled driver has no open-mode flag equivalent to
         * SQLiteDatabase.OPEN_READONLY.
         */
        fun open(path: String): MemoryStore = MemoryStore(driver.open(path))
    }
}

private fun SQLiteStatement.getTextOrNull(idx: Int): String? =
    if (isNull(idx)) null else getText(idx)

private fun parseTags(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        List(arr.length()) { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }
}
