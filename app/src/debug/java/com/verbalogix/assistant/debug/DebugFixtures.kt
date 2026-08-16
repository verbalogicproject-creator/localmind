package com.verbalogix.assistant.debug

import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilitySource
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.experts.ExpertDetail
import com.verbalogix.assistant.ui.experts.ExpertLifecycle
import com.verbalogix.assistant.ui.experts.ExpertSummary
import com.verbalogix.assistant.ui.tools.ToolDecision
import com.verbalogix.assistant.ui.tools.ToolProposal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fixtures for previews. THIS FILE IS IN `src/debug` AND THAT IS THE POINT.
 *
 * A release build does not compile this source set, so a reference to anything here
 * from `src/main` is a COMPILE ERROR rather than a runtime condition someone has to
 * remember to check. That is the whole isolation mechanism, and it is worth stating why
 * the obvious alternative was rejected: a `if (BuildConfig.DEBUG)` branch in `main`
 * puts the fake success data INSIDE the release binary and relies on a flag to keep it
 * off the screen. The data would ship; only the branch would be dead. Here it does not
 * ship at all.
 *
 * Nothing in this file is wired into Hilt. `CapabilityModule` binds the real
 * `UnavailableCapabilitySource` in both build types, so the dependency graph a
 * developer exercises is identical to the one a user gets -- otherwise the gate under
 * test would be the one that never runs on a phone.
 *
 * The values below are OBVIOUSLY FAKE on purpose. No plausible pack size, no realistic
 * digest, no signature, no percentage. A fixture that looks like real data is a
 * screenshot waiting to be mistaken for evidence, and this project has already been
 * burned by mock values reading as measurements.
 */

/** Grants everything, so preview code can render the populated branches. */
class FakeAvailableCapabilitySource : CapabilitySource {
    override fun capabilities(): Flow<Capabilities> = flowOf(
        Capabilities(
            expertLibrary = CapabilityState.Available,
            evidenceQuery = CapabilityState.Available,
            toolProposals = CapabilityState.Available,
        ),
    )
}

/** Denies everything, matching the production default. Used to test the gate itself. */
class FakeUnavailableCapabilitySource : CapabilitySource {
    override fun capabilities(): Flow<Capabilities> = flowOf(Capabilities.NONE)
}

/**
 * Experts for the populated-library preview.
 *
 * Named `EXAMPLE-*` rather than after anything real, so a screenshot of this can never
 * be mistaken for a mounted pack.
 */
val fakeExperts: List<ExpertSummary> = listOf(
    ExpertSummary(
        packId = "kf:pack:" + "e0".repeat(32),
        releaseId = "kf:pack-release:" + "e1".repeat(32),
        name = "EXAMPLE expert (preview fixture)",
        namespace = "example.invalid",
        slug = "example-expert",
        version = "EXAMPLE-version",
        lifecycle = ExpertLifecycle.MOUNTED,
        trustState = "trusted",
    ),
    ExpertSummary(
        packId = "kf:pack:" + "e2".repeat(32),
        releaseId = "kf:pack-release:" + "e3".repeat(32),
        name = "EXAMPLE inactive expert (preview fixture)",
        namespace = "example.invalid",
        slug = "example-inactive",
        version = "EXAMPLE-version",
        lifecycle = ExpertLifecycle.INSTALLED_INACTIVE,
        trustState = "trusted",
    ),
)

/**
 * One expert detail, for the preview only.
 *
 * `example.invalid` is a reserved namespace that can never resolve, and every digest is a
 * repeated byte pattern no real SHA-256 would produce. Both are deliberate: a fixture that
 * could be mistaken for a real pack is the failure this file exists to avoid.
 */
val fakeExpertDetail: ExpertDetail = ExpertDetail(
    summary = fakeExperts.first(),
    description = "EXAMPLE description (preview fixture).",
    profile = "EXAMPLE-profile",
    riskClass = "EXAMPLE-risk",
    publicationChannel = "EXAMPLE-channel",
    capabilities = listOf("EXAMPLE-capability-a", "EXAMPLE-capability-b"),
    allowedSensitivities = listOf("internal"),
    contentSha256 = "a0".repeat(32),
    archiveSha256 = "a1".repeat(32),
    installRecordSha256 = "a2".repeat(32),
    installId = "kf:install:" + "a3".repeat(32),
    signerKeyId = "ed25519:" + "a4".repeat(32),
    compatibility = "EXAMPLE-compatibility",
    dependencyReleaseIds = emptyList(),
    verificationSha256 = "a5".repeat(32),
    predecessorReleaseId = null,
    rollbackReleaseId = null,
    supersededContentSha256 = null,
)

/**
 * A proposal for the populated approval preview.
 *
 * Reachable only because [ToolProposal]'s constructor is `internal` and this is the same
 * Gradle module. It targets `EXAMPLE-system` rather than a real service: a fixture that
 * names a genuine API is one screenshot away from looking like a working integration.
 */
fun fakeToolProposal(): ToolProposal = ToolProposal(
    proposalId = "EXAMPLE-proposal",
    sessionId = "EXAMPLE-session",
    action = "EXAMPLE action (preview fixture)",
    target = "EXAMPLE-system",
    preview = "EXAMPLE effect — nothing is executed by this build.",
    requiredPermission = "EXAMPLE-permission",
    // Fixed, not `now() + n`. A fixture whose value changes per render makes a preview
    // non-deterministic, and a screenshot diff then reports noise as a change.
    expiresAtEpochMillis = 0L,
)

/** A decision sink that records rather than acts. Previews only. */
class RecordingDecisionSink {
    val decisions = mutableListOf<ToolDecision>()
    fun accept(decision: ToolDecision) {
        decisions += decision
    }
}
