package com.verbalogix.assistant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.ui.evidence.EvidenceEntry
import com.verbalogix.assistant.ui.evidence.RetrievalEvidence
import com.verbalogix.assistant.ui.evidence.RetrievalReceipt
import com.verbalogix.assistant.ui.evidence.RetrievalTarget
import com.verbalogix.assistant.ui.evidence.RetrievalUiState
import com.verbalogix.assistant.ui.evidence.SourceRef
import com.verbalogix.assistant.ui.evidence.TAG_RETRIEVAL_OMISSIONS
import com.verbalogix.assistant.ui.evidence.TAG_RETRIEVAL_RECEIPT
import com.verbalogix.assistant.ui.experts.ExpertDetail
import com.verbalogix.assistant.ui.experts.ExpertDetailScreen
import com.verbalogix.assistant.ui.experts.ExpertDetailUiState
import com.verbalogix.assistant.ui.experts.ExpertLifecycle
import com.verbalogix.assistant.ui.experts.ExpertSummary
import com.verbalogix.assistant.ui.experts.TAG_EXPERT_QUERY_FIELD
import com.verbalogix.assistant.ui.experts.TAG_EXPERT_QUERY_SUBMIT
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Searching an expert, end to end on the composition the navigation graph builds.
 *
 * THE WHOLE CYCLE, not the pieces. `ExpertsDestinationTest` exists because a screen tested
 * in isolation was wrapped differently by the thing that shipped, and the composition under
 * test was never the composition that ran. The same lesson applies here: retrieval lives
 * INSIDE `ExpertDetailScreen`, so it is exercised there — typed into, submitted, rendered —
 * rather than as a detached view with a hand-made state.
 *
 * `performScrollTo` is correct on this screen and would be wrong on the library: this is a
 * `Column` with `verticalScroll`, so every node is composed whether or not it is visible.
 * The lazy list is the one that needs `performScrollToNode`.
 */
class ExpertDetailRetrievalTest {

    @get:Rule val compose = createComposeRule()

    private val packId = "kf:pack:${"a1".repeat(32)}"
    private val releaseId = "kf:pack-release:${"b2".repeat(32)}"

    private fun expert(lifecycle: ExpertLifecycle = ExpertLifecycle.MOUNTED) = ExpertDetail(
        summary = ExpertSummary(
            packId = packId,
            releaseId = releaseId,
            name = "Knowledge Foundry Project Expert",
            namespace = "org.knowledge-foundry",
            slug = "project-expert",
            version = "1.0.0",
            lifecycle = lifecycle,
            trustState = "trusted",
        ),
        description = "The Knowledge Foundry's own documentation.",
        profile = "project-expert",
        riskClass = "standard",
        publicationChannel = "internal",
        capabilities = listOf("lexical", "graph"),
        allowedSensitivities = listOf("internal"),
        contentSha256 = "c1".repeat(32),
        archiveSha256 = "c2".repeat(32),
        installRecordSha256 = "c3".repeat(32),
        installId = "kf:install:${"c4".repeat(32)}",
        signerKeyId = "kf:key:${"c5".repeat(32)}",
        compatibility = "compatible",
        dependencyReleaseIds = emptyList(),
        verificationSha256 = "c6".repeat(32),
        predecessorReleaseId = null,
        rollbackReleaseId = null,
        supersededContentSha256 = null,
    )

    /** Shaped like the golden's two items, small enough to assert against. */
    private fun evidence() = RetrievalEvidence(
        answerability = "supported",
        disposition = "succeeded",
        reasonCode = null,
        items = listOf(
            EvidenceEntry(
                evidenceId = "kf:evidence:${"d1".repeat(32)}",
                packId = packId,
                releaseId = releaseId,
                kind = "passage",
                // LONG ON PURPOSE. The Foundry's per-item ceiling is 8 000 bytes, so a
                // real item can be an entire source file -- which is what the phone
                // showed, and what made eight results unreadable.
                text = (1..240).joinToString("\n") { "line $it of a quoted source file" },
                knowledgeStatus = "supported",
                uncertainty = "none",
                sources = listOf(
                    SourceRef(
                        sourceId = "kf:source:${"d2".repeat(32)}",
                        logicalLocator = "docs/packs.md#L12",
                        sensitivity = "internal",
                        contentSha256 = "d3".repeat(32),
                    ),
                ),
                graphPathIds = emptyList(),
                contradictionIds = emptyList(),
                packFusedRank = 1,
                globalFusedRank = 1,
                lexicalRank = 1,
                graphRank = null,
            ),
            EvidenceEntry(
                // THE GOLDEN'S SECOND ITEM, shaped as the server actually returns it:
                // reached through the graph channel, with a real locator, a real rank and
                // a graph path -- and no quotable text at all.
                evidenceId = "kf:evidence:${"d4".repeat(32)}",
                packId = packId,
                releaseId = releaseId,
                kind = "source",
                text = "",
                knowledgeStatus = "supported",
                uncertainty = "not_observed",
                sources = listOf(
                    SourceRef(
                        sourceId = "kf:source:${"d5".repeat(32)}",
                        logicalLocator = "README.md",
                        sensitivity = "internal",
                        contentSha256 = "d6".repeat(32),
                    ),
                ),
                graphPathIds = listOf("kf:graph-path:${"d7".repeat(32)}"),
                contradictionIds = emptyList(),
                packFusedRank = 2,
                globalFusedRank = 2,
                lexicalRank = null,
                graphRank = 1,
            ),
        ),
        contradictions = emptyList(),
        omissions = (1..10).map { "kf:candidate:${"%02x".format(it).repeat(32)}" },
        truncationBoundaries = emptyList(),
        receipt = RetrievalReceipt(
            packetId = "kf:evidence-packet:${"e1".repeat(32)}",
            packetSha256 = "e2".repeat(32),
            traceId = "kf:retrieval-trace:${"e3".repeat(32)}",
            deterministicCoreSha256 = "e4".repeat(32),
            planId = "kf:retrieval-plan:${"e5".repeat(32)}",
            resultSha256 = "e6".repeat(32),
            mountRegistrySha256 = "e7".repeat(32),
        ),
    )

    private val submissions = mutableListOf<RetrievalTarget>()

    /**
     * The screen, wired the way the navigation graph wires it.
     *
     * The question is hoisted into plain `remember` state here rather than
     * `rememberSaveable`, mirroring the production wiring where it lives in the view model.
     * Saved instance state is a Bundle the system may persist, and a question typed against
     * a private knowledge base does not belong in one.
     */
    private fun screen(lifecycle: ExpertLifecycle = ExpertLifecycle.MOUNTED) {
        submissions.clear()
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 720.dp)),
            ) {
                LocalmindTheme(darkTheme = true) {
                    var text by remember { mutableStateOf("") }
                    var retrieval by remember {
                        mutableStateOf<RetrievalUiState>(RetrievalUiState.Idle)
                    }
                    ExpertDetailScreen(
                        state = ExpertDetailUiState.Ready(expert(lifecycle)),
                        retrieval = retrieval,
                        queryText = text,
                        onQueryChange = { text = it },
                        onSubmitQuery = { target ->
                            submissions += target
                            retrieval = RetrievalUiState.Ready(evidence())
                        },
                    )
                }
            }
        }
    }

    // ── explicit submission ─────────────────────────────────────────────────

    /**
     * THE PROPERTY THAT COSTS REAL REQUESTS TO GET WRONG.
     *
     * A search-as-you-type retrieval would send one request per prefix — a dozen partial
     * questions against a real knowledge base on the way to the one the user meant — and
     * would then race its own answers back onto the screen.
     */
    @Test
    fun typing_a_question_sends_nothing() {
        screen()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performScrollTo()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performTextInput("what is a pack")
        assertTrue("typing must not issue a retrieval: $submissions", submissions.isEmpty())
    }

    @Test
    fun submitting_sends_exactly_one_request_for_the_release_on_screen() {
        screen()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performScrollTo()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performTextInput("what is a pack")
        compose.onNodeWithTag(TAG_EXPERT_QUERY_SUBMIT).performScrollTo().performClick()

        assertEquals(1, submissions.size)
        val target = submissions.single()
        assertEquals(packId, target.packId)
        assertEquals(releaseId, target.releaseId)
        // Copied from the release, never defaulted.
        assertEquals(listOf("internal"), target.allowedSensitivities)
        assertTrue(target.active)
    }

    @Test
    fun the_action_is_disabled_until_there_is_a_question() {
        screen()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_SUBMIT).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performTextInput("what is a pack")
        compose.onNodeWithTag(TAG_EXPERT_QUERY_SUBMIT).assertIsEnabled()
    }

    // ── what comes back ─────────────────────────────────────────────────────

    @Test
    fun the_returned_evidence_is_rendered_with_its_provenance() {
        screen()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performScrollTo()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performTextInput("what is a pack")
        compose.onNodeWithTag(TAG_EXPERT_QUERY_SUBMIT).performScrollTo().performClick()

        compose.onNodeWithText("line 1 of a quoted source file", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("docs/packs.md#L12 · internal", substring = true)
            .performScrollTo().assertIsDisplayed()
        // The Harness's verdict, attributed to the Harness rather than stated as the app's.
        compose.onNodeWithText("Knowledge Foundry assessed this evidence as", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun the_receipt_stays_behind_progressive_disclosure() {
        screen()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performScrollTo()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performTextInput("what is a pack")
        compose.onNodeWithTag(TAG_EXPERT_QUERY_SUBMIT).performScrollTo().performClick()

        compose.onAllNodesWithText("Mount registry SHA-256").assertCountEquals(0)
        compose.onNodeWithTag(TAG_RETRIEVAL_RECEIPT).performScrollTo().performClick()
        compose.onNodeWithText("Mount registry SHA-256").performScrollTo().assertIsDisplayed()
    }

    /**
     * NO ANSWER, ASSERTED ON THE RENDERED SCREEN.
     *
     * The data model has no field one could occupy — a unit test proves that. This is the
     * other half: that nothing on screen presents the retrieval AS an answer, which is the
     * form the mistake would actually take.
     */
    @Test
    fun nothing_on_screen_offers_an_answer() {
        screen()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performScrollTo()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performTextInput("what is a pack")
        compose.onNodeWithTag(TAG_EXPERT_QUERY_SUBMIT).performScrollTo().performClick()

        // EXACT LABELS. Substring matching on "Answer" would hit the disclaimer that says
        // Localmind has NOT written one -- reporting the safeguard as the violation.
        for (forbidden in listOf("Ask", "Ask this expert", "Answer", "Get answer", "Send")) {
            compose.onAllNodesWithText(forbidden).assertCountEquals(0)
        }
        compose.onNodeWithText("Localmind has not written an answer", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    /**
     * AN EMPTY QUOTATION IS NOT A BLANK SPACE.
     *
     * The Foundry returns graph-reached items whose `selected_text` is empty. Rendering
     * that through the quotation block leaves a gap between a locator and a rank line that
     * reads as a rendering failure -- and a user who thinks the screen is broken has no
     * reason to trust the cards that did render.
     */
    @Test
    fun an_item_with_no_quoted_text_says_so_instead_of_showing_a_gap() {
        screen()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performScrollTo()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performTextInput("Project Expert")
        compose.onNodeWithTag(TAG_EXPERT_QUERY_SUBMIT).performScrollTo().performClick()

        compose.onNodeWithText("No quoted text was included for this item.")
            .performScrollTo().assertIsDisplayed()
        // The card is kept, not dropped: what it does carry is still evidence.
        compose.onNodeWithText("README.md · internal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("graph paths: 1").performScrollTo().assertIsDisplayed()
    }

    /**
     * A QUOTATION THE SIZE OF A SOURCE FILE IS FOLDED, NOT SHORTENED.
     *
     * Eight items at the Foundry's 8 000-byte ceiling is several hundred screens of
     * monospace with the receipt somewhere past the end. The fold states the real total
     * and opens on request -- nothing is condensed, and nothing is dropped from the middle.
     */
    @Test
    fun a_long_quotation_is_folded_with_its_real_length_and_opens_in_full() {
        screen()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performScrollTo()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performTextInput("memory")
        compose.onNodeWithTag(TAG_EXPERT_QUERY_SUBMIT).performScrollTo().performClick()

        // The head is shown and the tail is not.
        compose.onNodeWithText("line 1 of a quoted source file", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("line 240 of a quoted source file", substring = true)
            .assertCountEquals(0)
        // THE REAL TOTAL, not a vague "truncated".
        compose.onNodeWithText("first 12 of 240 lines").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Show the whole quotation (240 lines)")
            .performScrollTo().performClick()
        compose.onNodeWithText("line 240 of a quoted source file", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    /**
     * OMISSIONS ARE COUNTED FIRST AND LISTED SECOND.
     *
     * On this Foundry `truncation.boundaries` is always empty and the real signal is the
     * omission list, so the count IS the "what fit versus what exists" notice. Ten raw
     * identities rendered above the evidence was the shape that buried it.
     */
    @Test
    fun omitted_candidates_are_summarised_before_they_are_listed() {
        screen()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performScrollTo()
        compose.onNodeWithTag(TAG_EXPERT_QUERY_FIELD).performTextInput("memory")
        compose.onNodeWithTag(TAG_EXPERT_QUERY_SUBMIT).performScrollTo().performClick()

        compose.onNodeWithText("10 further candidate(s) matched and were not included")
            .performScrollTo().assertIsDisplayed()
        // Identities stay available -- folded, not withheld.
        compose.onAllNodesWithText("kf:candidate:", substring = true).assertCountEquals(0)
        compose.onNodeWithTag(TAG_RETRIEVAL_OMISSIONS).performScrollTo().performClick()
        compose.onAllNodesWithText("kf:candidate:", substring = true)
            .assertCountEquals(10)
    }

    // ── the gate ────────────────────────────────────────────────────────────

    @Test
    fun an_inactive_release_offers_no_search_at_all() {
        screen(ExpertLifecycle.INSTALLED_INACTIVE)
        compose.onNodeWithText("Search this expert").performScrollTo().assertIsDisplayed()
        // The heading stays so the absence is explained rather than silent; the field and
        // the action are gone, because retrieval runs against what is MOUNTED and a
        // question asked here would be answered from something else.
        compose.onAllNodesWithTag(TAG_EXPERT_QUERY_FIELD).assertCountEquals(0)
        compose.onAllNodesWithTag(TAG_EXPERT_QUERY_SUBMIT).assertCountEquals(0)
        compose.onNodeWithText("installed but not active", substring = true)
            .performScrollTo().assertIsDisplayed()
    }
}
