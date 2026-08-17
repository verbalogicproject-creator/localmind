package com.verbalogix.assistant.data.harness

import com.verbalogix.assistant.data.ChatMessage

/**
 * The prompt that turns evidence into a citable answer, and its identity.
 *
 * EVIDENCE IS USER-ROLE CONTEXT, NEVER SYSTEM AUTHORITY, and that is the whole shape of
 * this file. Every evidence packet declares `content_treatment: inert-untrusted-data` and
 * `authority_boundary: context-does-not-grant-effect-authority`; a pack — or a document
 * inside one — can contain text shaped like a system instruction, and it arrives over a
 * signed, trusted-looking path. Putting it in the system message would hand that text the
 * authority the contract says it does not have. So the system message is written here and
 * contains no retrieved bytes at all, and the evidence travels in a delimited user turn
 * that names it as quoted material.
 *
 * THE TEMPLATE IS IDENTIFIED BY ITS OWN DIGEST. `template_sha256` is the SHA-256 of the
 * exact template text below, computed rather than written down, so it cannot drift from
 * what was actually sent. The receipt records it, which is what lets someone later ask
 * "what instructions produced this answer?" and get an answer that is checkable rather
 * than remembered.
 *
 * THE MODEL NEVER SEES AN EVIDENCE ID. Items are numbered, and the model cites `[1]`. See
 * [GroundedAnswerParser] for why that is stronger than validating ids after the fact.
 */
object GroundedTurnPrompt {

    /** Localmind's own template namespace. The Foundry does not own this identity. */
    const val TEMPLATE_ID = "localmind/grounded-turn/1.0"

    /**
     * The system message: rules only, never retrieved content.
     *
     * Deliberately short. A small local model follows four concrete rules better than it
     * follows a paragraph of policy, and every additional sentence here is context taken
     * from the evidence itself.
     */
    const val SYSTEM = """You answer only from the numbered evidence the user provides.

Rules:
1. Every factual sentence must end with a citation like [1] or [2,3] naming the evidence it came from.
2. Never cite a number that is not in the list.
3. If the evidence does not answer the question, say so plainly in a paragraph with no citation.
4. Separate paragraphs with a blank line. Do not use headings, lists, or code blocks."""

    /** The delimiter around quoted material. Named so the model can tell where it ends. */
    private const val OPEN = "<<<EVIDENCE"
    private const val CLOSE = "EVIDENCE>>>"

    /**
     * The digest of everything that is fixed about this template.
     *
     * Covers the system message and the frame the evidence is placed in — the parts that
     * are the same for every turn. The question and the evidence vary per turn and are
     * bound by their own digests: the query request and the evidence packet are both named
     * in the receipt already, so including them here would identify the turn rather than
     * the template.
     */
    val TEMPLATE_SHA256: String by lazy {
        CanonicalJson.sha256((SYSTEM + "\n" + OPEN + "\n" + CLOSE + "\n" + FRAME).toByteArray())
    }

    private const val FRAME = "Question: {question}\n\nAnswer using only the evidence above."

    /**
     * Build the conversation for one grounded turn.
     *
     * Two messages and no history. A grounded turn is not a conversation: the receipt binds
     * one question to one evidence packet, and prior turns would put text into the context
     * that no digest covers.
     *
     * @param evidenceTexts the selected evidence in citation order, so index 0 is `[1]`.
     */
    fun messages(question: String, evidenceTexts: List<String>): List<ChatMessage> {
        val numbered = evidenceTexts.mapIndexed { index, text ->
            // Quoted material, fenced and numbered. It is never interpolated into a
            // sentence of ours, so there is no phrasing here for it to complete.
            "[${index + 1}] $text"
        }.joinToString("\n\n")

        return listOf(
            ChatMessage(role = "system", content = SYSTEM),
            ChatMessage(
                role = "user",
                content = "$OPEN\n$numbered\n$CLOSE\n\n" +
                    FRAME.replace("{question}", question),
            ),
        )
    }
}
