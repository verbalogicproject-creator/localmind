package com.verbalogix.assistant.ui.experts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.components.AmberPanel
import com.verbalogix.assistant.ui.evidence.RetrievalEvidenceView
import com.verbalogix.assistant.ui.evidence.RetrievalTarget
import com.verbalogix.assistant.ui.evidence.RetrievalUiState
import com.verbalogix.assistant.ui.components.EmptyNotice
import com.verbalogix.assistant.ui.components.MARK_INFO
import com.verbalogix.assistant.ui.components.MARK_OK
import com.verbalogix.assistant.ui.components.StatusLine
import com.verbalogix.assistant.ui.components.UnavailableNotice
import com.verbalogix.assistant.ui.components.minimumTouchTarget
import com.verbalogix.assistant.ui.theme.AmberTokens
import com.verbalogix.assistant.ui.theme.LocalmindTheme

const val TAG_EXPERT_DETAIL = "expert-detail"
const val TAG_TECHNICAL_DISCLOSURE = "expert-technical-disclosure"
const val TAG_EXPERT_QUERY_FIELD = "expert-query-field"
const val TAG_EXPERT_QUERY_SUBMIT = "expert-query-submit"

/**
 * One release, in full.
 *
 * KEYED BY RELEASE IDENTITY. The route is `experts/{releaseId}` and the lookup sends
 * exactly `{"release_id": "kf:pack-release:…"}`.
 *
 * It was `experts/{packId}/{version}` -- the shape the route manifest still declares --
 * until the live adapter showed the lookup is by RELEASE. The distinction is substantive
 * rather than cosmetic: a pack id names a pack across all of its releases and a version
 * is a label attached to one, so identifying a release by the pair resolves two softer
 * facts to reach an immutable thing that already has its own name. A digest cannot drift,
 * be reused, or be ambiguous. Pack id and version remain on screen as things to SHOW.
 *
 * DIVERGES FROM `docs/ui/route-manifest.json`, deliberately and visibly: that file still
 * declares the two-argument form. The live contract is the stronger authority here, and
 * the manifest is expected to follow.
 *
 * EVERY FIELD IS CONTRACTED. Nothing is computed, inferred or defaulted: no evaluation
 * score, no source-standing figure, no "last updated", no pack size. Those appear in the
 * Stitch mockups and in no schema. A field with a plausible value in it is a field that
 * will eventually be read as a measurement.
 *
 * READ-ONLY. No Activate, Deactivate, Apply Update, Rollback or Remove — the mockups show
 * all five, and every one is a Foundry authority this client does not hold. `lifecycle`
 * names a predecessor and a rollback target when they exist, and NAMING them is the whole
 * of what Localmind may do with them.
 */
@Composable
fun ExpertDetailScreen(
    state: ExpertDetailUiState,
    onBack: () -> Unit = {},
    retrieval: RetrievalUiState = RetrievalUiState.Idle,
    queryText: String = "",
    onQueryChange: (String) -> Unit = {},
    onSubmitQuery: (RetrievalTarget) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_EXPERT_DETAIL),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AmberTokens.mobileMargin),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.mobileMargin),
        ) {
            // A detail route needs its own way out: the bottom bar is hidden here so it
            // does not compete with Back, which leaves the system gesture as the only
            // other exit and that is not discoverable enough to be the only one.
            TextButton(onClick = onBack, modifier = Modifier.minimumTouchTarget()) {
                Text("Back to experts")
            }

            when (state) {
                ExpertDetailUiState.Loading ->
                    StatusLine(mark = MARK_INFO, label = "Checking what is available…")

                is ExpertDetailUiState.Unavailable -> UnavailableNotice(state.capability)

                is ExpertDetailUiState.NotFound -> EmptyNotice(
                    title = "Not found",
                    body = "No mounted release matches ${state.releaseId}.",
                )

                // Separate from Refused: only this one is fixed by a release.
                is ExpertDetailUiState.Incompatible -> EmptyNotice(
                    title = "Version mismatch",
                    body = state.detail,
                )

                is ExpertDetailUiState.Refused -> EmptyNotice(
                    title = "Reply not accepted",
                    body = state.detail,
                )

                is ExpertDetailUiState.Ready -> ReadyDetail(
                    expert = state.expert,
                    retrieval = retrieval,
                    queryText = queryText,
                    onQueryChange = onQueryChange,
                    onSubmitQuery = onSubmitQuery,
                )
            }
        }
    }
}

@Composable
private fun ReadyDetail(
    expert: ExpertDetail,
    retrieval: RetrievalUiState,
    queryText: String,
    onQueryChange: (String) -> Unit,
    onSubmitQuery: (RetrievalTarget) -> Unit,
) {
    val summary = expert.summary

    Text(
        summary.name,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        "${summary.namespace}/${summary.slug} · v${summary.version}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (expert.description.isNotBlank()) {
        Text(
            expert.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ── state, in words ─────────────────────────────────────────────────────
    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            StatusLine(
                mark = if (summary.lifecycle == ExpertLifecycle.MOUNTED) MARK_OK else MARK_INFO,
                label = summary.lifecycle.label(),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            TrustStatusLine(summary.trustState)
            // `compatibility` is the Foundry's verdict, shown verbatim. This client does
            // not decide compatibility and does not translate the word it was given.
            Field("Compatibility", expert.compatibility)
            Field("Profile", expert.profile)
            Field("Risk class", expert.riskClass)
            Field("Publication channel", expert.publicationChannel)
        }
    }

    SearchThisExpert(
        target = expert.retrievalTarget(),
        retrieval = retrieval,
        queryText = queryText,
        onQueryChange = onQueryChange,
        onSubmitQuery = onSubmitQuery,
    )

    if (expert.capabilities.isNotEmpty()) {
        Section("Capabilities") {
            // One per line rather than comma-joined: these are discrete grants, and a
            // wrapped comma list invites reading two names as one.
            for (capability in expert.capabilities) {
                Text(
                    "· $capability",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (expert.allowedSensitivities.isNotEmpty()) {
        Section("Allowed sensitivities") {
            Text(
                expert.allowedSensitivities.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ── lifecycle, only where the Harness supplied a value ──────────────────
    //
    // Rendered ONLY when non-null. A null predecessor means the Harness said there is
    // none, and a "Predecessor: none" row reads as a fact about the pack's history rather
    // than about this field, so the row is absent instead of empty.
    val lifecycleRows = buildList {
        expert.predecessorReleaseId?.let { add("Predecessor" to it) }
        expert.rollbackReleaseId?.let { add("Rollback target" to it) }
        expert.supersededContentSha256?.let { add("Supersedes content" to it) }
    }
    if (lifecycleRows.isNotEmpty()) {
        Section("Lifecycle") {
            for ((label, value) in lifecycleRows) CopyableField(label, value)
        }
    }
    if (expert.dependencyReleaseIds.isNotEmpty()) {
        Section("Dependencies") {
            for (dependency in expert.dependencyReleaseIds) {
                CopyableField("Release", dependency)
            }
        }
    }

    // ── progressive disclosure: identities and digests ──────────────────────
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .minimumTouchTarget()
            .testTag(TAG_TECHNICAL_DISCLOSURE),
    ) {
        Text(if (expanded) "Hide technical detail" else "Show technical detail")
    }

    if (expanded) {
        Section("Identity") {
            CopyableField("Pack", summary.packId)
            CopyableField("Release", summary.releaseId)
            CopyableField("Install", expert.installId)
        }
        Section("Verification") {
            CopyableField("Signer key", expert.signerKeyId)
            CopyableField("Content SHA-256", expert.contentSha256)
            CopyableField("Archive SHA-256", expert.archiveSha256)
            CopyableField("Install record SHA-256", expert.installRecordSha256)
            CopyableField("Verification SHA-256", expert.verificationSha256)
        }
    }
}

/**
 * Ask this expert what it knows — and get back what it knows, not a reply.
 *
 * "SEARCH", NOT "ASK", AND "RETRIEVE EVIDENCE", NOT "ANSWER". The words are the honest
 * description of what happens: the Foundry returns quoted source material with provenance,
 * and Localmind writes nothing. A field labelled "Ask" sets the expectation of a reply,
 * and the surface below it would then read as a badly-formatted one rather than as
 * evidence. `canonical-assistant-turn` is planned-not-implemented; until it exists nothing
 * could attest that a generated answer used this material, so nothing generates one.
 *
 * SUBMISSION IS EXPLICIT. The text field only records text. A search-as-you-type retrieval
 * would send a real request against a real knowledge base for every prefix of the question
 * — a dozen partial questions on the way to the one the user meant — and would then race
 * its own results back onto the screen.
 *
 * OFFERED ONLY FOR THE ACTIVE RELEASE. Retrieval runs against what is MOUNTED, so a
 * question posed from an inactive release's screen would be answered from whatever is
 * mounted instead and attributed here. The field is absent rather than disabled-with-a-
 * tooltip, and [com.verbalogix.assistant.ui.evidence.RetrievalController] refuses the same
 * case again on its own.
 */
@Composable
private fun SearchThisExpert(
    target: RetrievalTarget,
    retrieval: RetrievalUiState,
    queryText: String,
    onQueryChange: (String) -> Unit,
    onSubmitQuery: (RetrievalTarget) -> Unit,
) {
    Section("Search this expert") {
        if (!target.active) {
            Text(
                "This release is installed but not active, so it cannot be searched. " +
                    "Activation is a Knowledge Foundry decision and happens in Knowledge " +
                    "Studio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Section
        }

        Text(
            "Returns quoted evidence from this expert's own sources. Localmind does not " +
                "write an answer from it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = queryText,
            onValueChange = onQueryChange,
            label = { Text("Search this expert") },
            singleLine = true,
            // Search, not Send or Go. The keyboard's own action is a submission the user
            // performed deliberately, so it counts as explicit -- unlike a text change.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmitQuery(target) }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_EXPERT_QUERY_FIELD),
        )
        Button(
            onClick = { onSubmitQuery(target) },
            // Disabled while one is in flight, so a second tap cannot queue a request whose
            // answer would be discarded on arrival anyway.
            enabled = queryText.isNotBlank() && retrieval !is RetrievalUiState.Querying,
            modifier = Modifier
                .fillMaxWidth()
                .minimumTouchTarget()
                .testTag(TAG_EXPERT_QUERY_SUBMIT),
        ) { Text("Retrieve evidence") }

        // Sensitivity is stated where the question is asked, not only in a section below:
        // it is the scope of what can come back, and it is the Foundry's decision, echoed.
        Text(
            "Scope: this release only · sensitivities " +
                target.allowedSensitivities.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RetrievalEvidenceView(state = retrieval)
    }
}

/**
 * Trust, as a full line of text.
 *
 * A `when` rather than an interpolated sentence, so a value the contract does not yet emit
 * gets deliberate wording instead of being dropped into a phrase that reads like approval.
 */
@Composable
private fun TrustStatusLine(trustState: String) {
    StatusLine(
        mark = if (trustState == "trusted") MARK_OK else MARK_INFO,
        label = if (trustState == "trusted") {
            "Trusted signature, verified by Knowledge Foundry"
        } else {
            "Trust state: $trustState"
        },
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
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

/**
 * A digest or identity, shown IN FULL, with a copy action.
 *
 * NEVER TRUNCATED HERE. Cards abbreviate, computing a prefix long enough to stay unique
 * across what is on screen; this surface does not, because it is where a value gets read
 * against something outside the app — a build log, a Studio receipt, a `kf:` string
 * someone was handed. An abbreviation that is unambiguous within this list is not
 * unambiguous against the world, and a digest compared at half its length has not been
 * compared.
 *
 * The copy action carries its own TalkBack label naming the field, because a row of hex is
 * unreadable aloud and "copy" alone would not say what had been copied.
 */
@Composable
private fun CopyableField(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .minimumTouchTarget()
            .clickable { clipboard.setText(AnnotatedString(value)) }
            .semantics { contentDescription = "Copy $label" },
        verticalArrangement = Arrangement.spacedBy(1.dp),
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

@Preview(showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun ExpertDetailUnavailablePreview() {
    LocalmindTheme(darkTheme = true) {
        ExpertDetailScreen(
            state = ExpertDetailUiState.Unavailable(
                Capabilities.NONE.expertLibrary as CapabilityState.Unavailable,
            ),
        )
    }
}
