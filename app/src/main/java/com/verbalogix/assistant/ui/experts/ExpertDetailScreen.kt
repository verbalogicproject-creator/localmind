package com.verbalogix.assistant.ui.experts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.components.AmberPanel
import com.verbalogix.assistant.ui.components.EmptyNotice
import com.verbalogix.assistant.ui.components.MARK_INFO
import com.verbalogix.assistant.ui.components.StatusLine
import com.verbalogix.assistant.ui.components.UnavailableNotice
import com.verbalogix.assistant.ui.components.minimumTouchTarget
import com.verbalogix.assistant.ui.theme.AmberTokens
import com.verbalogix.assistant.ui.theme.LocalmindTheme

const val TAG_EXPERT_DETAIL = "expert-detail"

/**
 * One expert.
 *
 * WHAT THIS SCREEN DOES NOT DO, and will not until Foundry declares the contract:
 * install, verify, mount, deactivate, update or roll back. The approved screenshot
 * shows Update and Rollback buttons in its app bar; those are Foundry verbs with their
 * own authority and their own receipts (`docs/ui/api-bindings.json` keeps
 * `pack.install`, `mount.activate`, `mount.update` and `mount.rollback` as four
 * separate operations). A button here that appeared to perform one would be a client
 * claiming authority it does not hold.
 *
 * It also shows no pack size, signer, trust badge, content digest or evaluation
 * receipt. Those exist in the mock and nowhere else.
 */
@Composable
fun ExpertDetailScreen(
    state: ExpertDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_EXPERT_DETAIL),
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
                TextButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumTouchTarget()
                        .semantics { contentDescription = "Back to expert library" },
                ) { Text("Back") }
                Text(
                    text = "Expert",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = AmberTokens.baseUnit)
                        .semantics { heading() },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AmberTokens.mobileMargin),
                verticalArrangement = Arrangement.spacedBy(AmberTokens.mobileMargin),
            ) {
                when (state) {
                    ExpertDetailUiState.Loading -> StatusLine(
                        mark = MARK_INFO,
                        label = "Checking what is available…",
                    )

                    is ExpertDetailUiState.Unavailable -> UnavailableNotice(state.capability)

                    is ExpertDetailUiState.NotFound -> EmptyNotice(
                        title = "That expert is not available",
                        body = if (state.packId.isBlank()) {
                            "The link that opened this screen did not name a valid pack."
                        } else {
                            "Nothing is mounted for ${state.packId} at ${state.version}."
                        },
                    )

                    is ExpertDetailUiState.Ready -> ReadyBody(state.expert)
                }
            }
        }
    }
}

/**
 * Constructible, unreachable at runtime -- written now so it has been reviewed before
 * anything can drive it. It renders only the four fields [ExpertSummary] actually has.
 */
@Composable
private fun ReadyBody(expert: ExpertSummary) {
    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            Text(
                expert.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${expert.packId} · ${expert.version}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusLine(mark = MARK_INFO, label = expert.lifecycle.label())
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun ExpertDetailUnavailablePreview() {
    LocalmindTheme(darkTheme = true) {
        ExpertDetailScreen(
            state = ExpertDetailUiState.Unavailable(
                Capabilities.NONE.expertLibrary as CapabilityState.Unavailable,
            ),
            onBack = {},
        )
    }
}
