package com.verbalogix.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backtick spans, and deliberately nothing else.
 *
 * The restraint is the feature, so several of these assert what is NOT rendered. Every
 * construct a renderer understands is another way for generated text to control what the
 * screen looks like, and a model that can emit application chrome can be talked into
 * misrepresenting it.
 */
class InlineCodeTest {

    private fun runsOf(text: String) = InlineCode.parse(text)
    private fun codeRuns(text: String) = runsOf(text).filter { it.code }.map { it.text }
    private fun rendered(text: String) = runsOf(text).joinToString("") { it.text }

    @Test
    fun a_fenced_span_becomes_code_and_the_ticks_are_dropped() {
        val runs = runsOf("Call `activateExpert` first.")
        assertEquals(listOf("activateExpert"), runs.filter { it.code }.map { it.text })
        assertEquals("Call activateExpert first.", rendered("Call `activateExpert` first."))
    }

    @Test
    fun several_spans_in_one_sentence_are_all_found() {
        assertEquals(
            listOf("ExpertManager.ts", "verifyPackIntegrity(expert.packId)"),
            codeRuns("`ExpertManager.ts` calls `verifyPackIntegrity(expert.packId)`."),
        )
    }

    @Test
    fun an_unpaired_backtick_stays_literal() {
        // THE CASE THAT MATTERS. A model writing "use the ` character" has not opened a
        // span, and swallowing the rest of the message into monospace would look exactly
        // like a broken answer rather than a rendering bug.
        assertTrue(codeRuns("use the ` character").isEmpty())
        assertEquals("use the ` character", rendered("use the ` character"))
    }

    @Test
    fun a_trailing_unclosed_span_does_not_consume_the_rest() {
        assertTrue(codeRuns("this is `unclosed and continues").isEmpty())
        assertEquals("this is `unclosed and continues", rendered("this is `unclosed and continues"))
    }

    @Test
    fun an_empty_pair_is_two_characters_not_a_zero_width_span() {
        assertTrue(codeRuns("nothing here: ``").isEmpty())
        assertEquals("nothing here: ``", rendered("nothing here: ``"))
    }

    @Test
    fun no_other_markdown_construct_is_interpreted() {
        // Bold, headings, list bullets, links and tables all pass through as the literal
        // characters the model wrote. This is not a gap to fill in later.
        for (markdown in listOf(
            "**bold**",
            "# heading",
            "- bullet",
            "[link](https://example.invalid)",
            "| a | b |",
            "> quote",
            "```\nfenced block\n```",
        )) {
            assertEquals("must pass through verbatim: $markdown", markdown, rendered(markdown))
        }
    }

    @Test
    fun a_multi_tick_run_is_literal_and_loses_no_characters() {
        // FOUND BY THIS TEST FAILING. A naive scan read "```" as an empty pair plus an
        // opening tick, so a fenced block swallowed its content AND silently dropped two
        // backticks from the model's text. No block formatting was applied -- the failure
        // was quieter than that -- and text vanishing from an answer is the worst kind of
        // rendering bug, because the reader cannot tell anything is missing.
        for (text in listOf("before ``` after", "``code``", "`````")) {
            assertEquals("must survive intact: $text", text, rendered(text))
            assertTrue("must open no span: $text", codeRuns(text).isEmpty())
        }
    }

    @Test
    fun the_annotated_string_carries_the_same_text_the_model_wrote() {
        val source = "Call `activateExpert` before `mount`."
        assertEquals("Call activateExpert before mount.", InlineCode.annotate(source).text)
    }

    @Test
    fun plain_text_produces_one_run_and_no_styling() {
        val runs = runsOf("nothing special here")
        assertEquals(1, runs.size)
        assertTrue(runs.single().code.not())
    }
}
