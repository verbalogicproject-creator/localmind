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
     * EMPTY ON PURPOSE, TODAY. Foundry 0.3.2 is introducing explicit `/3.0` negotiation
     * along with `expert.catalog.list` and `expert.release.inspect`, and the golden
     * responses do not exist yet. Adding `/2.0` here would claim this client can read
     * documents it has never seen a single example of, and the decoders that would have
     * to honour that claim are not written.
     *
     * The consequence is deliberate and correct: every negotiation refuses today, and
     * the app shows an unavailable state with a reason. That is the same answer it gives
     * now, reached honestly. When the schemas and golden payloads land, this set gains
     * entries in the same commit as the decoders that back them -- never before.
     */
    val ACCEPTED: Set<String> = emptySet()

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
