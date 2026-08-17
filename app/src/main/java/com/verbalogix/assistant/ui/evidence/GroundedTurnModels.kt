package com.verbalogix.assistant.ui.evidence

/**
 * What a grounded turn can be showing.
 *
 * ONLY [Grounded] MAY BE PRESENTED AS AN ANSWER, and it is reachable exactly one way: the
 * Foundry returned a successful `operation-response/4.0` whose turn disposition is
 * `grounded` and whose receipt closes over the answer. Every other route through this
 * hierarchy ends somewhere that does not look like a reply.
 *
 * THE THREE FAILURE KINDS ARE KEPT APART because they have different owners:
 *
 *  - [ProviderFailed] — the model produced nothing usable. Retry may help.
 *  - [NotGrounded] — the Foundry declined to bind a turn: the evidence was insufficient or
 *    refused, so no provider was called at all. Retrying the same question will not help;
 *    the expert does not hold the material.
 *  - [Refused] — this client would not read the reply, or the Foundry failed the operation
 *    on drift, citation closure, or digest mismatch. Something is wrong, not absent.
 *
 * Collapsing them into "couldn't answer" would tell a user to retry a question that cannot
 * succeed, or to give up on one that would.
 */
sealed interface GroundedTurnUiState {

    data object Idle : GroundedTurnUiState

    /** The model is writing. Localmind is calling the provider; the Foundry is not. */
    data object Generating : GroundedTurnUiState

    /** The model has answered and the Foundry is deciding whether it is grounded. */
    data object Finalizing : GroundedTurnUiState

    /**
     * The model produced no usable answer.
     *
     * Covers a non-`stop` finish — `length`, `timeout`, `refusal`, `error` — and every
     * parse failure, including a citation to evidence that was never offered. None of these
     * ever reaches the Foundry: an answer that cannot be grounded is not sent to be
     * receipted.
     */
    data class ProviderFailed(val reason: String) : GroundedTurnUiState

    /**
     * The Foundry declined to bind a turn: `abstained` or `refused`.
     *
     * No provider observation exists in this case, and the contract forbids one. The
     * receipt is still real and still shown — it certifies that the retrieval happened and
     * that nothing was grounded on it.
     */
    data class NotGrounded(
        val disposition: String,
        val answerability: String,
        val receipt: TurnReceiptView,
    ) : GroundedTurnUiState

    /** This client would not read the reply, or the operation failed. */
    data class Refused(val detail: String) : GroundedTurnUiState

    /**
     * A grounded answer, and everything needed to check it.
     *
     * [segments] rather than a string, because the citation lives on the segment. Rendering
     * the joined text alone would show exactly the same words with no way to tell which
     * sentence rests on which source — which is the difference between an answer that is
     * grounded and one that says it is.
     */
    data class Grounded(
        val segments: List<AnswerSegmentView>,
        val modelId: String,
        val templateId: String,
        val answerability: String,
        val receipt: TurnReceiptView,
    ) : GroundedTurnUiState
}

/**
 * One segment of an answer.
 *
 * [citations] are ordinals into the evidence shown above — `[1]`, `[2]` — resolved from the
 * identities the receipt carries. The number a reader sees is the number of the evidence
 * card, so a citation can be followed rather than merely counted.
 */
data class AnswerSegmentView(
    val kind: String,
    val text: String,
    val citations: List<Int>,
)

/**
 * The turn receipt, as the screen shows it.
 *
 * WHAT IT PROVES IS NARROW AND IS STATED. [proofLimit] is the Foundry's own sentence:
 * structural grounding and derivation closure only — not source truth, not factuality, not
 * provider honesty, not model quality. It is displayed rather than summarised, because a
 * row of digests under an answer reads as proof of the answer unless something says
 * otherwise.
 */
data class TurnReceiptView(
    val receiptId: String,
    val receiptSha256: String,
    val turnId: String,
    val requestSha256: String,
    val queryResultSha256: String,
    val packetId: String,
    val packetSha256: String,
    val mountRegistrySha256: String,
    val providerObservationSha256: String?,
    val modelIdentitySha256: String?,
    val promptTemplateSha256: String?,
    val answerSha256: String?,
    val citedEvidenceIds: List<String>,
    val disposition: String,
    val proofLimit: String,
)
