package com.verbalogix.assistant.ui.evidence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.data.Citation
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.components.AmberPanel
import com.verbalogix.assistant.ui.components.EmptyNotice
import com.verbalogix.assistant.ui.components.MARK_ERROR
import com.verbalogix.assistant.ui.components.MARK_INFO
import com.verbalogix.assistant.ui.components.MARK_OK
import com.verbalogix.assistant.ui.components.StatusLine
import com.verbalogix.assistant.ui.components.UnavailableNotice
import com.verbalogix.assistant.ui.components.minimumTouchTarget
import com.verbalogix.assistant.ui.theme.AmberTokens
import com.verbalogix.assistant.ui.theme.LocalmindTheme

/** Lets the suite address the close affordance without depending on its label. */
const val TAG_EVIDENCE_CLOSE = "evidence-close"
const val TAG_EVIDENCE_ROOT = "evidence-root"

/**
 * Where an answer came from -- or the visible fact that it came from nowhere.
 *
 * Everything here is READ BACK FROM STORAGE. No retrieval runs, nothing is re-scored,
 * and no Harness is consulted, because `query.retrieve` is not a capability this client
 * has. That constraint is also what makes the drawer trustworthy: it shows the evidence
 * as it was when the answer was given, not a fresh search that might now agree by
 * accident.
 *
 * WHAT IS DELIBERATELY ABSENT. The approved screenshot shows tabs for graph paths,
 * contradictions and retrieval telemetry. None of those has a contract, a field in
 * storage, or a source -- so rather than render empty tabs that imply the data exists
 * and failed to load, the surface states once that deeper inspection is unavailable and
 * names the capability that would provide it.
 */
@Composable
fun EvidenceDrawer(
    state: EvidenceUiState,
    reQueryCapability: CapabilityState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Focus moves to the drawer when it opens, and the caller restores it on close.
    // Without this a screen reader stays parked on the transcript behind the overlay,
    // announcing nothing, and the user has no idea anything happened.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_EVIDENCE_ROOT),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AmberTokens.mobileMargin,
                        vertical = AmberTokens.baseUnit,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Evidence",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .semantics { heading() },
                )
                TextButton(
                    onClick = onClose,
                    modifier = Modifier
                        .minimumTouchTarget()
                        .testTag(TAG_EVIDENCE_CLOSE)
                        .semantics { contentDescription = "Close evidence" },
                ) { Text("Close") }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(AmberTokens.mobileMargin),
                verticalArrangement = Arrangement.spacedBy(AmberTokens.mobileMargin),
            ) {
                when (state) {
                    EvidenceUiState.Loading -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                    }

                    EvidenceUiState.MessageNotFound -> EmptyNotice(
                        title = "That message is no longer here",
                        body = "It was cleared from the transcript, so the evidence stored " +
                            "with it went with it.",
                    )

                    EvidenceUiState.NoRetrieval -> Column(
                        verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
                    ) {
                        StatusLine(
                            mark = MARK_INFO,
                            label = "No retrieval ran",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "This answer came from a model with no retrieval attached, so " +
                                "there is no evidence to show and none was claimed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    EvidenceUiState.Abstained -> Column(
                        verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
                    ) {
                        StatusLine(
                            mark = MARK_ERROR,
                            label = "Not grounded",
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Retrieval ran and found no supporting passage. Treat this " +
                                "answer as unsupported.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    // Loud on purpose. Grounded-with-nothing-cited is a broken promise
                    // from whatever answered, and rendering it as an empty list would
                    // read as "no evidence to display" -- the opposite of the truth.
                    EvidenceUiState.ReceiptMissing -> Column(
                        verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
                    ) {
                        StatusLine(
                            mark = MARK_ERROR,
                            label = "Receipt missing",
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "This answer was reported as grounded but arrived with no " +
                                "citations. That is a contract violation by the server that " +
                                "answered, not a display problem — the answer should not be " +
                                "treated as supported.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    is EvidenceUiState.Grounded -> Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
                    ) {
                        StatusLine(
                            mark = MARK_OK,
                            label = "Grounded · ${state.citations.size} cited",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
                        ) {
                            items(state.citations) { CitationCard(it) }
                        }
                    }
                }

                if (reQueryCapability is CapabilityState.Unavailable) {
                    UnavailableNotice(reQueryCapability)
                }
            }
        }
    }
}

/**
 * One citation, showing exactly the fields that exist and no others.
 *
 * Every optional field is rendered ONLY when present. A `page` of null is not "p0" and
 * a missing score is not "0.00" -- inventing a value to fill a row is how a display
 * starts asserting things the data never said.
 */
@Composable
private fun CitationCard(citation: Citation) {
    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.mobileMargin),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit)) {
                Text(
                    "[${citation.n}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Source first, quote second: the reader's question is "can I check
                // this?", and the answer is the document and the page.
                Text(
                    buildString {
                        append(citation.document.ifBlank { "untitled source" })
                        citation.page?.let { append("  p$it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (citation.quote.isNotBlank()) {
                Text(
                    "“${citation.quote}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            citation.score?.let {
                Text(
                    "score ${"%.3f".format(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The ids are shown when present because they are what makes a citation
            // checkable against the Foundry side. They are NOT parsed or interpreted
            // here -- they are opaque strings this app carries and displays.
            val ids = listOfNotNull(
                citation.documentId.takeIf { it.isNotBlank() }?.let { "document $it" },
                citation.chunkId.takeIf { it.isNotBlank() }?.let { "chunk $it" },
            )
            if (ids.isNotEmpty()) {
                Text(
                    ids.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun EvidenceReceiptMissingPreview() {
    LocalmindTheme(darkTheme = true) {
        EvidenceDrawer(
            state = EvidenceUiState.ReceiptMissing,
            reQueryCapability =
                com.verbalogix.assistant.data.capability.Capabilities.NONE.evidenceQuery,
            onClose = {},
        )
    }
}
