package com.verbalogix.assistant.ui.evidence

import com.verbalogix.assistant.data.harness.HarnessDecoder
import com.verbalogix.assistant.data.harness.HarnessOutcome
import com.verbalogix.assistant.data.harness.HarnessRefusal
import com.verbalogix.assistant.data.harness.wire.QueryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retrieval surface, mapped from the server's own bytes.
 *
 * The property under test throughout is that nothing is added. The Harness computed
 * `answerability`; this layer carries it. Everything else is quotation, identity or
 * digest, and a summary sentence anywhere in here would be a claim the Foundry did not
 * make.
 */
class RetrievalMappingTest {

    /**
     * The pack and release the golden was actually retrieved from.
     *
     * Passed to every mapping so the correlation check runs on this path too: a mapper
     * exercised only against a target it cannot fail to match would not be exercising the
     * check at all. The mismatches live in `RetrievalCorrelationTest`.
     */
    private val target = RetrievalTarget(
        packId = "kf:pack:c597b1bfbc5ff099921cfc338451b34c5d8e10e82c2ee6a290d4c53a2e7e5efe",
        releaseId =
            "kf:pack-release:7b8d2db313b98cb708b65b34e0143faf19b126daa9eda77e6407ca3ed452dac5",
        allowedSensitivities = listOf("internal"),
        active = true,
    )

    private fun golden(name: String = "client-query-response.json"): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("goldens/stage3c-v1/$name")) {
            "missing golden: $name"
        }.readBytes().decodeToString()

    private fun evidence(): RetrievalEvidence =
        (
            HarnessDecoder.decodeQueryResult(golden()).toRetrievalState(target)
                as RetrievalUiState.Ready
            ).evidence

    @Test
    fun a_real_retrieval_becomes_a_ready_state() {
        val e = evidence()
        assertEquals("supported", e.answerability)
        assertEquals("succeeded", e.disposition)
        assertEquals(2, e.items.size)
    }

    @Test
    fun the_answerability_is_carried_not_computed() {
        // Two items and "supported" arrive together. A client rule like "two or more
        // items means supported" would agree with this golden and be wrong the moment a
        // conflicted packet carried three.
        val e = evidence()
        assertEquals("supported", e.answerability)
        assertEquals(2, e.items.size)
    }

    @Test
    fun every_item_keeps_its_quotation_provenance_and_ranks() {
        val item = evidence().items.first()
        assertTrue(item.text.isNotBlank())
        assertTrue(item.sources.isNotEmpty())
        assertTrue(item.sources.first().logicalLocator.isNotBlank())
        assertTrue(item.packFusedRank >= 1)
        assertTrue(item.globalFusedRank >= 1)
    }

    @Test
    fun the_receipt_carries_full_length_digests() {
        val r = evidence().receipt
        for (digest in listOf(
            r.packetSha256, r.deterministicCoreSha256, r.resultSha256, r.mountRegistrySha256,
        )) {
            assertEquals("digests are never abbreviated here", 64, digest.length)
        }
        for (id in listOf(r.packetId, r.traceId, r.planId)) {
            assertTrue(HarnessDecoder.isWellFormedIdentity(id))
        }
    }

    // ── the shapes the golden does not carry ────────────────────────────────

    @Test
    fun a_contradiction_reference_maps_to_a_group_with_no_members() {
        // The bare-identity arm of the oneOf. Empty members must mean "detail not
        // included", which the view says explicitly -- rendering it as "no members" would
        // understate a disagreement between packs.
        val id = "kf:contradiction:${"ab".repeat(32)}"
        val raw = golden().replace(""""contradictions":[]""", """"contradictions":["$id"]""")
        val e = (
            HarnessDecoder.decodeQueryResult(raw).toRetrievalState(target)
                as RetrievalUiState.Ready
            ).evidence
        val view = e.contradictions.single()
        assertEquals(id, view.groupId)
        assertTrue(view.members.isEmpty())
        assertNull(view.detectionMethod)
    }

    @Test
    fun a_declined_disposition_is_not_reported_as_a_refusal() {
        // The Harness abstaining is it working correctly. Calling that a client failure
        // would blame the wrong component and bury the reason code.
        val outcome: HarnessOutcome<QueryResult> = HarnessOutcome.Unsuccessful("abstained", "no-evidence")
        val state = outcome.toRetrievalState(target)
        assertTrue(state is RetrievalUiState.Declined)
        assertEquals("abstained", (state as RetrievalUiState.Declined).disposition)
        assertEquals("no-evidence", state.reasonCode)
    }

    @Test
    fun a_contract_mismatch_is_reported_as_a_version_problem_not_a_refusal() {
        // Both mean "the reply was not read", and only one is fixed by shipping software.
        // A user told "something went wrong" would try pairing again, forever.
        val outcome: HarnessOutcome<QueryResult> =
            HarnessOutcome.Refused(HarnessRefusal.RuntimeContract("0.4.0", "0.3.2"))
        val state = outcome.toRetrievalState(target)
        assertTrue("got $state", state is RetrievalUiState.Incompatible)
        assertTrue((state as RetrievalUiState.Incompatible).detail.contains("0.4.0"))
    }

    @Test
    fun a_spent_token_is_reported_as_a_session_to_restore() {
        // `token-expired` has a remedy the user can perform, so it must not be folded into
        // "the expert did not answer" -- which would leave them waiting on a Foundry that
        // is working perfectly.
        val outcome: HarnessOutcome<QueryResult> =
            HarnessOutcome.Unsuccessful("failed", "token-expired")
        assertTrue(outcome.toRetrievalState(target) is RetrievalUiState.SessionExpired)
    }

    // ── no answer, anywhere ─────────────────────────────────────────────────

    @Test
    fun the_evidence_model_has_no_field_that_could_hold_an_answer() {
        // Structural, not a matter of discipline. `canonical-assistant-turn` is
        // planned-not-implemented, so nothing could attest that a reply used this
        // evidence -- and a field to put one in is how that claim starts.
        val fields = RetrievalEvidence::class.java.declaredFields.map { it.name }
        for (forbidden in listOf("answer", "completion", "reply", "generated", "summary")) {
            assertFalse("must carry no $forbidden field: $fields", forbidden in fields)
        }
    }

    @Test
    fun nothing_in_the_mapping_scores_or_counts_the_evidence() {
        // No confidence, no score, no "strength". The Foundry computed the only verdict
        // there is, and a second one invented here would compete with it.
        val fields = RetrievalEvidence::class.java.declaredFields.map { it.name }
        for (forbidden in listOf("score", "confidence", "strength", "relevance")) {
            assertFalse("must carry no $forbidden field: $fields", forbidden in fields)
        }
    }
}
