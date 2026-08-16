package com.verbalogix.assistant.data.harness

import com.verbalogix.assistant.data.harness.wire.CapabilitiesResult
import com.verbalogix.assistant.data.harness.wire.ExpertCatalogResult
import com.verbalogix.assistant.data.harness.wire.OperationResponseEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns Harness bytes into typed values, or refuses and says why.
 *
 * STRICT BY CONFIGURATION, not by convention. [STRICT] leaves `ignoreUnknownKeys` at its
 * default of false and `coerceInputValues` off, so an unknown field throws rather than
 * being dropped and a null in a non-null position throws rather than becoming a default.
 * Both are the behaviour the closed schemas ask for: every one of them is
 * `additionalProperties: false`, and a document carrying a field this build has never
 * heard of was written to an agreement this build does not share.
 *
 * THE ORDER OF CHECKS IS THE DESIGN. Envelope schema, then disposition, then operation,
 * then the inner result schema, and only then the payload. Each step is cheap and each
 * one can end the decode with a specific reason, so a refusal names the first thing that
 * was wrong rather than a parse error twelve fields deep.
 */
object HarnessDecoder {

    val STRICT: Json = Json {
        // Everything here is the default except explicitness. Written out because the
        // defaults ARE the contract, and a future edit that flips one should have to
        // delete a line that says why it exists.
        ignoreUnknownKeys = false   // additionalProperties: false, honoured
        coerceInputValues = false   // a null is a null, not a silent default
        isLenient = false           // no unquoted keys, no relaxed literals
        allowStructuredMapKeys = false
        explicitNulls = true
    }

    /** Identity is `kf:<kind>:<sha256>` across every Foundry schema that constrains it. */
    private val IDENTITY = Regex("""^kf:[a-z0-9-]+:[0-9a-f]{64}$""")

    /**
     * Validate an identity string.
     *
     * STRICTER THAN `expert-release-summary/3.0` DECLARES, deliberately and visibly.
     * That schema types `pack_id` and `release_id` as plain strings, while
     * `mount-registry/2.0` constrains the same values to `^kf:[a-z0-9-]+:[0-9a-f]{64}$`
     * and every observed identifier -- including `request_id` in both goldens -- matches
     * it. An identity is what a detail route is keyed by, so accepting an arbitrary
     * string here would let a malformed one become a route argument.
     *
     * Recorded as a deliberate divergence: if the Foundry ever emits an identity outside
     * this shape, this client refuses a valid document, and THIS is the line to revisit.
     */
    fun isWellFormedIdentity(value: String): Boolean = IDENTITY.matches(value)

    /**
     * Decode a capabilities response.
     *
     * @param operationId the operation this response is expected to answer. Checked
     *   rather than assumed: a reply carrying a different `operation_id` is a correlation
     *   failure, and treating it as the answer would attribute one operation's result to
     *   another.
     */
    internal fun decodeCapabilities(raw: String): HarnessOutcome<CapabilitiesResult> =
        decodeResult(raw, "harness.capabilities", SchemaIds.CAPABILITIES) { element ->
            STRICT.decodeFromJsonElement(CapabilitiesResult.serializer(), element)
        }.also { outcome ->
            if (outcome is HarnessOutcome.Decoded) {
                // The runtime contract is part of the agreement, not a version string to
                // display. A Harness reporting a contract this build was not written
                // against is refused even though every field parsed.
                val contract = outcome.value.runtimeContract
                if (contract != SchemaIds.RUNTIME_CONTRACT) {
                    return HarnessOutcome.Refused(
                        HarnessRefusal.RuntimeContract(contract, SchemaIds.RUNTIME_CONTRACT),
                    )
                }
            }
        }

    /** Decode an expert catalog response. */
    internal fun decodeExpertCatalog(raw: String): HarnessOutcome<ExpertCatalogResult> =
        decodeResult(raw, "expert.catalog.list", SchemaIds.EXPERT_CATALOG) { element ->
            STRICT.decodeFromJsonElement(ExpertCatalogResult.serializer(), element)
        }.also { outcome ->
            if (outcome is HarnessOutcome.Decoded) {
                for (release in outcome.value.releases) {
                    // `trust_state` is a const in the schema: the catalog never lists an
                    // untrusted release. Any other value means this is not the document
                    // the contract describes, so it is refused rather than displayed
                    // with a warning badge.
                    if (release.trustState != SchemaIds.TRUST_STATE_TRUSTED) {
                        return HarnessOutcome.Refused(
                            HarnessRefusal.TrustState(release.trustState),
                        )
                    }
                    if (release.mountState !in SchemaIds.MOUNT_STATES) {
                        return HarnessOutcome.Refused(
                            HarnessRefusal.MountState(release.mountState),
                        )
                    }
                    if (!isWellFormedIdentity(release.packId) ||
                        !isWellFormedIdentity(release.releaseId)
                    ) {
                        return HarnessOutcome.Refused(
                            HarnessRefusal.MalformedIdentity(release.releaseId),
                        )
                    }
                }
            }
        }

    private inline fun <T> decodeResult(
        raw: String,
        expectedOperation: String,
        expectedResultSchema: String,
        decode: (kotlinx.serialization.json.JsonElement) -> T,
    ): HarnessOutcome<T> {
        val envelope = try {
            STRICT.decodeFromString(OperationResponseEnvelope.serializer(), raw)
        } catch (e: Exception) {
            // Includes unknown fields, missing required keys and malformed JSON. The
            // message is kept because it names the offending key, and it contains no
            // credential -- the token never appears in a response body.
            return HarnessOutcome.Refused(HarnessRefusal.Undecodable(e.message ?: "unreadable"))
        }

        SchemaNegotiation.negotiate(envelope.schema).let { verdict ->
            if (!verdict.isAccepted) return HarnessOutcome.Refused(HarnessRefusal.Schema(verdict))
        }

        if (envelope.operationId != expectedOperation) {
            return HarnessOutcome.Refused(
                HarnessRefusal.OperationMismatch(envelope.operationId, expectedOperation),
            )
        }

        if (envelope.disposition !in SchemaIds.DISPOSITIONS) {
            return HarnessOutcome.Refused(HarnessRefusal.UnknownDisposition(envelope.disposition))
        }

        if (envelope.disposition != SchemaIds.DISPOSITION_SUCCEEDED) {
            // failed / abstained / refused are legitimate outcomes, not decode errors.
            // Surfaced as themselves so the UI can say what the Harness said.
            return HarnessOutcome.Unsuccessful(envelope.disposition, envelope.error?.code)
        }

        val result = envelope.result
            ?: return HarnessOutcome.Refused(HarnessRefusal.Undecodable("succeeded with no result"))

        val innerSchema = (result as? JsonObject)?.get("schema")?.jsonPrimitive?.contentOrNullSafe()
        SchemaNegotiation.negotiate(innerSchema).let { verdict ->
            if (!verdict.isAccepted) return HarnessOutcome.Refused(HarnessRefusal.Schema(verdict))
        }
        if (innerSchema != expectedResultSchema) {
            return HarnessOutcome.Refused(
                HarnessRefusal.ResultSchemaMismatch(innerSchema, expectedResultSchema),
            )
        }

        return try {
            HarnessOutcome.Decoded(decode(result))
        } catch (e: Exception) {
            HarnessOutcome.Refused(HarnessRefusal.Undecodable(e.message ?: "unreadable result"))
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (isString) content else null
}
