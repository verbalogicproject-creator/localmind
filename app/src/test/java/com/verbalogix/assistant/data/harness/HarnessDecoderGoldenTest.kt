package com.verbalogix.assistant.data.harness

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding the Foundry's own bytes, unmodified.
 *
 * THE FILES ARE SERVER-EMITTED AND CHECKED IN VERBATIM. Their digests are asserted below,
 * so a golden that drifts fails here rather than quietly re-baselining the expectations
 * built on it. That check is the difference between "the decoder still works" and "the
 * decoder still agrees with the server".
 *
 *   client-capabilities-response.json   77799b8f…
 *   client-empty-catalog-response.json  f0eacd3c…
 *
 * WHAT THESE DO NOT PROVE. The only catalog golden is EMPTY, so not one release summary
 * is exercised. `ExpertReleaseSummary` is transcribed from its schema and has never met a
 * server response; a populated Expert Library is unverified and is not claimed anywhere.
 */
class HarnessDecoderGoldenTest {

    private fun golden(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("goldens/stage3c-v1/$name")) {
            "missing golden: $name"
        }.readBytes().decodeToString()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ── the goldens are what we think they are ──────────────────────────────

    @Test
    fun the_goldens_are_byte_for_byte_what_was_supplied() {
        assertEquals(
            "77799b8f967bc9d98cca0e5972ccbe470718183322022e5fab4faed63d7c2d56",
            sha256(golden("client-capabilities-response.json")),
        )
        assertEquals(
            "f0eacd3ce4b8e012205f8f986e684f4bd85ea5c0bffc986b2552c72e114e1f2f",
            sha256(golden("client-empty-catalog-response.json")),
        )
    }

    // ── capabilities ────────────────────────────────────────────────────────

    @Test
    fun the_capabilities_golden_decodes() {
        val outcome = HarnessDecoder.decodeCapabilities(golden("client-capabilities-response.json"))
        val result = (outcome as HarnessOutcome.Decoded).value

        assertEquals("knowledge-foundry-capabilities/3.0", result.schema)
        assertEquals("0.3.2", result.runtimeContract)
        assertEquals("0.3.2", result.distributionVersion)
        // The negotiation facts this client depends on: absence means /2.0, and /3.0 has
        // to be asked for explicitly.
        assertEquals("/2.0", result.defaultResponseSchema)
        assertTrue(result.explicitResponseSchemas.contains("/3.0"))
        assertEquals(15, result.operations.size)
    }

    @Test
    fun the_declared_operations_include_authority_this_client_must_not_offer() {
        // Asserting the SERVER's list contains them, which is why the mapper exists. A
        // naive `operations.contains(x) -> enable x` would wire up activation.
        val result = (HarnessDecoder.decodeCapabilities(
            golden("client-capabilities-response.json"),
        ) as HarnessOutcome.Decoded).value

        for (privileged in listOf(
            "mount.activate", "mount.rollback", "pack.install", "state.initialize",
        )) {
            assertTrue("$privileged should be declared by the Harness", privileged in result.operations)
        }
    }

    @Test
    fun capabilities_enable_only_the_read_only_expert_surface() {
        val result = (HarnessDecoder.decodeCapabilities(
            golden("client-capabilities-response.json"),
        ) as HarnessOutcome.Decoded).value

        assertEquals(
            setOf("expert.catalog.list", "expert.release.inspect", "query.retrieve"),
            HarnessCapabilityMapper.consumable(result.operations),
        )
    }

    @Test
    fun a_real_capabilities_document_opens_the_expert_library_gate() {
        val result = (HarnessDecoder.decodeCapabilities(
            golden("client-capabilities-response.json"),
        ) as HarnessOutcome.Decoded).value
        val capabilities = HarnessCapabilityMapper.toCapabilities(result)

        assertTrue(capabilities.expertLibrary is com.verbalogix.assistant.data.capability.CapabilityState.Available)
        assertTrue(capabilities.evidenceQuery is com.verbalogix.assistant.data.capability.CapabilityState.Available)
        // Tool approval stays shut: `governed-tool-proposal-decision-receipt` is still
        // planned-not-implemented, so there is no contract to gate on.
        assertTrue(
            capabilities.toolProposals is
                com.verbalogix.assistant.data.capability.CapabilityState.Unavailable,
        )
    }

    // ── empty catalog ───────────────────────────────────────────────────────

    @Test
    fun the_empty_catalog_golden_decodes_as_empty_rather_than_absent() {
        val outcome = HarnessDecoder.decodeExpertCatalog(
            golden("client-empty-catalog-response.json"),
        )
        val result = (outcome as HarnessOutcome.Decoded).value

        assertEquals("knowledge-foundry-expert-catalog/3.0", result.schema)
        assertEquals(0L, result.generation)
        assertTrue("the supplied golden carries no releases", result.releases.isEmpty())
        // Generation 0 with an empty list is a real answer -- "nothing is mounted" --
        // and must not be rendered as an error or a loading state.
        assertEquals(
            "f8e1e95ff85f268b0f7c0868aa331f968f138d00d8cb4acc34b51e5f7354a4c4",
            result.mountRegistrySha256,
        )
    }

    @Test
    fun a_catalog_reply_is_not_accepted_as_a_capabilities_reply() {
        // Correlation, not shape. Both are valid /3.0 envelopes; the operation_id is what
        // stops one being read as the other.
        val outcome = HarnessDecoder.decodeCapabilities(
            golden("client-empty-catalog-response.json"),
        )
        val refusal = (outcome as HarnessOutcome.Refused).refusal
        assertTrue(refusal is HarnessRefusal.OperationMismatch)
    }
}
