package com.verbalogix.assistant.data.harness

/**
 * What each `adapter-error/1.0` code means for the session.
 *
 * THE SPLIT IS THE POINT. Some failures end the session and are fixed by pairing again;
 * others are faults in the exchange that pairing cannot touch. Sending a version mismatch
 * down the "Pair again" path would loop the user through a ritual that cannot help, and
 * sending an expired token down the "refuse" path would strand them with an error where a
 * remedy exists.
 *
 * CODES ARE TRANSCRIBED FROM THE ADAPTER, not guessed: `token-missing`, `token-invalid`,
 * `token-bind-denied`, `token-origin-denied`, `pairing-required`,
 * `adapter-version-unsupported`, `adapter-route-unsupported`, `request-invalid`,
 * `unknown-field`, `http-body-too-large`, `http-content-type-invalid`.
 *
 * AN UNKNOWN CODE IS NOT A RE-PAIR. That is the fail-closed direction and it is worth
 * stating plainly: guessing "pair again" for an unrecognised failure invites an infinite
 * pairing loop against a fault pairing cannot fix, and each attempt burns a one-use
 * credential the operator has to fetch by hand. Unknown means refused, and the code is
 * shown so it can be reported.
 */
object HarnessErrorCodes {

    /** The session is over; a fresh pairing credential restores it. */
    private val ENDS_SESSION = mapOf(
        // The adapter's own words: "a fresh operator-mediated pairing credential is
        // required". The most explicit instruction the server ever gives.
        "pairing-required" to PairAgainCause.INVALID,
        "token-missing" to PairAgainCause.INVALID,
        "token-invalid" to PairAgainCause.INVALID,
        "token-expired" to PairAgainCause.EXPIRED,
        "token-revoked" to PairAgainCause.REVOKED,
        // Bound to a server instance or address that is no longer the one answering.
        // Nothing is wrong with the user or the token; the other end moved.
        "token-bind-denied" to PairAgainCause.PREVIOUS_SERVER_INSTANCE,
        "token-instance-unknown" to PairAgainCause.PREVIOUS_SERVER_INSTANCE,
    )

    /**
     * Faults in how the request was made. Every one is this client's bug or a version
     * disagreement, and none is improved by a new credential.
     *
     * `token-origin-denied` sits here rather than above on purpose: it means an Origin
     * header was sent on a route that forbids one, which is a client defect. Treating it
     * as a session problem would send the user to re-pair while the app kept making the
     * same malformed request.
     */
    private val CLIENT_FAULT = setOf(
        "token-origin-denied",
        "adapter-version-unsupported",
        "adapter-route-unsupported",
        "request-invalid",
        "unknown-field",
        "http-body-too-large",
        "http-content-type-invalid",
    )

    /**
     * Classify a code from an `adapter-error/1.0` payload.
     *
     * @return the cause when the session ended, or null when it did not -- in which case
     *   the caller reports a refusal and leaves the session alone.
     */
    fun pairAgainCause(code: String?): PairAgainCause? = ENDS_SESSION[code]

    fun isClientFault(code: String?): Boolean = code in CLIENT_FAULT

    /**
     * A code neither recognised as ending the session nor as a known client fault.
     *
     * Kept as its own question so the fail-closed choice is explicit at the call site
     * rather than implied by a null.
     */
    fun isUnknown(code: String?): Boolean =
        code != null && code !in ENDS_SESSION && code !in CLIENT_FAULT
}
