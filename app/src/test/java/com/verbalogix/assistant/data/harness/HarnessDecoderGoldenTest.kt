package com.verbalogix.assistant.data.harness

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
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
 * These two cover the ENVELOPE, negotiation, correlation and the empty-catalog frame.
 * The populated catalog, release detail and both token responses arrived later and are
 * proved in `HarnessStage3cGoldenTest` below.
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

/**
 * The four goldens that arrived with Stage 3C, decoded from the server's own bytes.
 *
 * Kept separate from the first two so the history stays legible: `ExpertReleaseSummary`,
 * the detail decoder and the token decoder were all written from schemas and shipped
 * DISABLED or unverified, and each was promoted only when its bytes arrived. This file is
 * the promotion evidence.
 */
class HarnessStage3cGoldenTest {

    private fun golden(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("goldens/stage3c-v1/$name")) {
            "missing golden: $name"
        }.readBytes().decodeToString()

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun the_new_goldens_are_byte_for_byte_what_was_supplied() {
        assertEquals(
            "cd60a86d543c06ff638633b7c48df92bd6bf349c446734186aeeecf7afaff46c",
            sha256(golden("client-populated-catalog-response.json")),
        )
        assertEquals(
            "19f235f477399364679ed560c223abfa001ff693ac03ae64d579932e829502ab",
            sha256(golden("client-release-detail-response.json")),
        )
        assertEquals(
            "70029e75d75d6bc58a18caa36e4def2b869d703e7122d21e8df544f7086dbced",
            sha256(golden("client-token-exchange-response.json")),
        )
        assertEquals(
            "baa261465e571f915c5078b61d932f679e65744893005054a679d07883a42943",
            sha256(golden("client-token-refresh-response.json")),
        )
    }

    // ── the populated catalog ───────────────────────────────────────────────

    @Test
    fun a_populated_catalog_decodes_every_summary_field() {
        val result = (HarnessDecoder.decodeExpertCatalog(
            golden("client-populated-catalog-response.json"),
        ) as HarnessOutcome.Decoded).value

        assertEquals(1L, result.generation)
        assertEquals(1, result.releases.size)
        val release = result.releases.single()

        // The fields the Expert Library renders, each from the server rather than derived.
        assertEquals("Knowledge Foundry Project Expert", release.name)
        assertEquals("org.knowledge-foundry", release.namespace)
        assertEquals("project-expert", release.slug)
        assertEquals("1.0.0", release.version)
        assertEquals("active", release.mountState)
        assertEquals("trusted", release.trustState)
        assertEquals("moderate", release.riskClass)
        assertEquals("development", release.publicationChannel)
        assertEquals(listOf("internal"), release.allowedSensitivities)
        assertEquals(8, release.capabilities.size)
    }

    @Test
    fun catalog_identities_use_distinct_kinds_for_pack_and_release() {
        val release = (HarnessDecoder.decodeExpertCatalog(
            golden("client-populated-catalog-response.json"),
        ) as HarnessOutcome.Decoded).value.releases.single()

        // `kf:pack:` and `kf:pack-release:` are DIFFERENT namespaces. Keying a detail
        // route on the wrong one would look plausible and resolve to nothing.
        assertTrue(release.packId.startsWith("kf:pack:"))
        assertTrue(release.releaseId.startsWith("kf:pack-release:"))
        assertNotEquals(release.packId, release.releaseId)
        assertTrue(HarnessDecoder.isWellFormedIdentity(release.packId))
        assertTrue(HarnessDecoder.isWellFormedIdentity(release.releaseId))
    }

    // ── release detail ──────────────────────────────────────────────────────

    @Test
    fun the_release_detail_golden_decodes_all_three_sections() {
        val result = (HarnessDecoder.decodeExpertReleaseDetail(
            golden("client-release-detail-response.json"),
        ) as HarnessOutcome.Decoded).value

        assertEquals("Knowledge Foundry Project Expert", result.release.name)
        assertEquals("accepted", result.install.compatibility)
        assertTrue(result.install.signerKeyId.startsWith("ed25519:"))
        assertTrue(result.install.dependencyReleaseIds.isEmpty())
        // Required AND nullable: the key is present and its value is null, which means
        // "the Harness says there is no predecessor" -- not "the Harness did not say".
        assertNull(result.lifecycle.predecessorReleaseId)
        assertNull(result.lifecycle.rollbackReleaseId)
        assertNull(result.lifecycle.supersededContentSha256)
    }

    @Test
    fun a_catalog_reply_is_not_accepted_as_a_release_detail_reply() {
        val outcome = HarnessDecoder.decodeExpertReleaseDetail(
            golden("client-populated-catalog-response.json"),
        )
        assertTrue((outcome as HarnessOutcome.Refused).refusal is HarnessRefusal.OperationMismatch)
    }

    // ── tokens ──────────────────────────────────────────────────────────────

    @Test
    fun the_exchange_golden_decodes_and_grants_exactly_the_four_scopes() {
        val result = HarnessTokenDecoder.decode(
            golden("client-token-exchange-response.json"),
            expectedClientInstanceId = "0123456789abcdef0123456789abcdef",
            nowEpochSeconds = 1_000_000,
        )
        val granted = result as TokenDecodeResult.Granted
        assertEquals(HarnessScope.REQUESTED, granted.scopes)
        // expires_in is 300 -- the value this client asks for, echoed back.
        assertEquals(1_000_300L, granted.expiresAtEpochSeconds)
    }

    @Test
    fun the_refresh_golden_decodes_to_a_distinct_successor_token() {
        val exchange = HarnessTokenDecoder.decode(
            golden("client-token-exchange-response.json"), "0123456789abcdef0123456789abcdef", 1_000_000,
        ) as TokenDecodeResult.Granted
        val refresh = HarnessTokenDecoder.decode(
            golden("client-token-refresh-response.json"), "0123456789abcdef0123456789abcdef", 1_000_000,
        ) as TokenDecodeResult.Granted

        // Rotation must actually rotate: same shape, same scopes, DIFFERENT credential.
        // Comparing the values themselves, because `toString` redacts them -- and an
        // assertion over two redacted strings would compare two identical placeholders
        // and pass no matter what the server sent.
        assertEquals(exchange.scopes, refresh.scopes)
        assertNotEquals(exchange.token.value, refresh.token.value)
    }

    @Test
    fun a_token_issued_for_another_client_instance_is_refused() {
        // The echoed id is compared, not trusted. Adopting a token minted for someone
        // else's request would bind this app to a session it never asked for.
        val result = HarnessTokenDecoder.decode(
            golden("client-token-exchange-response.json"),
            expectedClientInstanceId = "ffffffffffffffffffffffffffffffff",
            nowEpochSeconds = 1_000_000,
        )
        assertTrue(result is TokenDecodeResult.Refused)
        assertTrue((result as TokenDecodeResult.Refused).reason.contains("different client instance"))
    }

    @Test
    fun a_token_refusal_never_contains_the_token() {
        val mutated = golden("client-token-exchange-response.json")
            .replace(""""token_type":"Bearer"""", """"token_type":"MAC"""")
        val result = HarnessTokenDecoder.decode(mutated, "0123456789abcdef0123456789abcdef", 1_000)
        val reason = (result as TokenDecodeResult.Refused).reason
        assertFalse("a refusal is the likeliest place for a credential to escape",
            reason.contains("kft2."))
    }
}
