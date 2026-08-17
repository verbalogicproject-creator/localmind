package com.verbalogix.assistant.ui.evidence

import com.verbalogix.assistant.data.LlamaClient
import com.verbalogix.assistant.data.harness.AssistantTurnRequest
import com.verbalogix.assistant.data.harness.GroundedAnswerParser
import com.verbalogix.assistant.data.harness.GroundedTurnPrompt
import com.verbalogix.assistant.data.harness.HarnessOutcome
import com.verbalogix.assistant.data.harness.HarnessRequest
import com.verbalogix.assistant.data.harness.SchemaIds
import com.verbalogix.assistant.data.harness.wire.AssistantTurnResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs one grounded turn: model, then Foundry, then a receipt — or it stops.
 *
 * THE ORDER OF THE GATES IS THE CONTRACT. Retrieval must already have succeeded; the model
 * is called only then; its output must finish with `stop` and must parse into cited
 * segments; only then is anything sent to the Foundry. An answer that cannot be grounded is
 * never submitted to be receipted, so no receipt can exist for one.
 *
 * NOTHING IS RELABELLED ON THE WAY BACK. A `length` finish is not a short answer, a failed
 * parse is not a partial answer, and an `abstained` turn is not a quiet one. Each ends in
 * its own state with its own words, because the single thing this whole slice exists to
 * prevent is an ungrounded answer wearing a grounded answer's clothes.
 */
internal class GroundedTurnController(
    private val scope: CoroutineScope,
    /** Calls the provider. Null means there is no reachable model endpoint. */
    private val generate: suspend (List<com.verbalogix.assistant.data.ChatMessage>) -> LlamaClient.TurnCompletion?,
    /** Calls the Foundry. Null means there is no session. */
    private val finalize: suspend (JsonObject) -> HarnessOutcome<AssistantTurnResult>?,
    /** Injected so the observation timestamp is testable; UTC whole seconds. */
    private val now: () -> String = {
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    },
) {

    private val _state = MutableStateFlow<GroundedTurnUiState>(GroundedTurnUiState.Idle)
    val state: StateFlow<GroundedTurnUiState> = _state.asStateFlow()

    /** Same ordering rule as retrieval: the answer to a replaced question never displays. */
    private val issued = AtomicLong(0)

    fun reset() {
        issued.incrementAndGet()
        _state.value = GroundedTurnUiState.Idle
    }

    /**
     * Draft an answer from evidence that has already been retrieved.
     *
     * @param evidence the Ready retrieval this turn is bound to. Its receipt supplies the
     *   four digests the Foundry re-derives, and its items supply both the text the model
     *   reads and the identities it may cite.
     */
    fun submit(question: String, target: RetrievalTarget, evidence: RetrievalEvidence) {
        val ticket = issued.incrementAndGet()
        _state.value = GroundedTurnUiState.Generating

        scope.launch {
            val outcome = runCatching { run(question, target, evidence) }
                .getOrElse { failure ->
                    // A thrown provider error is a provider failure, not a Foundry verdict.
                    GroundedTurnUiState.ProviderFailed(
                        failure.message?.takeIf { "kft2." !in it } ?: "the model did not answer",
                    )
                }
            if (ticket == issued.get()) _state.value = outcome
        }
    }

    private suspend fun run(
        question: String,
        target: RetrievalTarget,
        evidence: RetrievalEvidence,
    ): GroundedTurnUiState {
        val items = evidence.items
        if (items.isEmpty()) {
            return GroundedTurnUiState.ProviderFailed(
                "there is no evidence to answer from",
            )
        }

        val completion = generate(GroundedTurnPrompt.messages(question, items.map { it.text }))
            ?: return GroundedTurnUiState.ProviderFailed("no model endpoint is configured")

        if (completion.finishReason != SchemaIds.FINISH_STOP) {
            // `length` and `timeout` mean the model stopped mid-sentence. Binding that to a
            // receipt would certify a fragment as an answer, so it never reaches the wire.
            return GroundedTurnUiState.ProviderFailed(
                "the model stopped with \"${completion.finishReason}\" rather than finishing",
            )
        }

        val evidenceIds = items.map { it.evidenceId }
        val parsed = when (val result = GroundedAnswerParser.parse(completion.text, evidenceIds)) {
            is GroundedAnswerParser.Result.Unusable ->
                return GroundedTurnUiState.ProviderFailed(result.reason)
            is GroundedAnswerParser.Result.Parsed -> result.segments
        }

        val answer = AssistantTurnRequest.groundedAnswer(parsed)
        val observation = AssistantTurnRequest.providerObservation(
            // The model the SERVER named, falling back to the one that was asked for. A
            // swap proxy can load something other than the requested name, and the receipt
            // should record what actually ran.
            modelId = completion.modelId?.takeIf { it.isNotBlank() } ?: UNNAMED_MODEL,
            artifactSha256 = null,
            runtimeVersion = null,
            templateId = GroundedTurnPrompt.TEMPLATE_ID,
            templateSha256 = GroundedTurnPrompt.TEMPLATE_SHA256,
            packetId = evidence.receipt.packetId,
            packetSha256 = evidence.receipt.packetSha256,
            answer = answer,
            finishReason = completion.finishReason,
            observedAt = now(),
        )
        val request = AssistantTurnRequest.turnRequest(
            // THE SAME QUERY, rebuilt from the same inputs by the same builder. The Foundry
            // re-runs it and compares digests, so anything that differed here would surface
            // as evidence drift and blame the mounted packs.
            queryRequest = HarnessRequest.queryRequest(
                question, target.packId, target.allowedSensitivities,
            ),
            queryResultSha256 = evidence.receipt.resultSha256,
            packetId = evidence.receipt.packetId,
            packetSha256 = evidence.receipt.packetSha256,
            mountRegistrySha256 = evidence.receipt.mountRegistrySha256,
            observation = observation,
        )

        _state.value = GroundedTurnUiState.Finalizing
        val reply = finalize(request)
            ?: return GroundedTurnUiState.Refused("the Knowledge Foundry session has ended.")

        return when (reply) {
            is HarnessOutcome.Decoded -> present(reply.value, evidenceIds, question)

            // The operation failed: drift, citation closure, a digest that did not match.
            // Never softened into "the model could not answer" -- the model may have
            // answered perfectly and the mounted state moved underneath it.
            is HarnessOutcome.Unsuccessful -> GroundedTurnUiState.Refused(
                "The Knowledge Foundry did not finalise this turn" +
                    (reply.errorCode?.let { " ($it)" } ?: "") + ".",
            )

            is HarnessOutcome.Refused -> GroundedTurnUiState.Refused(reply.refusal.reason)
        }
    }

    /**
     * @param question the one this turn was submitted for, carried through so the screen can
     *   name it. It is the question the request was built from and the Foundry re-ran, not
     *   whatever the field says by the time the reply lands.
     */
    private fun present(
        turn: AssistantTurnResult,
        evidenceIds: List<String>,
        question: String,
    ): GroundedTurnUiState {
        val receipt = turn.receipt.toView(turn.turnId)
        if (turn.disposition != SchemaIds.TURN_GROUNDED) {
            return GroundedTurnUiState.NotGrounded(
                question = question,
                disposition = turn.disposition,
                answerability = turn.evidence.answerability,
                receipt = receipt,
            )
        }
        // The decoder has already refused a grounded turn missing any of these; the
        // elvis is unreachable and stays as a refusal rather than a `!!`.
        val answer = turn.answer ?: return GroundedTurnUiState.Refused(
            "the Foundry called this grounded but carried no answer",
        )
        return GroundedTurnUiState.Grounded(
            question = question,
            segments = answer.segments.map { segment ->
                AnswerSegmentView(
                    kind = segment.kind,
                    text = segment.text,
                    // Back to ordinals, so a citation points at the evidence card a reader
                    // can actually see. An id that is not among the shown evidence would be
                    // dropped here -- and cannot occur, because the Foundry already required
                    // citation closure over this packet.
                    citations = segment.evidenceIds
                        .mapNotNull { id -> evidenceIds.indexOf(id).takeIf { it >= 0 }?.plus(1) }
                        .sorted(),
                )
            },
            modelId = turn.model?.modelId.orEmpty(),
            templateId = turn.promptTemplate?.templateId.orEmpty(),
            answerability = turn.evidence.answerability,
            receipt = receipt,
        )
    }

    private companion object {
        /** A server that names no model still produced the answer; say so rather than lie. */
        const val UNNAMED_MODEL = "unnamed-model"
    }
}

private fun com.verbalogix.assistant.data.harness.wire.AssistantTurnReceipt.toView(
    turnId: String,
): TurnReceiptView = TurnReceiptView(
    receiptId = receiptId,
    receiptSha256 = receiptSha256,
    turnId = turnId,
    requestSha256 = requestSha256,
    queryResultSha256 = queryResultSha256,
    packetId = packetId,
    packetSha256 = packetSha256,
    mountRegistrySha256 = mountRegistrySha256,
    providerObservationSha256 = providerObservationSha256,
    modelIdentitySha256 = modelIdentitySha256,
    promptTemplateSha256 = promptTemplateSha256,
    answerSha256 = answerSha256,
    citedEvidenceIds = citedEvidenceIds,
    disposition = disposition,
    proofLimit = proofLimit,
)
