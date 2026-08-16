package com.verbalogix.assistant.data.memory

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.RequiresApi
import org.json.JSONArray

/**
 * Read-only view into a project-graph-memory store.db (SQLite + FTS5),
 * built by the Python side (~/projects/localmind-memory) and consumed
 * here by Kotlin.
 *
 * The interchange IS the file. Python owns write; Kotlin owns read.
 *
 * WHY BARE SUPPORTSQLITE, NOT ROOM
 *
 * The schema is Python-owned by design -- the same store.db could be
 * built on a laptop, in Termux, or (later) on-device by a Python-in-app
 * runtime. Room's identity-hash validation would reject any DB it did
 * not create; setting up the hash to match Python's would be fragile
 * and would tie the app's build to a specific schema version. Bare
 * SupportSQLite is honest about what this is: a foreign SQLite file,
 * opened read-only, queried with hand-written SQL.
 *
 * WHY @RequiresApi(30)
 *
 * SQLite's FTS5 module is only guaranteed on Android's system SQLite
 * from API 30. Localmind's embedded engine is already gated to API 30+;
 * this feature shares the floor, no new architectural work required.
 *
 * @see ~/projects/localmind-memory/project-graph-memory/schema.sql
 */
@RequiresApi(30)
class MemoryStore private constructor(private val db: SQLiteDatabase) : AutoCloseable {

    fun countEpisodes(): Int =
        db.rawQuery("SELECT COUNT(*) FROM episodes", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    /**
     * FTS5 MATCH against `episodes_fts`, ordered by BM25.
     *
     * The FTS5 index covers content + kind + tags, so a hit can come from
     * any of those. Bag-of-words tokenization is porter + unicode61 (see
     * schema.sql). Version-number tokenization ("3.6" etc.) is a known
     * limitation of this tokenizer -- flag for query preprocessing later.
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
        return db.rawQuery(sql, arrayOf(query, limit.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Episode(
                            id = c.getString(0),
                            content = c.getString(1),
                            kind = c.getString(2),
                            batch = c.getStringOrNull(3),
                            tags = parseTags(c.getStringOrNull(4)),
                            metadata = c.getStringOrNull(5).orEmpty(),
                            createdAt = c.getString(6),
                            rank = c.getDouble(7),
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
        return db.rawQuery(sql, arrayOf(query, limit.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Fact(
                            id = c.getString(0),
                            claim = c.getString(1),
                            reason = c.getStringOrNull(2),
                            status = c.getString(3),
                            supersededBy = c.getStringOrNull(4),
                            tags = parseTags(c.getStringOrNull(5)),
                            createdAt = c.getString(6),
                            updatedAt = c.getString(7),
                            rank = c.getDouble(8),
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
        return db.rawQuery(sql, arrayOf(query, limit.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DocPointer(
                            id = c.getString(0),
                            title = c.getString(1),
                            path = c.getString(2),
                            purpose = c.getStringOrNull(3),
                            tags = parseTags(c.getStringOrNull(4)),
                            lastUpdated = c.getStringOrNull(5),
                            rank = c.getDouble(6),
                        ),
                    )
                }
            }
        }
    }

    override fun close() {
        db.close()
    }

    companion object {
        /**
         * Open a store.db at [path] read-only.
         *
         * READ-ONLY on purpose: Ev1 proves the read side works. Writes
         * happen on the Python side; if that changes, this call needs to
         * change explicitly, not by accident.
         */
        fun open(path: String): MemoryStore =
            MemoryStore(SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY))
    }
}

private fun Cursor.getStringOrNull(idx: Int): String? =
    if (isNull(idx)) null else getString(idx)

private fun parseTags(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        List(arr.length()) { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }
}
