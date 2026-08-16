package com.verbalogix.assistant.ui.evidence

import com.verbalogix.assistant.data.harness.HarnessOutcome
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
 * Fold a decode outcome into the screen state.
 *
 * A disposition other than `succeeded` becomes [RetrievalUiState.Declined], NOT a refusal:
 * the Harness abstaining is it working correctly and saying so, and presenting that as a
 * client-side failure would blame the wrong component and hide the reason code.
 */
internal fun HarnessOutcome<QueryResult>.toRetrievalState(): RetrievalUiState = when (this) {
    is HarnessOutcome.Decoded -> {
        val packet = value.evidencePacket
        if (packet.disposition == SchemaIds.DISPOSITION_SUCCEEDED) {
            RetrievalUiState.Ready(value.toRetrievalEvidence())
        } else {
            RetrievalUiState.Declined(packet.disposition, packet.reasonCode)
        }
    }

    is HarnessOutcome.Unsuccessful -> RetrievalUiState.Declined(disposition, errorCode)

    is HarnessOutcome.Refused -> RetrievalUiState.Refused(refusal.reason)
}
