package com.verbalogix.assistant.data.harness

import com.verbalogix.assistant.data.harness.wire.AssistantTurnResult
import com.verbalogix.assistant.data.harness.wire.CapabilitiesResult
import com.verbalogix.assistant.data.harness.wire.ExpertCatalogResult
import com.verbalogix.assistant.data.harness.wire.ExpertReleaseDetailResult
import com.verbalogix.assistant.data.harness.wire.EvidencePacket
import com.verbalogix.assistant.data.harness.wire.ExpertReleaseSummary
import com.verbalogix.assistant.data.harness.wire.QueryResult
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
                // Shared with the detail path. `trust_state` is a const in the schema, so
                // the catalog never lists an untrusted release -- any other value means
                // this is not the document the contract describes, and the WHOLE catalog
                // is refused rather than shown minus the offending entry.
                for (release in outcome.value.releases) {
                    validateRelease(release)?.let { return HarnessOutcome.Refused(it) }
                }
            }
        }

    /**
     * Decode a release-detail response.
     *
     * Reuses the same release validation as the catalog rather than repeating it: the
     * detail carries the identical `expert-release-summary/3.0` object, so a second copy
     * of the trust and identity checks could drift from the first and let a value through
     * on one path that the other refuses.
     */
    internal fun decodeExpertReleaseDetail(raw: String): HarnessOutcome<ExpertReleaseDetailResult> =
        decodeResult(raw, "expert.release.inspect", SchemaIds.EXPERT_RELEASE_DETAIL) { element ->
            STRICT.decodeFromJsonElement(ExpertReleaseDetailResult.serializer(), element)
        }.also { outcome ->
            if (outcome is HarnessOutcome.Decoded) {
                validateRelease(outcome.value.release)?.let { return HarnessOutcome.Refused(it) }
                // Dependencies are release identities and become navigable, so they get
                // the same scrutiny as the release's own -- an unchecked one would reach
                // a route argument by a different door.
                for (dependency in outcome.value.install.dependencyReleaseIds) {
                    if (!isWellFormedIdentity(dependency)) {
                        return HarnessOutcome.Refused(HarnessRefusal.MalformedIdentity(dependency))
                    }
                }
                val lifecycle = outcome.value.lifecycle
                for (id in listOfNotNull(
                    lifecycle.predecessorReleaseId, lifecycle.rollbackReleaseId,
                )) {
                    if (!isWellFormedIdentity(id)) {
                        return HarnessOutcome.Refused(HarnessRefusal.MalformedIdentity(id))
                    }
                }
            }
        }

    /**
     * Decode a retrieval response.
     *
     * VALIDATES THE CONSTS RATHER THAN TRUSTING THEM. `content_treatment` and
     * `authority_boundary` are the Foundry telling this client that retrieved text is
     * inert data with no authority attached. A packet that omits or weakens either is not
     * a packet this client understands, so it is refused -- checking them is the whole
     * point of their being in the schema.
     *
     * Closed vocabularies are checked too. `answerability` is the Harness's verdict and
     * the one thing the UI must never compute; a value outside the enum means the
     * document is not the one this contract describes.
     */
    internal fun decodeQueryResult(raw: String): HarnessOutcome<QueryResult> =
        decodeResult(
            raw,
            "query.retrieve",
            SchemaIds.QUERY_RESULT,
            // AN ABSTENTION STILL CARRIES ITS PACKET, and the packet is where the reason
            // lives. The facade derives the envelope's disposition FROM
            // `evidence_packet.disposition` and seals the result either way, while
            // `error` stays null -- so treating a decline as an empty outcome here would
            // report "the expert abstained" with no account of why, discarding a
            // `reason_code` the server did send. Retrieval is the only operation with
            // this shape; the others fail with a result of null.
            resultSurvivesDecline = true,
        ) { element ->
            STRICT.decodeFromJsonElement(QueryResult.serializer(), element)
        }.also { outcome ->
            if (outcome is HarnessOutcome.Decoded) {
                validatePacket(outcome.value.evidencePacket)?.let {
                    return HarnessOutcome.Refused(it)
                }
            }
        }

    /**
     * Decode a finalised assistant turn.
     *
     * THE RECEIPT IS CHECKED AGAINST THE TURN, not merely displayed. A receipt is what
     * makes "grounded" a claim someone else can verify, so one that disagrees with the body
     * it accompanies is worse than none: it looks like proof. The turn is refused when they
     * diverge.
     *
     * The nullable bindings are checked BY DISPOSITION rather than accepted as optional.
     * `grounded` requires a model, a prompt template, an answer and all four provider
     * digests; `abstained` and `refused` require their ABSENCE, because the Foundry forbids
     * a provider having run at all in those cases. Accepting either shape for either
     * disposition would let "the model was never called" and "the model was called and the
     * record was lost" decode to the same value.
     */
    internal fun decodeAssistantTurn(raw: String): HarnessOutcome<AssistantTurnResult> =
        decodeResult(
            raw,
            SchemaIds.OP_ASSISTANT_TURN_FINALIZE,
            SchemaIds.ASSISTANT_TURN,
            // Abstention and refusal are real turns with real receipts, not empty outcomes.
            resultSurvivesDecline = true,
        ) { element ->
            STRICT.decodeFromJsonElement(AssistantTurnResult.serializer(), element)
        }.also { outcome ->
            if (outcome is HarnessOutcome.Decoded) {
                validateTurn(outcome.value)?.let { return HarnessOutcome.Refused(it) }
            }
        }

    private fun validateTurn(turn: AssistantTurnResult): HarnessRefusal? {
        if (turn.disposition !in SchemaIds.TURN_DISPOSITIONS) {
            return HarnessRefusal.UnknownDisposition(turn.disposition)
        }
        if (turn.evidence.answerability !in SchemaIds.ANSWERABILITY) {
            return HarnessRefusal.Undecodable(
                "unknown answerability \"${turn.evidence.answerability}\"",
            )
        }
        val receipt = turn.receipt
        if (receipt.schema != SchemaIds.ASSISTANT_TURN_RECEIPT) {
            return HarnessRefusal.ResultSchemaMismatch(
                receipt.schema, SchemaIds.ASSISTANT_TURN_RECEIPT,
            )
        }
        // The receipt must describe THIS turn. Each of these is a binding the receipt exists
        // to make; a mismatch means the two documents are about different events.
        if (receipt.disposition != turn.disposition ||
            receipt.packetId != turn.evidence.packetId ||
            receipt.packetSha256 != turn.evidence.packetSha256 ||
            receipt.mountRegistrySha256 != turn.evidence.mountRegistrySha256 ||
            receipt.queryResultSha256 != turn.queryResultSha256
        ) {
            return HarnessRefusal.Undecodable(
                "the receipt does not describe the turn it accompanies",
            )
        }
        for (id in listOf(turn.turnId, receipt.receiptId, turn.evidence.packetId)) {
            if (!isWellFormedIdentity(id)) return HarnessRefusal.MalformedIdentity(id)
        }
        for (id in receipt.citedEvidenceIds) {
            if (!isWellFormedIdentity(id)) return HarnessRefusal.MalformedIdentity(id)
        }

        val grounded = turn.disposition == SchemaIds.TURN_GROUNDED
        val bound = listOf(
            turn.model, turn.promptTemplate, turn.answer,
            receipt.providerObservationSha256, receipt.modelIdentitySha256,
            receipt.promptTemplateSha256, receipt.answerSha256,
        )
        if (grounded && bound.any { it == null }) {
            return HarnessRefusal.Undecodable(
                "a grounded turn must name the model, template and answer it came from",
            )
        }
        if (!grounded && bound.any { it != null }) {
            return HarnessRefusal.Undecodable(
                "a \"${turn.disposition}\" turn must carry no provider binding",
            )
        }

        val answer = turn.answer ?: return null
        if (answer.schema != SchemaIds.GROUNDED_ANSWER) {
            return HarnessRefusal.ResultSchemaMismatch(answer.schema, SchemaIds.GROUNDED_ANSWER)
        }
        // Re-checked rather than trusted, because this is the exact property that makes a
        // citation meaningful: the rendered text IS the segments, so no sentence can reach
        // the screen outside a segment that carries its sources.
        if (answer.text != answer.segments.joinToString("\n\n") { it.text }) {
            return HarnessRefusal.Undecodable("the answer text does not close its segments")
        }
        val cited = sortedSetOf<String>()
        for (segment in answer.segments) {
            if (segment.kind !in SchemaIds.SEGMENT_KINDS) {
                return HarnessRefusal.Undecodable("unknown segment kind \"${segment.kind}\"")
            }
            if (segment.kind == SchemaIds.SEGMENT_CLAIM && segment.evidenceIds.isEmpty()) {
                return HarnessRefusal.Undecodable("a claim segment carries no evidence")
            }
            for (id in segment.evidenceIds) {
                if (!isWellFormedIdentity(id)) return HarnessRefusal.MalformedIdentity(id)
            }
            cited += segment.evidenceIds
        }
        if (cited.toList() != receipt.citedEvidenceIds) {
            return HarnessRefusal.Undecodable("the receipt's citations do not match the answer's")
        }
        return null
    }

    private fun validatePacket(packet: EvidencePacket): HarnessRefusal? {
        if (packet.contentTreatment != SchemaIds.CONTENT_TREATMENT) {
            return HarnessRefusal.Undecodable(
                "evidence packet did not declare content_treatment " +
                    "\"${SchemaIds.CONTENT_TREATMENT}\"",
            )
        }
        if (packet.authorityBoundary != SchemaIds.AUTHORITY_BOUNDARY) {
            return HarnessRefusal.Undecodable(
                "evidence packet did not declare the expected authority boundary",
            )
        }
        if (packet.answerability !in SchemaIds.ANSWERABILITY) {
            return HarnessRefusal.Undecodable("unknown answerability \"${packet.answerability}\"")
        }
        if (packet.disposition !in SchemaIds.DISPOSITIONS) {
            return HarnessRefusal.UnknownDisposition(packet.disposition)
        }
        for (id in listOf(packet.packetId, packet.trace.traceId)) {
            if (!isWellFormedIdentity(id)) return HarnessRefusal.MalformedIdentity(id)
        }
        for (item in packet.items) {
            // PER ITEM TOO. A single item carrying a different treatment would otherwise
            // travel with the rest of a packet that declared the right one.
            if (item.contentTreatment != SchemaIds.CONTENT_TREATMENT) {
                return HarnessRefusal.Undecodable("an evidence item is not declared inert")
            }
            if (item.knowledgeStatus !in SchemaIds.KNOWLEDGE_STATUS) {
                return HarnessRefusal.Undecodable(
                    "unknown knowledge_status \"${item.knowledgeStatus}\"",
                )
            }
            if (item.uncertainty !in SchemaIds.UNCERTAINTY) {
                return HarnessRefusal.Undecodable("unknown uncertainty \"${item.uncertainty}\"")
            }
            val identities = listOf(
                item.evidenceId, item.candidateId, item.packId, item.releaseId,
                item.revisionId, item.logicalId,
            ) + item.graphPathIds + item.contradictionIds + item.transformationIds
            for (id in identities) {
                if (!isWellFormedIdentity(id)) return HarnessRefusal.MalformedIdentity(id)
            }
        }
        return null
    }

    /** The checks every release summary must pass, wherever it arrives from. */
    private fun validateRelease(release: ExpertReleaseSummary): HarnessRefusal? = when {
        release.trustState != SchemaIds.TRUST_STATE_TRUSTED ->
            HarnessRefusal.TrustState(release.trustState)

        release.mountState !in SchemaIds.MOUNT_STATES ->
            HarnessRefusal.MountState(release.mountState)

        !isWellFormedIdentity(release.packId) || !isWellFormedIdentity(release.releaseId) ->
            HarnessRefusal.MalformedIdentity(release.releaseId)

        else -> null
    }

    private inline fun <T> decodeResult(
        raw: String,
        expectedOperation: String,
        expectedResultSchema: String,
        resultSurvivesDecline: Boolean = false,
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
            //
            // Unless the operation seals a result alongside the decline, in which case the
            // decode continues and the CALLER reads the disposition off the payload -- the
            // payload's own account is more specific than the envelope's one word.
            if (!resultSurvivesDecline || envelope.result == null) {
                return HarnessOutcome.Unsuccessful(envelope.disposition, envelope.error?.code)
            }
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
