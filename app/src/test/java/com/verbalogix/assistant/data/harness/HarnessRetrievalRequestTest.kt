package com.verbalogix.assistant.data.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact bytes of a retrieval request.
 *
 * ASSERTED LITERALLY, AND THEN AGAINST THE SERVER'S OWN ECHO. The literal string catches
 * drift; the second assertion is the one that proves the shape is right, because the
 * golden's `plan.request` is the request the LIVE Foundry received, canonicalised and
 * handed back inside its own plan. If this client's bytes and that echo agree, the request
 * has been validated against a server rather than against this author's reading of a
 * schema — which is the mistake that cost a refused retrieval the first time round.
 */
class HarnessRetrievalRequestTest {

    private val packId =
        "kf:pack:c597b1bfbc5ff099921cfc338451b34c5d8e10e82c2ee6a290d4c53a2e7e5efe"

    private fun golden(): String =
        checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "goldens/stage3c-v1/client-query-response.json",
            ),
        ) { "missing retrieval golden" }.readBytes().decodeToString()

    @Test
    fun the_body_is_exactly_the_wrapped_query_request() {
        val body = HarnessRequest.retrieveBody("Knowledge", packId, listOf("internal"))
        assertEquals(
            """{"request":{"access":{"allowed_sensitivities":["internal"]},""" +
                """"answer_mode":"evidence-only","limits":{},""" +
                """"pack_scope":{"mode":"include","pack_ids":["$packId"]},""" +
                """"provider_id":null,"query_mode":"auto","role":"project-expert",""" +
                """"schema":"knowledge-foundry-query-request/2.0","text":"Knowledge"}}""",
            body,
        )
    }

    /**
     * THE ARGUMENT IS WRAPPED, and this is the check that would have caught it.
     *
     * A bare `query-request/2.0` at the top level is what the golden's `plan.request`
     * makes the body look like, and it is what the adapter answers `unknown-field` to: the
     * facade allows exactly `{trust_store, request}` as this operation's arguments.
     */
    @Test
    fun the_query_request_is_carried_under_a_request_key() {
        val body = HarnessDecoder.STRICT.decodeFromString(
            JsonObject.serializer(),
            HarnessRequest.retrieveBody("Knowledge", packId, listOf("internal")),
        )
        assertEquals(setOf("request"), body.keys)
        // `trust_store` is a SERVER startup input. The adapter refuses a body carrying one.
        assertFalse("trust_store" in body)
    }

    @Test
    fun the_built_request_matches_the_one_the_server_planned() {
        val ours = HarnessDecoder.STRICT
            .decodeFromString(
                JsonObject.serializer(),
                HarnessRequest.retrieveBody("Knowledge", packId, listOf("internal")),
            )
            .getValue("request")
            .jsonObject

        val theirs = HarnessDecoder.STRICT
            .decodeFromString(JsonObject.serializer(), golden())
            .getValue("result").jsonObject
            .getValue("plan").jsonObject
            .getValue("request").jsonObject

        assertEquals("this client's request must be the one the Foundry planned", theirs, ours)
    }

    // ── what cannot be varied ───────────────────────────────────────────────

    @Test
    fun the_fixed_fields_are_not_parameters() {
        // Compiled proof rather than a naming convention: there is no overload that takes
        // a role, a query mode, an answer mode or a provider, so no screen and no future
        // edit can widen a request without changing HarnessRequest itself.
        assertEquals("evidence-only", HarnessRequest.QUERY_ANSWER_MODE)
        assertEquals("project-expert", HarnessRequest.QUERY_ROLE)
        assertEquals("auto", HarnessRequest.QUERY_MODE)
        val body = HarnessRequest.retrieveBody("Knowledge", packId, listOf("internal"))
        assertTrue(""""provider_id":null""" in body)
    }

    @Test
    fun the_scope_names_exactly_the_inspected_pack() {
        val body = HarnessRequest.retrieveBody("anything", packId, listOf("public"))
        assertTrue(""""mode":"include"""" in body)
        assertTrue(""""pack_ids":["$packId"]""" in body)
    }

    // ── refusals, before anything reaches the wire ──────────────────────────

    @Test
    fun a_control_character_is_refused_rather_than_escaped() {
        // Representable in JSON as \n, and still forbidden: the schema's pattern excludes
        // the CHARACTER, not its encoding. Escaping it would send a document the Foundry
        // then refuses with an opaque code.
        assertFalse(HarnessRequest.isSendableQueryText("two\nlines"))
        assertFalse(HarnessRequest.isSendableQueryText("bell\u0007"))
        assertFalse(HarnessRequest.isSendableQueryText("delete\u007f"))
        runCatching { HarnessRequest.retrieveBody("two\nlines", packId, listOf("internal")) }
            .also { assertTrue("a control character must not reach the wire", it.isFailure) }
    }

    @Test
    fun a_quote_or_backslash_is_escaped_rather_than_refused() {
        // The reason this body goes through an encoder instead of a string template: the
        // question is the one field a person types, and it is allowed to contain these.
        val body = HarnessRequest.retrieveBody("""a "quoted" \ thing""", packId, listOf("internal"))
        val text = HarnessDecoder.STRICT.decodeFromString(JsonObject.serializer(), body)
            .getValue("request").jsonObject
            .getValue("text")
        assertEquals("\"a \\\"quoted\\\" \\\\ thing\"", text.toString())
    }

    @Test
    fun an_empty_or_oversize_question_is_refused() {
        assertFalse(HarnessRequest.isSendableQueryText(""))
        assertTrue(HarnessRequest.isSendableQueryText("x".repeat(HarnessRequest.MAX_QUERY_CHARS)))
        assertFalse(
            HarnessRequest.isSendableQueryText("x".repeat(HarnessRequest.MAX_QUERY_CHARS + 1)),
        )
    }

    @Test
    fun a_malformed_pack_identity_never_reaches_a_request_body() {
        for (bad in listOf("kf:pack:short", "pack-1", "kf:pack:${"A".repeat(64)}", "")) {
            assertTrue(
                "must refuse $bad",
                runCatching {
                    HarnessRequest.retrieveBody("Knowledge", bad, listOf("internal"))
                }.isFailure,
            )
        }
    }

    @Test
    fun sensitivities_are_taken_from_the_release_and_validated_not_defaulted() {
        // No default anywhere: a release that permits nothing cannot be queried, rather
        // than being queried at some assumed level.
        assertTrue(
            runCatching { HarnessRequest.retrieveBody("q", packId, emptyList()) }.isFailure,
        )
        // Outside the closed enum.
        assertTrue(
            runCatching { HarnessRequest.retrieveBody("q", packId, listOf("secret")) }.isFailure,
        )
        // uniqueItems.
        assertTrue(
            runCatching {
                HarnessRequest.retrieveBody("q", packId, listOf("public", "public"))
            }.isFailure,
        )
        // Both values, in the order the release stated them.
        assertTrue(
            """"allowed_sensitivities":["public","internal"]""" in
                HarnessRequest.retrieveBody("q", packId, listOf("public", "internal")),
        )
    }

    @Test
    fun the_route_carries_no_query_string() {
        assertTrue(HarnessRequest.isSendableTarget(HarnessRequest.PATH_QUERY_RETRIEVE))
        assertEquals("/v1/queries", HarnessRequest.PATH_QUERY_RETRIEVE)
    }
}
