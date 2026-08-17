package com.verbalogix.assistant.data.harness

/**
 * Schema identifiers and closed vocabularies, in one place.
 *
 * Written as constants rather than inline literals so that a value appearing in a
 * decoder, a negotiation check and a test is provably the same value. Every one is
 * transcribed from a schema in the Foundry's `local_client/schemas/` directory.
 */
object SchemaIds {

    const val OPERATION_RESPONSE = "knowledge-foundry-operation-response/3.0"
    const val CAPABILITIES = "knowledge-foundry-capabilities/3.0"
    const val EXPERT_CATALOG = "knowledge-foundry-expert-catalog/3.0"
    const val EXPERT_RELEASE_SUMMARY = "knowledge-foundry-expert-release-summary/3.0"

    /**
     * Accepted since its golden arrived.
     *
     * It spent one commit named here as a deliberate absence, kept out of
     * [SchemaNegotiation.ACCEPTED] because a decoder verified only against payloads its
     * author invented is verified against its author's assumptions. The rule held, the
     * golden came, and the id moved -- in that order.
     */
    const val EXPERT_RELEASE_DETAIL = "knowledge-foundry-expert-release-detail/3.0"

    /**
     * Retrieval, carried as a 2.0 body inside a 3.0 envelope.
     *
     * Not a typo and not a downgrade: `operation-response/3.0` names `query-result-2.0`
     * in its own `oneOf`. Retrieval was specified before the expert surfaces and did not
     * need re-versioning. Worth a constant of its own because "everything inside /3.0 is
     * /3.0" is the natural assumption and it is wrong here.
     */
    const val QUERY_RESULT = "knowledge-foundry-query-result/2.0"

    // ── Stage 3D: the assistant turn ───────────────────────────────────────
    //
    // ADDITIVE, NOT AN UPGRADE. `/1.0`, `/2.0` and `/3.0` responses stay byte-compatible;
    // `/4.0` adds one operation and a separate schema registry. Exactly one route
    // negotiates it, and the existing surfaces are untouched.

    const val OPERATION_RESPONSE_TURN = "knowledge-foundry-operation-response/4.0"
    const val ASSISTANT_TURN = "knowledge-foundry-assistant-turn/1.0"
    const val ASSISTANT_TURN_REQUEST = "knowledge-foundry-assistant-turn-request/1.0"
    const val ASSISTANT_TURN_RECEIPT = "knowledge-foundry-assistant-turn-receipt/1.0"
    const val GROUNDED_ANSWER = "knowledge-foundry-grounded-answer/1.0"
    const val PROVIDER_OBSERVATION = "knowledge-foundry-provider-observation/1.0"

    const val OP_ASSISTANT_TURN_FINALIZE = "assistant.turn.finalize"

    /**
     * What a finalised turn can be.
     *
     * `grounded` is the ONLY one that may be presented as an answer. `abstained` and
     * `refused` are the Foundry declining to bind a provider observation at all, and in
     * both cases it forbids one having been made.
     */
    const val TURN_GROUNDED = "grounded"
    val TURN_DISPOSITIONS = setOf("grounded", "abstained", "refused")

    /** A segment either asserts something and must cite, or hedges and need not. */
    const val SEGMENT_CLAIM = "claim"
    const val SEGMENT_UNCERTAINTY = "uncertainty"
    val SEGMENT_KINDS = setOf(SEGMENT_CLAIM, SEGMENT_UNCERTAINTY)

    /**
     * Only `stop` may become a grounded answer.
     *
     * `length` and `timeout` mean the model was cut off mid-thought, `refusal` and `error`
     * that it produced nothing usable. The Foundry refuses all four, and this client
     * refuses them first so the failure is named locally rather than returned as an
     * opaque `provider-observation-invalid`.
     */
    const val FINISH_STOP = "stop"
    val FINISH_REASONS = setOf("stop", "length", "timeout", "refusal", "error")

    /** The Harness's own verdict on the evidence. This client never computes it. */
    val ANSWERABILITY = setOf("supported", "conflicted", "insufficient", "refused", "failed")

    /** Per-item, and narrower than [ANSWERABILITY]: an item is not "refused". */
    val KNOWLEDGE_STATUS = setOf("supported", "conflicted", "insufficient")

    val UNCERTAINTY = setOf("none", "declared", "not_observed")

    /**
     * Consts the Foundry states about every packet and item.
     *
     * Checked rather than assumed: a response that omits or weakens either is refused,
     * because they are the contract's instruction that retrieved text is DATA -- never a
     * prompt, never an instruction, and never authority to act.
     */
    const val CONTENT_TREATMENT = "inert-untrusted-data"
    const val AUTHORITY_BOUNDARY = "context-does-not-grant-effect-authority"

    /** `capabilities/3.0` pins this exactly; a different runtime is a different contract. */
    const val RUNTIME_CONTRACT = "0.3.2"

    const val DISPOSITION_SUCCEEDED = "succeeded"
    val DISPOSITIONS = setOf("succeeded", "failed", "abstained", "refused")

    /** `trust_state` is a const in the schema: the catalog lists trusted releases only. */
    const val TRUST_STATE_TRUSTED = "trusted"

    val MOUNT_STATES = setOf("active", "installed-inactive")
}

/** The result of trying to read a Harness response. */
sealed interface HarnessOutcome<out T> {

    data class Decoded<T>(val value: T) : HarnessOutcome<T>

    /**
     * The Harness answered, and the answer was not success.
     *
     * Distinct from [Refused] because nothing is wrong with the exchange: a `refused` or
     * `abstained` disposition is the Harness working correctly and declining, and
     * presenting it as a client-side failure would blame the wrong component.
     */
    data class Unsuccessful(val disposition: String, val errorCode: String?) : HarnessOutcome<Nothing>

    /** This client would not read the response. Always carries which check stopped it. */
    data class Refused(val refusal: HarnessRefusal) : HarnessOutcome<Nothing>
}

/**
 * Why a response was refused.
 *
 * Enumerated rather than collapsed into a string, because these are acted on differently:
 * a runtime-contract mismatch needs a software update, a correlation failure is worth
 * retrying once, and a malformed identity means never routing on that value. A single
 * "decode failed" would flatten all of that into one unhelpful dialog.
 *
 * None of these is recoverable by pairing again -- see [PairAgainCause] for the ones that
 * are. Keeping the two vocabularies apart is what stops a version problem from being
 * presented as a session problem and looping the user through re-pairing.
 */
sealed interface HarnessRefusal {

    /** The declared payload version is not one this build reads. */
    data class Schema(val verdict: SchemaVerdict) : HarnessRefusal

    /** A `/3.0` document from a Harness reporting a different runtime contract. */
    data class RuntimeContract(val declared: String, val expected: String) : HarnessRefusal

    /** The reply answers a different operation than the one that was asked. */
    data class OperationMismatch(val declared: String, val expected: String) : HarnessRefusal

    /** A disposition outside the closed set. */
    data class UnknownDisposition(val declared: String) : HarnessRefusal

    /** The envelope succeeded but its result is a different document than expected. */
    data class ResultSchemaMismatch(val declared: String?, val expected: String) : HarnessRefusal

    /** A trust state other than the schema's `trusted` const. */
    data class TrustState(val declared: String) : HarnessRefusal

    /** A mount state outside `active` / `installed-inactive`. */
    data class MountState(val declared: String) : HarnessRefusal

    /** A pack or release id that is not `kf:<kind>:<sha256>`. */
    data class MalformedIdentity(val declared: String) : HarnessRefusal

    /** Unknown field, missing required key, or malformed JSON. Carries the parser's own note. */
    data class Undecodable(val detail: String) : HarnessRefusal

    /**
     * Shown to a person. Never blames the user, and never suggests pairing -- none of
     * these is fixed by pairing again.
     */
    val reason: String
        get() = when (this) {
            is Schema -> verdict.reason
            is RuntimeContract ->
                "The Knowledge Foundry reports runtime contract $declared; this build " +
                    "was written for $expected. Nothing was read from the reply."
            is OperationMismatch ->
                "A reply for \"$declared\" arrived where \"$expected\" was expected, so " +
                    "it was discarded."
            is UnknownDisposition ->
                "The reply reported an outcome this build does not recognise " +
                    "(\"$declared\"). Nothing was read from it."
            is ResultSchemaMismatch ->
                "The reply carried ${declared ?: "no identifiable payload"} where " +
                    "$expected was expected. Nothing was read from it."
            is TrustState ->
                "An expert declared trust state \"$declared\", which this build does " +
                    "not recognise. The whole catalog was refused rather than shown in " +
                    "part."
            is MountState ->
                "An expert declared mount state \"$declared\", which this build does " +
                    "not recognise. The whole catalog was refused rather than shown in " +
                    "part."
            is MalformedIdentity ->
                "An expert identity was not in the expected form, so it was not used."
            is Undecodable ->
                "The reply could not be read as a Knowledge Foundry response. Nothing " +
                    "was taken from it."
        }
}
