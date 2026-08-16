package com.verbalogix.assistant.data.harness

/**
 * Explicit response-schema negotiation for the `/3.0` client path.
 *
 * The Harness defaults to `/2.0` when the header is ABSENT, so silence is not neutral --
 * it selects a version this client has no decoder for. The header is therefore sent on
 * every `/3.0` request, and its absence in a request is a bug rather than a preference.
 *
 * NOT SENT ON TOKEN EXCHANGE OR REFRESH. Those are outside the negotiated surface, and
 * attaching the header there would imply an agreement that does not apply to them.
 */
object HarnessNegotiation {

    const val HEADER = "Knowledge-Foundry-Accept-Schema"

    /** The only value this client ever sends. */
    const val ACCEPT_VALUE = "/3.0"

    /** What the Harness falls back to when nothing is sent. Never requested explicitly. */
    const val LEGACY_DEFAULT = "/2.0"

    /**
     * Whether a header value observed on the wire selects the `/3.0` path.
     *
     * FAILS CLOSED ON EVERY AMBIGUITY, and the interesting cases are the ambiguous ones:
     *
     *  - `"/3.0, /2.0"` -- a comma-combined value is not a preference list here. Content
     *    negotiation habits invite reading it as "3.0 preferred", and a client that did
     *    would proceed under an agreement the server may not have made.
     *  - duplicate headers -- [values] takes a list precisely so the duplicate case is
     *    representable. Two headers means two claims, and there is no rule for choosing.
     *  - `"/2.0"` -- valid, understood, and refused for the expert routes, because those
     *    routes exist only under `/3.0`.
     *  - anything else -- unknown.
     *
     * Whitespace is tolerated because HTTP does; nothing else is.
     */
    fun selectsThreePointZero(values: List<String>): Boolean =
        values.size == 1 && values[0].trim() == ACCEPT_VALUE

    /** Convenience for the single-header case. */
    fun selectsThreePointZero(value: String?): Boolean =
        value != null && selectsThreePointZero(listOf(value))

    /**
     * Responses on the `/3.0` path and every token response must not be stored.
     *
     * A cached capability document would let a client act on authority the Harness has
     * since withdrawn, and a cached token response is a credential written to disk by
     * something other than this app. `no-store` is required rather than preferred: it is
     * the only directive that forbids writing the response at all, where `no-cache`
     * merely requires revalidation.
     */
    fun forbidsStorage(cacheControl: String?): Boolean =
        cacheControl != null &&
            cacheControl.split(',').any { it.trim().equals("no-store", ignoreCase = true) }
}
