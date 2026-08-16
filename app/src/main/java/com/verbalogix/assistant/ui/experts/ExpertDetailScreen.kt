package com.verbalogix.assistant.ui.experts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.components.AmberPanel
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

/**
 * One release, in full.
 *
 * KEYED BY IDENTITY, not by display name. The route is `experts/{packId}/{version}` per
 * the Foundry's own route manifest, and `packId` is `kf:pack:<sha256>` -- an immutable
 * identity, not a label. That distinction is the point: a name is something a server can
 * change, and routing on one would let a rename silently repoint this screen at a
 * different artifact. The release's own `kf:pack-release:` identity is shown and copyable
 * below rather than used as the key, because the manifest is the authority on routes.
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
                    body = "No mounted release matches ${state.packId} at version ${state.version}.",
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

                is ExpertDetailUiState.Ready -> ReadyDetail(state.expert)
            }
        }
    }
}

@Composable
private fun ReadyDetail(expert: ExpertDetail) {
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
