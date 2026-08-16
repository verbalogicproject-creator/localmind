package com.verbalogix.assistant.ui.evidence

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.ui.components.AmberPanel
import com.verbalogix.assistant.ui.components.EmptyNotice
import com.verbalogix.assistant.ui.components.MARK_ERROR
import com.verbalogix.assistant.ui.components.MARK_INFO
import com.verbalogix.assistant.ui.components.MARK_OK
import com.verbalogix.assistant.ui.components.StatusLine
import com.verbalogix.assistant.ui.components.minimumTouchTarget
import com.verbalogix.assistant.ui.theme.AmberTokens

const val TAG_RETRIEVAL_EVIDENCE = "retrieval-evidence"
const val TAG_RETRIEVAL_RECEIPT = "retrieval-receipt"
const val TAG_RETRIEVAL_OMISSIONS = "retrieval-omissions"

/**
 * How much of a quotation is shown before it is folded.
 *
 * Twelve lines is roughly a phone screen of monospace at the default text size — enough to
 * see what a passage is and decide whether to open it, and short enough that eight items
 * remain scrollable as a list rather than as a document. The character ceiling catches the
 * other shape of long: one enormous line, which no line count would fold.
 */
private const val PREVIEW_LINES = 12
private const val PREVIEW_CHARS = 800

/**
 * What an expert knows about a question — and nothing about an answer.
 *
 * THIS SURFACE DELIBERATELY PRODUCES NO ANSWER. `canonical-assistant-turn` is
 * planned-not-implemented, so no artifact could link a model's reply to this evidence.
 * Showing the evidence and stopping is the honest half of the feature; the other half
 * waits for a contract that can attest it.
 *
 * The Harness's [RetrievalEvidence.answerability] is rendered as the Harness's word, in a
 * line that says so. Nothing here is scored, summarised or counted into a verdict.
 */
@Composable
fun RetrievalEvidenceView(
    state: RetrievalUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_RETRIEVAL_EVIDENCE),
        verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
    ) {
        when (state) {
            RetrievalUiState.Idle -> Unit

            RetrievalUiState.Querying ->
                StatusLine(mark = MARK_INFO, label = "Retrieving evidence…")

            // Reached only if something submitted anyway; the screen does not offer the
            // field for an inactive release.
            RetrievalUiState.InactiveRelease -> EmptyNotice(
                title = "Not active",
                body = "Only the active release can be searched, because retrieval runs " +
                    "against what is mounted.",
            )

            is RetrievalUiState.SessionExpired -> EmptyNotice(
                title = "Session ended",
                // The one state on this surface with a remedy the user can perform, so it
                // names the remedy. The cause is included when the Harness gave one and
                // omitted rather than guessed when it did not.
                body = "Pair with Knowledge Foundry again to search this expert." +
                    (state.cause?.let { " (${it.name.lowercase().replace('_', ' ')})" } ?: ""),
            )

            is RetrievalUiState.Incompatible -> EmptyNotice(
                title = "Version mismatch",
                body = state.detail,
            )

            is RetrievalUiState.Uncorrelated -> EmptyNotice(
                title = "Reply did not match this expert",
                // NEVER SHOWN AS EVIDENCE. A well-formed document about a different pack
                // is the failure that does not look like one, so it is named rather than
                // rendered.
                body = state.detail + " Nothing from it was shown.",
            )

            is RetrievalUiState.Unavailable -> AmberPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(AmberTokens.panelPadding),
                    verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
                ) {
                    StatusLine(mark = MARK_INFO, label = "Unavailable")
                    Text(
                        state.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Requires: ${state.requiredCapability}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // The Harness declining is the Harness working. Its own words, its own code.
            is RetrievalUiState.Declined -> EmptyNotice(
                title = "No evidence returned",
                body = "Knowledge Foundry reported \"${state.disposition}\"" +
                    (state.reasonCode?.let { ": $it" } ?: "") + ".",
            )

            is RetrievalUiState.Refused -> EmptyNotice(
                title = "Reply not accepted",
                body = state.detail,
            )

            is RetrievalUiState.Ready -> ReadyEvidence(state.evidence)
        }
    }
}

@Composable
private fun ReadyEvidence(evidence: RetrievalEvidence) {
    AnswerabilityLine(evidence.answerability)

    // NOT AN ANSWER, said once and plainly. Without it a reader can mistake a quoted
    // excerpt for the app's reply, which is the precise confusion this design avoids.
    Text(
        "Evidence retrieved from mounted experts. Localmind has not written an answer " +
            "from it — these are quotations from the sources.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (evidence.truncationBoundaries.isNotEmpty()) {
        // "What fit" versus "what exists" is a difference the user cannot otherwise see.
        StatusLine(
            mark = MARK_INFO,
            label = "Limited by ${evidence.truncationBoundaries.joinToString(", ")}",
        )
    }

    for (entry in evidence.items) EvidenceCard(entry)

    for (contradiction in evidence.contradictions) ContradictionCard(contradiction)

    // AFTER the evidence, not before it. These used to render above the items, one raw
    // `kf:candidate:` identity per status line -- ten or more three-line blocks of hex
    // between the user and the first thing they asked for. What was left out is real and
    // worth stating, and it is still secondary to what came back.
    OmittedCandidates(evidence.omissions)

    ReceiptPanel(evidence.receipt)
}

/**
 * What matched and did not fit.
 *
 * ON THIS DEPLOYMENT THIS *IS* THE TRUNCATION NOTICE. The Foundry emits
 * `truncation.boundaries` as an empty list and carries the real signal in `omissions`
 * instead — a candidate identity per dropped row, capped at 64 — so the "Limited by …"
 * line above never fires here while eight of eighteen matches quietly become the answer.
 * Saying how many were dropped is therefore not a detail; it is the difference between
 * "this is what there is" and "this is what fit".
 *
 * THE IDENTITIES STAY, BEHIND A DISCLOSURE. They are the only form the Foundry gives —
 * no titles, no locators, nothing to read — so listing them openly costs a wall of hex and
 * tells a person nothing. Folding them away is not hiding them: they are exactly what
 * someone cross-checking against a Studio record needs, and they are one tap from view.
 */
@Composable
private fun OmittedCandidates(omissions: List<String>) {
    if (omissions.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    StatusLine(
        mark = MARK_INFO,
        label = "${omissions.size} further candidate(s) matched and were not included",
    )
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .minimumTouchTarget()
            .testTag(TAG_RETRIEVAL_OMISSIONS),
    ) {
        Text(if (expanded) "Hide omitted candidates" else "Show omitted candidates")
    }
    if (!expanded) return

    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            Text(
                // The Foundry's own reasons, named rather than guessed at from the count.
                "Identities only. A candidate is omitted when it exceeds the per-item size " +
                    "limit, when the packet's byte budget is full, or when the evidence-item " +
                    "cap is reached.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (omission in omissions) {
                Text(
                    omission,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The Harness's verdict, attributed to the Harness.
 *
 * Attribution is the load-bearing part. "Supported" alone reads as the app's assessment;
 * naming who said it keeps the claim where it belongs, and makes "conflicted" legible as
 * a finding rather than an error.
 */
@Composable
private fun AnswerabilityLine(answerability: String) {
    val (mark, tint) = when (answerability) {
        "supported" -> MARK_OK to MaterialTheme.colorScheme.primary
        "conflicted", "insufficient" -> MARK_INFO to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MARK_ERROR to MaterialTheme.colorScheme.error
    }
    StatusLine(
        mark = mark,
        label = "Knowledge Foundry assessed this evidence as \"$answerability\"",
        tint = tint,
    )
}

@Composable
private fun EvidenceCard(entry: EvidenceEntry) {
    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    entry.kind,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // Per-item status, from the contract's own enum.
                    entry.knowledgeStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (entry.text.isBlank()) {
                // AN ABSENCE, STATED. The Foundry can return an item it genuinely reached
                // -- through the graph channel, typically -- whose `selected_text` is
                // empty: the record ranked, its provenance is real, and there is nothing
                // to quote from it. The golden's second item is exactly this, so this is
                // an observed case rather than a defensive one.
                //
                // Rendering it through the quotation block leaves an empty monospace gap
                // between a locator and a rank line, which reads as a rendering failure
                // and invites the user to distrust the rest of the card. Saying so costs
                // one line and is the difference between "nothing was returned" and
                // "something is broken".
                //
                // THE CARD STAYS. Dropping the item would hide something the retrieval
                // found; its locator, ranks and graph path are still evidence of what the
                // expert holds, just not a passage to read. Italic and not monospace, so
                // it cannot be mistaken for a quotation of an empty string.
                Text(
                    "No quoted text was included for this item.",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                Quotation(entry)
            }

            for (source in entry.sources) {
                Text(
                    "${source.logicalLocator} · ${source.sensitivity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (entry.uncertainty != "none") {
                StatusLine(mark = MARK_INFO, label = "uncertainty ${entry.uncertainty}")
            }

            if (entry.graphPathIds.isNotEmpty()) {
                // IDS, LABELLED AS IDS. The contract carries graph_path_ids and no path
                // narration, so a rendered "A → B → C" would be invented. A linear list
                // of real identities is worth more than a diagram of made-up ones.
                Text(
                    "graph paths: ${entry.graphPathIds.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (id in entry.graphPathIds) {
                    Text(
                        id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                buildString {
                    append("rank ${entry.packFusedRank} in pack, ${entry.globalFusedRank} overall")
                    // Null means the channel did not surface it -- not rank zero.
                    entry.lexicalRank?.let { append(" · lexical $it") }
                    entry.graphRank?.let { append(" · graph $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A QUOTATION, folded when it is long — never shortened.
 *
 * The Foundry's per-item ceiling is 8 000 bytes, so one evidence item can legitimately be
 * an entire source file. Eight of those rendered in full is several hundred screens of
 * monospace, and the receipt, the omissions and the next item are all somewhere past it.
 * The feature stops being usable at exactly the point where retrieval works well.
 *
 * FOLDING IS NOT SUMMARISING, and the distinction is the whole design of this surface.
 * Nothing is condensed, paraphrased, elided from the middle, or characterised: the fold
 * takes the first [PREVIEW_LINES] lines, states the real total, and puts the rest one tap
 * away. What a summary would do — decide which parts matter — is precisely what is never
 * done here.
 *
 * It is also kept distinct from the Foundry's own truncation. "Limited by …" and the
 * omitted-candidate count mean the SERVER left material out; this fold is a display choice
 * this client made and can undo, so it says "first N of M lines" rather than anything that
 * sounds like a limit.
 */
@Composable
private fun Quotation(entry: EvidenceEntry) {
    val lines = entry.text.lines()
    val long = lines.size > PREVIEW_LINES || entry.text.length > PREVIEW_CHARS
    // Keyed by identity so opening one item's quotation does not open the next one's, and
    // so the state follows the item rather than its position in the list.
    var expanded by rememberSaveable(entry.evidenceId) { mutableStateOf(false) }

    val shown = when {
        !long || expanded -> entry.text
        else -> lines.take(PREVIEW_LINES).joinToString("\n").take(PREVIEW_CHARS)
    }

    // Monospace and offset so it reads as material from elsewhere rather than as the app
    // speaking. It is never parsed, never interpreted, and never given to anything that
    // could act on it.
    Text(
        shown,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 8.dp),
    )

    if (!long) return

    if (!expanded) {
        Text(
            "first $PREVIEW_LINES of ${lines.size} lines",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.minimumTouchTarget(),
    ) {
        Text(
            if (expanded) {
                "Show less of this quotation"
            } else {
                "Show the whole quotation (${lines.size} lines)"
            },
        )
    }
}

@Composable
private fun ContradictionCard(contradiction: ContradictionView) {
    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            StatusLine(
                mark = MARK_ERROR,
                label = "Sources disagree" +
                    (contradiction.disposition?.let { " · $it" } ?: ""),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            if (contradiction.members.isEmpty()) {
                // The bare-identity arm of the oneOf. "Detail not included" is a
                // different statement from "no members", and saying the wrong one would
                // understate a disagreement.
                Text(
                    "The Foundry reported this disagreement by identity only; its members " +
                        "were not included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (member in contradiction.members) {
                    Text(
                        member.packId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The receipt, behind progressive disclosure and copyable in full.
 *
 * It certifies WHAT WAS RETRIEVED. It says nothing about any answer, and the heading says
 * so, because a row of digests under a chat reply would otherwise read as proof of the
 * reply.
 */
@Composable
private fun ReceiptPanel(receipt: RetrievalReceipt) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .minimumTouchTarget()
            .testTag(TAG_RETRIEVAL_RECEIPT),
    ) {
        Text(if (expanded) "Hide retrieval receipt" else "Show retrieval receipt")
    }
    if (!expanded) return

    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            Text(
                "Retrieval receipt",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Identifies what was retrieved and how. It does not attest to any answer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CopyableValue("Packet", receipt.packetId)
            CopyableValue("Packet SHA-256", receipt.packetSha256)
            CopyableValue("Trace", receipt.traceId)
            CopyableValue("Deterministic core SHA-256", receipt.deterministicCoreSha256)
            CopyableValue("Plan", receipt.planId)
            CopyableValue("Result SHA-256", receipt.resultSha256)
            CopyableValue("Mount registry SHA-256", receipt.mountRegistrySha256)
        }
    }
}

/** Full length, never abbreviated: this is what gets compared against a Studio record. */
@Composable
private fun CopyableValue(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .minimumTouchTarget()
            .clickable { clipboard.setText(AnnotatedString(value)) }
            .semantics { contentDescription = "Copy $label" },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
