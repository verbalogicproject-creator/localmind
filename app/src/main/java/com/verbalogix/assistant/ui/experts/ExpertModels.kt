package com.verbalogix.assistant.ui.experts

import com.verbalogix.assistant.data.capability.CapabilityState

/**
 * Where a pack stands in its life.
 *
 * Transcribed from `lifecycle_states` in `docs/ui/state-catalog.json`, not invented here.
 *
 * FOUR OF THESE ARE UNREACHABLE FROM `/3.0`, and that is worth stating rather than
 * quietly mapping around: `expert-release-summary/3.0` closes `mount_state` to `active`
 * and `installed-inactive` and pins `trust_state` to the const `trusted`, so the catalog
 * structurally cannot report a revoked, incompatible, updatable or rollback-able release.
 * They are kept because the contract may widen and because a renderer that has never
 * considered "revoked" is a renderer that will show one as ordinary.
 *
 * Localmind renders these. It does not compute them and cannot cause a transition between
 * them: every one of those verbs belongs to Knowledge Foundry.
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
 * One expert, as a card shows it.
 *
 * Every field comes from `expert-release-summary/3.0`. NOTE WHAT IS STILL NOT HERE: no
 * pack size, no evaluation score, no source-coverage percentage, no release history. The
 * Stitch mockups show all of those and every one was invented by a design tool.
 *
 * `packId`, `releaseId` and `version` are OPAQUE. This app carries them and puts them in
 * routes; it never parses, compares or orders them, because versioning a `.kpack` is a
 * Foundry verb (`api-bindings.json`: `localmind-never-parses-or-versions-kpacks`).
 */
data class ExpertSummary(
    val packId: String,
    val releaseId: String,
    val name: String,
    val namespace: String,
    val slug: String,
    val version: String,
    val lifecycle: ExpertLifecycle,
    /** Verbatim from the contract. Rendered as a sentence, never as a colour. */
    val trustState: String,
)

/**
 * Everything `expert-release-detail/3.0` carries about one release.
 *
 * Flat rather than nested because the screen groups these by MEANING -- identity,
 * verification, lifecycle -- which does not match the wire's three sub-objects. Keeping
 * the wire shape here would push that regrouping into the composable.
 */
data class ExpertDetail(
    val summary: ExpertSummary,
    val description: String,
    val profile: String,
    val riskClass: String,
    val publicationChannel: String,
    val capabilities: List<String>,
    val allowedSensitivities: List<String>,
    val contentSha256: String,
    val archiveSha256: String,
    val installRecordSha256: String,
    val installId: String,
    val signerKeyId: String,
    val compatibility: String,
    val dependencyReleaseIds: List<String>,
    val verificationSha256: String,
    val predecessorReleaseId: String?,
    val rollbackReleaseId: String?,
    val supersededContentSha256: String?,
)

/**
 * The library's filters.
 *
 * ALL / ACTIVE / INACTIVE, and no more. An "Updates" filter appears in the mockups and is
 * NOT here: nothing in `expert-release-summary/3.0` carries predecessor or successor
 * information, so an update badge could only be inferred from two versions existing --
 * exactly the derived claim this app refuses to make. The detail response does carry
 * `predecessor_release_id`, but a catalog-level filter cannot be built from a field only
 * the detail route returns.
 */
enum class ExpertFilter { ALL, ACTIVE, INACTIVE }

fun ExpertFilter.label(): String = when (this) {
    ExpertFilter.ALL -> "All"
    ExpertFilter.ACTIVE -> "Active"
    ExpertFilter.INACTIVE -> "Inactive"
}

fun ExpertFilter.matches(expert: ExpertSummary): Boolean = when (this) {
    ExpertFilter.ALL -> true
    ExpertFilter.ACTIVE -> expert.lifecycle == ExpertLifecycle.MOUNTED
    ExpertFilter.INACTIVE -> expert.lifecycle != ExpertLifecycle.MOUNTED
}

/**
 * What the expert library can be showing.
 *
 * [Incompatible] is split out from [Refused] deliberately. Both mean "the reply was not
 * read", but only one is fixed by updating software, and a user told "something went
 * wrong" cannot tell which. That is the same separation that keeps version problems out
 * of the pairing vocabulary.
 */
sealed interface ExpertLibraryUiState {

    data object Loading : ExpertLibraryUiState

    /** No capability to discover experts. Carries reason and required capability. */
    data class Unavailable(val capability: CapabilityState.Unavailable) : ExpertLibraryUiState

    /** Discovery worked and there is genuinely nothing mounted. */
    data object Empty : ExpertLibraryUiState

    /** This build and that Harness do not share a contract. Only a release fixes it. */
    data class Incompatible(val detail: String) : ExpertLibraryUiState

    /** The reply was refused for some other reason. Carries the refusal's own words. */
    data class Refused(val detail: String) : ExpertLibraryUiState

    data class Ready(val experts: List<ExpertSummary>) : ExpertLibraryUiState
}

/** The same shape for one release's detail surface. */
sealed interface ExpertDetailUiState {
    data object Loading : ExpertDetailUiState
    data class Unavailable(val capability: CapabilityState.Unavailable) : ExpertDetailUiState
    data class NotFound(val releaseId: String) : ExpertDetailUiState
    data class Incompatible(val detail: String) : ExpertDetailUiState
    data class Refused(val detail: String) : ExpertDetailUiState
    data class Ready(val expert: ExpertDetail) : ExpertDetailUiState
}

/**
 * The words for each lifecycle state, and a glyph to go with them.
 *
 * Paired here rather than at the call site so no surface can render one without the other
 * -- `status: text-and-icon-never-color-only` is a rule broken by convenience, one badge
 * at a time.
 */
fun ExpertLifecycle.label(): String = when (this) {
    ExpertLifecycle.VERIFIED_UNINSTALLED -> "Verified, not installed"
    ExpertLifecycle.INSTALLED_INACTIVE -> "Installed, inactive"
    ExpertLifecycle.MOUNTED -> "Active"
    ExpertLifecycle.UPDATE_AVAILABLE -> "Update available"
    ExpertLifecycle.TRUST_REVOKED -> "Trust revoked"
    ExpertLifecycle.INCOMPATIBLE -> "Incompatible"
    ExpertLifecycle.ROLLBACK_AVAILABLE -> "Rollback available"
}

/**
 * Local search over name and identity.
 *
 * NO QUERY LEAVES THE DEVICE. This filters a list already in memory; the Harness is not
 * asked, because `expert.catalog.list` takes no search argument and inventing one would
 * be inventing an operation.
 *
 * Matches the display name, namespace, slug and BOTH identities, so a user who has copied
 * a `kf:pack-release:` string from a log can paste it and find the row. Case-insensitive
 * on the name; identities are lowercase hex by contract, so the same fold is harmless.
 */
fun List<ExpertSummary>.search(query: String): List<ExpertSummary> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return filter { expert ->
        expert.name.contains(needle, ignoreCase = true) ||
            expert.namespace.contains(needle, ignoreCase = true) ||
            expert.slug.contains(needle, ignoreCase = true) ||
            expert.packId.contains(needle, ignoreCase = true) ||
            expert.releaseId.contains(needle, ignoreCase = true)
    }
}

/**
 * Shorten identities for a card WITHOUT making two of them ambiguous.
 *
 * A fixed truncation is the obvious approach and is wrong: `kf:pack:aaaa…` and
 * `kf:pack:aaab…` both abbreviate to the same thing at eight characters, and the user is
 * then looking at two rows that appear to name the same pack. Digests exist precisely to
 * distinguish things, so an abbreviation that stops distinguishing them is worse than no
 * abbreviation at all.
 *
 * So the prefix length is COMPUTED over the set actually being displayed: start at
 * [MIN_DIGEST_CHARS] and grow until every identity in the set is unique. The full value
 * is always available on the detail screen, and is what gets copied.
 *
 * @return identity -> abbreviated form, for exactly the ids passed in.
 */
fun abbreviateIdentities(ids: Collection<String>): Map<String, String> {
    val distinct = ids.distinct()
    if (distinct.isEmpty()) return emptyMap()

    val digests = distinct.associateWith { it.substringAfterLast(':') }
    var length = MIN_DIGEST_CHARS
    val longest = digests.values.maxOf { it.length }
    while (length < longest) {
        val prefixes = digests.values.map { it.take(length) }
        if (prefixes.distinct().size == prefixes.size) break
        length++
    }

    return distinct.associateWith { id ->
        val kind = id.substringBeforeLast(':', missingDelimiterValue = "")
        val digest = digests.getValue(id)
        val shortened = if (digest.length > length) digest.take(length) + "…" else digest
        if (kind.isEmpty()) shortened else "$kind:$shortened"
    }
}

/**
 * Twelve hex characters is 48 bits.
 *
 * Not a guess: it is the same order as git's abbreviated object names, which have carried
 * this exact job for two decades at a comparable scale. The uniqueness loop above raises
 * it whenever a real collision appears, so this is a floor rather than a promise.
 */
const val MIN_DIGEST_CHARS = 12
