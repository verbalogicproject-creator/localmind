package com.verbalogix.assistant.data.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability discovery, against the server's own bytes.
 *
 * THIS DOCUMENT'S SHAPE COULD NOT HAVE BEEN GUESSED, which is the entire argument for
 * insisting on a server-emitted golden before writing the decoder. Its name says
 * `capabilities/4.0`, and by analogy to `capabilities/3.0` one would expect an
 * `operation-response` envelope wrapping a body with an `operations` array. It has
 * neither: it is FLAT, with `schema` at the top level, and it describes exactly ONE
 * operation in a singular `operation` field. A decoder written from the description
 * rather than the bytes would have been wrong twice, and its tests would have passed.
 */
class AssistantCapabilitiesGoldenTest {

    private fun golden(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("goldens/stage3d-v1/$name")) {
            "missing capability golden: $name"
        }.readBytes().decodeToString()

    private fun response() = golden("client-assistant-capabilities-response.json")
    private fun evidence() = HarnessDecoder.STRICT
        .decodeFromString(JsonObject.serializer(), golden("client-assistant-capabilities-http-evidence.json"))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ── the golden decodes, and says the operation is offered ────────────────

    @Test
    fun the_golden_decodes_and_declares_the_assistant_turn() {
        val outcome = HarnessDecoder.decodeAssistantCapabilities(response())
        check(outcome is HarnessOutcome.Decoded) { "got $outcome" }
        val caps = outcome.value

        assertEquals(SchemaIds.CAPABILITIES_TURN, caps.schema)
        assertEquals(SchemaIds.OP_ASSISTANT_TURN_FINALIZE, caps.operation)
        assertEquals(SchemaIds.OPERATION_RESPONSE_TURN, caps.responseSchema)
        assertEquals(SchemaIds.RUNTIME_CONTRACT_TURN, caps.runtimeContract)
    }

    /**
     * The two runtime contracts disagree ON PURPOSE, and both are load-bearing.
     *
     * `/3.0` is the frozen Localmind projection at 0.3.2; `/4.0` is the additive assistant
     * contract at 0.3.3. A single shared pin would make one surface silently wrong, and it
     * would be whichever was checked second.
     */
    @Test
    fun the_two_surfaces_pin_different_runtime_contracts() {
        assertNotEquals(SchemaIds.RUNTIME_CONTRACT, SchemaIds.RUNTIME_CONTRACT_TURN)
        assertEquals("0.3.2", SchemaIds.RUNTIME_CONTRACT)
        assertEquals("0.3.3", SchemaIds.RUNTIME_CONTRACT_TURN)
    }

    /**
     * Discovering a capability grants no authority, and the server says so in the payload.
     *
     * Asserted rather than assumed because the decoder REFUSES a true here. If the Foundry
     * ever offers an executing capability, this client must not quietly accept it as a
     * richer version of the one it was written for.
     */
    @Test
    fun the_declared_capability_carries_no_effect_authority() {
        val outcome = HarnessDecoder.decodeAssistantCapabilities(response())
        check(outcome is HarnessOutcome.Decoded)
        assertEquals(false, outcome.value.providerExecution)
        assertEquals(false, outcome.value.toolExecution)
        assertEquals(false, outcome.value.persistence)
    }

    // ── the self-digest is verified, not displayed ───────────────────────────

    @Test
    fun the_capabilities_digest_seals_the_document() {
        val root = HarnessDecoder.STRICT.decodeFromString(JsonObject.serializer(), response())
        val declared = root.getValue("capabilities_sha256").jsonPrimitive.content
        assertEquals(
            "capabilities_sha256 must be the canonical self-digest of everything else",
            declared,
            CanonicalJson.selfDigest(root, "capabilities_sha256"),
        )
    }

    /**
     * A tampered field is refused even though every field still parses.
     *
     * The mutation targets a value the decoder does not otherwise check, so ONLY the digest
     * can catch it. Mutating `operation` or `runtime_contract` would prove nothing about
     * the seal, since a dedicated check would fire first -- the same vacuous-mutation trap
     * that made an earlier packet test pass while asserting nothing.
     */
    @Test
    fun a_document_whose_digest_no_longer_seals_it_is_refused() {
        val root = HarnessDecoder.STRICT.decodeFromString(JsonObject.serializer(), response())
        val tampered = JsonObject(root + ("distribution_version" to JsonPrimitive("9.9.9")))
        val outcome = HarnessDecoder.decodeAssistantCapabilities(tampered.toString())

        check(outcome is HarnessOutcome.Refused) { "got $outcome" }
        val refusal = outcome.refusal
        check(refusal is HarnessRefusal.Undecodable)
        assertTrue(refusal.detail, "capabilities_sha256" in refusal.detail)
    }

    // ── every other gate fires ───────────────────────────────────────────────

    @Test
    fun a_different_operation_is_not_evidence_about_this_one() {
        val refusal = refuse("operation", "expert.catalog.list")
        check(refusal is HarnessRefusal.OperationMismatch) { "got $refusal" }
        assertEquals(SchemaIds.OP_ASSISTANT_TURN_FINALIZE, refusal.expected)
    }

    @Test
    fun the_wrong_runtime_contract_is_refused() {
        val refusal = refuse("runtime_contract", "0.3.2")
        check(refusal is HarnessRefusal.RuntimeContract) { "got $refusal" }
        assertEquals("0.3.2", refusal.declared)
        assertEquals(SchemaIds.RUNTIME_CONTRACT_TURN, refusal.expected)
    }

    @Test
    fun a_response_schema_this_client_cannot_read_is_refused() {
        // The server naming a reply format we have no decoder for means the turn we would
        // send is one whose answer we could not read. Better to refuse discovery than to
        // discover, send, and fail on the way back.
        val refusal = refuse("response_schema", "knowledge-foundry-operation-response/5.0")
        check(refusal is HarnessRefusal.ResultSchemaMismatch) { "got $refusal" }
    }

    @Test
    fun a_capability_claiming_an_effect_is_refused_rather_than_accepted_as_richer() {
        for (flag in listOf("provider_execution", "tool_execution", "persistence")) {
            val root = HarnessDecoder.STRICT.decodeFromString(JsonObject.serializer(), response())
            val mutated = JsonObject(root + (flag to JsonPrimitive(true)))
            val outcome = HarnessDecoder.decodeAssistantCapabilities(mutated.toString())
            check(outcome is HarnessOutcome.Refused) { "$flag=true must be refused, got $outcome" }
            val refusal = outcome.refusal
            check(refusal is HarnessRefusal.Undecodable)
            assertTrue(refusal.detail, flag in refusal.detail)
        }
    }

    @Test
    fun the_three_point_zero_capabilities_document_is_not_accepted_here() {
        // Same path, different negotiated version, genuinely different document. Routing
        // one to the other's decoder must fail loudly rather than half-succeed.
        val outcome = HarnessDecoder.decodeAssistantCapabilities(
            """{"schema":"${SchemaIds.CAPABILITIES}","operations":[]}""",
        )
        check(outcome is HarnessOutcome.Refused) { "got $outcome" }
        check(outcome.refusal is HarnessRefusal.ResultSchemaMismatch)
    }

    @Test
    fun an_unknown_field_is_refused_rather_than_ignored() {
        val root = HarnessDecoder.STRICT.decodeFromString(JsonObject.serializer(), response())
        val extended = JsonObject(root + ("future_field" to JsonPrimitive("x")))
        val outcome = HarnessDecoder.decodeAssistantCapabilities(extended.toString())
        check(outcome is HarnessOutcome.Refused) { "got $outcome" }
    }

    /** Mutate one field, decode, and return the refusal it produced. */
    private fun refuse(field: String, value: String): HarnessRefusal {
        val root = HarnessDecoder.STRICT.decodeFromString(JsonObject.serializer(), response())
        val mutated = JsonObject(root + (field to JsonPrimitive(value)))
        val outcome = HarnessDecoder.decodeAssistantCapabilities(mutated.toString())
        check(outcome is HarnessOutcome.Refused) { "$field=$value must be refused, got $outcome" }
        return outcome.refusal
    }

    // ── the request this client sends is the one the server documented ───────

    /**
     * The HTTP evidence golden is a contract about the REQUEST, so it is asserted against
     * the builder rather than read for reassurance.
     *
     * The absences matter as much as the values: no `Content-Type` on a GET with no body,
     * and no `Origin` at all. Both are recorded in the server's evidence, and a builder
     * that added either would be sending a request the adapter is entitled to reject.
     */
    @Test
    fun the_request_matches_the_servers_own_http_evidence() {
        val request = evidence().getValue("request").jsonObject
        val host = request.getValue("headers").jsonObject.getValue("Host").jsonPrimitive.content
        val ours = HarnessRequest.capabilitiesTurnHeaders(host, "TOKEN")

        assertEquals("GET", request.getValue("method").jsonPrimitive.content)
        assertEquals(
            HarnessRequest.PATH_CAPABILITIES,
            request.getValue("path").jsonPrimitive.content,
        )
        assertEquals(
            "the documented request has exactly these headers",
            setOf("Host", "Authorization", "Knowledge-Foundry-Accept-Schema"),
            ours.keys,
        )
        assertEquals("/4.0", ours[HarnessNegotiation.HEADER])
        assertEquals(host, ours["Host"])
        assertEquals("Bearer TOKEN", ours["Authorization"])

        // A GET with no body, asserted through the digest the server recorded rather than
        // by trusting the byte length beside it.
        assertEquals(0, request.getValue("body_byte_length").jsonPrimitive.content.toInt())
        assertEquals(
            sha256(ByteArray(0)),
            request.getValue("body_sha256").jsonPrimitive.content,
        )
    }

    @Test
    fun the_evidence_binds_the_response_file_it_ships_with() {
        // The two goldens are only worth anything together: the evidence asserts a digest
        // and a length, and the response file must be what it is talking about. Checked
        // here so a future copy that updates one file and not the other fails loudly.
        val response = evidence().getValue("response").jsonObject
        val bytes = response().toByteArray()

        assertEquals(200, response.getValue("status").jsonPrimitive.content.toInt())
        assertEquals(
            "client-assistant-capabilities-response.json",
            response.getValue("body_file").jsonPrimitive.content,
        )
        assertEquals(bytes.size, response.getValue("body_byte_length").jsonPrimitive.content.toInt())
        assertEquals(sha256(bytes), response.getValue("body_sha256").jsonPrimitive.content)
        assertEquals(
            "no-store",
            response.getValue("headers").jsonObject.getValue("Cache-Control").jsonPrimitive.content,
        )
    }

    /**
     * The credential never appears in the evidence, and this asserts it stays that way.
     *
     * The golden is checked into the repository, so a future regeneration that captured a
     * live Authorization value would commit a token. Cheap to check, catastrophic to miss.
     */
    @Test
    fun the_evidence_carries_no_credential() {
        val raw = golden("client-assistant-capabilities-http-evidence.json")
        assertTrue("a pairing or access token must never enter a golden", "kft2." !in raw)
        assertEquals(
            "Bearer <redacted>",
            evidence().getValue("request").jsonObject
                .getValue("headers").jsonObject
                .getValue("Authorization").jsonPrimitive.content,
        )
    }

    // ── the contract decisions, as data rather than prose ────────────────────

    /**
     * The Foundry stated three contract decisions and encoded them in the evidence, so they
     * are asserted rather than paraphrased in a comment that can drift from the code.
     */
    @Test
    fun the_contract_decisions_are_the_ones_this_client_implements() {
        val ev = evidence()
        assertEquals(
            "hide-or-disable-grounded-drafting-without-inferring-from-retrieval",
            ev.getValue("absence_disposition").jsonPrimitive.content,
        )
        assertTrue(
            "presence promises the operation is offered, not that a turn will succeed",
            "individual-turns-may-still-abstain-refuse-or-fail-validation" in
                ev.getValue("presence_promise").jsonPrimitive.content,
        )
    }
}
