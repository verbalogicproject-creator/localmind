package com.verbalogix.assistant.data.memory

/**
 * One row from the `doc_pointers` table. A doc_pointer is an index entry
 * into an external document -- title, path, purpose -- NOT a mirror.
 */
data class DocPointer(
    val id: String,
    val title: String,
    val path: String,
    val purpose: String?,
    val tags: List<String>,
    val lastUpdated: String?,
    val rank: Double? = null,
)
