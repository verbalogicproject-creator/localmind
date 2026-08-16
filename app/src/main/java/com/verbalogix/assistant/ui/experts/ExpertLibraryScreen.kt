package com.verbalogix.assistant.ui.experts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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

const val TAG_EXPERT_LIBRARY = "expert-library"

/**
 * The expert library.
 *
 * On every build that exists today this renders one thing: that expert discovery is
 * unavailable, why, and which capability would supply it. That is the screen working
 * correctly, not a stub. The approved screenshot shows four populated packs with sizes,
 * signatures and update badges; every value in it was invented by a design tool, and
 * reproducing any of it would put a fabricated trust decision on screen -- which is the
 * exact failure mode this project exists to prevent.
 */
@Composable
fun ExpertLibraryScreen(
    state: ExpertLibraryUiState,
    onOpenExpert: (packId: String, version: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_EXPERT_LIBRARY),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AmberTokens.mobileMargin),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.mobileMargin),
        ) {
            Text(
                text = "Experts",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            when (state) {
                ExpertLibraryUiState.Loading -> StatusLine(
                    mark = MARK_INFO,
                    label = "Checking what is available…",
                )

                is ExpertLibraryUiState.Unavailable -> Column(
                    verticalArrangement = Arrangement.spacedBy(AmberTokens.mobileMargin),
                ) {
                    UnavailableNotice(state.capability)
                    Text(
                        "Localmind still works without this. Direct chat is unaffected, " +
                            "and answers keep whatever evidence they arrived with.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ExpertLibraryUiState.Empty -> EmptyNotice(
                    title = "No experts mounted",
                    body = "Knowledge Foundry reported no mounted packs.",
                )

                is ExpertLibraryUiState.Ready -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
                ) {
                    items(state.experts, key = { "${it.packId}@${it.version}" }) { expert ->
                        ExpertRow(expert, onOpenExpert)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpertRow(
    expert: ExpertSummary,
    onOpenExpert: (packId: String, version: String) -> Unit,
) {
    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minimumTouchTarget()
                .clickable { onOpenExpert(expert.packId, expert.version) }
                .padding(AmberTokens.mobileMargin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    expert.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // The id and version are shown as the opaque tokens they are, in
                // monospace, so nobody reads them as a sentence.
                Text(
                    "${expert.packId} · ${expert.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusLine(mark = MARK_INFO, label = expert.lifecycle.label())
            }
        }
    }
}

/**
 * The runtime state, previewed -- because this is what a build actually shows.
 */
@Preview(showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun ExpertLibraryUnavailablePreview() {
    LocalmindTheme(darkTheme = true) {
        ExpertLibraryScreen(
            state = ExpertLibraryUiState.Unavailable(
                Capabilities.NONE.expertLibrary as CapabilityState.Unavailable,
            ),
            onOpenExpert = { _, _ -> },
        )
    }
}
