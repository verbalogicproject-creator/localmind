package com.verbalogix.assistant.data.harness

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the three objects Localmind must digest, and the body that carries them.
 *
 * ASSEMBLED AS `JsonObject`, NOT AS `@Serializable` CLASSES, and the reason is the digests.
 * Each of these objects states a SHA-256 of itself which the Foundry recomputes from the
 * bytes it receives; if the object were a data class it would be encoded once by kotlinx to
 * be hashed and again to be sent, and any disagreement between those two encoders — key
 * order, escaping, a default that appears in one and not the other — would produce a
 * request whose digest does not match its own contents. One representation, hashed and sent
 * by the same code, removes that failure mode by construction.
 *
 * WHAT IS DIGESTED AND WHEN:
 *
 *   answer        `answer_sha256` over the answer without it
 *   observation   `observation_id` over everything except BOTH self-fields, then
 *                 `observation_sha256` over everything except itself — so the identity is
 *                 inside the digest's basis, and the two bases are not the same object
 *   request       `request_sha256` over the request without it
 *
 * The observation's two-step is the easy thing to get wrong: computing both from the same
 * basis yields two values that look right and match nothing.
 */
object AssistantTurnRequest {

    const val PATH_ASSISTANT_TURN = "/v1/assistant/turns"

    /**
     * The provider identity Localmind reports for itself.
     *
     * Localmind owns provider selection and provider I/O; the Foundry never calls a model.
     * This names WHO made the observation, and it is a constant because there is one
     * answer: this app, through its own loopback seam.
     */
    const val PROVIDER_ID = "localmind-lfm"

    /** llama-swap over loopback, in the contract's own vocabulary. */
    const val ENDPOINT_KIND = "loopback-openai-compatible"
    const val RUNTIME_ID = "llama-swap"

    /**
     * One segment of an answer, before it becomes canonical JSON.
     *
     * [evidenceIds] must be sorted and unique — the Foundry rejects a segment whose
     * citations are not — and that is enforced when the answer is built rather than left to
     * the caller.
     */
    data class Segment(val kind: String, val text: String, val evidenceIds: List<String>)

    /**
     * `knowledge-foundry-grounded-answer/1.0`, sealed.
     *
     * `text` is DERIVED, never passed in: the contract requires it to equal the ordered
     * segment texts joined by two line feeds, and computing it here means the client cannot
     * send an answer whose visible text says something its segments do not. That check is
     * the reason citations mean anything — a sentence outside a segment would appear on
     * screen carrying no sources.
     */
    fun groundedAnswer(segments: List<Segment>): JsonObject {
        require(segments.isNotEmpty()) { "an answer needs at least one segment" }
        require(segments.size <= MAX_SEGMENTS) { "at most $MAX_SEGMENTS segments" }
        for (segment in segments) {
            require(segment.kind in SchemaIds.SEGMENT_KINDS) { "unknown kind ${segment.kind}" }
            require(segment.text.isNotEmpty()) { "a segment carries no text" }
            require(segment.evidenceIds.size <= MAX_CITATIONS) {
                "at most $MAX_CITATIONS citations per segment"
            }
            require(segment.evidenceIds == segment.evidenceIds.sorted().distinct()) {
                "segment citations must be sorted and unique"
            }
            require(segment.evidenceIds.all(HarnessDecoder::isWellFormedIdentity)) {
                "a citation is not a kf: identity"
            }
            require(segment.kind != SchemaIds.SEGMENT_CLAIM || segment.evidenceIds.isNotEmpty()) {
                "a claim segment must cite at least one evidence id"
            }
        }
        val answer = buildJsonObject {
            put("schema", SchemaIds.GROUNDED_ANSWER)
            putJsonArray("segments") {
                for (segment in segments) {
                    add(
                        buildJsonObject {
                            putJsonArray("evidence_ids") {
                                for (id in segment.evidenceIds) add(id)
                            }
                            put("kind", segment.kind)
                            put("text", segment.text)
                        },
                    )
                }
            }
            put("text", segments.joinToString("\n\n") { it.text })
        }
        return CanonicalJson.seal(answer, "answer_sha256")
    }

    /**
     * `knowledge-foundry-provider-observation/1.0`, sealed and identified.
     *
     * `tool_calls` is EMPTY AND CANNOT BE OTHERWISE. The schema caps it at zero items and
     * the Foundry refuses a non-empty one; there is no parameter here that could fill it,
     * so no future edit can introduce a tool call by passing an argument.
     *
     * @param finishReason from the provider, unmodified. Only `stop` can become grounded,
     *   and that is checked by the caller before this is built — reshaping a `length` into
     *   a `stop` here is exactly the lie the receipt exists to prevent.
     * @param observedAt UTC, whole seconds, `…Z`. No sub-second precision: the schema's
     *   pattern forbids it and a client clock is not evidence of anything finer.
     */
    fun providerObservation(
        modelId: String,
        artifactSha256: String?,
        runtimeVersion: String?,
        templateId: String,
        templateSha256: String,
        packetId: String,
        packetSha256: String,
        answer: JsonObject,
        finishReason: String,
        observedAt: String,
    ): JsonObject {
        require(finishReason in SchemaIds.FINISH_REASONS) { "unknown finish reason" }
        require(OBSERVED_AT.matches(observedAt)) { "observed_at must be UTC whole seconds" }
        require(TEMPLATE_ID.matches(templateId)) { "template_id is malformed" }
        require(DIGEST.matches(templateSha256)) { "template_sha256 is malformed" }
        require(HarnessDecoder.isWellFormedIdentity(packetId)) { "packet_id is malformed" }
        require(DIGEST.matches(packetSha256)) { "packet_sha256 is malformed" }

        val basis = buildJsonObject {
            put("answer", answer)
            putJsonObject("evidence") {
                put("packet_id", packetId)
                put("packet_sha256", packetSha256)
            }
            put("finish_reason", finishReason)
            putJsonObject("model") {
                put("artifact_sha256", artifactSha256?.let(::JsonPrimitive) ?: JsonNull)
                put("endpoint_kind", ENDPOINT_KIND)
                put("model_id", modelId)
                put("runtime_id", RUNTIME_ID)
                put("runtime_version", runtimeVersion?.let(::JsonPrimitive) ?: JsonNull)
            }
            put("observed_at", observedAt)
            putJsonObject("prompt_template") {
                put("template_id", templateId)
                put("template_sha256", templateSha256)
            }
            put("provider_id", PROVIDER_ID)
            put("schema", SchemaIds.PROVIDER_OBSERVATION)
            put("tool_calls", buildJsonArray { })
        }
        // TWO BASES, IN ORDER. The identity is computed over the basis alone; the digest is
        // then computed over the basis PLUS the identity. Using one basis for both produces
        // two plausible values that the Foundry matches neither of.
        val identified = JsonObject(
            basis + ("observation_id" to
                JsonPrimitive(CanonicalJson.identity("provider-observation", basis))),
        )
        return CanonicalJson.seal(identified, "observation_sha256")
    }

    /**
     * `knowledge-foundry-assistant-turn-request/1.0`, sealed.
     *
     * `expected_evidence` is what makes drift detectable. The Foundry RE-RUNS the exact
     * query against current mounted state and compares what it gets to these four values;
     * if a pack was activated, deactivated or updated between the retrieval and this call,
     * the digests differ and the turn fails closed rather than binding an answer to
     * evidence that no longer exists.
     *
     * @param queryRequest the SAME object that produced the retrieval, not a rebuild of it.
     * @param observation null for abstention and refusal, where the contract forbids a
     *   provider having run at all.
     */
    fun turnRequest(
        queryRequest: JsonObject,
        queryResultSha256: String,
        packetId: String,
        packetSha256: String,
        mountRegistrySha256: String,
        observation: JsonObject?,
    ): JsonObject {
        for (digest in listOf(queryResultSha256, packetSha256, mountRegistrySha256)) {
            require(DIGEST.matches(digest)) { "a digest is malformed" }
        }
        require(HarnessDecoder.isWellFormedIdentity(packetId)) { "packet_id is malformed" }
        val request = buildJsonObject {
            putJsonObject("expected_evidence") {
                put("mount_registry_sha256", mountRegistrySha256)
                put("packet_id", packetId)
                put("packet_sha256", packetSha256)
                put("query_result_sha256", queryResultSha256)
            }
            put("provider_observation", observation ?: JsonNull)
            put("query_request", queryRequest)
            put("schema", SchemaIds.ASSISTANT_TURN_REQUEST)
        }
        return CanonicalJson.seal(request, "request_sha256")
    }

    /**
     * The HTTP body: exactly `{"turn_request": …}` and nothing else.
     *
     * "No additional wrapper fields are accepted." Sent as a canonical LINE, per the freeze
     * document's "terminated by one line feed when stored or transported" — the terminator
     * is not part of any digest, which is computed over the unterminated form.
     */
    fun body(turnRequest: JsonObject): String =
        CanonicalJson.line(buildJsonObject { put("turn_request", turnRequest) })

    private val DIGEST = Regex("^[0-9a-f]{64}$")
    private val OBSERVED_AT = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""")
    private val TEMPLATE_ID = Regex("^[a-z0-9][a-z0-9._/-]{0,127}$")

    /** Both caps are the schema's, not a client preference. */
    private const val MAX_SEGMENTS = 64
    private const val MAX_CITATIONS = 8
}
