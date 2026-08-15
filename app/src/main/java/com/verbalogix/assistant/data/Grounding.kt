package com.verbalogix.assistant.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a Harness returns alongside an answer: where it came from, and whether it came
 * from anywhere at all.
 *
 * These are ADDITIVE fields on the standard OpenAI-compatible response, which is what
 * lets one parser serve both a direct llama.cpp server and a Harness. A direct server
 * simply omits them and every field here stays null.
 *
 * See contracts/harness-v0.md, and contracts/mock/harness_mock.py for the runnable
 * version -- on disagreement the mock is authoritative, because it is the thing that
 * gets tested.
 */
@Serializable
data class Citation(
    /** Matches the `[1]` markers in the answer text, so the UI links them without parsing prose. */
    val n: Int = 0,
    val document: String = "",
    val page: Int? = null,
    val quote: String = "",
    @SerialName("document_id") val documentId: String = "",
    @SerialName("chunk_id") val chunkId: String = "",
    val score: Double? = null,
)

/**
 * The evidence that an answer was grounded -- or the evidence that it was not.
 *
 * `grounded` is the load-bearing field and it is required on every Harness response.
 * When retrieval finds nothing usable it must be false with `chunksUsed = 0`, and the
 * UI must render that DIFFERENTLY from a grounded answer.
 *
 * That requirement is the whole point of the arrangement. An ungrounded answer shown
 * identically to a cited one makes a confident guess indistinguishable from a fact,
 * which is the most damaging thing this interface could do. A MISSING receipt is
 * therefore a bug to surface, not a field to quietly skip.
 */
@Serializable
data class Receipt(
    val grounded: Boolean = false,
    @SerialName("chunks_retrieved") val chunksRetrieved: Int = 0,
    @SerialName("chunks_used") val chunksUsed: Int = 0,
    /** The pack and version that answered, e.g. "handbook-2026@3.1.0". */
    val kpack: String = "",
    @SerialName("retrieval_ms") val retrievalMs: Int = 0,
)

/**
 * Citations are persisted as JSON in a single column rather than a normalised table.
 *
 * They are a display artefact of one message -- always read with it, never queried
 * across messages, and never the source of truth. Normalising would buy a join and a
 * migration for no query that anyone will run. It also keeps the Foundry boundary
 * clean: Room stores what was SHOWN, not the knowledge it was drawn from.
 */
object CitationCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(citations: List<Citation>): String? =
        if (citations.isEmpty()) null else json.encodeToString(citations)

    /**
     * Never throws. A row written by an older build, or corrupted, degrades to "no
     * citations" -- which renders as an uncited answer rather than crashing the
     * transcript. Losing a citation is bad; losing the conversation is worse.
     */
    fun decode(raw: String?): List<Citation> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<Citation>>(raw) }.getOrDefault(emptyList())
}
