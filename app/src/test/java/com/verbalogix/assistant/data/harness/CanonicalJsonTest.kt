package com.verbalogix.assistant.data.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does this client's canonical JSON produce the Foundry's bytes?
 *
 * THE ONLY MEANINGFUL TEST IS AGAINST THE FOUNDRY'S OWN NUMBERS. A canonicaliser tested
 * against its author's expectations is tested against the assumption that produced it, and
 * the failure mode here is silent: one differing byte anywhere in the tree yields a
 * different SHA-256, the Foundry reports drift, and nothing says which byte.
 *
 * So the request golden is used as an oracle. It contains four digests the CLIENT is
 * responsible for computing — the answer, the provider observation, its identity, and the
 * assistant-turn request — each written by an implementation of this algorithm that already
 * agrees with the server. Recomputing them here and requiring equality proves the
 * serialiser, the key ordering, the escaping, and the self-digest rule all at once.
 */
class CanonicalJsonTest {

    private fun golden(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("goldens/stage3d-v1/$name")) {
            "missing Stage 3D golden: $name"
        }.readBytes().decodeToString()

    private fun turnRequest(): JsonObject =
        HarnessDecoder.STRICT
            .decodeFromString(JsonObject.serializer(), golden("client-assistant-turn-request.json"))
            .getValue("turn_request").jsonObject

    // ── the four client-computed digests ────────────────────────────────────

    @Test
    fun the_answer_digest_matches_the_foundrys() {
        val answer = turnRequest().getValue("provider_observation").jsonObject
            .getValue("answer").jsonObject
        assertEquals(
            answer.getValue("answer_sha256").jsonPrimitive.content,
            CanonicalJson.selfDigest(answer, "answer_sha256"),
        )
    }

    @Test
    fun the_provider_observation_identity_matches_the_foundrys() {
        // The identity's basis excludes BOTH self-referential fields; the digest's basis
        // excludes only its own and therefore includes the identity. Two different bases
        // over the same object, and swapping them yields two plausible wrong answers.
        val observation = turnRequest().getValue("provider_observation").jsonObject
        val basis = JsonObject(
            observation.filterKeys { it != "observation_id" && it != "observation_sha256" },
        )
        assertEquals(
            observation.getValue("observation_id").jsonPrimitive.content,
            CanonicalJson.identity("provider-observation", basis),
        )
    }

    @Test
    fun the_provider_observation_digest_matches_the_foundrys() {
        val observation = turnRequest().getValue("provider_observation").jsonObject
        assertEquals(
            observation.getValue("observation_sha256").jsonPrimitive.content,
            CanonicalJson.selfDigest(observation, "observation_sha256"),
        )
    }

    @Test
    fun the_assistant_turn_request_digest_matches_the_foundrys() {
        val request = turnRequest()
        assertEquals(
            request.getValue("request_sha256").jsonPrimitive.content,
            CanonicalJson.selfDigest(request, "request_sha256"),
        )
    }

    /**
     * The model identity digest, which the RECEIPT states and the client never sends.
     *
     * Checked anyway: it is a plain digest of the `model` object, so agreeing with it
     * proves the serialiser against a value produced by a different code path on the server
     * — one that never round-tripped through a client at all.
     */
    @Test
    fun the_model_identity_digest_matches_the_receipt() {
        val model = turnRequest().getValue("provider_observation").jsonObject
            .getValue("model").jsonObject
        val receipt = HarnessDecoder.STRICT
            .decodeFromString(JsonObject.serializer(), golden("client-assistant-turn-response.json"))
            .getValue("receipt").jsonObject
        assertEquals(
            receipt.getValue("model_identity_sha256").jsonPrimitive.content,
            CanonicalJson.sha256(model),
        )
    }

    // ── the rules themselves ────────────────────────────────────────────────

    @Test
    fun keys_are_sorted_and_output_is_compact() {
        val value = buildJsonObject {
            put("zebra", 1)
            put("alpha", "two")
            putJsonArray("middle") { }
        }
        assertEquals("""{"alpha":"two","middle":[],"zebra":1}""", CanonicalJson.text(value))
    }

    @Test
    fun non_ascii_travels_raw_and_is_nfc_normalised() {
        // `ensure_ascii=False`: an encoder that escaped this to é would emit valid
        // JSON with a different digest. And the decomposed form must normalise to the
        // composed one, or the same visible text hashes two ways.
        assertEquals("""{"k":"café"}""", CanonicalJson.text(buildJsonObject { put("k", "café") }))
        val decomposed = "cafe\u0301"  // e + COMBINING ACUTE, NFD
        assertEquals(
            CanonicalJson.text(buildJsonObject { put("k", "café") }),
            CanonicalJson.text(buildJsonObject { put("k", decomposed) }),
        )
    }

    @Test
    fun control_characters_use_the_short_escapes_python_uses() {
        // The expectation is Python's own output for this value, not a guess.
        // U+0001 has no short form and becomes lowercase \\u0001; U+000C does have
        // one and must use it. An encoder that spelled every control character out in
        // \\u form would agree on the first and differ on the second.
        val value = buildJsonObject { put("k", "a\nb\tc\u0001d\u000Ce") }
        assertEquals("""{"k":"a\nb\tc\u0001d\fe"}""", CanonicalJson.text(value))
    }

    @Test
    fun a_float_is_refused_rather_than_formatted() {
        // 1.0 versus 1, exponent form, precision -- every language picks differently, so
        // the contract forbids floats instead of choosing. Refusing loudly here beats
        // emitting bytes the Foundry will hash to something else.
        val value = JsonObject(mapOf("k" to JsonPrimitive(1.5)))
        assertTrue(runCatching { CanonicalJson.text(value) }.isFailure)
    }

    @Test
    fun the_hashed_form_has_no_trailing_line_feed_but_the_stored_form_does() {
        // The distinction that would break every digest while looking right: `digest()`
        // hashes `canonical_bytes`, and only storage and transport append the terminator.
        val value = buildJsonObject { put("k", 1) }
        assertEquals("""{"k":1}""", CanonicalJson.text(value))
        assertEquals("""{"k":1}""" + "\n", CanonicalJson.line(value))
        assertEquals(
            CanonicalJson.sha256("""{"k":1}""".toByteArray()),
            CanonicalJson.sha256(value),
        )
    }

    @Test
    fun the_goldens_are_stored_as_canonical_lines() {
        for (name in listOf(
            "client-assistant-turn-request.json", "client-assistant-turn-response.json",
        )) {
            val raw = golden(name)
            assertTrue("$name must end in exactly one line feed", raw.endsWith("\n"))
            val value = HarnessDecoder.STRICT.decodeFromString(JsonObject.serializer(), raw)
            // Round-trip: the Foundry's stored bytes ARE this serialiser's output.
            assertEquals(name, raw, CanonicalJson.line(value))
        }
    }
}
