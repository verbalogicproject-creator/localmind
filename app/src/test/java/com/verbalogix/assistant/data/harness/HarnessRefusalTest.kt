package com.verbalogix.assistant.data.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything this client will not read.
 *
 * These are built by MUTATING the server-emitted golden, one field at a time, rather than
 * by hand-authoring a payload. The distinction matters: a hand-written "bad response" is a
 * guess about what wrong looks like, while a golden with exactly one field changed is a
 * real document with a known single defect. Every mutation below starts from bytes the
 * Foundry produced.
 *
 * THE GOLDENS ARE CANONICAL JSON -- compact, keys sorted, no whitespace. The first version
 * of this file mutated against pretty-printed strings, so every `replace` was a no-op and
 * the tests ran against the UNMODIFIED golden. They failed loudly here, which was luck:
 * seven of them would have PASSED for the wrong reason had the decoder been more
 * permissive. [mutate] now fails when its target is absent, so a mutation that does not
 * apply can never be mistaken for a defect that was tolerated.
 */
class HarnessRefusalTest {

    private fun golden(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("goldens/stage3c-v1/$name")) {
            "missing golden: $name"
        }.readBytes().decodeToString()

    private fun capabilities() = golden("client-capabilities-response.json")
    private fun catalog() = golden("client-empty-catalog-response.json")

    /** Replace exactly one substring, and prove the replacement happened. */
    private fun mutate(source: String, target: String, replacement: String): String {
        assertTrue("mutation target absent from the golden: $target", source.contains(target))
        val mutated = source.replace(target, replacement)
        assertNotEquals("mutation produced identical bytes", source, mutated)
        return mutated
    }

    private fun refusalOf(raw: String): HarnessRefusal =
        (HarnessDecoder.decodeCapabilities(raw) as HarnessOutcome.Refused).refusal

    // ── negotiation and versions ────────────────────────────────────────────

    @Test
    fun a_two_point_zero_envelope_is_refused_on_the_expert_path() {
        val refusal = refusalOf(
            mutate(
                capabilities(),
                "knowledge-foundry-operation-response/3.0",
                "knowledge-foundry-operation-response/2.0",
            ),
        )
        assertTrue("expected Schema, got $refusal", refusal is HarnessRefusal.Schema)
        assertTrue(refusal.reason.contains("2.0"))
    }

    @Test
    fun release_detail_was_admitted_only_after_its_golden_arrived() {
        // This test previously asserted the OPPOSITE, and firing was its job: the id was
        // held out of ACCEPTED while it had a closed schema and no server response. The
        // golden arrived, the decoder was proved against it, and only then did the id
        // move. The ordering is the rule; this is where it is recorded.
        assertTrue(SchemaIds.EXPERT_RELEASE_DETAIL in SchemaNegotiation.ACCEPTED)
        assertTrue(
            SchemaNegotiation.negotiate(SchemaIds.EXPERT_RELEASE_DETAIL) is SchemaVerdict.Accepted,
        )
    }

    @Test
    fun a_different_runtime_contract_is_refused_even_when_every_field_parses() {
        val refusal = refusalOf(
            mutate(capabilities(), """"runtime_contract":"0.3.2"""", """"runtime_contract":"0.4.0""""),
        )
        assertTrue("expected RuntimeContract, got $refusal", refusal is HarnessRefusal.RuntimeContract)
        assertTrue(refusal.reason.contains("0.4.0"))
    }

    // ── the closed shapes ───────────────────────────────────────────────────

    @Test
    fun an_unknown_field_in_the_envelope_is_refused_rather_than_ignored() {
        // additionalProperties: false, honoured. Dropping the field would mean reading a
        // document written to an agreement this build does not share.
        val refusal = refusalOf(
            mutate(
                capabilities(),
                """"disposition":"succeeded"""",
                """"disposition":"succeeded","unexpected_field":1""",
            ),
        )
        assertTrue("expected Undecodable, got $refusal", refusal is HarnessRefusal.Undecodable)
    }

    @Test
    fun an_unknown_field_inside_the_result_is_refused_too() {
        val refusal = refusalOf(
            mutate(
                capabilities(),
                """"runtime_contract":"0.3.2"""",
                """"runtime_contract":"0.3.2","unexpected_inner":true""",
            ),
        )
        assertTrue("expected Undecodable, got $refusal", refusal is HarnessRefusal.Undecodable)
    }

    @Test
    fun a_missing_required_key_is_refused() {
        // `receipt` is required and appears only in the envelope. Required means PRESENT,
        // and a Kotlin default would have silently accepted its absence -- which is why
        // the wire types declare none.
        val refusal = refusalOf(mutate(capabilities(), """"receipt":null,""", ""))
        assertTrue("expected Undecodable, got $refusal", refusal is HarnessRefusal.Undecodable)
    }

    @Test
    fun an_unknown_disposition_is_refused() {
        val refusal = refusalOf(
            mutate(capabilities(), """"disposition":"succeeded"""", """"disposition":"maybe""""),
        )
        assertTrue("expected UnknownDisposition, got $refusal", refusal is HarnessRefusal.UnknownDisposition)
    }

    @Test
    fun a_declined_disposition_is_reported_as_the_harness_declining_not_as_a_client_fault() {
        // `refused` is the Harness working correctly. Presenting it as a decode failure
        // would blame the wrong component.
        val outcome = HarnessDecoder.decodeCapabilities(
            mutate(capabilities(), """"disposition":"succeeded"""", """"disposition":"refused""""),
        )
        assertTrue("expected Unsuccessful, got $outcome", outcome is HarnessOutcome.Unsuccessful)
        assertEquals("refused", (outcome as HarnessOutcome.Unsuccessful).disposition)
    }

    @Test
    fun a_result_carrying_the_wrong_inner_schema_is_refused() {
        val refusal = refusalOf(
            mutate(
                capabilities(),
                """"schema":"knowledge-foundry-capabilities/3.0"""",
                """"schema":"knowledge-foundry-expert-catalog/3.0"""",
            ),
        )
        assertTrue("expected ResultSchemaMismatch, got $refusal", refusal is HarnessRefusal.ResultSchemaMismatch)
    }

    // ── identity ────────────────────────────────────────────────────────────

    @Test
    fun identity_must_be_kf_kind_sha256() {
        assertTrue(HarnessDecoder.isWellFormedIdentity("kf:handbook:" + "a".repeat(64)))
        for (bad in listOf(
            "handbook-2026",                  // no scheme
            "kf:handbook:" + "a".repeat(63),  // short digest
            "kf:handbook:" + "A".repeat(64),  // upper-case hex
            "kf:Handbook:" + "a".repeat(64),  // upper-case kind
            "kf::" + "a".repeat(64),          // empty kind
            "../../etc/passwd",
        )) {
            assertFalse("$bad must be rejected", HarnessDecoder.isWellFormedIdentity(bad))
        }
    }

    // ── refusals stay out of the pairing vocabulary ─────────────────────────

    @Test
    fun no_refusal_suggests_pairing_again() {
        // The two failure families must not blur. A version or contract problem is not
        // fixed by re-pairing, and saying so would loop the user against a fault only a
        // software update clears.
        val refusals: List<HarnessRefusal> = listOf(
            HarnessRefusal.Schema(SchemaVerdict.Unsupported("x/9.9")),
            HarnessRefusal.RuntimeContract("0.4.0", "0.3.2"),
            HarnessRefusal.OperationMismatch("a", "b"),
            HarnessRefusal.UnknownDisposition("maybe"),
            HarnessRefusal.ResultSchemaMismatch("a", "b"),
            HarnessRefusal.TrustState("revoked"),
            HarnessRefusal.MountState("elsewhere"),
            HarnessRefusal.MalformedIdentity("nope"),
            HarnessRefusal.Undecodable("boom"),
        )
        for (refusal in refusals) {
            val text = refusal.reason.lowercase()
            assertTrue("${refusal::class.simpleName} has no reason", text.isNotBlank())
            assertFalse("${refusal::class.simpleName} suggests pairing: $text", text.contains("pair"))
        }
    }

    @Test
    fun an_empty_catalog_is_a_result_and_never_a_refusal() {
        assertTrue(
            "empty must decode, not refuse",
            HarnessDecoder.decodeExpertCatalog(catalog()) is HarnessOutcome.Decoded,
        )
    }
}
