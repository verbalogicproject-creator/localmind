package com.verbalogix.assistant.ui.evidence

import com.verbalogix.assistant.data.LlamaClient
import com.verbalogix.assistant.data.harness.GroundedTurnPrompt
import com.verbalogix.assistant.data.harness.HarnessDecoder
import com.verbalogix.assistant.data.harness.HarnessOutcome
import com.verbalogix.assistant.data.harness.HarnessRefusal
import com.verbalogix.assistant.data.harness.wire.AssistantTurnResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order of the gates, which is the contract.
 *
 * Retrieval first, then the model, then the Foundry — and each gate closed means nothing
 * downstream runs. The properties worth testing are the NEGATIVE ones: a truncated
 * generation must never reach the Foundry, because a receipt for a fragment is exactly the
 * artefact this whole slice exists to make impossible.
 *
 * `Dispatchers.Unconfined` keeps it deterministic without a test-dispatcher dependency:
 * `launch` runs inline to completion when nothing suspends.
 */
class GroundedTurnControllerTest {

    private val packId = "kf:pack:${"a1".repeat(32)}"
    private val releaseId = "kf:pack-release:${"b2".repeat(32)}"
    private val evidenceId = "kf:evidence:${"d1".repeat(32)}"

    private val target = RetrievalTarget(
        packId = packId,
        releaseId = releaseId,
        allowedSensitivities = listOf("internal"),
        active = true,
    )

    private fun evidence(items: Int = 1) = RetrievalEvidence(
        answerability = "supported",
        disposition = "succeeded",
        reasonCode = null,
        items = (0 until items).map { index ->
            EvidenceEntry(
                evidenceId = "kf:evidence:${"%02x".format(index + 1).repeat(32)}",
                packId = packId,
                releaseId = releaseId,
                kind = "chunk",
                text = "Evidence body $index",
                knowledgeStatus = "supported",
                uncertainty = "none",
                sources = emptyList(),
                graphPathIds = emptyList(),
                contradictionIds = emptyList(),
                packFusedRank = index + 1,
                globalFusedRank = index + 1,
                lexicalRank = index + 1,
                graphRank = null,
            )
        },
        contradictions = emptyList(),
        omissions = emptyList(),
        truncationBoundaries = emptyList(),
        receipt = RetrievalReceipt(
            packetId = "kf:evidence-packet:${"e1".repeat(32)}",
            packetSha256 = "e2".repeat(32),
            traceId = "kf:retrieval-trace:${"e3".repeat(32)}",
            deterministicCoreSha256 = "e4".repeat(32),
            planId = "kf:query-plan:${"e5".repeat(32)}",
            resultSha256 = "e6".repeat(32),
            mountRegistrySha256 = "e7".repeat(32),
        ),
    )

    private fun goldenTurn(): HarnessOutcome<AssistantTurnResult> {
        val raw = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "goldens/stage3d-v1/client-assistant-turn-response.json",
            ),
        ) { "missing Stage 3D response golden" }.readBytes().decodeToString()
        return HarnessDecoder.decodeAssistantTurn(raw)
    }

    private fun controller(
        completion: LlamaClient.TurnCompletion? = LlamaClient.TurnCompletion(
            text = "Knowledge Foundry is deterministic. [1]",
            finishReason = "stop",
            modelId = "lfm-8b",
        ),
        finalize: suspend (JsonObject) -> HarnessOutcome<AssistantTurnResult>? = { goldenTurn() },
        submitted: MutableList<JsonObject> = mutableListOf(),
    ) = GroundedTurnController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        generate = { completion },
        finalize = { request ->
            submitted += request
            finalize(request)
        },
        now = { "2026-08-17T00:00:00Z" },
    )

    // ── nothing unusable ever reaches the Foundry ───────────────────────────

    @Test
    fun a_truncated_generation_is_never_submitted() {
        // THE CENTRAL NEGATIVE PROPERTY. `length` means the model stopped mid-sentence;
        // binding that to a receipt would certify a fragment as an answer.
        val submitted = mutableListOf<JsonObject>()
        val controller = controller(
            completion = LlamaClient.TurnCompletion("Half a sen", "length", "lfm-8b"),
            submitted = submitted,
        )
        controller.submit("q", target, evidence())

        val state = controller.state.value
        assertTrue("got $state", state is GroundedTurnUiState.ProviderFailed)
        assertTrue((state as GroundedTurnUiState.ProviderFailed).reason.contains("length"))
        assertEquals("nothing may be sent for a non-stop finish", 0, submitted.size)
    }

    @Test
    fun every_non_stop_finish_reason_stops_before_the_wire() {
        for (reason in listOf("length", "timeout", "refusal", "error")) {
            val submitted = mutableListOf<JsonObject>()
            controller(
                completion = LlamaClient.TurnCompletion("text [1]", reason, "lfm-8b"),
                submitted = submitted,
            ).submit("q", target, evidence())
            assertEquals("$reason must not be submitted", 0, submitted.size)
        }
    }

    @Test
    fun a_hallucinated_citation_is_never_submitted() {
        val submitted = mutableListOf<JsonObject>()
        val controller = controller(
            completion = LlamaClient.TurnCompletion("Invented. [7]", "stop", "lfm-8b"),
            submitted = submitted,
        )
        controller.submit("q", target, evidence(items = 1))

        assertTrue(controller.state.value is GroundedTurnUiState.ProviderFailed)
        assertEquals(0, submitted.size)
    }

    @Test
    fun an_empty_evidence_set_never_calls_the_model_at_all() {
        var generated = 0
        val controller = GroundedTurnController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            generate = {
                generated++
                LlamaClient.TurnCompletion("x [1]", "stop", "lfm-8b")
            },
            finalize = { goldenTurn() },
            now = { "2026-08-17T00:00:00Z" },
        )
        controller.submit("q", target, evidence(items = 0))

        assertTrue(controller.state.value is GroundedTurnUiState.ProviderFailed)
        assertEquals("a turn with nothing to ground on calls no model", 0, generated)
    }

    @Test
    fun no_model_endpoint_is_a_provider_failure_not_a_foundry_verdict() {
        val submitted = mutableListOf<JsonObject>()
        val controller = controller(completion = null, submitted = submitted)
        controller.submit("q", target, evidence())
        assertTrue(controller.state.value is GroundedTurnUiState.ProviderFailed)
        assertEquals(0, submitted.size)
    }

    @Test
    fun no_session_is_reported_as_a_refusal_rather_than_an_answer() {
        val controller = controller(finalize = { null })
        controller.submit("q", target, evidence())
        assertTrue(controller.state.value is GroundedTurnUiState.Refused)
    }

    // ── what gets sent, when something is sent ──────────────────────────────

    @Test
    fun the_submitted_request_binds_the_retrieval_it_came_from() {
        val submitted = mutableListOf<JsonObject>()
        controller(submitted = submitted).submit("Knowledge", target, evidence())

        val request = submitted.single()
        val expected = request["expected_evidence"]!!.toString()
        // The four digests the Foundry re-derives. If any of these were rebuilt from
        // somewhere other than the retrieval that produced the evidence, the Foundry would
        // report drift and blame the mounted packs for a client-side inconsistency.
        assertTrue("e6".repeat(32) in expected)
        assertTrue("e2".repeat(32) in expected)
        assertTrue("e7".repeat(32) in expected)
        assertTrue("kf:evidence-packet:${"e1".repeat(32)}" in expected)
        // And it seals itself, exactly as the Foundry will recompute.
        assertEquals(
            request["request_sha256"]!!.toString().trim('"'),
            com.verbalogix.assistant.data.harness.CanonicalJson
                .selfDigest(request, "request_sha256"),
        )
    }

    @Test
    fun the_submitted_observation_declares_the_template_that_was_actually_used() {
        // The receipt's `prompt_template_sha256` is how someone later asks "what
        // instructions produced this answer?". If this were pinned to a literal here, or
        // built from anything other than the object that renders the prompt, an edit to the
        // rules could ship while every receipt kept certifying the old text.
        val submitted = mutableListOf<JsonObject>()
        controller(submitted = submitted).submit("Knowledge", target, evidence())

        val template = submitted.single()
            .getValue("provider_observation").jsonObject
            .getValue("prompt_template").jsonObject
        assertEquals(
            GroundedTurnPrompt.TEMPLATE_ID,
            template.getValue("template_id").jsonPrimitive.content,
        )
        assertEquals(
            GroundedTurnPrompt.TEMPLATE_SHA256,
            template.getValue("template_sha256").jsonPrimitive.content,
        )
    }

    @Test
    fun a_grounded_reply_is_presented_with_its_receipt() {
        val controller = controller()
        controller.submit("Knowledge", target, evidence())

        val state = controller.state.value
        assertTrue("got $state", state is GroundedTurnUiState.Grounded)
        val grounded = state as GroundedTurnUiState.Grounded
        // The question the turn was BUILT from, carried through to the screen. The field it
        // was typed into stays editable while the answer sits below it, so an answer that
        // could not name its own question would silently appear to be about a newer one.
        assertEquals("Knowledge", grounded.question)
        assertEquals("lfm-8b", grounded.modelId)
        assertEquals("grounded", grounded.receipt.disposition)
        assertEquals(2, grounded.segments.size)
        // The receipt names what it does NOT prove, and that sentence is carried through.
        assertTrue(grounded.receipt.proofLimit.contains("not source truth"))
    }

    @Test
    fun a_foundry_failure_is_not_relabelled_as_a_model_failure() {
        // Drift, citation closure and digest mismatch all arrive here. The model may have
        // answered perfectly and the mounted state moved underneath it, so blaming the
        // model would send the user to rephrase a question that was never the problem.
        val controller = controller(
            finalize = { HarnessOutcome.Unsuccessful("failed", "evidence-drift") },
        )
        controller.submit("q", target, evidence())

        val state = controller.state.value
        assertTrue("got $state", state is GroundedTurnUiState.Refused)
        assertTrue((state as GroundedTurnUiState.Refused).detail.contains("evidence-drift"))
    }

    @Test
    fun a_refused_reply_keeps_its_own_reason() {
        val controller = controller(
            finalize = { HarnessOutcome.Refused(HarnessRefusal.RuntimeContract("0.4.0", "0.3.3")) },
        )
        controller.submit("q", target, evidence())
        assertTrue(controller.state.value is GroundedTurnUiState.Refused)
    }

    @Test
    fun a_new_turn_supersedes_an_older_one() {
        // Same ordering rule as retrieval: the answer to a replaced question must not
        // appear beneath evidence that has moved on.
        val controller = controller()
        controller.submit("first", target, evidence())
        controller.reset()
        assertEquals(GroundedTurnUiState.Idle, controller.state.value)
    }
}
