package com.verbalogix.assistant.ui.nav

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route contract, checked against the committed JSON rather than against itself.
 *
 * A test that asserted `Destinations.CHAT == "chat"` would only prove the constant had
 * not been edited since the test was written -- it restates the code. These read
 * `docs/ui/route-manifest.json`, which is the artifact shared with Knowledge Studio, so
 * a route renamed on either side fails here in two seconds.
 */
class DestinationsTest {

    private val manifest by lazy {
        val file = repoFile("docs/ui/route-manifest.json")
        assertTrue("route-manifest.json not found at ${file.absolutePath}", file.exists())
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    /** Route -> component, for the localmind-android client only. */
    private fun androidRoutes(): Map<String, String> =
        manifest["clients"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["client"]!!.jsonPrimitive.content == "localmind-android" }
            .get("routes")!!.jsonArray
            .associate {
                val o = it.jsonObject
                o["route"]!!.jsonPrimitive.content to o["component"]!!.jsonPrimitive.content
            }

    @Test
    fun `all seven android destinations are declared`() {
        assertEquals(7, Destinations.ALL.size)
        assertEquals(
            "Destinations.ALL must contain no duplicates",
            7,
            Destinations.ALL.toSet().size,
        )
    }

    /**
     * Every destination matches the shared manifest, except one that is knowingly ahead
     * of it.
     *
     * THE EXCEPTION IS NAMED RATHER THAN THE CHECK RELAXED. `docs/ui/route-manifest.json`
     * still declares `experts/{packId}/{version}`; the live adapter takes a release
     * identity and the app now routes on one. That is a deliberate divergence made on the
     * stronger authority -- an observed contract beats a document describing it -- and it
     * is expected to close when the manifest follows.
     *
     * Listing the single exception keeps the test doing its job for the other six. A
     * loosened assertion, or a deleted one, would have let any FUTURE drift through
     * silently, which is the failure this test exists to prevent.
     */
    @Test
    fun `every declared destination appears in the shared route manifest`() {
        val contract = androidRoutes().keys
        val knownDivergence = setOf(
            "experts/{packId}/{version}",   // manifest, superseded
            "experts/{releaseId}",          // app, live-contract correct
        )
        assertEquals(
            "app destinations and the contract's routes must be the same set, " +
                "apart from the recorded expert-detail divergence",
            contract - knownDivergence,
            Destinations.ALL.toSet() - knownDivergence,
        )
        // And the divergence is exactly the one described: the manifest still has the old
        // form, the app has the new one. If either side changes, this stops being true
        // and the exception must be revisited rather than widened.
        assertTrue(
            "the manifest is expected to still declare the two-argument form",
            "experts/{packId}/{version}" in contract,
        )
        assertTrue(
            "the app is expected to route on the release identity",
            "experts/{releaseId}" in Destinations.ALL,
        )
    }

    @Test
    fun `the seven contract components are the seven surfaces this slice builds`() {
        assertEquals(
            setOf(
                "SetupReadinessScreen",
                "ChatScreen",
                "EvidenceDrawer",
                "ExpertLibraryScreen",
                "ExpertDetailScreen",
                "ModelsProvidersScreen",
                "ToolApprovalSheet",
            ),
            androidRoutes().values.toSet(),
        )
    }

    // ── Builders ────────────────────────────────────────────────────────────────

    @Test
    fun `evidence route is built from a valid row id`() {
        assertEquals("chat/message/7/evidence", Destinations.evidence(7L))
    }

    @Test
    fun `evidence route refuses ids that cannot address a row`() {
        // 0 is Room's unsaved-entity sentinel, used as the default all over Message.
        assertNull(Destinations.evidence(0L))
        assertNull(Destinations.evidence(-1L))
        assertNull(Destinations.evidence(Long.MIN_VALUE))
    }

    @Test
    fun `expert detail route is built from an immutable release identity`() {
        // Was `experts/{packId}/{version}` until the live adapter showed the lookup is by
        // RELEASE. A pack id names a pack across all of its releases and a version is a
        // label attached to one, so the pair resolved two softer facts to reach an
        // immutable thing that already had its own name.
        val releaseId = "kf:pack-release:" + "7b".repeat(32)
        assertEquals("experts/$releaseId", Destinations.expertDetail(releaseId))
    }

    @Test
    fun `a release identity must match the exact kf grammar`() {
        // STRICTER than the general identifier rule, not looser: exactly `kf:`, a
        // lowercase kind, and sixty-four lowercase hex characters. A release id is 80
        // characters with colons and fails the generic rule on both counts, so the fix
        // was an exact-shape allow-list rather than a relaxed one.
        assertNull(RouteArgs.releaseIdOrNull("kf-core-env"))
        assertNull(RouteArgs.releaseIdOrNull("kf:pack-release:" + "a".repeat(63)))
        assertNull(RouteArgs.releaseIdOrNull("kf:pack-release:" + "A".repeat(64)))
        assertNull(RouteArgs.releaseIdOrNull("../../etc/passwd"))
        assertEquals("kf:pack-release:" + "7b".repeat(32), RouteArgs.releaseIdOrNull("kf:pack-release:" + "7b".repeat(32)))
    }

    @Test
    fun `tool proposal route is built from valid identifiers`() {
        assertEquals(
            "sessions/s1/tool-proposals/p1",
            Destinations.toolProposal("s1", "p1"),
        )
    }

    // ── Argument validation is a trust boundary ─────────────────────────────────

    @Test
    fun `malformed identifiers are rejected`() {
        val rejected = listOf(
            "",                       // empty
            "..",                     // parent traversal
            ".",                      // current dir
            "../../etc/passwd",       // traversal with separators
            "a/b",                    // path separator
            "a b",                    // whitespace
            "a%2Fb",                  // percent-encoding, so no decode asymmetry exists
            "-leading-dash",          // could read as a flag
            ".leading-dot",           // hidden-file shape
            "_leading-underscore",
            "a".repeat(65),           // over the length bound
            "pack\u0000id",         // embedded NUL
            "pack\nid",               // newline
            "packé",                  // non-ASCII
            "<script>",               // markup
        )
        for (candidate in rejected) {
            assertNull("must reject identifier: <$candidate>", RouteArgs.identifierOrNull(candidate))
            assertNull(
                "must refuse to build an expert route from: <$candidate>",
                Destinations.expertDetail(candidate),
            )
            assertNull(
                "must refuse to build a proposal route from: <$candidate>",
                Destinations.toolProposal("session", candidate),
            )
        }
    }

    @Test
    fun `well formed identifiers are accepted`() {
        val accepted = listOf(
            "a",
            "kf-core-env",
            "pack.id",
            "pack_id",
            "1.0.0",
            "v1",
            "a".repeat(64), // exactly at the bound
        )
        for (candidate in accepted) {
            assertEquals(candidate, RouteArgs.identifierOrNull(candidate))
        }
    }

    @Test
    fun `null identifier is rejected rather than crashing`() {
        assertNull(RouteArgs.identifierOrNull(null))
        assertNull(RouteArgs.rowIdOrNull(null))
    }

    @Test
    fun `row ids arriving as route arguments are parsed and bounded`() {
        assertEquals(7L, RouteArgs.rowIdOrNull("7"))
        assertNull(RouteArgs.rowIdOrNull("0"))
        assertNull(RouteArgs.rowIdOrNull("-3"))
        assertNull(RouteArgs.rowIdOrNull("abc"))
        assertNull(RouteArgs.rowIdOrNull("7.5"))
        assertNull(RouteArgs.rowIdOrNull(""))
        // Larger than Long.MAX_VALUE: toLongOrNull returns null rather than wrapping.
        assertNull(RouteArgs.rowIdOrNull("99999999999999999999"))
    }

    @Test
    fun `parameterised patterns use the same argument names the graph reads back`() {
        // The classic navigation bug is two spellings of one argument name: the pattern
        // declares `messageId`, the lookup asks for `message_id`, and the screen
        // silently receives null. Asserting the constant is IN the pattern is what
        // makes that impossible to introduce.
        assertTrue(Destinations.EVIDENCE.contains("{$ARG_MESSAGE_ID}"))
        assertTrue(Destinations.EXPERT_DETAIL.contains("{$ARG_RELEASE_ID}"))
        assertTrue(Destinations.TOOL_PROPOSAL.contains("{$ARG_SESSION_ID}"))
        assertTrue(Destinations.TOOL_PROPOSAL.contains("{$ARG_PROPOSAL_ID}"))
    }

    @Test
    fun `built routes contain no unsubstituted placeholders`() {
        val built = listOfNotNull(
            Destinations.evidence(1L),
            Destinations.expertDetail("kf:pack-release:" + "7b".repeat(32)),
            Destinations.toolProposal("s", "p"),
        )
        assertEquals(3, built.size)
        for (route in built) {
            assertTrue("route still contains a placeholder: $route", !route.contains("{"))
            assertTrue("route still contains a placeholder: $route", !route.contains("}"))
        }
    }

    companion object {
        /**
         * Unit tests run with the module directory as the working directory, but that
         * is a convention rather than a guarantee, so the root is located by walking up
         * for a known marker instead of hard-coding `../`.
         */
        fun repoFile(relative: String): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val candidate = File(dir, relative)
                if (candidate.exists()) return candidate
                dir = dir.parentFile
            }
            return File(relative)
        }
    }

    @Test
    fun `manifest is actually being read, not silently skipped`() {
        // Guards the guard. If repoFile() ever failed to find the file, the map would
        // be empty and every set comparison above would still "pass" against an empty
        // expectation -- a check that reports success while verifying nothing, which is
        // the exact failure this repo's corpus was built to prevent.
        assertNotNull(manifest["clients"])
        assertEquals(7, androidRoutes().size)
    }
}
