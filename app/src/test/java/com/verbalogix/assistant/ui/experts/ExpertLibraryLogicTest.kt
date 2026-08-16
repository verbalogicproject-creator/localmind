package com.verbalogix.assistant.ui.experts

import com.verbalogix.assistant.data.harness.HarnessDecoder
import com.verbalogix.assistant.data.harness.HarnessOutcome
import com.verbalogix.assistant.data.harness.HarnessRefusal
import com.verbalogix.assistant.data.harness.SchemaVerdict
import com.verbalogix.assistant.data.harness.wire.ExpertCatalogResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library's own logic: filtering, search, and abbreviating identities safely.
 *
 * All pure functions, so the interesting cases -- two digests that collide at twelve
 * characters, a search for a pasted `kf:` string -- are ordinary assertions rather than
 * something reachable only by contriving a catalog on a device.
 */
class ExpertLibraryLogicTest {

    private fun summary(
        name: String,
        releaseDigest: String,
        lifecycle: ExpertLifecycle = ExpertLifecycle.MOUNTED,
        slug: String = "slug",
        namespace: String = "org.example",
        version: String = "1.0.0",
    ) = ExpertSummary(
        packId = "kf:pack:" + "b".repeat(64),
        releaseId = "kf:pack-release:$releaseDigest",
        name = name,
        namespace = namespace,
        slug = slug,
        version = version,
        lifecycle = lifecycle,
        trustState = "trusted",
    )

    // ── abbreviation must never make two things look alike ──────────────────

    @Test
    fun distinct_identities_abbreviate_to_the_floor_length() {
        val a = summary("A", "a".repeat(64))
        val b = summary("B", "f".repeat(64))
        val short = abbreviateIdentities(listOf(a.releaseId, b.releaseId))

        assertEquals("kf:pack-release:" + "a".repeat(MIN_DIGEST_CHARS) + "…", short[a.releaseId])
        assertNotEquals(short[a.releaseId], short[b.releaseId])
    }

    @Test
    fun colliding_prefixes_grow_until_they_are_unique() {
        // THE CASE A FIXED TRUNCATION GETS WRONG. These two share the first twenty hex
        // characters; abbreviated at twelve they render identically, and the user is then
        // looking at two rows that appear to name the same release. A digest that has
        // stopped distinguishing things is worse than no digest.
        val shared = "c".repeat(20)
        val a = summary("A", shared + "1" + "0".repeat(43))
        val b = summary("B", shared + "2" + "0".repeat(43))
        val short = abbreviateIdentities(listOf(a.releaseId, b.releaseId))

        assertNotEquals(
            "abbreviations must stay distinguishable",
            short[a.releaseId], short[b.releaseId],
        )
        assertTrue(
            "the prefix must have grown past the floor",
            short.getValue(a.releaseId).length > "kf:pack-release:".length + MIN_DIGEST_CHARS,
        )
    }

    @Test
    fun a_single_identity_still_abbreviates_and_keeps_its_kind() {
        val only = summary("Only", "d".repeat(64))
        val short = abbreviateIdentities(listOf(only.releaseId)).getValue(only.releaseId)
        // The kind survives: `kf:pack:` and `kf:pack-release:` are different namespaces,
        // and dropping the prefix would make a release look like a pack.
        assertTrue(short.startsWith("kf:pack-release:"))
        assertTrue(short.endsWith("…"))
    }

    // ── filters ─────────────────────────────────────────────────────────────

    @Test
    fun filters_split_on_mount_state_and_nothing_else() {
        val active = summary("Active", "1".repeat(64), ExpertLifecycle.MOUNTED)
        val inactive = summary("Inactive", "2".repeat(64), ExpertLifecycle.INSTALLED_INACTIVE)
        val all = listOf(active, inactive)

        assertEquals(all, all.filter { ExpertFilter.ALL.matches(it) })
        assertEquals(listOf(active), all.filter { ExpertFilter.ACTIVE.matches(it) })
        assertEquals(listOf(inactive), all.filter { ExpertFilter.INACTIVE.matches(it) })
    }

    @Test
    fun there_is_no_updates_filter() {
        // Deliberate. `expert-release-summary/3.0` carries no predecessor, so an update
        // badge could only be inferred from two versions existing -- a claim this client
        // is not entitled to make. The mockups show the chip; the contract does not
        // support it.
        assertEquals(
            listOf(ExpertFilter.ALL, ExpertFilter.ACTIVE, ExpertFilter.INACTIVE),
            ExpertFilter.entries.toList(),
        )
    }

    // ── search ──────────────────────────────────────────────────────────────

    @Test
    fun search_matches_name_namespace_slug_and_both_identities() {
        val one = summary("Project Expert", "aa".repeat(32), slug = "project-expert")
        val two = summary("Repo Coder", "bb".repeat(32), slug = "repo-coder")
        val all = listOf(one, two)

        assertEquals(listOf(one), all.search("project"))
        assertEquals(listOf(one), all.search("PROJECT"))          // case-insensitive
        assertEquals(listOf(one), all.search("project-expert"))   // slug
        // A shared namespace matches both, which is the correct behaviour for a filter
        // over a field the entries have in common.
        assertEquals(all, all.search("org.example"))
        // A user who copied a release id out of a log can paste the whole thing.
        assertEquals(listOf(two), all.search(two.releaseId))
    }

    @Test
    fun an_empty_query_filters_nothing() {
        val all = listOf(summary("A", "1".repeat(64)), summary("B", "2".repeat(64)))
        assertEquals(all, all.search(""))
        assertEquals(all, all.search("   "))
    }

    // ── mapping from real bytes ─────────────────────────────────────────────

    private fun golden(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("goldens/stage3c-v1/$name")) {
            "missing golden"
        }.readBytes().decodeToString()

    @Test
    fun the_populated_golden_becomes_a_ready_library() {
        val state = HarnessDecoder.decodeExpertCatalog(
            golden("client-populated-catalog-response.json"),
        ).toLibraryState()

        val ready = state as ExpertLibraryUiState.Ready
        val expert = ready.experts.single()
        assertEquals("Knowledge Foundry Project Expert", expert.name)
        assertEquals(ExpertLifecycle.MOUNTED, expert.lifecycle)
        assertEquals("trusted", expert.trustState)
    }

    @Test
    fun the_empty_golden_stays_empty_and_never_becomes_a_failure() {
        val state = HarnessDecoder.decodeExpertCatalog(
            golden("client-empty-catalog-response.json"),
        ).toLibraryState()
        assertEquals(ExpertLibraryUiState.Empty, state)
    }

    @Test
    fun an_incompatibility_is_reported_separately_from_a_refusal() {
        // Both mean "nothing was read", and only one is fixed by shipping a release.
        val incompatible: HarnessOutcome<ExpertCatalogResult> = HarnessOutcome.Refused(
            HarnessRefusal.RuntimeContract("0.4.0", "0.3.2"),
        )
        assertTrue(incompatible.toLibraryState() is ExpertLibraryUiState.Incompatible)

        val unsupported: HarnessOutcome<ExpertCatalogResult> = HarnessOutcome.Refused(
            HarnessRefusal.Schema(SchemaVerdict.Unsupported("x/9.9")),
        )
        assertTrue(unsupported.toLibraryState() is ExpertLibraryUiState.Incompatible)

        // A correlation failure is one bad exchange, not a standing disagreement, so
        // telling the user to update the app would be wrong advice.
        val mismatched: HarnessOutcome<ExpertCatalogResult> = HarnessOutcome.Refused(
            HarnessRefusal.OperationMismatch("a", "b"),
        )
        assertTrue(mismatched.toLibraryState() is ExpertLibraryUiState.Refused)
    }

    @Test
    fun the_detail_golden_becomes_a_ready_detail_with_every_contracted_field() {
        val state = HarnessDecoder.decodeExpertReleaseDetail(
            golden("client-release-detail-response.json"),
        ).toDetailState()

        val detail = (state as ExpertDetailUiState.Ready).expert
        assertEquals("accepted", detail.compatibility)
        assertTrue(detail.signerKeyId.startsWith("ed25519:"))
        assertEquals(8, detail.capabilities.size)
        assertEquals(listOf("internal"), detail.allowedSensitivities)
        // Digests are carried at full length; the detail screen never truncates them.
        assertEquals(64, detail.contentSha256.length)
        assertEquals(64, detail.verificationSha256.length)
    }
}
