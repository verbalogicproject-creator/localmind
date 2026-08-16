package com.verbalogix.assistant.data.harness

import com.verbalogix.assistant.data.harness.wire.Contradiction
import com.verbalogix.assistant.data.harness.wire.EvidencePacket
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retrieval, decoded from the server's own bytes — and from the shapes it did not send.
 *
 * The golden is `supported`, with two evidence items, no contradictions, no omissions and
 * a null reason code. A type shaped around exactly that would decode this file perfectly
 * and fail the first time two packs disagreed, which is the moment the screen matters
 * most. So the variants are exercised separately, by mutating the golden.
 */
class QueryResultGoldenTest {

    private fun golden(): String =
        checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "goldens/stage3c-v1/client-query-response.json",
            ),
        ) { "missing query golden" }.readBytes().decodeToString()

    /** Replace exactly one substring, proving the replacement happened. */
    private fun mutate(target: String, replacement: String): String {
        val source = golden()
        assertTrue("mutation target absent: $target", source.contains(target))
        return source.replace(target, replacement)
    }

    private fun decoded(): EvidencePacket =
        (HarnessDecoder.decodeQueryResult(golden()) as HarnessOutcome.Decoded).value.evidencePacket

    // ── the bytes are what was supplied ─────────────────────────────────────

    @Test
    fun the_golden_matches_its_published_digest_and_size() {
        val raw = golden()
        val sha = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("9150d4c7d39ba1464a375f52bfac66857ddd91566167f3914e94dc6446357d5a", sha)
        assertEquals(9_686, raw.toByteArray().size)
    }

    // ── the observed instance ───────────────────────────────────────────────

    @Test
    fun a_real_retrieval_decodes_end_to_end() {
        val result = (HarnessDecoder.decodeQueryResult(golden()) as HarnessOutcome.Decoded).value

        assertEquals("knowledge-foundry-query-result/2.0", result.schema)
        assertEquals("knowledge-foundry-query-plan/2.0", result.plan.schema)
        assertEquals("knowledge-foundry-retrieval-trace/2.0", result.trace.schema)
        assertEquals("succeeded", result.evidencePacket.disposition)
        assertEquals("supported", result.evidencePacket.answerability)
        assertEquals(2, result.evidencePacket.items.size)
    }

    @Test
    fun answerability_comes_from_the_harness_and_is_not_derived_from_a_count() {
        // The distinction this whole surface rests on. Two items and "supported" arrive
        // together, and the client reports the SECOND. A rule like "two or more items
        // means supported" would agree with this golden and be wrong the moment a
        // conflicted packet carried three.
        val packet = decoded()
        assertEquals("supported", packet.answerability)
        assertTrue(packet.answerability in SchemaIds.ANSWERABILITY)
    }

    @Test
    fun every_item_carries_its_provenance_ranks_and_graph_paths() {
        val item = decoded().items.first()
        assertTrue("provenance must not be empty", item.provenance.isNotEmpty())
        assertNotNull(item.ranks.packFused)
        assertNotNull(item.ranks.globalFused)
        // Present as a list even when empty: graph paths are identities, and the absence
        // of one is a fact rather than a missing field.
        assertNotNull(item.graphPathIds)
        assertTrue(item.selectedText.isNotBlank())
    }

    @Test
    fun the_receipt_identities_are_all_well_formed() {
        val result = (HarnessDecoder.decodeQueryResult(golden()) as HarnessOutcome.Decoded).value
        for (id in listOf(
            result.evidencePacket.packetId,
            result.evidencePacket.trace.traceId,
            result.plan.planId,
            result.trace.traceId,
        )) {
            assertTrue("not a kf identity: $id", HarnessDecoder.isWellFormedIdentity(id))
        }
    }

    @Test
    fun the_golden_carries_none_of_the_variants_that_matter_most() {
        // Stated as an assertion so the gap cannot be forgotten: everything below this
        // point is testing shapes the server has never actually sent us.
        val packet = decoded()
        assertTrue(packet.contradictions.isEmpty())
        assertTrue(packet.omissions.isEmpty())
        assertNull(packet.reasonCode)
    }

    // ── the shapes the golden does not contain ──────────────────────────────

    @Test
    fun a_contradiction_group_decodes() {
        // THE POLYMORPHIC CASE. `contradictions` items are `oneOf` an identity string or a
        // full group object, and no golden exercises either. A client modelling one form
        // decodes the sample perfectly and throws when two packs first disagree.
        val digest = "ab".repeat(32)
        val group = """{"detection_method":"explicit-semantic-key","disposition":"unresolved",""" +
            """"group_id":"kf:contradiction:$digest","members":[""" +
            """{"candidate_id":"kf:candidate:$digest","canonical_value_sha256":"$digest",""" +
            """"pack_id":"kf:pack:$digest","release_id":"kf:pack-release:$digest",""" +
            """"revision_id":"kf:revision:$digest"},""" +
            """{"candidate_id":"kf:candidate:${"cd".repeat(32)}","canonical_value_sha256":"$digest",""" +
            """"pack_id":"kf:pack:$digest","release_id":"kf:pack-release:$digest",""" +
            """"revision_id":"kf:revision:$digest"}]}"""
        val raw = mutate(""""contradictions":[]""", """"contradictions":[$group]""")

        val packet = (HarnessDecoder.decodeQueryResult(raw) as HarnessOutcome.Decoded)
            .value.evidencePacket
        val decodedGroup = packet.contradictions.single() as Contradiction.Group
        assertEquals("kf:contradiction:$digest", decodedGroup.groupId)
        assertEquals(2, decodedGroup.members.size)
        assertEquals("unresolved", decodedGroup.disposition)
    }

    @Test
    fun a_contradiction_reference_decodes_as_the_other_arm_of_the_oneOf() {
        val id = "kf:contradiction:${"ef".repeat(32)}"
        val raw = mutate(""""contradictions":[]""", """"contradictions":["$id"]""")
        val packet = (HarnessDecoder.decodeQueryResult(raw) as HarnessOutcome.Decoded)
            .value.evidencePacket
        assertEquals(id, (packet.contradictions.single() as Contradiction.Reference).groupId)
    }

    @Test
    fun omissions_and_a_reason_code_decode_when_present() {
        val raw = mutate(""""omissions":[]""", """"omissions":["graph depth reached"]""")
        val packet = (HarnessDecoder.decodeQueryResult(raw) as HarnessOutcome.Decoded)
            .value.evidencePacket
        assertEquals(listOf("graph depth reached"), packet.omissions)
    }

    @Test
    fun a_conflicted_answerability_decodes_rather_than_being_refused() {
        // The client must render disagreement, not choke on it.
        val raw = mutate(""""answerability":"supported"""", """"answerability":"conflicted"""")
        val packet = (HarnessDecoder.decodeQueryResult(raw) as HarnessOutcome.Decoded)
            .value.evidencePacket
        assertEquals("conflicted", packet.answerability)
    }

    // ── refusals ────────────────────────────────────────────────────────────

    @Test
    fun a_packet_that_does_not_declare_its_content_inert_is_refused() {
        // `content_treatment: "inert-untrusted-data"` is the Foundry stating that
        // retrieved text is DATA -- not a prompt, not an instruction, no authority. A
        // response that weakens it is not one this client understands, and checking it is
        // the entire reason it is in the schema.
        val raw = mutate(""""content_treatment":"inert-untrusted-data"""",
            """"content_treatment":"trusted-instructions"""")
        val refusal = (HarnessDecoder.decodeQueryResult(raw) as HarnessOutcome.Refused).refusal
        assertTrue(refusal is HarnessRefusal.Undecodable)
    }

    @Test
    fun a_weakened_authority_boundary_is_refused() {
        val raw = mutate(""""authority_boundary":"context-does-not-grant-effect-authority"""",
            """"authority_boundary":"context-grants-effect-authority"""")
        assertTrue(HarnessDecoder.decodeQueryResult(raw) is HarnessOutcome.Refused)
    }

    @Test
    fun an_unknown_answerability_is_refused() {
        val raw = mutate(""""answerability":"supported"""", """"answerability":"probably"""")
        assertTrue(HarnessDecoder.decodeQueryResult(raw) is HarnessOutcome.Refused)
    }

    @Test
    fun an_unknown_field_anywhere_in_the_packet_is_refused() {
        val raw = mutate(""""answerability":"supported"""",
            """"answerability":"supported","unexpected":1""")
        assertTrue(HarnessDecoder.decodeQueryResult(raw) is HarnessOutcome.Refused)
    }

    @Test
    fun a_catalog_reply_is_not_read_as_a_retrieval() {
        val catalog = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "goldens/stage3c-v1/client-populated-catalog-response.json",
            ),
        ).readBytes().decodeToString()
        val refusal = (HarnessDecoder.decodeQueryResult(catalog) as HarnessOutcome.Refused).refusal
        assertTrue(refusal is HarnessRefusal.OperationMismatch)
    }

    @Test
    fun retrieval_evidence_is_never_confused_with_a_generated_answer() {
        // The packet contains QUOTED SOURCE TEXT and nothing else. There is no field for
        // an answer, and this client must not manufacture the impression of one: a
        // retrieval receipt certifies WHAT WAS RETRIEVED, never that any reply used it.
        val fields = EvidencePacket.serializer().descriptor.let { d ->
            (0 until d.elementsCount).map { d.getElementName(it) }
        }
        for (absent in listOf("answer", "completion", "response_text", "generated")) {
            assertFalse("the packet must carry no answer field: $absent", absent in fields)
        }
    }
}
