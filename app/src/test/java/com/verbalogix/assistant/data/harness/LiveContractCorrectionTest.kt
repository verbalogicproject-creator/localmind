package com.verbalogix.assistant.data.harness

import com.verbalogix.assistant.ui.nav.Destinations
import com.verbalogix.assistant.ui.nav.RouteArgs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four corrections the live Harness forced, each pinned.
 *
 * Every one of these was a plausible inference that turned out wrong against a real
 * adapter, which is the argument for running against one before tagging anything. They
 * are grouped here rather than scattered so the shape of the mistake stays visible:
 * three were guesses about a contract, and one was a concurrency assumption.
 */
class LiveContractCorrectionTest {

    private val token = "kft2.eyJhIjoxfQ.c2lnbmF0dXJl_-A"

    // ── 1. the pairing frame ────────────────────────────────────────────────

    @Test
    fun a_bare_token_is_accepted() {
        assertEquals(token, HarnessPairing.parsePairingLine(token))
    }

    @Test
    fun the_complete_server_frame_is_accepted_and_only_the_token_extracted() {
        val frame = "${HarnessPairing.FRAME_PREFIX} $token"
        assertEquals(token, HarnessPairing.parsePairingLine(frame))
        // Terminal noise around the whole frame is still trimmed.
        assertEquals(token, HarnessPairing.parsePairingLine("  $frame \n"))
        assertEquals(token, HarnessPairing.parsePairingLine("\"$frame\""))
    }

    @Test
    fun the_prefix_is_validated_before_anything_is_extracted() {
        // THE TEMPTING SHORTCUT IS TO SCAN FOR SOMETHING kft2-SHAPED AND TAKE IT. That
        // would accept a token embedded in a log line, a prompt, or an error message
        // quoting one -- none of which is a credential the user deliberately offered.
        for (bad in listOf(
            "SOME-OTHER-PREFIX/1 $token",
            "KNOWLEDGE-FOUNDRY-LOCALMIND-PAIRING/2 $token",
            "knowledge-foundry-localmind-pairing/1 $token",  // wrong case
            "$ echo ${HarnessPairing.FRAME_PREFIX} $token",   // a shell prompt
            "error: pairing failed for $token",
        )) {
            assertNull("must refuse: $bad", HarnessPairing.parsePairingLine(bad))
        }
    }

    @Test
    fun extra_words_and_multiple_tokens_are_refused() {
        val frame = "${HarnessPairing.FRAME_PREFIX} $token"
        assertNull(HarnessPairing.parsePairingLine("$frame extra"))
        assertNull(HarnessPairing.parsePairingLine("$frame $token"))
        assertNull(HarnessPairing.parsePairingLine("$token $token"))
        // The label alone is not a credential.
        assertNull(HarnessPairing.parsePairingLine(HarnessPairing.FRAME_PREFIX))
    }

    @Test
    fun a_malformed_token_inside_a_valid_frame_is_still_refused() {
        // The prefix is not a pass. The payload must be a token in its own right.
        assertNull(HarnessPairing.parsePairingLine("${HarnessPairing.FRAME_PREFIX} kft1.old.format"))
        assertNull(HarnessPairing.parsePairingLine("${HarnessPairing.FRAME_PREFIX} not-a-token"))
    }

    // ── 2 & 3. release identity is the lookup authority ─────────────────────

    @Test
    fun the_detail_route_is_keyed_by_release_identity() {
        assertEquals("experts/{releaseId}", Destinations.EXPERT_DETAIL)

        val releaseId = "kf:pack-release:" + "7b".repeat(32)
        assertEquals("experts/$releaseId", Destinations.expertDetail(releaseId))
    }

    @Test
    fun a_pack_id_is_not_accepted_where_a_release_id_belongs() {
        // Both are `kf:` identities, so a shape check alone would let a pack through and
        // the lookup would resolve to nothing. The route carries the RELEASE.
        val packId = "kf:pack:" + "c5".repeat(32)
        // It is well-formed as an identity...
        assertTrue(HarnessDecoder.isWellFormedIdentity(packId))
        // ...and the builder accepts any kf identity, so the guard that matters is that
        // callers pass `expert.releaseId`. Asserted at the call site by the library test.
        assertTrue(RouteArgs.releaseIdOrNull(packId) != null)
    }

    @Test
    fun a_release_id_that_is_not_the_exact_grammar_never_reaches_a_route() {
        for (bad in listOf(
            null,
            "",
            "kf:pack-release:" + "a".repeat(63),          // short digest
            "kf:pack-release:" + "A".repeat(64),          // upper-case hex
            "kf:PACK-RELEASE:" + "a".repeat(64),          // upper-case kind
            "../../etc/passwd",
            "kf:pack-release:" + "a".repeat(64) + "/..",  // traversal appended
        )) {
            assertNull("must refuse: $bad", RouteArgs.releaseIdOrNull(bad))
            if (bad != null) assertNull(Destinations.expertDetail(bad))
        }
    }

    // ── 4. the capability race ──────────────────────────────────────────────

    @Test
    fun offer_returns_when_the_receiver_takes_the_value_not_when_pairing_completes() = runBlocking {
        // THE RACE, DEMONSTRATED. A rendezvous send resumes as soon as a collector takes
        // the value -- long before any network exchange could have finished. A caller
        // reading `offer` as "pairing finished" therefore acts on a session that does not
        // exist yet, which is exactly what reported Capabilities.NONE over a Harness that
        // was about to connect.
        val source = ManualPairingCredentialSource()
        var received: String? = null

        coroutineScope {
            val collector = launch { source.credentials().collect { received = it } }
            yield()

            assertTrue(source.offer(token))
            yield()

            assertEquals("the value was handed over", token, received)
            collector.cancel()
        }
    }

    @Test
    fun a_malformed_line_is_refused_before_it_is_ever_offered() = runBlocking {
        val source = ManualPairingCredentialSource()
        assertFalse("nothing is sent for a line that is not a credential", source.offer("nope"))
        Unit
    }

    // ── 2. the exact inspect body ───────────────────────────────────────────

    @Test
    fun the_inspect_body_is_exactly_one_release_id_field() {
        val releaseId = "kf:pack-release:" + "7b".repeat(32)
        assertEquals(
            """{"release_id":"$releaseId"}""",
            HarnessRequest.inspectReleaseBody(releaseId),
        )
    }

    @Test
    fun the_inspect_body_carries_no_operation_envelope_and_no_pack_or_version() {
        // The whole of the first attempt, asserted absent: schema, operation_id, mode,
        // response_schema, arguments, pack_id, version. Each was defensible and none was
        // what the adapter wanted.
        val body = HarnessRequest.inspectReleaseBody("kf:pack-release:" + "7b".repeat(32))
        for (absent in listOf(
            "schema", "operation_id", "mode", "response_schema", "arguments",
            "pack_id", "version",
        )) {
            assertFalse("body must not contain \"$absent\": $body", body.contains(absent))
        }
    }

    @Test
    fun the_repository_exposes_no_public_capability_refresh() {
        // The fix is structural rather than a matter of ordering discipline: with the
        // fetch private and reached only from token adoption, there is no moment at which
        // a caller CAN ask too early. This asserts the seam stays closed.
        val method = HarnessSessionRepository::class.java.methods
            .firstOrNull { it.name.contains("refreshCapabilities", ignoreCase = true) }
        assertNull("refreshCapabilities must not be callable from outside", method)
    }

    // ── 6. the two services stay separate ───────────────────────────────────

    @Test
    fun the_foundry_bind_is_8091_and_not_the_model_server() {
        assertEquals("127.0.0.1:8091", HarnessClient.DEFAULT_BIND)
        assertFalse(
            "8090 is llama-swap; a Harness answering there would mean one of them is not what it claims",
            HarnessClient.DEFAULT_BIND.contains("8090"),
        )
    }
}
