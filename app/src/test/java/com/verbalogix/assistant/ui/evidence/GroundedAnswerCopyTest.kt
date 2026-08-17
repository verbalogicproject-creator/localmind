package com.verbalogix.assistant.ui.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What leaves the app when someone copies an answer.
 *
 * THIS IS THE ONLY PLACE A GROUNDED ANSWER ESCAPES ITS RECEIPT. On screen, the citation sits
 * under the sentence and the receipt is one tap away; in a paste buffer there is nothing but
 * the characters that were handed over. So the property under test is that the copied text
 * carries its own attribution — otherwise copying converts a receipted answer into an
 * anonymous assertion, which is the exact artefact the whole slice exists to prevent.
 */
class GroundedAnswerCopyTest {

    private val receipt = TurnReceiptView(
        receiptId = "kf:assistant-turn-receipt:${"f1".repeat(32)}",
        receiptSha256 = "f2".repeat(32),
        turnId = "kf:assistant-turn:${"f3".repeat(32)}",
        requestSha256 = "f4".repeat(32),
        queryResultSha256 = "e6".repeat(32),
        packetId = "kf:evidence-packet:${"e1".repeat(32)}",
        packetSha256 = "e2".repeat(32),
        mountRegistrySha256 = "e7".repeat(32),
        providerObservationSha256 = "f5".repeat(32),
        modelIdentitySha256 = "f6".repeat(32),
        promptTemplateSha256 = "f7".repeat(32),
        answerSha256 = "f8".repeat(32),
        citedEvidenceIds = listOf("kf:evidence:${"d1".repeat(32)}"),
        disposition = "grounded",
        proofLimit = "Structural grounding and derivation closure only.",
    )

    private fun grounded(segments: List<AnswerSegmentView>) = GroundedTurnUiState.Grounded(
        question = "how are sources verified",
        segments = segments,
        modelId = "lfm-8b",
        templateId = "localmind/grounded-turn/1.1",
        answerability = "supported",
        receipt = receipt,
    )

    @Test
    fun a_claim_is_copied_with_the_citation_the_parser_stripped() {
        val text = grounded(
            listOf(AnswerSegmentView("claim", "Every pack carries a signature.", listOf(1))),
        ).asCopyableText()
        assertTrue(text, text.startsWith("Every pack carries a signature. [1]"))
    }

    @Test
    fun a_claim_resting_on_several_items_keeps_all_of_them() {
        val text = grounded(
            listOf(AnswerSegmentView("claim", "Both stores agree.", listOf(1, 3))),
        ).asCopyableText()
        assertTrue(text, "Both stores agree. [1,3]" in text)
    }

    @Test
    fun a_hedge_is_copied_without_an_invented_citation() {
        // An uncertainty segment cites nothing BECAUSE it claims nothing. Attaching a
        // citation to make the output look uniform would assert that the evidence supports a
        // sentence the model wrote precisely to say it does not.
        val text = grounded(
            listOf(AnswerSegmentView("uncertainty", "The evidence does not say when.", emptyList())),
        ).asCopyableText()
        assertTrue(text, text.startsWith("The evidence does not say when.\n\n"))
        assertFalse("The evidence does not say when. [" in text)
    }

    @Test
    fun segments_are_separated_the_way_the_contract_separates_them() {
        // A blank line, the same separator the Foundry uses to join `answer.text`.
        val text = grounded(
            listOf(
                AnswerSegmentView("claim", "First.", listOf(1)),
                AnswerSegmentView("claim", "Second.", listOf(2)),
            ),
        ).asCopyableText()
        assertTrue(text, "First. [1]\n\nSecond. [2]" in text)
    }

    @Test
    fun the_provenance_block_says_who_wrote_it_and_what_certifies_it() {
        val text = grounded(
            listOf(AnswerSegmentView("claim", "Signed.", listOf(1))),
        ).asCopyableText()
        assertTrue(text, "model lfm-8b" in text)
        assertTrue(text, "template localmind/grounded-turn/1.1" in text)
        assertTrue(text, "Receipt kf:assistant-turn-receipt:" in text)
        assertTrue(text, "Answer SHA-256 ${"f8".repeat(32)}" in text)
    }

    @Test
    fun the_question_is_not_carried_out_of_the_app() {
        // Copying is an act on the ANSWER. The question is held in memory only for as long
        // as the evidence it produced is on screen, and the clipboard is readable by every
        // app on the device; the receipt already binds the question through request_sha256,
        // so nothing checkable is lost by leaving it out.
        val text = grounded(
            listOf(AnswerSegmentView("claim", "Signed.", listOf(1))),
        ).asCopyableText()
        assertFalse(text, "how are sources verified" in text)
    }

    @Test
    fun an_answer_with_no_digest_omits_the_line_rather_than_faking_one() {
        // Unreachable through the decoder, which refuses a grounded turn without one. It
        // omits rather than printing "none", because a placeholder in a copied block reads
        // as a value that was checked and found absent.
        val text = GroundedTurnUiState.Grounded(
            question = "q",
            segments = listOf(AnswerSegmentView("claim", "Signed.", listOf(1))),
            modelId = "lfm-8b",
            templateId = "localmind/grounded-turn/1.1",
            answerability = "supported",
            receipt = receipt.copy(answerSha256 = null),
        ).asCopyableText()
        assertFalse("Answer SHA-256" in text)
        assertTrue(text, "Receipt kf:assistant-turn-receipt:" in text)
    }

    @Test
    fun the_copied_prose_is_the_prose_that_was_displayed() {
        // No summarising, no reordering, no repair. The parser already refused anything it
        // could not carry verbatim; this must not become a second place where text is edited.
        val segments = listOf(
            AnswerSegmentView("claim", "A pack is signed and verifiable.", listOf(1)),
            AnswerSegmentView("uncertainty", "The evidence does not say when.", emptyList()),
        )
        val text = grounded(segments).asCopyableText()
        val body = text.substringBefore("\n\nGrounded by Knowledge Foundry")
        assertEquals(
            "A pack is signed and verifiable. [1]\n\nThe evidence does not say when.",
            body,
        )
    }
}
