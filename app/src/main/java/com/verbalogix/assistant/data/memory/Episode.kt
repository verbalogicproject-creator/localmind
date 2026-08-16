package com.verbalogix.assistant.data.memory

/**
 * One row from the `episodes` table of a project-graph-memory store.db.
 *
 * `tags` and `metadata` are stored as JSON in SQLite; `tags` is parsed to
 * List<String> here for convenience, `metadata` is left as the raw JSON
 * string until a caller needs it (avoids paying a parse cost per row).
 *
 * `rank` is populated only by FTS5 MATCH queries -- null for a lookup
 * by id.
 */
data class Episode(
    val id: String,
    val content: String,
    val kind: String,
    val batch: String?,
    val tags: List<String>,
    val metadata: String,
    val createdAt: String,
    val rank: Double? = null,
)
