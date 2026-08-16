package com.verbalogix.assistant.ui.experts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.ui.components.AmberPanel
import com.verbalogix.assistant.ui.components.EmptyNotice
import com.verbalogix.assistant.ui.components.MARK_INFO
import com.verbalogix.assistant.ui.components.MARK_OK
import com.verbalogix.assistant.ui.components.StatusLine
import com.verbalogix.assistant.ui.components.UnavailableNotice
import com.verbalogix.assistant.ui.components.minimumTouchTarget
import com.verbalogix.assistant.ui.theme.AmberTokens
import com.verbalogix.assistant.ui.theme.LocalmindTheme

const val TAG_EXPERT_LIBRARY = "expert-library"
const val TAG_EXPERT_SEARCH = "expert-search"

/**
 * The scrolling list itself, distinct from [TAG_EXPERT_LIBRARY] on the Surface.
 *
 * A test cannot drive a lazy list through a tag on its container: `performScrollToNode`
 * needs the node that actually scrolls. Tagging only the Surface is what made the first
 * version of the regression test unable to reach row twelve.
 */
const val TAG_EXPERT_LIST = "expert-list"

/**
 * The expert library.
 *
 * READ-ONLY BY CONSTRUCTION. There is no Install, Update, Activate, Deactivate, Remove or
 * Import control anywhere on this screen, and their absence is the design rather than an
 * unfinished state. Localmind holds `expert:read` and nothing more; every one of those
 * verbs is a Foundry authority, and the Stitch mockups show all six. A button that cannot
 * work is worse than no button, because it teaches the user the app is broken when it is
 * behaving exactly as intended.
 *
 * Nothing here is derived, either. The mockups also carry an "Updates" filter with a
 * notification dot; `expert-release-summary/3.0` has no predecessor field, so that badge
 * could only come from comparing two versions -- which is a claim this client is not
 * entitled to make.
 */
@Composable
fun ExpertLibraryScreen(
    state: ExpertLibraryUiState,
    onOpenExpert: (releaseId: String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Content above the library, inside THIS screen's scroll.
     *
     * A slot rather than a sibling, and that distinction is the whole bug fix. The
     * destination used to wrap this screen and the pairing panel in a
     * `Column(verticalScroll(...))` -- two scroll owners, one of them lazy -- which is an
     * infinite-height measure and a hard crash the moment a non-empty catalog renders.
     * Passing the panel IN means there is exactly one scrolling owner and no way for a
     * caller to add a second by accident.
     */
    header: @Composable () -> Unit = {},
) {
    // Survives rotation and process death: a user who typed a digest fragment to find one
    // row should not have to type it again because the keyboard resized the window.
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ExpertFilter.ALL) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_EXPERT_LIBRARY),
        color = MaterialTheme.colorScheme.background,
    ) {
        // Computed OUTSIDE the lazy content: `remember` needs composable scope, and the
        // lazy DSL's item builders are not that. Also correct on its own terms -- these
        // derive from the whole list, so recomputing them per visible item would be work
        // proportional to scrolling.
        val ready = state as? ExpertLibraryUiState.Ready
        val visible = remember(ready, query, filter) {
            ready?.experts.orEmpty().search(query).filter { filter.matches(it) }
        }
        // Over the WHOLE list, not the visible subset, so an abbreviation does not change
        // length as the user types -- and two ids that collide stay distinguishable even
        // when a filter is currently hiding one of them.
        val short = remember(ready) {
            abbreviateIdentities(ready?.experts.orEmpty().map { it.releaseId })
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(AmberTokens.mobileMargin)
                .testTag(TAG_EXPERT_LIST),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.mobileMargin),
        ) {
            item(key = "header") { header() }

            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit)) {
                    Text(
                        text = "Experts",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = "Knowledge packs mounted by Knowledge Foundry. This app " +
                            "reads them and changes none of them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (state) {
                ExpertLibraryUiState.Loading -> item(key = "loading") {
                    StatusLine(mark = MARK_INFO, label = "Checking what is available…")
                }

                is ExpertLibraryUiState.Unavailable -> item(key = "unavailable") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(AmberTokens.mobileMargin),
                    ) {
                        UnavailableNotice(state.capability)
                        Text(
                            "Localmind still works without this. Direct chat is " +
                                "unaffected, and answers keep whatever evidence they " +
                                "arrived with.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                ExpertLibraryUiState.Empty -> item(key = "empty") {
                    EmptyNotice(
                        title = "No experts mounted",
                        body = "Knowledge Foundry reported no mounted packs.",
                    )
                }

                // SEPARATE FROM Refused, because only this one is fixed by a release. A
                // user told "something went wrong" cannot tell whether waiting helps.
                is ExpertLibraryUiState.Incompatible -> item(key = "incompatible") {
                    EmptyNotice(title = "Version mismatch", body = state.detail)
                }

                is ExpertLibraryUiState.Refused -> item(key = "refused") {
                    EmptyNotice(title = "Reply not accepted", body = state.detail)
                }

                is ExpertLibraryUiState.Ready -> {
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search name or id") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TAG_EXPERT_SEARCH),
                        )
                    }
                    item(key = "filters") {
                        Row(horizontalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit)) {
                            for (option in ExpertFilter.entries) {
                                FilterChip(
                                    selected = filter == option,
                                    onClick = { filter = option },
                                    label = { Text(option.label()) },
                                    modifier = Modifier.minimumTouchTarget(),
                                )
                            }
                        }
                    }
                    if (visible.isEmpty()) {
                        // DISTINCT FROM Empty. "Nothing is mounted" and "nothing matches
                        // what you typed" are different facts, and only one has a remedy
                        // the user can act on.
                        item(key = "no-match") {
                            EmptyNotice(
                                title = "Nothing matches",
                                body = "No mounted expert matches this filter and search.",
                            )
                        }
                    } else {
                        items(visible, key = { it.releaseId }) { expert ->
                            ExpertCard(
                                expert = expert,
                                abbreviatedId = short[expert.releaseId] ?: expert.releaseId,
                                onOpen = { onOpenExpert(expert.releaseId) },
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun ExpertCard(
    expert: ExpertSummary,
    abbreviatedId: String,
    onOpen: () -> Unit,
) {
    val active = expert.lifecycle == ExpertLifecycle.MOUNTED
    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minimumTouchTarget()
                .clickable(onClick = onOpen)
                .semantics {
                    contentDescription = "${expert.name}, version ${expert.version}, " +
                        "${expert.lifecycle.label()}, ${expert.trustState}. Opens details."
                },
        ) {
            // An accent bar, and never the only signal: the state is also spelled out in
            // words two lines below. Colour alone fails for a third of readers and every
            // screenshot printed in grey.
            if (active) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Column(
                modifier = Modifier
                    .padding(AmberTokens.mobileMargin)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        expert.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusLine(
                        mark = if (active) MARK_OK else MARK_INFO,
                        label = expert.lifecycle.label(),
                        tint = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                // The label is for the reader; the identity is what the system acts on.
                // Abbreviated only as far as uniqueness allows -- see abbreviateIdentities.
                Text(
                    "${expert.namespace}/${expert.slug} · v${expert.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    abbreviatedId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // TRUST AS A FULL LINE OF TEXT, for the property where being misread is
                // costliest. Never a colour, never a badge on its own.
                TrustLine(expert.trustState)
            }
        }
    }
}

/**
 * Trust, spelled out.
 *
 * The catalog only ever lists `trusted` releases -- the schema pins it to a const -- so
 * this reads affirmatively today. It is written as a `when` rather than a formatted string
 * so that a value the contract does not yet emit gets its own deliberate wording instead of
 * being interpolated into a sentence that reads like approval.
 */
@Composable
private fun TrustLine(trustState: String) {
    val (mark, text) = when (trustState) {
        "trusted" -> MARK_OK to "Trusted signature, verified by Knowledge Foundry"
        else -> MARK_INFO to "Trust state: $trustState"
    }
    StatusLine(
        mark = mark,
        label = text,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun ExpertLibraryUnavailablePreview() {
    LocalmindTheme(darkTheme = true) {
        ExpertLibraryScreen(
            state = ExpertLibraryUiState.Unavailable(
                Capabilities.NONE.expertLibrary as com.verbalogix.assistant.data.capability.CapabilityState.Unavailable,
            ),
            onOpenExpert = {},
        )
    }
}
