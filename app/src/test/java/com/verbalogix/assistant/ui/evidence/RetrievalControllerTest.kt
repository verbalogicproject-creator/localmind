package com.verbalogix.assistant.ui.evidence

import com.verbalogix.assistant.data.harness.HarnessDecoder
import com.verbalogix.assistant.data.harness.HarnessOutcome
import com.verbalogix.assistant.data.harness.HarnessRefusal
import com.verbalogix.assistant.data.harness.wire.QueryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Submission, gating and ordering — the rules whose failures are invisible.
 *
 * A stale answer looks exactly like a fresh one, and a question answered from the wrong
 * release looks exactly like one answered from the right one. So these are tested rather
 * than reasoned about, on the JVM, against a source that is a lambda.
 *
 * `Dispatchers.Unconfined` is what makes that deterministic without a test dispatcher
 * dependency: `launch` runs the body inline until it suspends on the fake's deferred, and
 * completing that deferred resumes it inline on the completing thread. Every step below
 * therefore happens in a defined order that the test itself chooses.
 */
class RetrievalControllerTest {

    private val packId =
        "kf:pack:c597b1bfbc5ff099921cfc338451b34c5d8e10e82c2ee6a290d4c53a2e7e5efe"
    private val releaseId =
        "kf:pack-release:7b8d2db313b98cb708b65b34e0143faf19b126daa9eda77e6407ca3ed452dac5"

    private val active = RetrievalTarget(
        packId = packId,
        releaseId = releaseId,
        allowedSensitivities = listOf("internal"),
        active = true,
    )

    private fun goldenOutcome(): HarnessOutcome<QueryResult> = HarnessDecoder.decodeQueryResult(
        checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "goldens/stage3c-v1/client-query-response.json",
            ),
        ) { "missing retrieval golden" }.readBytes().decodeToString(),
    )

    private fun controller(source: RetrievalSource) =
        RetrievalController(CoroutineScope(Dispatchers.Unconfined), source)

    // ── the gate ────────────────────────────────────────────────────────────

    @Test
    fun an_inactive_release_is_refused_before_anything_reaches_the_wire() {
        var asked = 0
        val controller = controller { _, _ ->
            asked++
            goldenOutcome()
        }
        controller.submit("what is a pack", active.copy(active = false))

        assertEquals(RetrievalUiState.InactiveRelease, controller.state.value)
        // THE COUNT IS THE ASSERTION. Retrieval runs against what is MOUNTED, so a question
        // posed from an inactive release would have been answered from whatever is mounted
        // instead and shown under this release's name.
        assertEquals("no request may be made against an inactive release", 0, asked)
    }

    @Test
    fun an_empty_question_is_not_an_act() {
        var asked = 0
        val controller = controller { _, _ ->
            asked++
            goldenOutcome()
        }
        controller.submit("   ", active)
        assertEquals(RetrievalUiState.Idle, controller.state.value)
        assertEquals(0, asked)
    }

    @Test
    fun text_the_contract_forbids_never_reaches_the_wire() {
        var asked = 0
        val controller = controller { _, _ ->
            asked++
            goldenOutcome()
        }
        controller.submit("two\nlines", active)
        assertTrue(controller.state.value is RetrievalUiState.Refused)
        assertEquals(0, asked)
    }

    @Test
    fun a_question_is_trimmed_and_sent_once() {
        val sent = mutableListOf<String>()
        val controller = controller { text, _ ->
            sent += text
            goldenOutcome()
        }
        controller.submit("  what is a pack  ", active)

        assertEquals(listOf("what is a pack"), sent)
        assertTrue(controller.state.value is RetrievalUiState.Ready)
    }

    // ── ordering ────────────────────────────────────────────────────────────

    /**
     * THE OUT-OF-ORDER CASE, which is the one that matters.
     *
     * The second question's answer arrives first and the first question's answer arrives
     * late. Without the ticket check the late one wins, and the screen then shows evidence
     * for a question the user has already replaced — with no visible sign of it, because a
     * stale retrieval renders exactly like a fresh one.
     */
    @Test
    fun a_late_answer_never_replaces_a_newer_one() {
        val first = CompletableDeferred<HarnessOutcome<QueryResult>>()
        val second = CompletableDeferred<HarnessOutcome<QueryResult>>()
        val controller = controller { text, _ ->
            if (text == "first") first.await() else second.await()
        }

        controller.submit("first", active)
        controller.submit("second", active)
        assertEquals(RetrievalUiState.Querying, controller.state.value)

        // Newer completes first, and is displayed.
        second.complete(goldenOutcome())
        assertTrue(controller.state.value is RetrievalUiState.Ready)

        // Older completes late, carrying something visibly different, and is discarded.
        first.complete(HarnessOutcome.Refused(HarnessRefusal.Undecodable("stale")))
        assertTrue(
            "the overtaken answer must not reach the screen",
            controller.state.value is RetrievalUiState.Ready,
        )
    }

    @Test
    fun the_newest_answer_is_the_one_shown_when_replies_arrive_in_order() {
        val first = CompletableDeferred<HarnessOutcome<QueryResult>>()
        val second = CompletableDeferred<HarnessOutcome<QueryResult>>()
        val controller = controller { text, _ ->
            if (text == "first") first.await() else second.await()
        }

        controller.submit("first", active)
        controller.submit("second", active)

        first.complete(HarnessOutcome.Refused(HarnessRefusal.Undecodable("stale")))
        assertEquals(
            "an answer to a replaced question is not displayed, in any order",
            RetrievalUiState.Querying,
            controller.state.value,
        )

        second.complete(goldenOutcome())
        assertTrue(controller.state.value is RetrievalUiState.Ready)
    }

    /** A submission while one is in flight invalidates the earlier answer even if it is a gate. */
    @Test
    fun switching_to_an_inactive_target_discards_an_in_flight_answer() {
        val pending = CompletableDeferred<HarnessOutcome<QueryResult>>()
        val controller = controller { _, _ -> pending.await() }

        controller.submit("first", active)
        controller.submit("second", active.copy(active = false))
        assertEquals(RetrievalUiState.InactiveRelease, controller.state.value)

        pending.complete(goldenOutcome())
        assertEquals(
            "an answer to the previous question must not overwrite a later refusal",
            RetrievalUiState.InactiveRelease,
            controller.state.value,
        )
    }

    // ── the session seam ────────────────────────────────────────────────────

    @Test
    fun no_session_is_reported_as_something_the_user_can_fix() {
        // A null source result means no bearer was held. Reported as an expired session
        // rather than a refusal, because the remedy is pairing and a refusal offers none.
        val controller = controller { _, _ -> null }
        controller.submit("what is a pack", active)
        assertEquals(RetrievalUiState.SessionExpired(null), controller.state.value)
    }

    @Test
    fun the_strict_golden_decodes_into_a_ready_state_through_the_whole_path() {
        val controller = controller { _, _ -> goldenOutcome() }
        controller.submit("Knowledge", active)

        val state = controller.state.value
        assertTrue("got $state", state is RetrievalUiState.Ready)
        val evidence = (state as RetrievalUiState.Ready).evidence
        assertEquals("supported", evidence.answerability)
        assertEquals(2, evidence.items.size)
        assertEquals(64, evidence.receipt.packetSha256.length)
    }
}
