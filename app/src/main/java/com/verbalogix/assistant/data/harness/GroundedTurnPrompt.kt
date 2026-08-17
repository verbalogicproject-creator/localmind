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

    /**
     * Localmind's own template namespace. The Foundry does not own this identity.
     *
     * VERSIONED WITH THE BYTES, so `1.1` was minted the moment [SYSTEM] changed. The id and
     * [TEMPLATE_SHA256] are recorded together in every receipt, and two different templates
     * sharing one id would make that pair useless: a reader comparing two receipts would see
     * the same name against different digests and have no way to tell which text produced
     * which answer. Bumping is cheap; the id is not a Foundry-registered name and nothing
     * validates it against a list.
     */
    const val TEMPLATE_ID = "localmind/grounded-turn/1.1"

    /**
     * The system message: rules only, never retrieved content.
     *
     * Deliberately short. A small local model follows a handful of concrete rules better
     * than it follows a paragraph of policy, and every additional sentence here is context
     * taken from the evidence itself.
     *
     * ONE CLAIM PER PARAGRAPH IS RULE 1 BECAUSE OF WHAT A PARAGRAPH IS HERE. A paragraph is
     * a SEGMENT — the unit the receipt cites, the unit the screen puts a citation next to,
     * and the unit the Foundry checks for closure. A model that answers in one block
     * produces one segment carrying every citation at once, which is the footnote-list
     * shape this design exists to avoid: it says these sources were involved somewhere
     * rather than which claim rests on what. The earlier wording asked only that paragraphs
     * be separated by a blank line, and qwen-4b honoured that literally by writing a single
     * paragraph. So the instruction now names the thing wanted rather than its formatting,
     * and says it first, where a small model weights it most.
     */
    const val SYSTEM = """You answer only from the numbered evidence the user provides.

Rules:
1. Write one claim per paragraph, separated by a blank line. Never put two claims in the same paragraph.
2. End each claim with a citation like [1] or [2,3] naming the evidence it came from.
3. Never cite a number that is not in the list.
4. If the evidence does not answer the question, say so plainly in a paragraph with no citation.
5. Do not use headings, lists, or code blocks."""

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
