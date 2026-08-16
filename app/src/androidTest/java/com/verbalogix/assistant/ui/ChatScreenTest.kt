package com.verbalogix.assistant.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.verbalogix.assistant.data.CitationCodec
import com.verbalogix.assistant.data.Citation
import com.verbalogix.assistant.data.Message
import com.verbalogix.assistant.data.Provider
import com.verbalogix.assistant.data.ServerStatus
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Chat still does what chat did.
 *
 * This is a REGRESSION suite for the refactor, not a specification of new behaviour.
 * The direct llama.cpp path is the only one proven end to end on a physical device, and
 * the shell was introduced around it -- so the assertions here are deliberately about
 * the things that were working before: the transcript renders, the composer sends, the
 * grounding states stay distinct, and the provider is still named on screen.
 */
class ChatScreenTest {

    @get:Rule val compose = createComposeRule()

    private val provider = Provider(1, "LFM2.5 8B", "http://127.0.0.1:8090", model = "lfm-8b")

    /**
     * A DIRECT server: it holds whatever model it was started with, so the request names
     * none. `Provider.isSwap` is `model.isNotEmpty()`, which makes this the only fixture
     * that renders the reported-model stat -- see
     * [provider_and_reported_model_remain_two_separate_facts].
     */
    private val directProvider =
        Provider(2, "Laptop llama-server", "https://box.local:8080", model = "")

    private val ready = ServerStatus(reachable = true, model = "lfm-8b", contextSize = 8192)

    private fun chat(
        messages: List<Message>,
        status: ServerStatus = ready,
        sending: Boolean = false,
        provider: Provider = this.provider,
        onSend: (String) -> Unit = {},
        onOpenProviders: () -> Unit = {},
        onOpenEvidence: (Long) -> Unit = {},
    ) {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                ChatScreen(
                    messages = messages,
                    status = status,
                    sending = sending,
                    onSend = onSend,
                    onRetryStatus = {},
                    buildLabel = "v0.0.1 · test",
                    provider = provider,
                    elapsed = null,
                    think = false,
                    onToggleThink = {},
                    onOpenProviders = onOpenProviders,
                    onOpenEvidence = onOpenEvidence,
                )
            }
        }
    }

    @Test
    fun transcript_renders_user_and_assistant_turns() {
        chat(
            listOf(
                Message(1, "user", "what did I write about the governor?", 0),
                Message(2, "assistant", "Pause at SEVERE, resume at LIGHT.", 0),
            ),
        )
        compose.onNodeWithText("what did I write about the governor?").assertIsDisplayed()
        compose.onNodeWithText("Pause at SEVERE, resume at LIGHT.").assertIsDisplayed()
        compose.onNodeWithText("YOU").assertIsDisplayed()
        compose.onNodeWithText("LOCALMIND").assertIsDisplayed()
    }

    @Test
    fun composer_sends_what_was_typed() {
        var sent: String? = null
        chat(emptyList(), onSend = { sent = it })
        compose.onNodeWithText("Ask about anything on this device…")
            .performTextInput("hello")
        // The send affordance is an Icon; "Send message" is its contentDescription and
        // it renders no text. `onNodeWithText` does not read contentDescription, so this
        // could never resolve -- the app's labelling was right and the matcher was not.
        compose.onNodeWithContentDescription("Send message").performClick()
        assertEquals("hello", sent)
    }

    /** The reasoning fold. Collapsed by default, and it says how much it hid. */
    @Test
    fun reasoning_is_folded_with_a_count_not_hidden() {
        chat(listOf(Message(1, "thinking", "one two three four five", 0)))
        compose.onNodeWithText("thought for 5 words · tap to read").assertIsDisplayed()
        compose.onNodeWithText("thought for 5 words · tap to read").performClick()
        compose.onNodeWithText("one two three four five").assertIsDisplayed()
    }

    @Test
    fun errors_stay_in_the_transcript() {
        chat(listOf(Message(1, "error", "connection refused", 0)))
        compose.onNodeWithText("ERROR").assertIsDisplayed()
        compose.onNodeWithText("connection refused").assertIsDisplayed()
    }

    // ── Grounding states stay visually distinct ─────────────────────────────────

    @Test
    fun a_direct_answer_shows_no_grounding_verdict_at_all() {
        // grounded = null means "never asked". It must not render as "not grounded",
        // or every ordinary direct answer would look suspect.
        chat(listOf(Message(1, "assistant", "plain answer", 0, grounded = null)))
        compose.onAllNodesWithText("not grounded", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("open evidence", substring = true).assertCountEquals(0)
    }

    @Test
    fun an_ungrounded_answer_says_so_and_offers_evidence() {
        chat(listOf(Message(1, "assistant", "guess", 0, grounded = false)))
        compose.onNodeWithText("not grounded — no supporting passage was found")
            .assertIsDisplayed()
    }

    @Test
    fun grounded_with_no_citations_is_reported_as_a_violation() {
        chat(listOf(Message(1, "assistant", "answer", 0, grounded = true)))
        compose.onNodeWithText("grounded, but the Harness returned no citations")
            .assertIsDisplayed()
    }

    @Test
    fun grounded_with_citations_lists_them() {
        val citations = CitationCodec.encode(
            listOf(Citation(n = 1, document = "Handbook", page = 3, quote = "q")),
        )
        chat(
            listOf(
                Message(1, "assistant", "answer", 0, citations = citations, grounded = true),
            ),
        )
        compose.onNodeWithText("[1]").assertIsDisplayed()
        compose.onNodeWithText("Handbook  p3").assertIsDisplayed()
    }

    // ── The new affordances ────────────────────────────────────────────────────

    @Test
    fun evidence_opens_with_the_id_of_the_message_it_was_tapped_on() {
        var opened: Long? = null
        val citations = CitationCodec.encode(listOf(Citation(n = 1, document = "D")))
        chat(
            listOf(
                Message(42, "assistant", "answer", 0, citations = citations, grounded = true),
            ),
            onOpenEvidence = { opened = it },
        )
        compose.onNodeWithText("open evidence ›").performClick()
        assertEquals(42L, opened)
    }

    @Test
    fun the_provider_is_still_named_and_now_links_out() {
        var openedProviders = 0
        chat(emptyList(), onOpenProviders = { openedProviders++ })
        compose.onNodeWithText("LFM2.5 8B").assertIsDisplayed()
        compose.onNodeWithText("LFM2.5 8B").performClick()
        assertEquals(1, openedProviders)
    }

    @Test
    fun provider_and_reported_model_remain_two_separate_facts() {
        // They disagree exactly when something is wrong; one label would hide it.
        //
        // Asserted against a DIRECT provider, because only a direct provider renders the
        // stat at all. Written originally against the swap fixture, where ChatScreen
        // deliberately omits it, this asserted the presence of something the design had
        // removed on purpose -- so it could only ever fail. The omission is not an
        // oversight and is not being worked around here: see the paired test below.
        chat(emptyList(), provider = directProvider)
        compose.onNodeWithText("Laptop llama-server").assertIsDisplayed()
        compose.onAllNodesWithText("lfm-8b", substring = true).assertCountEquals(1)
    }

    /**
     * The paired direction, which is why the test above had to change rather than be
     * deleted.
     *
     * For a SWAP provider the reported model is the id we asked for, and the provider
     * link one line below already shows it. Printing it twice was observed on a device
     * squeezing both copies down to "b…" and "q…" -- labels conveying nothing. So the
     * stat is omitted, and that omission is now asserted rather than assumed.
     */
    @Test
    fun a_swap_provider_does_not_report_the_model_twice() {
        chat(emptyList())
        compose.onNodeWithText("LFM2.5 8B").assertIsDisplayed()
        compose.onAllNodesWithText("lfm-8b", substring = true).assertCountEquals(0)
    }

    @Test
    fun the_empty_transcript_still_explains_the_way_out() {
        chat(
            emptyList(),
            status = ServerStatus(reachable = false, error = "no server"),
        )
        compose.onAllNodesWithText("No server at", substring = true).assertCountEquals(1)
        compose.onNodeWithText("Add endpoint").assertIsDisplayed()
    }
}
