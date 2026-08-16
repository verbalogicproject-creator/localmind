package com.verbalogix.assistant.data

import com.verbalogix.assistant.data.harness.HarnessDecoder
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model presentation, against bytes the live llama-swap actually produced.
 *
 * The fixture is a verbatim capture of `GET /v1/models` from the running server, so the
 * "unknown fields" these tests tolerate are the real ones -- `object`, `created`,
 * `owned_by`, `description`, `meta` -- and not a guess at what a server might add.
 */
class ModelPresentationTest {

    private fun liveModels(): String =
        checkNotNull(
            javaClass.classLoader?.getResourceAsStream("goldens/llama-swap/v1-models.json"),
        ) { "missing llama-swap fixture" }.readBytes().decodeToString()

    // ── the two JSON policies, and why they differ ──────────────────────────

    @Test
    fun llama_swap_fields_this_app_does_not_model_are_tolerated() {
        // Five undeclared fields per entry. Strict decoding would throw here, and every
        // status probe would report the server unreachable while it was working.
        val models = LENIENT_JSON.decodeFromString(SwapModels.serializer(), liveModels())
        assertEquals(3, models.data.size)
        assertEquals(listOf("bonsai-8b", "lfm-8b", "qwen-4b"), models.data.map { it.id })
    }

    @Test
    fun the_harness_decoder_would_refuse_the_same_document() {
        // The deliberate difference, asserted rather than described. Same bytes, opposite
        // verdict, because a surprise from a third-party server and a surprise from a
        // closed contract mean different things.
        val strict = HarnessDecoder.STRICT
        var threw = false
        try {
            strict.decodeFromString(SwapModels.serializer(), liveModels())
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("Harness strictness must reject undeclared fields", threw)
    }

    @Test
    fun the_two_policies_are_not_accidentally_the_same_object() {
        assertFalse(
            "leniency must be a per-contract decision, not one shared config",
            LENIENT_JSON === HarnessDecoder.STRICT,
        )
    }

    // ── identity comes from the id, never the label ─────────────────────────

    @Test
    fun models_are_identified_by_stable_id_not_display_name() {
        val models = LENIENT_JSON.decodeFromString(SwapModels.serializer(), liveModels())
        // The ids are the routing keys llama-swap matches on, and the app's seeded
        // provider rows carry exactly these. A rename in the server's config changes
        // `name` and must never change which model a provider selects.
        val byId = models.data.associateBy { it.id }
        assertEquals("LFM2.5 8B", byId.getValue("lfm-8b").name)
        assertEquals("Bonsai 8B (1-bit)", byId.getValue("bonsai-8b").name)
        assertNotNull(byId["qwen-4b"])
    }

    @Test
    fun description_is_not_modelled_at_all() {
        // The live descriptions read "MEASURED AT 2.8 tok/s" and "~24 tok/s". Those are
        // strings from a YAML file, not measurements -- and a field that cannot be
        // referenced cannot be rendered by accident.
        val fields = SwapModel.serializer().descriptor.let { d ->
            (0 until d.elementsCount).map { d.getElementName(it) }
        }
        assertEquals(listOf("id", "status", "name"), fields)
        assertFalse("description must not be modelled", "description" in fields)
    }

    @Test
    fun no_throughput_claim_reaches_the_status_object() {
        // Belt and braces: even the raw bytes contain "tok/s", so prove the parsed model
        // carries nothing of the sort into anything the UI reads.
        assertTrue("fixture should contain the claim", liveModels().contains("tok/s"))
        val models = LENIENT_JSON.decodeFromString(SwapModels.serializer(), liveModels())
        for (m in models.data) {
            assertFalse("a name must not smuggle a throughput claim", m.name.orEmpty().contains("tok/s"))
        }
        // ServerStatus.tokensPerSecond stays null unless an actual inference measured it.
        assertNull(ServerStatus(reachable = true).tokensPerSecond)
    }

    // ── the display-name override ───────────────────────────────────────────

    @Test
    fun a_safe_server_name_is_used_as_the_display_override() {
        assertEquals("LFM2.5 8B", ModelDisplayName.resolve("LFM2.5 8B", "seeded name"))
        assertEquals("Bonsai 8B (1-bit)", ModelDisplayName.resolve("Bonsai 8B (1-bit)", "seeded"))
    }

    @Test
    fun an_absent_or_blank_name_falls_back_to_the_configured_one() {
        assertEquals("seeded name", ModelDisplayName.resolve(null, "seeded name"))
        assertEquals("seeded name", ModelDisplayName.resolve("", "seeded name"))
        assertEquals("seeded name", ModelDisplayName.resolve("   ", "seeded name"))
    }

    @Test
    fun control_and_format_characters_are_refused_rather_than_stripped() {
        // Refused, not repaired: a sanitised hostile string is neither what the server
        // sent nor what the user configured.
        // Written as escapes on purpose: an invisible character pasted into source is
        // unreviewable, and each case matters for the CLASS it belongs to, not its glyph.
        for (hostile in listOf(
            "LFM\u0000 8B",   // Cc  NUL
            "LFM\n8B",        // Cc  newline -- a single-line row must stay one line
            "LFM\u2028 8B",   // Zl  line separator
            "LFM\u2029 8B",   // Zp  paragraph separator
            "LFM\u202E8B",    // Cf  right-to-left override: renders unlike its bytes
            "LFM\u200B8B",    // Cf  zero-width space: two names that look identical
            "LFM\u2066 8B",   // Cf  isolate
        )) {
            assertNull("must refuse: ${hostile.map { it.code }}", ModelDisplayName.sanitize(hostile))
            assertEquals("seeded", ModelDisplayName.resolve(hostile, "seeded"))
        }
    }

    @Test
    fun an_oversized_name_falls_back() {
        val long = "M".repeat(ModelDisplayName.MAX_LENGTH + 1)
        assertNull(ModelDisplayName.sanitize(long))
        assertEquals("seeded", ModelDisplayName.resolve(long, "seeded"))
        // And the boundary itself is accepted, so the limit is a limit and not an off-by-one.
        assertNotNull(ModelDisplayName.sanitize("M".repeat(ModelDisplayName.MAX_LENGTH)))
    }

    @Test
    fun a_padded_name_is_trimmed_rather_than_refused() {
        // Terminal output brings whitespace; that is not hostile, it is ordinary.
        assertEquals("LFM2.5 8B", ModelDisplayName.sanitize("  LFM2.5 8B \t"))
    }

    @Test
    fun the_override_never_becomes_the_configuration() {
        // ServerStatus is an ephemeral read model with no persistence path: it is not a
        // Room entity and nothing writes it back to the provider row. Asserted through
        // the type: a Provider built from a status would need a name field this does not
        // supply to any DAO.
        val status = ServerStatus(reachable = true, model = "lfm-8b", displayName = "Renamed By Server")
        val configured = Provider(1, "LFM2.5 8B", "http://127.0.0.1:8090", model = "lfm-8b")
        assertEquals("LFM2.5 8B", configured.name)
        assertEquals("Renamed By Server", status.displayName)
        // Identity still resolves through the id, not either label.
        assertEquals("lfm-8b", configured.model)
    }

    // ── unloaded is not unreachable ─────────────────────────────────────────

    @Test
    fun unloaded_means_reachable_with_the_model_not_resident() {
        // Every model on the live server reads "unloaded" at rest, because llama-swap
        // loads on demand. Reporting that as unreachable would tell the user to fix a
        // server that is working -- and would gate the very request that loads the model.
        val models = LENIENT_JSON.decodeFromString(SwapModels.serializer(), liveModels())
        assertTrue(models.data.all { it.status?.value == "unloaded" })

        val status = ServerStatus(reachable = true, model = "lfm-8b", modelLoaded = false)
        assertTrue("the proxy answered, so it is reachable", status.reachable)
        assertEquals(false, status.modelLoaded)
        assertNull("no error: nothing is wrong", status.error)
    }

    @Test
    fun unreachable_is_a_different_state_entirely() {
        val down = ServerStatus(reachable = false, error = "no server on http://127.0.0.1:8090")
        assertFalse(down.reachable)
        assertNotNull(down.error)
        // And it carries no residency claim, because nothing answered to make one.
        assertNull(down.modelLoaded)
    }
}
