package com.verbalogix.assistant.ui.nav

// Declared at file scope, ABOVE the patterns that interpolate them, so the NavHost can
// read the same constant back out of the arguments bundle that the pattern was built
// from. Two spellings of one argument name is the classic navigation bug: the pattern
// declares `messageId`, the lookup asks for `message_id`, and the screen silently
// receives null -- no crash, no warning, just a permanently empty drawer.
const val ARG_MESSAGE_ID = "messageId"
const val ARG_RELEASE_ID = "releaseId"
const val ARG_SESSION_ID = "sessionId"
const val ARG_PROPOSAL_ID = "proposalId"

/**
 * Every destination this app has, named once.
 *
 * The seven routes are transcribed from `docs/ui/route-manifest.json`, the shared
 * contract with Knowledge Studio -- so these strings are not this file's to invent, and
 * `DestinationsTest` reads that JSON on the JVM rung and asserts all seven appear with
 * exactly these paths. A route renamed in the contract but not here fails in seconds
 * rather than as a dead link discovered by a user.
 *
 * WHY CENTRALISED AND NOT INLINE. A route typed at its `composable(...)` declaration
 * and again at every `navigate(...)` call site is one string written twice, and the two
 * forms fail differently: a typo in the declaration is a screen that never renders, a
 * typo in the call is a crash at tap time. Neither is a compile error. Here the pattern
 * and its builder sit together, and the builder is the only sanctioned way to produce a
 * concrete path.
 */
object Destinations {

    // ── Argument-free ───────────────────────────────────────────────────────────

    const val SETUP_READINESS = "setup/readiness"
    const val CHAT = "chat"
    const val EXPERTS = "experts"
    const val MODELS_PROVIDERS = "models-providers"

    // ── Parameterised patterns ──────────────────────────────────────────────────
    //
    // These are the NavHost patterns, braces included. They are never navigated to
    // directly -- the builders below validate first and are the only way in.

    const val EVIDENCE = "chat/message/{$ARG_MESSAGE_ID}/evidence"
    const val EXPERT_DETAIL = "experts/{$ARG_RELEASE_ID}"
    const val TOOL_PROPOSAL = "sessions/{$ARG_SESSION_ID}/tool-proposals/{$ARG_PROPOSAL_ID}"

    /**
     * All seven, in the contract's order. The suite asserts against this list rather
     * than seven loose constants, so a destination added to the app but not to the
     * contract -- or the reverse -- is a failing test rather than a silent divergence.
     */
    val ALL: List<String> = listOf(
        SETUP_READINESS,
        CHAT,
        EVIDENCE,
        EXPERTS,
        EXPERT_DETAIL,
        MODELS_PROVIDERS,
        TOOL_PROPOSAL,
    )

    /**
     * The evidence overlay for one persisted assistant message.
     *
     * Returns null for an id that cannot address a row. Null rather than a thrown
     * exception because the caller is a tap handler: the right response to "this
     * message has no addressable id" is to not offer the affordance, not to crash the
     * transcript the user is currently reading.
     */
    fun evidence(messageId: Long): String? =
        if (RouteArgs.isValidRowId(messageId)) "chat/message/$messageId/evidence" else null

    /**
     * A specific pack version.
     *
     * `version` is carried as an OPAQUE TOKEN and is deliberately never parsed, compared
     * or ordered here. Localmind does not parse or version a `.kpack` -- that verb
     * belongs to Knowledge Foundry (`docs/ui/api-bindings.json`, rule
     * `localmind-never-parses-or-versions-kpacks`). Validation below asks only whether
     * the string is SAFE TO PLACE IN A PATH. Reading semver meaning into it here would
     * be this app quietly taking a verb it does not own.
     */
    fun expertDetail(releaseId: String): String? {
        val id = RouteArgs.releaseIdOrNull(releaseId) ?: return null
        return "experts/$id"
    }

    /**
     * A governed tool proposal.
     *
     * The route exists and validates. Nothing in the app can currently produce a
     * proposal to put in it, and that is the point -- see
     * [com.verbalogix.assistant.ui.tools.ToolApprovalSheet].
     */
    fun toolProposal(sessionId: String, proposalId: String): String? {
        val session = RouteArgs.identifierOrNull(sessionId) ?: return null
        val proposal = RouteArgs.identifierOrNull(proposalId) ?: return null
        return "sessions/$session/tool-proposals/$proposal"
    }
}

/**
 * What a navigation argument is allowed to be.
 *
 * THIS IS A TRUST BOUNDARY, not tidiness. A route argument is attacker-reachable in the
 * general case -- a deep link is a string another app hands you -- and these arguments
 * address persisted rows now and Foundry-side identities later. So the rule is an
 * ALLOW-LIST of what an identifier may contain, never a deny-list of what has caused
 * trouble before; a deny-list is a list of the attacks someone already thought of.
 *
 * Rejecting anything that would need percent-encoding is a deliberate second effect. If
 * no valid identifier can contain `/`, `..`, `%` or a space, then building a path by
 * string interpolation is safe by construction, and there is no encode/decode asymmetry
 * left to get wrong later.
 *
 * No Android imports, so this is testable on the JVM rung. `android.net.Uri` is stubbed
 * in unit tests and every method returns null, which would make these tests pass while
 * asserting nothing -- the same trap [com.verbalogix.assistant.data.EndpointUrl]
 * documents.
 */
object RouteArgs {

    /**
     * Room's `autoGenerate` keys start at 1, and 0 is the unsaved-entity sentinel used
     * throughout `Message`. A non-positive id therefore cannot address a row, and is
     * rejected here rather than passed to a query that would silently return nothing.
     */
    fun isValidRowId(id: Long): Boolean = id > 0L

    /** Parses and validates a row id arriving as a route argument. */
    fun rowIdOrNull(raw: String?): Long? {
        val parsed = raw?.toLongOrNull() ?: return null
        return if (isValidRowId(parsed)) parsed else null
    }

    /**
     * The first character must be alphanumeric, so a token can never begin with `-`,
     * `.` or `_`. That excludes `.` and `..` outright, and anything that would read as
     * a flag if a value ever reached a command line. The length bound is not decoration
     * either: an unbounded identifier inside a path is a denial of service shaped like
     * a URL.
     */
    private val IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    /**
     * A Knowledge Foundry identity, as a route argument.
     *
     * SEPARATE FROM, AND STRICTER THAN, [IDENTIFIER]. A release id is
     * `kf:pack-release:<64 hex>` -- 80 characters containing colons, so it fails the
     * general identifier rule on both length and alphabet. The tempting fix is to relax
     * that rule; this does the opposite and adds an EXACT-SHAPE allow-list, which admits
     * precisely one grammar and nothing else.
     *
     * That is a stronger boundary than the one it sits beside: `identifierOrNull` accepts
     * any bounded alphanumeric token, while this accepts only a literal `kf:` prefix, a
     * lowercase kind, and exactly sixty-four lowercase hex characters. No traversal
     * sequence, no flag-shaped value and no arbitrary string can satisfy it.
     */
    private val KF_IDENTITY = Regex("^kf:[a-z0-9-]{1,32}:[0-9a-f]{64}$")

    /** Validates a `kf:<kind>:<sha256>` identity arriving as, or destined for, a route. */
    fun releaseIdOrNull(raw: String?): String? =
        raw?.takeIf { KF_IDENTITY.matches(it) }

    fun identifierOrNull(raw: String?): String? {
        val text = raw ?: return null
        return if (IDENTIFIER.matches(text)) text else null
    }
}
