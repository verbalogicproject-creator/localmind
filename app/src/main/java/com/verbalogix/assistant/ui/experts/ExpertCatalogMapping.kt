package com.verbalogix.assistant.ui.experts

import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.data.harness.HarnessOutcome
import com.verbalogix.assistant.data.harness.wire.ExpertCatalogResult

/**
 * A decoded catalog becomes a screen state, and every failure keeps its reason.
 *
 * THE DISTINCTION THIS EXISTS TO PRESERVE: "the Foundry says nothing is mounted" and
 * "we could not ask" are different answers, and collapsing them is the easy mistake.
 * A client that renders both as an empty list tells a user with a broken connection that
 * their library is empty -- which is a lie the UI has no way to walk back.
 *
 *   Decoded + no releases  -> Empty        an answer, and a true one
 *   Decoded + releases     -> Ready        NEVER YET OBSERVED -- see below
 *   Unsuccessful           -> Unavailable  the Harness declined; its word, not ours
 *   Refused                -> Unavailable  we would not read the reply; our reason
 *
 * `Ready` IS UNVERIFIED. The only catalog golden the Foundry has emitted is empty, so no
 * release summary has ever been decoded from a real response. The path is written and
 * typed so it is reviewed rather than authored in a hurry on the day one arrives, but a
 * populated Expert Library is not a claim this build can make.
 */
internal fun ExpertCatalogResult.toLibraryState(): ExpertLibraryUiState =
    if (releases.isEmpty()) {
        ExpertLibraryUiState.Empty
    } else {
        ExpertLibraryUiState.Ready(
            releases.map { release ->
                ExpertSummary(
                    packId = release.packId,
                    version = release.version,
                    displayName = release.name,
                    lifecycle = release.mountState.toLifecycle(),
                )
            },
        )
    }

/**
 * `mount_state` is the only lifecycle signal the `/3.0` catalog carries.
 *
 * [ExpertLifecycle] is WIDER than this contract can express, and that is worth saying
 * rather than quietly mapping around. `expert-release-summary/3.0` closes `mount_state`
 * to `active` and `installed-inactive`, and pins `trust_state` to the const `trusted` --
 * so the catalog structurally cannot report a revoked, incompatible, updatable or
 * rollback-able release. Four of the seven enum constants are therefore unreachable from
 * this response, and none of them may be inferred: an expert is not "update available"
 * because two versions exist, and this client does not get to decide that.
 *
 * The exhaustive `when` is deliberate. A third mount state added upstream becomes a
 * compile error here rather than a silent fall-through to a wrong lifecycle.
 */
private fun String.toLifecycle(): ExpertLifecycle = when (this) {
    "active" -> ExpertLifecycle.MOUNTED
    "installed-inactive" -> ExpertLifecycle.INSTALLED_INACTIVE
    // Unreachable: the decoder refuses any other value before this is called, so this
    // branch exists to keep the mapping total, not to handle real input.
    else -> error("unvalidated mount_state reached the mapper: $this")
}

/**
 * Fold a decode outcome into the screen state.
 *
 * The refusal reason is carried through verbatim rather than replaced with a generic
 * message. `requiredCapability` names the operation so the gap stays greppable, matching
 * what every other unavailable surface in this app already does.
 */
internal fun HarnessOutcome<ExpertCatalogResult>.toLibraryState(): ExpertLibraryUiState =
    when (this) {
        is HarnessOutcome.Decoded -> value.toLibraryState()

        is HarnessOutcome.Unsuccessful -> ExpertLibraryUiState.Unavailable(
            CapabilityState.Unavailable(
                reason = "The Knowledge Foundry did not complete the request " +
                    "($disposition${errorCode?.let { ": $it" } ?: ""}).",
                requiredCapability = "expert.catalog.list",
            ),
        )

        is HarnessOutcome.Refused -> ExpertLibraryUiState.Unavailable(
            CapabilityState.Unavailable(
                reason = refusal.reason,
                requiredCapability = "expert.catalog.list",
            ),
        )
    }

