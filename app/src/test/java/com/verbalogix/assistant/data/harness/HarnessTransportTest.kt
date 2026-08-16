package com.verbalogix.assistant.data.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request shaping, pairing, and error classification.
 *
 * Every rule asserted here has a matching rejection in the Harness adapter, so these are
 * transcriptions rather than opinions. The value is that a request this client would send
 * incorrectly fails as a typed assertion instead of as an opaque `token-origin-denied`
 * from a loopback socket.
 */
class HarnessTransportTest {

    // ── request shaping ─────────────────────────────────────────────────────

    @Test
    fun the_token_body_carries_exactly_three_keys_with_sorted_scopes() {
        // The adapter compares the KEY SET and answers `unknown-field` for extras, so
        // this is not a place to add a client version or a nonce.
        val id = "0123456789abcdef0123456789abcdef"
        assertEquals(
            """{"client_instance_id":"$id","scopes":["capabilities:read","expert:read","query:read","token:refresh"],"ttl":300}""",
            HarnessRequest.tokenRequestBody(id),
        )
    }

    @Test
    fun scopes_are_sorted_by_wire_string_not_declaration_order() {
        val wire = HarnessRequest.requestedScopes()
        assertEquals(wire.sorted(), wire)
        assertEquals(wire.distinct(), wire)
        assertEquals(4, wire.size)
    }

    @Test
    fun the_access_ttl_is_fixed_at_300_seconds() {
        // `expires_in` is ECHOED from this value -- the client chooses how long a stolen
        // token stays useful. 900 is permitted and would be the worst available answer.
        assertEquals(300, HarnessRequest.ACCESS_TTL_SECONDS)
        assertTrue(HarnessRequest.ACCESS_TTL_SECONDS in 1..900)
        assertTrue(
            "the refresh lead must fit inside the session",
            HarnessSessionPolicy.REFRESH_LEAD_SECONDS < HarnessRequest.ACCESS_TTL_SECONDS,
        )
        assertEquals(60L, HarnessSessionPolicy.REFRESH_LEAD_SECONDS)
    }

    @Test
    fun a_malformed_client_instance_id_is_refused_before_a_request_is_built() {
        for (bad in listOf("", "short", "0123456789ABCDEF0123456789ABCDEF", "z".repeat(32))) {
            var threw = false
            try {
                HarnessRequest.tokenRequestBody(bad)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("must refuse client_instance_id '$bad'", threw)
        }
    }

    @Test
    fun token_routes_carry_no_negotiation_header_and_no_origin() {
        val headers = HarnessRequest.tokenHeaders("127.0.0.1:8765", "kft2.a.b")
        assertFalse(HarnessNegotiation.HEADER in headers.keys)
        // ABSENT, not matching. The adapter raises token-origin-denied if any Origin
        // accompanies a Localmind or token route -- browser habits say send it.
        assertFalse("Origin" in headers.keys)
        assertEquals("127.0.0.1:8765", headers["Host"])
        assertEquals("Bearer kft2.a.b", headers["Authorization"])
    }

    @Test
    fun operation_routes_carry_the_negotiation_header_and_still_no_origin() {
        val headers = HarnessRequest.operationHeaders("127.0.0.1:8765", "kft2.a.b", post = false)
        assertEquals("/3.0", headers[HarnessNegotiation.HEADER])
        assertFalse("Origin" in headers.keys)
        // GET carries no Content-Type; the adapter only demands it for POST.
        assertFalse("Content-Type" in headers.keys)
        assertTrue("Content-Type" in HarnessRequest.operationHeaders("h", "t", post = true).keys)
    }

    @Test
    fun query_strings_and_fragments_are_refused() {
        // The adapter answers adapter-route-unsupported; arguments travel in the body.
        assertTrue(HarnessRequest.isSendableTarget("/v1/experts"))
        assertFalse(HarnessRequest.isSendableTarget("/v1/experts?limit=10"))
        assertFalse(HarnessRequest.isSendableTarget("/v1/experts#frag"))
        assertFalse(HarnessRequest.isSendableTarget("v1/experts"))
    }

    // ── pairing ─────────────────────────────────────────────────────────────

    @Test
    fun a_pasted_pairing_line_is_trimmed_of_terminal_noise() {
        val token = "kft2.aGVsbG8-_x.c2ln_-A"
        assertEquals(token, HarnessPairing.parsePairingLine(token))
        assertEquals(token, HarnessPairing.parsePairingLine("  $token\n"))
        assertEquals(token, HarnessPairing.parsePairingLine("\"$token\""))
    }

    @Test
    fun anything_that_is_not_exactly_one_token_is_refused_rather_than_salvaged() {
        for (bad in listOf(
            null,
            "",
            "not-a-token",
            "kft1.old.format",                      // the previous token generation
            "kft2.only-two-parts",
            "kft2.a.b extra",                        // a prompt or a second word
            "kft2.a+b/c.d",                          // base64 standard alphabet, not url
        )) {
            assertNull("must refuse '$bad'", HarnessPairing.parsePairingLine(bad))
        }
    }

    @Test
    fun a_client_instance_id_is_32_lowercase_hex_and_fresh_each_time() {
        val a = HarnessPairing.newClientInstanceId()
        val b = HarnessPairing.newClientInstanceId()
        assertTrue(HarnessPairing.isWellFormedClientInstanceId(a))
        assertEquals(32, a.length)
        assertNotEquals("must not be a stable identifier across sessions", a, b)
        assertFalse(HarnessPairing.isWellFormedClientInstanceId(a.uppercase()))
    }

    // ── error classification ────────────────────────────────────────────────

    @Test
    fun codes_that_end_the_session_map_to_a_recoverable_cause() {
        assertEquals(PairAgainCause.INVALID, HarnessErrorCodes.pairAgainCause("pairing-required"))
        assertEquals(PairAgainCause.INVALID, HarnessErrorCodes.pairAgainCause("token-missing"))
        assertEquals(PairAgainCause.EXPIRED, HarnessErrorCodes.pairAgainCause("token-expired"))
        assertEquals(PairAgainCause.REVOKED, HarnessErrorCodes.pairAgainCause("token-revoked"))
        assertEquals(
            PairAgainCause.PREVIOUS_SERVER_INSTANCE,
            HarnessErrorCodes.pairAgainCause("token-bind-denied"),
        )
    }

    @Test
    fun client_faults_never_send_the_user_to_pair_again() {
        // token-origin-denied is the instructive one: it means WE sent a forbidden
        // header. Re-pairing would leave the app making the same malformed request.
        for (code in listOf(
            "token-origin-denied", "adapter-version-unsupported", "adapter-route-unsupported",
            "request-invalid", "unknown-field", "http-body-too-large",
            "http-content-type-invalid",
        )) {
            assertNull("$code must not end the session", HarnessErrorCodes.pairAgainCause(code))
            assertTrue("$code is a client fault", HarnessErrorCodes.isClientFault(code))
        }
    }

    @Test
    fun an_unrecognised_code_fails_closed_rather_than_re_pairing() {
        // Guessing "pair again" for an unknown failure invites an infinite loop against a
        // fault pairing cannot fix, and each attempt burns a one-use credential the
        // operator has to fetch by hand.
        assertNull(HarnessErrorCodes.pairAgainCause("something-new"))
        assertFalse(HarnessErrorCodes.isClientFault("something-new"))
        assertTrue(HarnessErrorCodes.isUnknown("something-new"))
    }

    // ── the token decoder stays fixture-gated ───────────────────────────────

    @Test
    fun the_token_decoder_is_disabled_until_a_server_emitted_golden_exists() {
        // Same rule as expert-release-detail, applied where being wrong is worst: a
        // mis-reading decoder could hold a session it should have rejected.
        assertFalse(HarnessTokenDecoder.ENABLED)
        assertEquals(
            TokenDecodeResult.Disabled,
            HarnessTokenDecoder.decode("{}", "0".repeat(32), nowEpochSeconds = 1_000),
        )
    }

    @Test
    fun token_responses_are_permanently_outside_the_negotiated_set() {
        // Not an omission awaiting a golden. That set governs documents carried inside
        // operation-response/3.0 and selected by the Accept-Schema header; a token
        // response is neither, and its routes are exempt from negotiation entirely.
        assertFalse(HarnessTokenDecoder.SCHEMA in SchemaNegotiation.ACCEPTED)
    }

    @Test
    fun a_token_never_appears_in_its_own_string_representation() {
        val token = HarnessCredentials.AccessToken(
            value = "kft2.SECRETPAYLOAD.SECRETSIG",
            expiresAtEpochSeconds = 1_000_300,
            scopes = HarnessScope.REQUESTED,
        )
        assertFalse("the value must never reach a log line", token.toString().contains("SECRET"))
        assertTrue("but the session must stay debuggable", token.toString().contains("1000300"))
    }

    @Test
    fun credentials_are_dropped_on_clear_and_replaced_atomically() {
        val store = HarnessCredentials()
        assertNull(store.peek())
        val first = HarnessCredentials.AccessToken("kft2.a.b", 1_000_300, HarnessScope.REQUESTED)
        store.replace(first)
        assertEquals(first, store.peek())
        // Rotation is a swap: there is never a window holding both.
        val second = HarnessCredentials.AccessToken("kft2.c.d", 1_000_600, HarnessScope.REQUESTED)
        store.replace(second)
        assertEquals(second, store.peek())
        store.clear()
        assertNull(store.peek())
    }
}
