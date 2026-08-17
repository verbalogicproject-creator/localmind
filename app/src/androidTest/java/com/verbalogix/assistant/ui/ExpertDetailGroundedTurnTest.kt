package com.verbalogix.assistant.ui

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.ui.evidence.AnswerSegmentView
import com.verbalogix.assistant.ui.evidence.EvidenceEntry
import com.verbalogix.assistant.ui.evidence.GroundedTurnUiState
import com.verbalogix.assistant.ui.evidence.RetrievalEvidence
import com.verbalogix.assistant.ui.evidence.RetrievalReceipt
import com.verbalogix.assistant.ui.evidence.RetrievalUiState
import com.verbalogix.assistant.ui.evidence.SourceRef
import com.verbalogix.assistant.ui.evidence.TAG_TURN_RECEIPT
import com.verbalogix.assistant.ui.evidence.TurnReceiptView
import com.verbalogix.assistant.ui.experts.ExpertDetail
import com.verbalogix.assistant.ui.experts.ExpertDetailScreen
import com.verbalogix.assistant.ui.experts.ExpertDetailUiState
import com.verbalogix.assistant.ui.experts.ExpertLifecycle
import com.verbalogix.assistant.ui.experts.ExpertSummary
import com.verbalogix.assistant.ui.experts.TAG_EXPERT_ANSWER_SUBMIT
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import org.junit.Rule
import org.junit.Test

/**
 * Every turn disposition, on the composition that ships.
 *
 * THE PROPERTY UNDER TEST IS WHERE THE WORD "GROUNDED" APPEARS. It is the one claim this
 * whole slice exists to make honest, so it must appear on exactly one path — the one with a
 * closed receipt behind it — and nowhere near a model failure, an abstention, or a refusal.
 * A screen that said "grounded" while the Foundry had refused the turn would be the precise
 * failure the receipt was built to prevent, and it would look completely normal.
 */
class ExpertDetailGroundedTurnTest {

    @get:Rule val compose = createComposeRule()

    private val packId = "kf:pack:${"a1".repeat(32)}"
    private val releaseId = "kf:pack-release:${"b2".repeat(32)}"

    private fun expert() = ExpertDetail(
        summary = ExpertSummary(
            packId = packId,
            releaseId = releaseId,
            name = "Knowledge Foundry Project Expert",
            namespace = "org.knowledge-foundry",
            slug = "project-expert",
            version = "1.0.0",
            lifecycle = ExpertLifecycle.MOUNTED,
            trustState = "trusted",
        ),
        description = "The Knowledge Foundry's own documentation.",
        profile = "project-expert",
        riskClass = "standard",
        publicationChannel = "internal",
        capabilities = emptyList(),
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

    private fun evidence() = RetrievalEvidence(
        answerability = "supported",
        disposition = "succeeded",
        reasonCode = null,
        items = listOf(
            EvidenceEntry(
                evidenceId = "kf:evidence:${"d1".repeat(32)}",
                packId = packId,
                releaseId = releaseId,
                kind = "chunk",
                text = "A pack is signed and verifiable.",
                knowledgeStatus = "supported",
                uncertainty = "none",
                sources = listOf(
                    SourceRef(
                        sourceId = "kf:source:${"d2".repeat(32)}",
                        logicalLocator = "README.md",
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
        ),
        contradictions = emptyList(),
        omissions = emptyList(),
        truncationBoundaries = emptyList(),
        receipt = RetrievalReceipt(
            packetId = "kf:evidence-packet:${"e1".repeat(32)}",
            packetSha256 = "e2".repeat(32),
            traceId = "kf:retrieval-trace:${"e3".repeat(32)}",
            deterministicCoreSha256 = "e4".repeat(32),
            planId = "kf:query-plan:${"e5".repeat(32)}",
            resultSha256 = "e6".repeat(32),
            mountRegistrySha256 = "e7".repeat(32),
        ),
    )

    private fun receipt(disposition: String, withProvider: Boolean) = TurnReceiptView(
        receiptId = "kf:assistant-turn-receipt:${"f1".repeat(32)}",
        receiptSha256 = "f2".repeat(32),
        turnId = "kf:assistant-turn:${"f3".repeat(32)}",
        requestSha256 = "f4".repeat(32),
        queryResultSha256 = "e6".repeat(32),
        packetId = "kf:evidence-packet:${"e1".repeat(32)}",
        packetSha256 = "e2".repeat(32),
        mountRegistrySha256 = "e7".repeat(32),
        providerObservationSha256 = if (withProvider) "f5".repeat(32) else null,
        modelIdentitySha256 = if (withProvider) "f6".repeat(32) else null,
        promptTemplateSha256 = if (withProvider) "f7".repeat(32) else null,
        answerSha256 = if (withProvider) "f8".repeat(32) else null,
        citedEvidenceIds = if (withProvider) listOf("kf:evidence:${"d1".repeat(32)}") else emptyList(),
        disposition = disposition,
        proofLimit = "Structural grounding and derivation closure only; not source truth, " +
            "semantic factuality, provider honesty, model quality, authentication, or " +
            "effect authority.",
    )

    /**
     * The answer text is DELIBERATELY NOT the evidence text.
     *
     * The first version reused the quotation verbatim, and both nodes then matched the same
     * assertion -- the test failed on ambiguity rather than on anything about the screen.
     * It is also the wrong fixture: a model paraphrases, so the evidence card and the answer
     * segment carry different words, and a test whose two halves are indistinguishable
     * could not tell which one it was looking at.
     */
    private fun grounded(answerability: String = "supported") = GroundedTurnUiState.Grounded(
        segments = listOf(
            AnswerSegmentView("claim", "Every pack carries a checkable signature.", listOf(1)),
            AnswerSegmentView("uncertainty", "The evidence does not say when.", emptyList()),
        ),
        modelId = "lfm-8b",
        templateId = "localmind/grounded-turn/1.0",
        answerability = answerability,
        receipt = receipt("grounded", withProvider = true),
    )

    private fun screen(
        retrieval: RetrievalUiState = RetrievalUiState.Ready(evidence()),
        turn: GroundedTurnUiState = GroundedTurnUiState.Idle,
    ) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 720.dp)),
            ) {
                LocalmindTheme(darkTheme = true) {
                    ExpertDetailScreen(
                        state = ExpertDetailUiState.Ready(expert()),
                        retrieval = retrieval,
                        turn = turn,
                    )
                }
            }
        }
    }

    // ── the action ──────────────────────────────────────────────────────────

    @Test
    fun the_action_appears_only_once_there_is_evidence_to_ground_on() {
        // "Grounded" means derived from a specific evidence packet, so before a retrieval
        // there is nothing to derive from -- and a button that could be pressed first would
        // imply otherwise.
        screen(retrieval = RetrievalUiState.Idle)
        compose.onAllNodesWithTag(TAG_EXPERT_ANSWER_SUBMIT).assertCountEquals(0)
    }

    @Test
    fun the_action_is_offered_over_ready_evidence() {
        screen()
        compose.onNodeWithTag(TAG_EXPERT_ANSWER_SUBMIT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Draft a grounded answer").assertIsDisplayed()
    }

    // ── the one path that may say "grounded" ────────────────────────────────

    @Test
    fun a_grounded_answer_shows_its_segments_with_per_segment_citations() {
        screen(turn = grounded())
        // The ANSWER's words, which differ from the evidence quotation above it.
        compose.onNodeWithText("Every pack carries a checkable signature.")
            .performScrollTo().assertIsDisplayed()
        // The citation sits ON the sentence, not in a footer. A list of sources under a
        // whole answer says "these were involved somewhere"; this says which claim rests on
        // what, which is the only form a reader can check.
        compose.onNodeWithText("cites [1]").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Grounded answer, receipt closed by Knowledge Foundry")
            .performScrollTo().assertIsDisplayed()
        // The hedge is kept and marked, not deleted for looking weak.
        compose.onNodeWithText("The evidence does not say when.").performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun a_grounded_answer_names_the_model_and_template_on_screen() {
        screen(turn = grounded())
        compose.onNodeWithText("Written by lfm-8b", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun the_receipt_is_behind_disclosure_and_states_what_it_does_not_prove() {
        screen(turn = grounded())
        compose.onAllNodesWithText("Answer SHA-256").assertCountEquals(0)
        compose.onNodeWithTag(TAG_TURN_RECEIPT).performScrollTo().performClick()
        compose.onNodeWithText("Answer SHA-256").performScrollTo().assertIsDisplayed()
        // The proof limit travels with the digests, because a column of hashes under an
        // answer reads as proof of the answer unless something says otherwise.
        compose.onNodeWithText("not source truth", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun conflicting_sources_are_stated_on_a_grounded_answer() {
        // A grounded answer over conflicting evidence is still grounded, and suppressing
        // that would be the most misleading thing this screen could do: the citations would
        // read as agreement.
        screen(turn = grounded(answerability = "conflicted"))
        compose.onNodeWithText("The cited sources disagree with one another")
            .performScrollTo().assertIsDisplayed()
    }

    // ── every path that may not ─────────────────────────────────────────────

    @Test
    fun a_provider_failure_says_nothing_was_sent_and_claims_nothing() {
        screen(
            turn = GroundedTurnUiState.ProviderFailed(
                "the model stopped with \"length\" rather than finishing.",
            ),
        )
        compose.onNodeWithText("No answer was produced").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nothing was sent to Knowledge Foundry", substring = true)
            .performScrollTo().assertIsDisplayed()
        assertNoGroundedClaim()
    }

    @Test
    fun an_abstained_turn_is_not_an_answer_and_keeps_its_receipt() {
        screen(
            turn = GroundedTurnUiState.NotGrounded(
                disposition = "abstained",
                answerability = "insufficient",
                receipt = receipt("abstained", withProvider = false),
            ),
        )
        compose.onNodeWithText("Not grounded").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("abstained", substring = true).performScrollTo().assertIsDisplayed()
        assertNoGroundedClaim()

        // The receipt is still real: it certifies that retrieval happened and that nothing
        // was grounded on it. The absent provider fields say so in words.
        compose.onNodeWithTag(TAG_TURN_RECEIPT).performScrollTo().performClick()
        compose.onAllNodesWithText("none — no provider ran for this turn")
            .assertCountEquals(5)
    }

    @Test
    fun a_refusal_is_not_an_answer() {
        screen(turn = GroundedTurnUiState.Refused("The Knowledge Foundry did not finalise this turn (evidence-drift)."))
        compose.onNodeWithText("Not finalised").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("evidence-drift", substring = true)
            .performScrollTo().assertIsDisplayed()
        assertNoGroundedClaim()
    }

    @Test
    fun the_two_waiting_states_name_who_is_working() {
        // Separate steps because they ARE separate: Localmind calls the model, then the
        // Foundry checks citation closure. One "thinking" spinner would hide the check.
        screen(turn = GroundedTurnUiState.Generating)
        compose.onNodeWithText("The model is drafting from the evidence…")
            .performScrollTo().assertIsDisplayed()
        assertNoGroundedClaim()
    }

    /** The claim must appear on exactly one path, and this is every other path. */
    private fun assertNoGroundedClaim() {
        compose.onAllNodesWithText("Grounded answer, receipt closed by Knowledge Foundry")
            .assertCountEquals(0)
        compose.onAllNodesWithText("cites [1]").assertCountEquals(0)
    }
}
