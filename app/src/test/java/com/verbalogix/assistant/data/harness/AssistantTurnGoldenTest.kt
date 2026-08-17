package com.verbalogix.assistant.data.harness

import com.verbalogix.assistant.data.harness.wire.AssistantTurnResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Stage 3D goldens, both directions.
 *
 * THIS IS THE FIRST PAYLOAD LOCALMIND BOTH SENDS AND READS, so the golden proves the
 * encoder as well as the decoder — and the encoder is the half that can fail silently. A
 * decoder that is wrong refuses a valid document and says so. An encoder that is wrong
 * produces a request the Foundry hashes to a different value, and the only symptom is
 * `request-invalid` with no indication of which byte moved.
 *
 * So the request test rebuilds the Foundry's own request from its own inputs and requires
 * byte equality with the file. Not field equality: BYTES.
 */
class AssistantTurnGoldenTest {

    private fun golden(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("goldens/stage3d-v1/$name")) {
            "missing Stage 3D golden: $name"
        }.readBytes().decodeToString()

    private fun request(): JsonObject = HarnessDecoder.STRICT
        .decodeFromString(JsonObject.serializer(), golden("client-assistant-turn-request.json"))
        .getValue("turn_request").jsonObject

    // ── the request this client builds ──────────────────────────────────────

    /**
     * Rebuild the golden request from the golden's own inputs, and require the same bytes.
     *
     * Every value fed in below is read out of the golden rather than written here, so what
     * is under test is the CONSTRUCTION — key order, nesting, the derived answer text, the
     * two-step observation identity, and the three self-digests — and not this author's
     * ability to retype a fixture.
     */
    @Test
    fun the_builder_reproduces_the_foundrys_request_byte_for_byte() {
        val theirs = request()
        val observation = theirs.getValue("provider_observation").jsonObject
        val model = observation.getValue("model").jsonObject
        val template = observation.getValue("prompt_template").jsonObject
        val evidence = observation.getValue("evidence").jsonObject
        val expected = theirs.getValue("expected_evidence").jsonObject
        val query = theirs.getValue("query_request").jsonObject

        val segments = observation.getValue("answer").jsonObject
            .getValue("segments").jsonArray.map { element ->
                val segment = element.jsonObject
                AssistantTurnRequest.Segment(
                    kind = segment.getValue("kind").jsonPrimitive.content,
                    text = segment.getValue("text").jsonPrimitive.content,
                    evidenceIds = segment.getValue("evidence_ids").jsonArray
                        .map { it.jsonPrimitive.content },
                )
            }

        val ours = AssistantTurnRequest.turnRequest(
            queryRequest = HarnessRequest.queryRequest(
                text = query.getValue("text").jsonPrimitive.content,
                packId = query.getValue("pack_scope").jsonObject
                    .getValue("pack_ids").jsonArray.single().jsonPrimitive.content,
                allowedSensitivities = query.getValue("access").jsonObject
                    .getValue("allowed_sensitivities").jsonArray.map { it.jsonPrimitive.content },
            ),
            queryResultSha256 = expected.getValue("query_result_sha256").jsonPrimitive.content,
            packetId = expected.getValue("packet_id").jsonPrimitive.content,
            packetSha256 = expected.getValue("packet_sha256").jsonPrimitive.content,
            mountRegistrySha256 = expected.getValue("mount_registry_sha256").jsonPrimitive.content,
            observation = AssistantTurnRequest.providerObservation(
                modelId = model.getValue("model_id").jsonPrimitive.content,
                artifactSha256 = null,
                runtimeVersion = null,
                templateId = template.getValue("template_id").jsonPrimitive.content,
                templateSha256 = template.getValue("template_sha256").jsonPrimitive.content,
                packetId = evidence.getValue("packet_id").jsonPrimitive.content,
                packetSha256 = evidence.getValue("packet_sha256").jsonPrimitive.content,
                answer = AssistantTurnRequest.groundedAnswer(segments),
                finishReason = observation.getValue("finish_reason").jsonPrimitive.content,
                observedAt = observation.getValue("observed_at").jsonPrimitive.content,
            ),
        )

        assertEquals(
            "the built request must be the Foundry's bytes, not merely its fields",
            golden("client-assistant-turn-request.json"),
            AssistantTurnRequest.body(ours),
        )
    }

    @Test
    fun the_query_request_embedded_in_a_turn_is_the_one_retrieval_sent() {
        // Stage 3D re-runs this exact request server-side and compares digests. A rebuild
        // that differed in any way would fail as drift, blaming the mounted packs for a
        // client-side inconsistency.
        val query = request().getValue("query_request").jsonObject
        val rebuilt = HarnessRequest.queryRequest(
            "Knowledge",
            query.getValue("pack_scope").jsonObject
                .getValue("pack_ids").jsonArray.single().jsonPrimitive.content,
            listOf("internal"),
        )
        assertEquals(CanonicalJson.text(query), CanonicalJson.text(rebuilt))
    }

    @Test
    fun the_body_carries_exactly_one_key() {
        // "No additional wrapper fields are accepted."
        val body = HarnessDecoder.STRICT
            .decodeFromString(JsonObject.serializer(), AssistantTurnRequest.body(request()))
        assertEquals(setOf("turn_request"), body.keys)
    }

    // ── the response this client reads ──────────────────────────────────────

    @Test
    fun the_response_golden_decodes_into_a_grounded_turn() {
        val outcome = HarnessDecoder.decodeAssistantTurn(golden("client-assistant-turn-response.json"))
        check(outcome is HarnessOutcome.Decoded)
        val turn: AssistantTurnResult = outcome.value
        assertEquals("grounded", turn.disposition)
        assertEquals("supported", turn.evidence.answerability)
        assertEquals("lfm-8b", turn.model?.modelId)
        assertEquals("localmind/grounded-turn/1.0", turn.promptTemplate?.templateId)
        assertEquals(2, turn.answer?.segments?.size)
        assertEquals(1, turn.receipt.citedEvidenceIds.size)
        // The artifact digest and runtime version are legitimately absent: llama-swap does
        // not name the weights it loaded. Null here is a fact, not a gap.
        assertNull(turn.model?.artifactSha256)
        assertNull(turn.model?.runtimeVersion)
    }

    @Test
    fun the_answer_text_is_exactly_its_segments() {
        val outcome = HarnessDecoder.decodeAssistantTurn(golden("client-assistant-turn-response.json"))
        check(outcome is HarnessOutcome.Decoded)
        val answer = checkNotNull(outcome.value.answer)
        assertEquals(answer.segments.joinToString("\n\n") { it.text }, answer.text)
        // One claim, cited; one uncertainty, uncited. Both arms of the oneOf in one golden.
        assertEquals(listOf("claim", "uncertainty"), answer.segments.map { it.kind })
        assertTrue(answer.segments[0].evidenceIds.isNotEmpty())
        assertTrue(answer.segments[1].evidenceIds.isEmpty())
    }

    // ── what the decoder must refuse ────────────────────────────────────────

    private fun mutate(from: String, to: String): HarnessOutcome<AssistantTurnResult> {
        val raw = golden("client-assistant-turn-response.json")
        assertTrue("mutation target absent: $from", from in raw)
        return HarnessDecoder.decodeAssistantTurn(raw.replace(from, to))
    }

    @Test
    fun a_receipt_that_describes_a_different_packet_is_refused() {
        // A receipt is what makes "grounded" checkable by someone else. One that disagrees
        // with the body it accompanies is worse than none, because it looks like proof.
        //
        // ANCHORED SO ONLY ONE COPY MOVES. `packet_id` appears four times in this document,
        // and the first version of this test replaced them all -- leaving a perfectly
        // self-consistent turn about a different packet, which the cross-check correctly
        // accepted and the test wrongly called a pass. The anchor below is unique to
        // `result.evidence`, so the receipt keeps the original and the two now disagree.
        val outcome = mutate(
            """"answerability":"supported","mount_registry_sha256":""" +
                """"495ec6fc758ab14e3e35ad8c18e354a8d001ed97fce9321dc7f1b9f70b7378ba",""" +
                """"packet_id":"kf:evidence-packet:27ad1838""",
            """"answerability":"supported","mount_registry_sha256":""" +
                """"495ec6fc758ab14e3e35ad8c18e354a8d001ed97fce9321dc7f1b9f70b7378ba",""" +
                """"packet_id":"kf:evidence-packet:99ad1838""",
        )
        assertTrue("got $outcome", outcome is HarnessOutcome.Refused)
    }

    @Test
    fun an_answer_whose_text_is_not_its_segments_is_refused() {
        // The property that makes citations mean anything: if the visible text could differ
        // from the segments, a sentence could appear on screen carrying no sources at all.
        val outcome = mutate(
            """"text":"Knowledge Foundry is deterministic and evidence grounded.\n\nThis receipt does not establish source truth."""",
            """"text":"Knowledge Foundry is deterministic and evidence grounded. Also it cures illness.\n\nThis receipt does not establish source truth."""",
        )
        assertTrue("got $outcome", outcome is HarnessOutcome.Refused)
    }

    @Test
    fun a_claim_segment_with_no_citation_is_refused() {
        val outcome = mutate(
            """"evidence_ids":["kf:evidence:095271fc8640f5dffb1e6250aa589cd08736b03de64d0da991782b794aad9c82"],"kind":"claim"""",
            """"evidence_ids":[],"kind":"claim"""",
        )
        assertTrue("got $outcome", outcome is HarnessOutcome.Refused)
    }

    @Test
    fun a_grounded_turn_missing_its_model_binding_is_refused() {
        // "Grounded" without a named model is a claim with nothing behind it.
        val outcome = mutate(
            """"model":{"artifact_sha256":null,"endpoint_kind":"loopback-openai-compatible","model_id":"lfm-8b","runtime_id":"llama-swap","runtime_version":null},"prompt_template"""",
            """"model":null,"prompt_template"""",
        )
        assertTrue("got $outcome", outcome is HarnessOutcome.Refused)
    }

    @Test
    fun an_unknown_field_anywhere_in_the_turn_is_refused() {
        val outcome = mutate(
            """"disposition":"grounded","evidence"""",
            """"disposition":"grounded","confidence":0.9,"evidence"""",
        )
        assertTrue("got $outcome", outcome is HarnessOutcome.Refused)
    }

    @Test
    fun a_response_that_is_not_the_turn_operation_is_refused() {
        val outcome = mutate(
            """"operation_id":"assistant.turn.finalize"""",
            """"operation_id":"query.retrieve"""",
        )
        assertTrue("got $outcome", outcome is HarnessOutcome.Refused)
    }

    @Test
    fun the_four_point_zero_envelope_is_admitted_and_three_point_zero_ids_still_are() {
        // Additive, not a replacement: the retrieval and expert surfaces keep working.
        for (id in listOf(
            SchemaIds.OPERATION_RESPONSE_TURN, SchemaIds.ASSISTANT_TURN,
            SchemaIds.ASSISTANT_TURN_RECEIPT, SchemaIds.GROUNDED_ANSWER,
            SchemaIds.PROVIDER_OBSERVATION,
            SchemaIds.OPERATION_RESPONSE, SchemaIds.QUERY_RESULT,
            SchemaIds.EXPERT_CATALOG, SchemaIds.EXPERT_RELEASE_DETAIL, SchemaIds.CAPABILITIES,
        )) {
            assertTrue("$id must be accepted", SchemaNegotiation.negotiate(id).isAccepted)
        }
        // And the turn's own request schema is NOT in the accepted set: it is something this
        // client sends, never something it reads back.
        assertTrue(SchemaIds.ASSISTANT_TURN_REQUEST !in SchemaNegotiation.ACCEPTED)
    }
}
