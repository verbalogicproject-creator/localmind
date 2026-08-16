package com.verbalogix.assistant.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.components.AmberPanel
import com.verbalogix.assistant.ui.components.MARK_INFO
import com.verbalogix.assistant.ui.components.StatusLine
import com.verbalogix.assistant.ui.components.UnavailableNotice
import com.verbalogix.assistant.ui.components.minimumTouchTarget
import com.verbalogix.assistant.ui.theme.AmberTokens
import com.verbalogix.assistant.ui.theme.LocalmindTheme

/**
 * The approval surface for a governed tool action.
 *
 * BUILT, AND DELIBERATELY INERT.
 *
 * The component exists so the contract it needs is written down in code rather than
 * described in a document, and so the day a Harness declares one there is a reviewed
 * surface to attach it to. What it does not have -- anywhere, in any branch -- is a
 * line that performs an action. No `Intent`, no `startActivity`, no shell, no
 * accessibility automation, no HTTP call. [onDecision] hands a [ToolDecision] back to
 * its caller and that is the entire effect; the executing half belongs to the Harness,
 * behind a receipt.
 *
 * WHY APPROVE CANNOT FIRE TODAY, in three independent layers, because one would be a
 * claim and three are a structure:
 *
 *   1. the route resolves through [NoToolProposalSource], which returns
 *      [ToolApprovalState.Unavailable] for every input;
 *   2. the decision buttons are only composed in the [ToolApprovalState.Awaiting]
 *      branch, which no production code can construct -- [ToolProposal] has an
 *      `internal` constructor and no production factory;
 *   3. even in that branch the buttons are disabled unless a non-null [onDecision] is
 *      supplied, and the navigation graph passes null.
 *
 * Remove any one and the other two still hold.
 */
@Composable
fun ToolApprovalSheet(
    state: ToolApprovalState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onDecision: ((ToolDecision) -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // Rounded at the top only. A sheet is anchored to the bottom edge, and
            // rounding the corners it is flush against reads as a floating card that
            // has been cropped.
            shape = RoundedCornerShape(
                topStart = AmberTokens.radiusLarge,
                topEnd = AmberTokens.radiusLarge,
                bottomStart = 0.dp,
                bottomEnd = 0.dp,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(AmberTokens.panelPadding)
                    // Safe area and IME are handled here rather than by the caller: a
                    // bottom-anchored sheet is exactly the thing that ends up under the
                    // gesture bar, and it is invisible on the emulator most reviews use.
                    .navigationBarsPadding()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(AmberTokens.mobileMargin),
            ) {
                Text(
                    text = "Action approval",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                when (state) {
                    is ToolApprovalState.Unavailable -> UnavailableBody(state)
                    is ToolApprovalState.Awaiting -> AwaitingBody(state.proposal, onDecision)
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumTouchTarget(),
                ) { Text("Close") }
            }
        }
    }
}

@Composable
private fun UnavailableBody(state: ToolApprovalState.Unavailable) {
    UnavailableNotice(
        state = CapabilityState.Unavailable(state.reason, state.requiredCapability),
    )
    AuthorityNotice()
}

/**
 * Only ever composed from a preview or a test.
 *
 * It is written as carefully as if it shipped, because the point of building it now is
 * to have reviewed it before it can do anything -- a surface authored in a hurry on the
 * day the contract lands is how an approval dialog becomes a rubber stamp.
 */
@Composable
private fun AwaitingBody(
    proposal: ToolProposal,
    onDecision: ((ToolDecision) -> Unit)?,
) {
    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            Field("Requested action", proposal.action)
            Field("Target", proposal.target)
            Field("Required permission", proposal.requiredPermission)
            // Verbatim, never summarised -- a paraphrased preview previews the client's
            // understanding rather than the action.
            Field("Expected effect", proposal.preview, mono = true)
        }
    }

    AuthorityNotice()

    // Disabled unless a decision sink was supplied. The navigation graph supplies none.
    val enabled = onDecision != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
    ) {
        Button(
            onClick = { onDecision?.invoke(ToolDecision.APPROVE_ONCE) },
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .minimumTouchTarget(),
        ) { Text("Approve once") }

        OutlinedButton(
            onClick = { onDecision?.invoke(ToolDecision.DENY) },
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier
                .weight(1f)
                .minimumTouchTarget(),
        ) { Text("Deny") }
    }
}

/**
 * The sentence the whole screen exists to enforce.
 *
 * Retrieval tells you what is true; it does not tell you what you may do. Keeping the
 * two apart is the difference between an assistant that cites a document and one that
 * can be argued into acting by a document.
 */
@Composable
private fun AuthorityNotice() {
    StatusLine(
        mark = MARK_INFO,
        label = "Retrieved knowledge never grants authority to act.",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The runtime state, previewed. This is what a shipping build actually renders, and
 * previewing it rather than the populated case is the honest default.
 */
@Preview(showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun ToolApprovalUnavailablePreview() {
    LocalmindTheme(darkTheme = true) {
        val gate = com.verbalogix.assistant.data.capability.Capabilities.NONE.toolProposals
            as CapabilityState.Unavailable
        ToolApprovalSheet(
            state = ToolApprovalState.Unavailable(gate.reason, gate.requiredCapability),
            onDismiss = {},
        )
    }
}
