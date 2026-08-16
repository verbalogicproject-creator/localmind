package com.verbalogix.assistant.data.harness

/**
 * Which payload versions this client will accept, and what happens to everything else.
 *
 * FAIL CLOSED, AND THE DIRECTION MATTERS. The dangerous case is not a payload this
 * client rejects; it is one it half-understands. A `mount-registry/2.0` reader pointed
 * at a `/3.0` document would find the fields it knows, ignore the ones it does not, and
 * render a confident screen built from a document whose meaning it does not share. Trust
 * state and compatibility are exactly the fields where a silent partial read produces a
 * safe-looking answer that is wrong.
 *
 * So an unrecognised `schema` is refused outright rather than parsed leniently, and the
 * refusal names the version it saw.
 *
 * WHY THIS IS SEPARATE FROM PAIRING. A version mismatch is NOT recoverable by pairing
 * again, and presenting it as though it were would put the user in a loop: re-pair,
 * fail, re-pair. One of these is fixed by the user, the other only by shipping software.
 * [SchemaVerdict] keeps them apart -- see [HarnessSessionPolicy] for the other half.
 */
object SchemaNegotiation {

    /**
     * The payload versions this build understands.
     *
     * ONE ENTRY PER (CLOSED SCHEMA + STRICT DECODER + SERVER-EMITTED GOLDEN). All three
     * are required, and the third is the one that is tempting to skip. A decoder verified
     * only against payloads its author invented is verified against its author's
     * assumptions -- this project has already paid for that lesson in a different form.
     *
     * `expert-release-detail/3.0` is therefore ABSENT despite having a closed schema and
     * a transcribable shape: no server-emitted detail response exists yet. Expert Detail
     * consequently refuses, with a reason, which is the honest state rather than a
     * limitation to work around. See [SchemaIds.EXPERT_RELEASE_DETAIL].
     *
     * The catalog entry is admitted on the strength of an EMPTY golden. That verifies the
     * envelope, the negotiation, the operation correlation and the catalog frame -- but
     * not one release summary, because the golden contains none. A populated Expert
     * Library is not verified by anything here and must not be claimed.
     */
    val ACCEPTED: Set<String> = setOf(
        SchemaIds.OPERATION_RESPONSE,
        SchemaIds.CAPABILITIES,
        SchemaIds.EXPERT_CATALOG,
    )

    /** The `schema` field's shape, checked before its value is compared. */
    private val WELL_FORMED = Regex("""^[a-z0-9-]+/[0-9]+\.[0-9]+$""")

    fun negotiate(declared: String?): SchemaVerdict = when {
        // A document with no `schema` is not an old document, it is an unidentified one.
        // Every Foundry schema makes `schema` required, so its absence means this is not
        // a Foundry payload at all -- or not one that survived whatever produced it.
        declared.isNullOrBlank() -> SchemaVerdict.Undeclared

        !WELL_FORMED.matches(declared) -> SchemaVerdict.Malformed(declared)

        declared in ACCEPTED -> SchemaVerdict.Accepted(declared)

        else -> SchemaVerdict.Unsupported(declared)
    }
}

/**
 * The outcome of inspecting a payload's declared version.
 *
 * Four cases rather than a boolean, because the three failures are distinguishable and a
 * user-facing message that cannot tell "you are talking to something that is not the
 * Harness" from "this build is too old" is not worth showing.
 */
sealed interface SchemaVerdict {

    /** Understood, and safe to decode. */
    data class Accepted(val schema: String) : SchemaVerdict

    /** A version this build has no decoder for. Fixed by updating software, not by pairing. */
    data class Unsupported(val schema: String) : SchemaVerdict

    /** Present but not a schema identifier. Suggests the response is not what it claims. */
    data class Malformed(val declared: String) : SchemaVerdict

    /** No `schema` field at all. Every Foundry payload requires one. */
    data object Undeclared : SchemaVerdict

    val isAccepted: Boolean get() = this is Accepted

    /**
     * Written for a person, and never blaming them: none of these is a user error, and
     * none is fixed by pairing again. Kept short enough to sit under a heading.
     */
    val reason: String
        get() = when (this) {
            is Accepted -> "Understood."
            is Unsupported ->
                "This version of Localmind cannot read $schema. Update the app, or the " +
                    "Knowledge Foundry, so the two agree."
            is Malformed ->
                "The reply declared \"$declared\", which is not a Knowledge Foundry " +
                    "payload version. Nothing was read from it."
            Undeclared ->
                "The reply did not say which payload version it is. Nothing was read " +
                    "from it."
        }
}
