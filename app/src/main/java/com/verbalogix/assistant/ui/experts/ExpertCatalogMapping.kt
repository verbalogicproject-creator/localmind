package com.verbalogix.assistant.ui.experts

import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.data.harness.HarnessOutcome
import com.verbalogix.assistant.data.harness.HarnessRefusal
import com.verbalogix.assistant.data.harness.SchemaVerdict
import com.verbalogix.assistant.data.harness.wire.ExpertCatalogResult
import com.verbalogix.assistant.data.harness.wire.ExpertReleaseDetailResult
import com.verbalogix.assistant.data.harness.wire.ExpertReleaseSummary

/**
 * Decoded Harness documents become screen states, and every failure keeps its reason.
 *
 * THE DISTINCTION THIS EXISTS TO PRESERVE: "the Foundry says nothing is mounted" and "we
 * could not ask" are different answers. A client that renders both as an empty list tells
 * a user with a broken connection that their library is empty -- a claim the UI has no way
 * to withdraw, and one the user has no reason to doubt.
 *
 * A second split sits underneath it. An INCOMPATIBLE reply and a REFUSED one both mean
 * nothing was read, but only the first is fixed by updating software. Collapsing them
 * into "something went wrong" leaves the user with no idea whether waiting helps.
 */
internal fun ExpertReleaseSummary.toSummary(): ExpertSummary = ExpertSummary(
    packId = packId,
    releaseId = releaseId,
    name = name,
    namespace = namespace,
    slug = slug,
    version = version,
    lifecycle = mountState.toLifecycle(),
    trustState = trustState,
)

/**
 * `mount_state` is the only lifecycle signal the catalog carries.
 *
 * The `when` is exhaustive over the contract's two values on purpose: a third added
 * upstream becomes a loud failure here rather than a silent fall-through to a wrong
 * lifecycle. Nothing is inferred -- an expert is not "update available" because two
 * versions exist, and this client does not get to decide that.
 */
private fun String.toLifecycle(): ExpertLifecycle = when (this) {
    "active" -> ExpertLifecycle.MOUNTED
    "installed-inactive" -> ExpertLifecycle.INSTALLED_INACTIVE
    // Unreachable: the decoder refuses any other value before this is called. Present to
    // keep the mapping total, not to handle real input.
    else -> error("unvalidated mount_state reached the mapper: $this")
}

internal fun ExpertCatalogResult.toLibraryState(): ExpertLibraryUiState =
    if (releases.isEmpty()) {
        ExpertLibraryUiState.Empty
    } else {
        ExpertLibraryUiState.Ready(releases.map { it.toSummary() })
    }

internal fun HarnessOutcome<ExpertCatalogResult>.toLibraryState(): ExpertLibraryUiState =
    when (this) {
        is HarnessOutcome.Decoded -> value.toLibraryState()

        is HarnessOutcome.Unsuccessful -> ExpertLibraryUiState.Refused(
            "The Knowledge Foundry did not complete the request " +
                "($disposition${errorCode?.let { ": $it" } ?: ""}).",
        )

        is HarnessOutcome.Refused ->
            if (refusal.isIncompatibility()) {
                ExpertLibraryUiState.Incompatible(refusal.reason)
            } else {
                ExpertLibraryUiState.Refused(refusal.reason)
            }
    }

internal fun ExpertReleaseDetailResult.toDetail(): ExpertDetail = ExpertDetail(
    summary = release.toSummary(),
    description = release.description,
    profile = release.profile,
    riskClass = release.riskClass,
    publicationChannel = release.publicationChannel,
    capabilities = release.capabilities,
    allowedSensitivities = release.allowedSensitivities,
    contentSha256 = release.contentSha256,
    archiveSha256 = release.archiveSha256,
    installRecordSha256 = release.installRecordSha256,
    installId = install.installId,
    signerKeyId = install.signerKeyId,
    compatibility = install.compatibility,
    dependencyReleaseIds = install.dependencyReleaseIds,
    verificationSha256 = install.verificationSha256,
    predecessorReleaseId = lifecycle.predecessorReleaseId,
    rollbackReleaseId = lifecycle.rollbackReleaseId,
    supersededContentSha256 = lifecycle.supersededContentSha256,
)

internal fun HarnessOutcome<ExpertReleaseDetailResult>.toDetailState(): ExpertDetailUiState =
    when (this) {
        is HarnessOutcome.Decoded -> ExpertDetailUiState.Ready(value.toDetail())

        is HarnessOutcome.Unsuccessful -> ExpertDetailUiState.Refused(
            "The Knowledge Foundry did not complete the request " +
                "($disposition${errorCode?.let { ": $it" } ?: ""}).",
        )

        is HarnessOutcome.Refused ->
            if (refusal.isIncompatibility()) {
                ExpertDetailUiState.Incompatible(refusal.reason)
            } else {
                ExpertDetailUiState.Refused(refusal.reason)
            }
    }

/**
 * Which refusals mean "this build and that Harness disagree about the contract".
 *
 * Only two: an unreadable payload version, and a runtime contract this build was not
 * written for. Everything else -- a correlation failure, a malformed identity, an unknown
 * disposition -- is a fault in one exchange rather than a standing disagreement, and
 * telling the user to update the app would be wrong advice.
 */
private fun HarnessRefusal.isIncompatibility(): Boolean = when (this) {
    is HarnessRefusal.RuntimeContract -> true
    is HarnessRefusal.Schema -> verdict is SchemaVerdict.Unsupported
    else -> false
}

/**
 * The capability gate, rendered as a library state.
 *
 * Kept separate from the decode outcomes because it is a different question: this is "the
 * Harness never offered the operation", not "the reply could not be read".
 */
internal fun CapabilityState.Unavailable.toLibraryState(): ExpertLibraryUiState =
    ExpertLibraryUiState.Unavailable(this)
