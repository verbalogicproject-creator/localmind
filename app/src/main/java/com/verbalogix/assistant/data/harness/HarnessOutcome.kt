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
