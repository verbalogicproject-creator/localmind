package com.verbalogix.assistant.data.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the model wrote, and whether it can honestly be called grounded.
 *
 * THE HALLUCINATED CITATION IS THE CASE THAT MATTERS. Everything else here is shape; that
 * one is the difference between an answer resting on evidence and an answer that says it
 * does. The parser is built so an invented citation is out of range rather than merely
 * wrong — the model writes `[2]`, never `kf:evidence:…` — and these tests hold that line.
 */
class GroundedAnswerParserTest {

    private val ids = listOf(
        "kf:evidence:${"a1".repeat(32)}",
        "kf:evidence:${"b2".repeat(32)}",
        "kf:evidence:${"c3".repeat(32)}",
    )

    private fun parse(output: String) = GroundedAnswerParser.parse(output, ids)

    private fun segments(output: String): List<AssistantTurnRequest.Segment> {
        val result = parse(output)
        check(result is GroundedAnswerParser.Result.Parsed) { "unusable: $result" }
        return result.segments
    }

    // ── the shapes a model actually produces ────────────────────────────────

    @Test
    fun a_cited_paragraph_becomes_a_claim_with_the_marker_stripped() {
        val segment = segments("Packs are signed and verifiable. [1]").single()
        assertEquals(SchemaIds.SEGMENT_CLAIM, segment.kind)
        // The marker is gone from the text: the citation lives in evidence_ids, and the
        // answer that is displayed is exactly the answer that is digested.
        assertEquals("Packs are signed and verifiable.", segment.text)
        assertEquals(listOf(ids[0]), segment.evidenceIds)
    }

    @Test
    fun an_uncited_paragraph_becomes_an_uncertainty() {
        // Not a failure. The contract has a kind for the model hedging, and deleting the
        // hedge would leave an answer more confident than the one produced.
        val segment = segments("The evidence does not say when this changed.").single()
        assertEquals(SchemaIds.SEGMENT_UNCERTAINTY, segment.kind)
        assertTrue(segment.evidenceIds.isEmpty())
    }

    @Test
    fun multiple_citations_are_sorted_and_deduplicated() {
        // The Foundry refuses a segment whose citations are not sorted and unique, so this
        // is fixed at construction rather than discovered as a remote refusal.
        val segment = segments("Both sources agree. [3,1] and again [1]").single()
        assertEquals(listOf(ids[0], ids[2]).sorted(), segment.evidenceIds)
    }

    @Test
    fun paragraphs_split_on_blank_lines_in_the_contracts_own_shape() {
        val parsed = segments("First point. [1]\n\nSecond point. [2]\n\nA caveat.")
        assertEquals(3, parsed.size)
        assertEquals(
            listOf(SchemaIds.SEGMENT_CLAIM, SchemaIds.SEGMENT_CLAIM, SchemaIds.SEGMENT_UNCERTAINTY),
            parsed.map { it.kind },
        )
        // The joined text is what the answer's `text` must equal, and the Foundry checks it.
        assertEquals(
            "First point.\n\nSecond point.\n\nA caveat.",
            parsed.joinToString("\n\n") { it.text },
        )
    }

    @Test
    fun windows_line_endings_are_normalised_rather_than_refused() {
        // A model trained on Windows-flavoured text emits \r\n, and the contract forbids a
        // bare \r. Refusing an otherwise perfect answer over line endings would be pedantry.
        assertEquals(2, segments("One. [1]\r\n\r\nTwo. [2]").size)
    }

    // ── what must never become an answer ────────────────────────────────────

    @Test
    fun a_citation_beyond_the_offered_evidence_is_refused() {
        // THE HALLUCINATION CASE. Three items were offered; the model cited a fourth.
        val result = parse("This is definitely true. [4]")
        assertTrue("got $result", result is GroundedAnswerParser.Result.Unusable)
        assertTrue((result as GroundedAnswerParser.Result.Unusable).reason.contains("4"))
    }

    @Test
    fun a_bad_citation_refuses_the_whole_answer_rather_than_being_dropped() {
        // Dropping the citation and keeping the sentence would turn a fabricated claim into
        // an "uncertainty" and send it on to be receipted. The sentence goes too.
        val result = parse("True thing. [1]\n\nInvented thing. [9]")
        assertTrue("got $result", result is GroundedAnswerParser.Result.Unusable)
    }

    @Test
    fun a_zero_citation_is_refused() {
        assertTrue(parse("Something. [0]") is GroundedAnswerParser.Result.Unusable)
    }

    @Test
    fun empty_or_marker_only_output_is_refused() {
        assertTrue(parse("") is GroundedAnswerParser.Result.Unusable)
        assertTrue(parse("   \n\n  ") is GroundedAnswerParser.Result.Unusable)
        // A paragraph that is nothing but a citation has no claim in it to ground.
        assertTrue(parse("[1]") is GroundedAnswerParser.Result.Unusable)
    }

    @Test
    fun text_the_contract_forbids_is_refused() {
        assertTrue(parse("a\u0000b [1]") is GroundedAnswerParser.Result.Unusable)
    }

    @Test
    fun more_segments_than_the_contract_allows_are_refused_not_truncated() {
        // Truncating would send an answer that stops mid-argument while presenting itself
        // as the whole of what the model said.
        val many = (1..65).joinToString("\n\n") { "Point $it. [1]" }
        assertTrue(parse(many) is GroundedAnswerParser.Result.Unusable)
    }

    // ── the answer that gets built from it ──────────────────────────────────

    @Test
    fun the_built_answer_derives_its_text_and_seals_itself() {
        val answer = AssistantTurnRequest.groundedAnswer(
            segments("First. [1]\n\nSecond, uncited."),
        )
        val text = answer["text"]!!.toString().trim('"')
        assertEquals("First.\\n\\nSecond, uncited.", text)
        // The self-digest is what the Foundry recomputes; check it closes over itself.
        assertEquals(
            answer["answer_sha256"]!!.toString().trim('"'),
            CanonicalJson.selfDigest(answer, "answer_sha256"),
        )
    }

    @Test
    fun a_claim_with_no_citation_cannot_be_built() {
        // Belt and braces: the parser cannot produce this, and the builder refuses it
        // anyway, so a future caller assembling segments by hand hits the same rule.
        assertTrue(
            runCatching {
                AssistantTurnRequest.groundedAnswer(
                    listOf(AssistantTurnRequest.Segment(SchemaIds.SEGMENT_CLAIM, "x", emptyList())),
                )
            }.isFailure,
        )
    }

    @Test
    fun unsorted_citations_cannot_be_built() {
        assertTrue(
            runCatching {
                AssistantTurnRequest.groundedAnswer(
                    listOf(
                        AssistantTurnRequest.Segment(
                            SchemaIds.SEGMENT_CLAIM, "x", listOf(ids[2], ids[0]),
                        ),
                    ),
                )
            }.isFailure,
        )
    }
}
