package com.verbalogix.assistant.ui.evidence

import com.verbalogix.assistant.data.harness.HarnessErrorCodes
import com.verbalogix.assistant.data.harness.HarnessOutcome
import com.verbalogix.assistant.data.harness.HarnessRefusal
import com.verbalogix.assistant.data.harness.HarnessRequest
import com.verbalogix.assistant.data.harness.SchemaIds
import com.verbalogix.assistant.data.harness.wire.Contradiction
import com.verbalogix.assistant.data.harness.wire.QueryResult

/**
 * A decoded retrieval becomes a screen state.
 *
 * NOTHING IS SUMMARISED AND NOTHING IS SCORED. The temptation on a surface like this is to
 * fold twelve fields into a friendly sentence — "3 strong matches" — and every such
 * sentence is a claim the Harness did not make. The Foundry already computed the only
 * verdict that matters, `answerability`, and this carries it across unchanged.
 */
internal fun QueryResult.toRetrievalEvidence(): RetrievalEvidence {
    val packet = evidencePacket
    return RetrievalEvidence(
        answerability = packet.answerability,
        disposition = packet.disposition,
        reasonCode = packet.reasonCode,
        items = packet.items.map { item ->
            EvidenceEntry(
                evidenceId = item.evidenceId,
                packId = item.packId,
                releaseId = item.releaseId,
                kind = item.kind,
                text = item.selectedText,
                knowledgeStatus = item.knowledgeStatus,
                uncertainty = item.uncertainty,
                sources = item.provenance.map {
                    SourceRef(
                        sourceId = it.sourceId,
                        logicalLocator = it.logicalLocator,
                        sensitivity = it.sensitivity,
                        contentSha256 = it.sourceContentSha256,
                    )
                },
                graphPathIds = item.graphPathIds,
                contradictionIds = item.contradictionIds,
                packFusedRank = item.ranks.packFused,
                globalFusedRank = item.ranks.globalFused,
                lexicalRank = item.ranks.packLexical,
                graphRank = item.ranks.packGraphLocal,
            )
        },
        // BOTH ARMS OF THE oneOf. A bare identity becomes a view with no members, which
        // the screen renders as "detail not included" rather than as an empty group --
        // those are different facts and only one of them is about the data.
        contradictions = packet.contradictions.map { contradiction ->
            when (contradiction) {
                is Contradiction.Reference -> ContradictionView(
                    groupId = contradiction.groupId,
                    detectionMethod = null,
                    disposition = null,
                    members = emptyList(),
                )
                is Contradiction.Group -> ContradictionView(
                    groupId = contradiction.groupId,
                    detectionMethod = contradiction.detectionMethod,
                    disposition = contradiction.disposition,
                    members = contradiction.members.map {
                        ContradictionMemberView(
                            candidateId = it.candidateId,
                            packId = it.packId,
                            releaseId = it.releaseId,
                            canonicalValueSha256 = it.canonicalValueSha256,
                        )
                    },
                )
            }
        },
        omissions = packet.omissions,
        truncationBoundaries = trace.truncation.boundaries,
        receipt = RetrievalReceipt(
            packetId = packet.packetId,
            packetSha256 = packet.packetSha256,
            traceId = packet.trace.traceId,
            deterministicCoreSha256 = packet.trace.deterministicCoreSha256,
            planId = plan.planId,
            resultSha256 = resultSha256,
            mountRegistrySha256 = trace.inputs.mountRegistrySha256,
        ),
    )
}

/**
 * Does this reply describe the expert that was asked about?
 *
 * THE ANSWER IS NOT ASSUMED FROM THE FACT THAT A REPLY ARRIVED. A retrieval is a POST to a
 * shared route with no client-side correlation id in the envelope this client reads, so
 * "the response to my request" is an inference, not an observation. Everything else here
 * is defence against a well-formed document about the wrong thing — which is the failure
 * that does not look like one.
 *
 * Four checks, each closing a different door:
 *
 *  1. The plan's echoed `pack_scope` is exactly the pack that was asked about. The plan
 *     carries the request back, so this catches a reply belonging to a different question.
 *  2. The plan's echoed `answer_mode` is still `evidence-only`. If a plan ever came back
 *     naming a generative mode, the payload is not the one this surface is built for.
 *  3. Every mount in the packet's snapshot is that pack at that release. The Foundry
 *     scopes the snapshot to the packs it selected, so a foreign entry means the retrieval
 *     considered material the request excluded.
 *  4. Every evidence item names that pack and that release.
 *
 * Check 3 depends on the snapshot being SCOPED rather than a whole-registry listing --
 * observed in the golden and in the Foundry's own packet construction. If a future Harness
 * widens it to every mount, this is the line that refuses a valid reply, and this comment
 * is the note to revisit.
 *
 * Contradiction members are deliberately NOT checked. A contradiction is the Foundry
 * reporting that sources disagree, and refusing the whole reply over the shape of a
 * disagreement would suppress exactly the finding a user most needs to see.
 *
 * @return the reason it does not correspond, or null when everything lines up.
 */
internal fun QueryResult.uncorrelatedWith(target: RetrievalTarget): String? {
    val scoped = plan.request.packScope.packIds
    if (scoped != listOf(target.packId)) {
        return "The reply was planned over ${scoped.size} pack(s) that do not match the " +
            "one this question was asked about."
    }
    if (plan.request.answerMode != HarnessRequest.QUERY_ANSWER_MODE) {
        return "The reply was planned as \"${plan.request.answerMode}\" rather than " +
            "\"${HarnessRequest.QUERY_ANSWER_MODE}\"."
    }
    for (mount in evidencePacket.mountSnapshot.mounts) {
        if (mount.packId != target.packId || mount.releaseId != target.releaseId) {
            return "The reply was retrieved against a mount this question did not name."
        }
    }
    for (item in evidencePacket.items) {
        if (item.packId != target.packId || item.releaseId != target.releaseId) {
            return "An evidence item came from a release other than the one on screen."
        }
    }
    return null
}

/**
 * Fold a decode outcome into the screen state.
 *
 * A disposition other than `succeeded` becomes [RetrievalUiState.Declined], NOT a refusal:
 * the Harness abstaining is it working correctly and saying so, and presenting that as a
 * client-side failure would blame the wrong component and hide the reason code.
 *
 * CORRELATION IS CHECKED ONLY ON THE PATH THAT DISPLAYS EVIDENCE. A decline carries no
 * items and often no mounts, so demanding a matching snapshot from it would turn a clean
 * abstention into a refusal and lose the reason code — while protecting nothing, because
 * nothing from a decline is ever rendered as evidence.
 */
internal fun HarnessOutcome<QueryResult>.toRetrievalState(
    target: RetrievalTarget,
): RetrievalUiState = when (this) {
    is HarnessOutcome.Decoded -> {
        val packet = value.evidencePacket
        when {
            packet.disposition != SchemaIds.DISPOSITION_SUCCEEDED ->
                RetrievalUiState.Declined(packet.disposition, packet.reasonCode)

            else -> value.uncorrelatedWith(target)
                ?.let { RetrievalUiState.Uncorrelated(it) }
                ?: RetrievalUiState.Ready(value.toRetrievalEvidence())
        }
    }

    // An adapter code that ends the session is a REMEDY, not a failure. Routing it to
    // Declined would report "the expert did not answer" for a token that simply ran out.
    is HarnessOutcome.Unsuccessful ->
        HarnessErrorCodes.pairAgainCause(errorCode)
            ?.let { RetrievalUiState.SessionExpired(it) }
            ?: RetrievalUiState.Declined(disposition, errorCode)

    is HarnessOutcome.Refused -> when (refusal) {
        // Fixed by shipping software, never by pairing again.
        is HarnessRefusal.Schema,
        is HarnessRefusal.ResultSchemaMismatch,
        is HarnessRefusal.RuntimeContract,
        -> RetrievalUiState.Incompatible(refusal.reason)

        // A reply for a different operation is the correlation failure this client CAN
        // observe directly, so it is reported as one rather than as a generic refusal.
        is HarnessRefusal.OperationMismatch -> RetrievalUiState.Uncorrelated(refusal.reason)

        else -> RetrievalUiState.Refused(refusal.reason)
    }
}
