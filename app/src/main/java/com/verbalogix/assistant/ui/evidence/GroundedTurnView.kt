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

const val TAG_GROUNDED_ANSWER = "grounded-answer"
const val TAG_TURN_RECEIPT = "turn-receipt"

/**
 * An answer, shown as grounded only when a receipt says so.
 *
 * THE WORD "GROUNDED" APPEARS ON EXACTLY ONE PATH THROUGH THIS FILE, and it is the path
 * that has a closed receipt behind it. Everything else — the model failing, the Foundry
 * abstaining, a refusal — renders as itself, in wording that could not be mistaken for a
 * reply.
 *
 * EVERY SEGMENT CARRIES ITS OWN CITATIONS, next to the text rather than gathered into a
 * footer. A list of sources under a whole answer says "these were involved somewhere"; a
 * citation on the sentence says which claim rests on what, which is the only form a reader
 * can actually check. Uncertainty segments carry none, and are shown as the hedges they are
 * rather than being quietly deleted for looking weak.
 */
@Composable
fun GroundedTurnView(
    state: GroundedTurnUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_GROUNDED_ANSWER),
        verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
    ) {
        when (state) {
            GroundedTurnUiState.Idle -> Unit

            GroundedTurnUiState.Generating ->
                StatusLine(mark = MARK_INFO, label = "The model is drafting from the evidence…")

            GroundedTurnUiState.Finalizing ->
                // Named as a separate step because it IS one: the Foundry re-runs the
                // retrieval and checks citation closure before anything may be called
                // grounded. A single "thinking" spinner would hide the check that matters.
                StatusLine(mark = MARK_INFO, label = "Knowledge Foundry is checking the answer…")

            is GroundedTurnUiState.ProviderFailed -> EmptyNotice(
                title = "No answer was produced",
                body = state.reason + " Nothing was sent to Knowledge Foundry.",
            )

            is GroundedTurnUiState.Refused -> EmptyNotice(
                title = "Not finalised",
                body = state.detail,
            )

            is GroundedTurnUiState.NotGrounded -> NotGroundedPanel(state)

            is GroundedTurnUiState.Grounded -> GroundedPanel(state)
        }
    }
}

/**
 * The Foundry declined to bind a turn, and no model was ever called.
 *
 * Distinct from a model failure and worded so: the expert did not hold enough to answer
 * from, which is a fact about the packs rather than about the model or the phrasing. The
 * receipt is still offered, because it certifies exactly that.
 */
@Composable
private fun NotGroundedPanel(state: GroundedTurnUiState.NotGrounded) {
    EmptyNotice(
        title = "Not grounded",
        body = "Knowledge Foundry reported \"${state.disposition}\" for evidence it rated " +
            "\"${state.answerability}\", so no answer was generated from it.",
    )
    TurnReceiptPanel(state.receipt)
}

@Composable
private fun GroundedPanel(state: GroundedTurnUiState.Grounded) {
    StatusLine(
        mark = MARK_OK,
        label = "Grounded answer, receipt closed by Knowledge Foundry",
        tint = MaterialTheme.colorScheme.primary,
    )
    if (state.answerability == "conflicted") {
        // A grounded answer over conflicting sources is still grounded, and the reader must
        // be told. Suppressing this would be the single most misleading thing this screen
        // could do: the citations would look like agreement.
        StatusLine(
            mark = MARK_ERROR,
            label = "The cited sources disagree with one another",
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }

    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            for (segment in state.segments) SegmentRow(segment)
        }
    }

    Text(
        // The model is named on screen, not only in the receipt. Which model wrote this is
        // part of reading it, and a digest in a collapsed panel is not on screen.
        "Written by ${state.modelId} from the evidence above, using ${state.templateId}.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TurnReceiptPanel(state.receipt)
}

@Composable
private fun SegmentRow(segment: AnswerSegmentView) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            segment.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            // Hedges are set apart in italic rather than removed. A model saying "the
            // evidence does not cover this" is the model working, and deleting it would
            // leave an answer that reads more confident than the one that was produced.
            fontStyle = if (segment.kind == "uncertainty") FontStyle.Italic else null,
        )
        if (segment.citations.isEmpty()) {
            if (segment.kind == "uncertainty") {
                Text(
                    "no citation — this sentence is not a claim from the evidence",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "cites ${segment.citations.joinToString(", ") { "[$it]" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The turn receipt, behind progressive disclosure and copyable in full.
 *
 * WHAT IT PROVES IS PRINTED WITH IT. The Foundry's own `proof_limit` says structural
 * grounding and derivation closure only — not source truth, not factuality, not provider
 * honesty, not model quality — and it is shown rather than summarised, because a column of
 * digests under an answer reads as proof of the answer unless something says otherwise.
 */
@Composable
private fun TurnReceiptPanel(receipt: TurnReceiptView) {
    var expanded by rememberSaveable(receipt.receiptId) { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .minimumTouchTarget()
            .testTag(TAG_TURN_RECEIPT),
    ) {
        Text(if (expanded) "Hide answer receipt" else "Show answer receipt")
    }
    if (!expanded) return

    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            Text(
                "Answer receipt",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                receipt.proofLimit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReceiptField("Disposition", receipt.disposition)
            ReceiptField("Turn", receipt.turnId)
            ReceiptField("Receipt", receipt.receiptId)
            ReceiptField("Receipt SHA-256", receipt.receiptSha256)
            ReceiptField("Request SHA-256", receipt.requestSha256)
            ReceiptField("Retrieval result SHA-256", receipt.queryResultSha256)
            ReceiptField("Evidence packet", receipt.packetId)
            ReceiptField("Packet SHA-256", receipt.packetSha256)
            ReceiptField("Mount registry SHA-256", receipt.mountRegistrySha256)
            // Null on an abstained or refused turn, where no provider ran at all. Shown as
            // an explicit absence rather than an empty row, so the reason is legible.
            ReceiptField("Provider observation SHA-256", receipt.providerObservationSha256)
            ReceiptField("Model identity SHA-256", receipt.modelIdentitySha256)
            ReceiptField("Prompt template SHA-256", receipt.promptTemplateSha256)
            ReceiptField("Answer SHA-256", receipt.answerSha256)
            if (receipt.citedEvidenceIds.isEmpty()) {
                ReceiptField("Cited evidence", null)
            } else {
                for (id in receipt.citedEvidenceIds) ReceiptField("Cited evidence", id)
            }
        }
    }
}

/** Full length, never abbreviated: this is what gets compared against a Foundry record. */
@Composable
private fun ReceiptField(label: String, value: String?) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (value == null) {
                    Modifier
                } else {
                    Modifier
                        .minimumTouchTarget()
                        .clickable { clipboard.setText(AnnotatedString(value)) }
                        .semantics { contentDescription = "Copy $label" }
                },
            ),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            value ?: "none — no provider ran for this turn",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontStyle = if (value == null) FontStyle.Italic else null,
        )
    }
}
