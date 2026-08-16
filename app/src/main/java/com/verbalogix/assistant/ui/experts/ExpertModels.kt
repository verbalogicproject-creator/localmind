package com.verbalogix.assistant.ui.experts

import com.verbalogix.assistant.data.capability.CapabilityState

/**
 * Where a pack stands in its life.
 *
 * Transcribed from `lifecycle_states` in `docs/ui/state-catalog.json`, not invented
 * here. They are seven distinct states rather than an "installed" boolean because the
 * contract keeps build, verify, sign, trust, install, mount, export, update and
 * rollback as SEPARATE operations with separate authority -- so "installed but not
 * mounted" and "mounted" are genuinely different, and so are "incompatible" and
 * "trust revoked", which a boolean would collapse into one shrug.
 *
 * Localmind renders these. It does not compute them, and it cannot cause a transition
 * between them: every one of those verbs belongs to Knowledge Foundry.
 */
enum class ExpertLifecycle {
    VERIFIED_UNINSTALLED,
    INSTALLED_INACTIVE,
    MOUNTED,
    UPDATE_AVAILABLE,
    TRUST_REVOKED,
    INCOMPATIBLE,
    ROLLBACK_AVAILABLE,
}

/**
 * One expert, as this client is allowed to describe it.
 *
 * NOTE WHAT IS NOT HERE: no pack size, no signature bytes, no content digest, no
 * source coverage percentage, no evaluation score, no release history. The approved
 * screenshots show all of those, and every one of them is mock data invented by a
 * design tool. They are omitted rather than defaulted, because a field with a
 * plausible zero in it is a field that will eventually be read as a measurement.
 *
 * `packId` and `version` are OPAQUE. This app carries them and puts them in routes; it
 * never parses, compares or orders them, because versioning a `.kpack` is a Foundry
 * verb (`docs/ui/api-bindings.json`: `localmind-never-parses-or-versions-kpacks`).
 */
data class ExpertSummary(
    val packId: String,
    val version: String,
    val displayName: String,
    val lifecycle: ExpertLifecycle,
)

/**
 * What the expert library can be showing.
 *
 * [Unavailable] is the only state a shipping build can reach today, because nothing
 * supplies [Ready] -- there is no Foundry client, and the mock Harness is explicitly
 * not one.
 */
sealed interface ExpertLibraryUiState {

    data object Loading : ExpertLibraryUiState

    /** No capability to discover experts. Carries reason and required capability. */
    data class Unavailable(val capability: CapabilityState.Unavailable) : ExpertLibraryUiState

    /** Discovery worked and there is genuinely nothing mounted. */
    data object Empty : ExpertLibraryUiState

    /**
     * Constructible, and unreachable at runtime. Kept typed so the rendering path is
     * written and reviewed before a real client can drive it, rather than authored
     * under time pressure on the day one appears.
     */
    data class Ready(val experts: List<ExpertSummary>) : ExpertLibraryUiState
}

/** The same three-way shape for one expert's detail surface. */
sealed interface ExpertDetailUiState {
    data object Loading : ExpertDetailUiState
    data class Unavailable(val capability: CapabilityState.Unavailable) : ExpertDetailUiState
    data class NotFound(val packId: String, val version: String) : ExpertDetailUiState
    data class Ready(val expert: ExpertSummary) : ExpertDetailUiState
}

/**
 * The words for each lifecycle state, and a glyph to go with them.
 *
 * Paired here rather than at the call site so no surface can render one without the
 * other -- `status: text-and-icon-never-color-only` is a rule that gets broken by
 * convenience, one badge at a time.
 */
fun ExpertLifecycle.label(): String = when (this) {
    ExpertLifecycle.VERIFIED_UNINSTALLED -> "Verified, not installed"
    ExpertLifecycle.INSTALLED_INACTIVE -> "Installed, inactive"
    ExpertLifecycle.MOUNTED -> "Mounted"
    ExpertLifecycle.UPDATE_AVAILABLE -> "Update available"
    ExpertLifecycle.TRUST_REVOKED -> "Trust revoked"
    ExpertLifecycle.INCOMPATIBLE -> "Incompatible"
    ExpertLifecycle.ROLLBACK_AVAILABLE -> "Rollback available"
}
