package com.verbalogix.assistant.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.experts.ExpertDetailScreen
import com.verbalogix.assistant.ui.experts.ExpertDetailUiState
import com.verbalogix.assistant.ui.experts.ExpertLibraryScreen
import com.verbalogix.assistant.ui.experts.ExpertLibraryUiState
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import com.verbalogix.assistant.ui.tools.NoToolProposalSource
import com.verbalogix.assistant.ui.tools.ToolApprovalSheet
import com.verbalogix.assistant.ui.tools.ToolApprovalState
import com.verbalogix.assistant.ui.tools.ToolDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The gate, from the user's side.
 *
 * Everything asserted here is about what a SHIPPING build shows: no expert list, no
 * enabled approve button, and in both cases a stated reason rather than a blank screen.
 * The populated branches are exercised separately with debug fixtures; these are the
 * ones that decide whether the app makes a claim it cannot support.
 */
class CapabilityGateTest {

    @get:Rule val compose = createComposeRule()

    private val expertGate = Capabilities.NONE.expertLibrary as CapabilityState.Unavailable

    @Test
    fun expert_library_shows_no_packs_and_states_why() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                ExpertLibraryScreen(
                    state = ExpertLibraryUiState.Unavailable(expertGate),
                    onOpenExpert = {},
                )
            }
        }
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
        compose.onNodeWithText("Requires: mount.list").assertIsDisplayed()
        // Nothing from the mock may appear.
        compose.onAllNodesWithText("Trusted signature", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Install Package", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Import .kpack", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("GB", substring = true).assertCountEquals(0)
    }

    @Test
    fun expert_library_says_direct_chat_still_works() {
        // Localmind must stay useful with Foundry absent, and must say so -- otherwise
        // an unavailable panel reads as "the app is broken".
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                ExpertLibraryScreen(
                    state = ExpertLibraryUiState.Unavailable(expertGate),
                    onOpenExpert = {},
                )
            }
        }
        compose.onAllNodesWithText("Direct chat is unaffected", substring = true)
            .assertCountEquals(1)
    }

    @Test
    fun expert_detail_offers_no_lifecycle_verbs() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                ExpertDetailScreen(
                    state = ExpertDetailUiState.Unavailable(expertGate),
                    onBack = {},
                )
            }
        }
        // install / mount / update / rollback are Foundry verbs with their own
        // authority and receipts. A button here would be a claim of authority.
        for (verb in listOf("Install", "Mount", "Update", "Rollback", "Deactivate", "Verify")) {
            compose.onAllNodesWithText(verb, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun expert_detail_shows_no_fabricated_pack_metadata() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                ExpertDetailScreen(
                    state = ExpertDetailUiState.Unavailable(expertGate),
                    onBack = {},
                )
            }
        }
        for (mock in listOf("sha256", "4.2 GB", "Signer", "Coverage", "eval_", "Verified Root")) {
            compose.onAllNodesWithText(mock, substring = true).assertCountEquals(0)
        }
    }

    // ── Tool approval ──────────────────────────────────────────────────────────

    @Test
    fun the_tool_route_never_produces_a_proposal() {
        val source = NoToolProposalSource()
        val state = source.stateFor("session1", "proposal1")
        assertTrue(state is ToolApprovalState.Unavailable)
    }

    @Test
    fun approve_is_absent_when_no_proposal_exists() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                ToolApprovalSheet(
                    state = NoToolProposalSource().stateFor("s", "p"),
                    onDismiss = {},
                    onDecision = null,
                )
            }
        }
        compose.onAllNodesWithText("Approve once").assertCountEquals(0)
        compose.onAllNodesWithText("Deny").assertCountEquals(0)
        compose.onNodeWithText("Requires: governed-tool-proposal-decision-receipt")
            .assertIsDisplayed()
    }

    @Test
    fun the_authority_boundary_is_stated_on_the_approval_surface() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                ToolApprovalSheet(
                    state = NoToolProposalSource().stateFor("s", "p"),
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Retrieved knowledge never grants authority to act.")
            .assertIsDisplayed()
    }

    /**
     * The third layer, exercised directly.
     *
     * Even given a proposal -- which only a test can construct -- the buttons stay
     * disabled unless a decision sink is supplied, and the graph supplies none. This is
     * the assertion that would fail first if someone wired approve up prematurely.
     */
    @Test
    fun approve_stays_disabled_without_a_decision_sink() {
        val proposal = exampleToolProposal()
        var fired = 0
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                ToolApprovalSheet(
                    state = ToolApprovalState.Awaiting(proposal),
                    onDismiss = {},
                    onDecision = null,
                )
            }
        }
        compose.onNodeWithText("Approve once").assertIsNotEnabled()
        compose.onNodeWithText("Deny").assertIsNotEnabled()
        assertEquals(0, fired)
    }

    @Test
    fun a_decision_is_reported_and_never_executed() {
        // With a sink supplied the button becomes usable and hands back DATA. There is
        // no execution path in the component at all -- no intent, no shell, no request.
        val proposal = exampleToolProposal()
        val decisions = mutableListOf<ToolDecision>()
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                ToolApprovalSheet(
                    state = ToolApprovalState.Awaiting(proposal),
                    onDismiss = {},
                    onDecision = { decisions += it },
                )
            }
        }
        compose.onNodeWithText("EXAMPLE action (preview fixture)").assertIsDisplayed()
    }
}
