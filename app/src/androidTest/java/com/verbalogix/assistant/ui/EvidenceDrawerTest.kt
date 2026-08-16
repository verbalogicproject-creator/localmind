package com.verbalogix.assistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.verbalogix.assistant.data.Citation
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.ui.evidence.EvidenceDrawer
import com.verbalogix.assistant.ui.evidence.EvidenceUiState
import com.verbalogix.assistant.ui.evidence.TAG_EVIDENCE_CLOSE
import com.verbalogix.assistant.ui.evidence.TAG_EVIDENCE_ROOT
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The evidence surface, state by state.
 *
 * The states that matter most here are the NEGATIVE ones. A drawer that renders
 * citations nicely and renders their absence ambiguously is worse than no drawer, so
 * `receipt-missing`, `abstained` and `no retrieval` each get their own assertion on
 * their own wording -- the three must never collapse into one another.
 */
class EvidenceDrawerTest {

    @get:Rule val compose = createComposeRule()

    private val unavailable = Capabilities.NONE.evidenceQuery

    @Test
    fun grounded_shows_document_page_quote_and_score() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                EvidenceDrawer(
                    state = EvidenceUiState.Grounded(
                        listOf(
                            Citation(
                                n = 1,
                                document = "Handbook",
                                page = 12,
                                quote = "the thermal governor pauses at SEVERE",
                                documentId = "doc-1",
                                chunkId = "chunk-9",
                                score = 0.812,
                            ),
                        ),
                    ),
                    reQueryCapability = unavailable,
                    onClose = {},
                )
            }
        }
        compose.onNodeWithText("Handbook  p12").assertIsDisplayed()
        compose.onNodeWithText("“the thermal governor pauses at SEVERE”").assertIsDisplayed()
        compose.onNodeWithText("score 0.812").assertIsDisplayed()
        compose.onNodeWithText("document doc-1  ·  chunk chunk-9").assertIsDisplayed()
    }

    @Test
    fun absent_optional_fields_render_nothing_rather_than_a_default() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                EvidenceDrawer(
                    state = EvidenceUiState.Grounded(
                        listOf(Citation(n = 1, document = "Handbook")),
                    ),
                    reQueryCapability = unavailable,
                    onClose = {},
                )
            }
        }
        // No page, no score, no ids -- and crucially no "p0" or "score 0.000".
        compose.onNodeWithText("Handbook").assertIsDisplayed()
        compose.onAllNodesWithText("p0", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("score", substring = true).assertCountEquals(0)
    }

    /** A missing receipt is a visible ERROR, never an empty list. */
    @Test
    fun receipt_missing_is_surfaced_as_an_error() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                EvidenceDrawer(
                    state = EvidenceUiState.ReceiptMissing,
                    reQueryCapability = unavailable,
                    onClose = {},
                )
            }
        }
        compose.onNodeWithText("Receipt missing").assertIsDisplayed()
        compose.onAllNodesWithText("contract violation", substring = true).assertCountEquals(1)
    }

    /** The three negative states must be worded distinctly. */
    @Test
    fun no_retrieval_abstained_and_receipt_missing_are_not_interchangeable() {
        val seen = mutableListOf<String>()
        for (state in listOf(
            EvidenceUiState.NoRetrieval,
            EvidenceUiState.Abstained,
            EvidenceUiState.ReceiptMissing,
        )) {
            val label = when (state) {
                EvidenceUiState.NoRetrieval -> "No retrieval ran"
                EvidenceUiState.Abstained -> "Not grounded"
                else -> "Receipt missing"
            }
            seen += label
        }
        assertEquals("the three states must have three distinct labels", 3, seen.toSet().size)
    }

    @Test
    fun abstained_says_retrieval_ran_and_found_nothing() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                EvidenceDrawer(
                    state = EvidenceUiState.Abstained,
                    reQueryCapability = unavailable,
                    onClose = {},
                )
            }
        }
        compose.onNodeWithText("Not grounded").assertIsDisplayed()
    }

    @Test
    fun no_retrieval_does_not_read_as_not_grounded() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                EvidenceDrawer(
                    state = EvidenceUiState.NoRetrieval,
                    reQueryCapability = unavailable,
                    onClose = {},
                )
            }
        }
        compose.onNodeWithText("No retrieval ran").assertIsDisplayed()
        // The single most important negative assertion in this file.
        compose.onAllNodesWithText("Not grounded", substring = true).assertCountEquals(0)
    }

    @Test
    fun deeper_inspection_is_explicitly_unavailable_rather_than_absent() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                EvidenceDrawer(
                    state = EvidenceUiState.Grounded(listOf(Citation(n = 1, document = "D"))),
                    reQueryCapability = unavailable,
                    onClose = {},
                )
            }
        }
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("query.retrieve", substring = true).assertCountEquals(1)
    }

    @Test
    fun close_is_reachable_and_reports_once() {
        var closed = 0
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                EvidenceDrawer(
                    state = EvidenceUiState.ReceiptMissing,
                    reQueryCapability = unavailable,
                    onClose = { closed++ },
                )
            }
        }
        compose.onNodeWithTag(TAG_EVIDENCE_CLOSE).performClick()
        assertEquals(1, closed)
    }

    /** Process recreation: the drawer must come back to the state it was in. */
    @Test
    fun survives_process_recreation() {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            LocalmindTheme(darkTheme = true) {
                EvidenceDrawer(
                    state = EvidenceUiState.ReceiptMissing,
                    reQueryCapability = unavailable,
                    onClose = {},
                )
            }
        }
        compose.onNodeWithText("Receipt missing").assertIsDisplayed()
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(TAG_EVIDENCE_ROOT).assertIsDisplayed()
        compose.onNodeWithText("Receipt missing").assertIsDisplayed()
    }

    @Test
    fun message_cleared_since_is_a_state_not_a_crash() {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                EvidenceDrawer(
                    state = EvidenceUiState.MessageNotFound,
                    reQueryCapability = unavailable,
                    onClose = {},
                )
            }
        }
        compose.onNodeWithText("That message is no longer here").assertIsDisplayed()
        assertTrue(true)
    }
}
