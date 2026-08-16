package com.verbalogix.assistant.data.memory

/**
 * One row from the `facts` table. Facts are crystallized claims with an
 * optional supersession chain -- Ev1 only reads `active` facts.
 */
data class Fact(
    val id: String,
    val claim: String,
    val reason: String?,
    val status: String,
    val supersededBy: String?,
    val tags: List<String>,
    val createdAt: String,
    val updatedAt: String,
    val rank: Double? = null,
)
