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
        val question: String,
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
     *
     * [question] is carried so the screen can name it. An answer outlives the field it was
     * asked from: edit the box after drafting and the words above the answer describe a
     * question it was never about, silently. See [asCopyableText] for the same reasoning
     * applied to text that leaves the app entirely.
     */
    data class Grounded(
        val question: String,
        val segments: List<AnswerSegmentView>,
        val modelId: String,
        val templateId: String,
        val answerability: String,
        val receipt: TurnReceiptView,
    ) : GroundedTurnUiState
}

/**
 * The answer as text a person can keep, with its citations and enough to check it.
 *
 * CITATION MARKERS ARE PUT BACK. The parser strips `[1]` from the model's prose because the
 * citation belongs in `evidence_ids` rather than in a sentence, and the screen puts it back
 * as its own line under each segment. Copying only the prose would produce the exact artefact
 * this slice exists to prevent: a confident, well-formed, entirely uncited answer, indexed
 * and forwarded and no longer attached to anything that grounds it. The marker is the
 * cheapest form of attribution that survives a paste into a plain-text note.
 *
 * THE PROVENANCE BLOCK IS NOT DECORATION. Model, template and receipt id are what let someone
 * who was not here go back to the Foundry and ask whether this answer was really closed over
 * that evidence. Without them a pasted answer is an assertion.
 *
 * THE QUESTION IS DELIBERATELY ABSENT. Copying is a user's act on the answer, not on their
 * own query text, and the query is held in memory only for exactly as long as the evidence it
 * produced is on screen. The receipt already binds it through `request_sha256`, so nothing
 * checkable is lost by leaving it out — and the clipboard is read by every app on the device.
 */
fun GroundedTurnUiState.Grounded.asCopyableText(): String {
    val body = segments.joinToString("\n\n") { segment ->
        if (segment.citations.isEmpty()) {
            segment.text
        } else {
            "${segment.text} [${segment.citations.joinToString(",")}]"
        }
    }
    val provenance = buildList {
        add("Grounded by Knowledge Foundry · model $modelId · template $templateId")
        add("Receipt ${receipt.receiptId}")
        // Null cannot occur on a grounded turn -- the decoder refuses one without it -- so
        // this omits a line rather than printing a placeholder that would read as a value.
        receipt.answerSha256?.let { add("Answer SHA-256 $it") }
    }
    return body + "\n\n" + provenance.joinToString("\n")
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

/**
 * Whether THIS server offers `assistant.turn.finalize`.
 *
 * THREE STATES, NOT A BOOLEAN, and the third is the one that matters. "Not yet asked" and
 * "asked, and the answer was no" must not collapse: a boolean would default one of them to
 * the other, and whichever way it defaulted would be wrong. Defaulting to false hides a
 * working feature during discovery; defaulting to true offers an action the server has not
 * declared — which is the inference this whole slice exists to remove.
 *
 * REPLACES AN INFERENCE. Grounded drafting used to be offered whenever a retrieval had
 * succeeded — "retrieval answered, so the turn endpoint is probably there too". The Foundry
 * has stated plainly that the client must not reason that way: absence means hide or
 * disable, and never infer from retrieval. Under the old gate a withdrawn operation
 * surfaced only when a user pressed the button, and it looked like the model's fault.
 *
 * PRESENCE IS NARROWER THAN IT SOUNDS. [Offered] promises the operation is offered by the
 * current server instance or build. It promises nothing about an individual turn, which may
 * still abstain, be refused, or fail validation — those remain [GroundedTurnUiState]'s job.
 */
sealed interface GroundedDrafting {

    /** No answer yet: no session, or discovery has not returned. The action is not offered. */
    data object Undiscovered : GroundedDrafting

    /** The server declared the operation. Individual turns may still decline. */
    data object Offered : GroundedDrafting

    /**
     * The server did not declare it, or declared something this client will not accept.
     *
     * @param reason shown to the user verbatim, so it is written as a sentence. A refusal
     *   with no reason is indistinguishable from a feature that was never built.
     */
    data class NotOffered(val reason: String) : GroundedDrafting
}
