package com.verbalogix.assistant.data.harness

/**
 * The Localmind ↔ Harness session, as a state machine.
 *
 * THE ONE FACT THE WHOLE DESIGN TURNS ON: the rotating access token is ALSO what
 * authorises its own replacement. It carries `token:refresh`, so refresh is only
 * possible while the token is still valid. An expired token cannot renew itself.
 *
 * That inverts the ordinary shape. The familiar pattern -- act, get 401, refresh, retry
 * -- cannot work here, because by the time a 401 arrives the credential needed to fix it
 * is already dead. Refresh must therefore be PROACTIVE, fired on a timer while the
 * session is healthy, and a refresh that is merely late is a re-pair.
 *
 *   Connected --(nearing expiry)--> Refreshing --(new token)--> Connected
 *                                        |
 *                                        +--(anything else)--> PairAgain
 *
 * Everything that is not a clean rotation lands in [PairAgain], which is a RECOVERABLE
 * state: the user re-pairs and continues. It is not an error dialog and not a dead end.
 *
 * NOTHING HERE IS PERSISTED. The pairing credential is one-use and the access token is
 * memory-only, so process death is indistinguishable from never having paired -- which
 * is the correct behaviour and not a limitation to work around. See [HarnessCredentials].
 */
sealed interface HarnessSessionState {

    /** No pairing has been performed in this process. The resting state at launch. */
    data object NotPaired : HarnessSessionState

    /** A one-use pairing credential is being exchanged for a session. */
    data object Pairing : HarnessSessionState

    /**
     * A live session.
     *
     * @param expiresAtEpochSeconds when the token stops being accepted. Refresh must
     *   complete BEFORE this, not after it.
     * @param scopes what this session actually carries, as granted -- not as requested.
     */
    data class Connected(
        val expiresAtEpochSeconds: Long,
        val scopes: Set<HarnessScope>,
    ) : HarnessSessionState

    /**
     * A rotation is in flight.
     *
     * Carries the outgoing expiry so a rotation that hangs past it can be recognised as
     * lost rather than waited on forever: once the old token expires, no reply to this
     * request can be acted on, because the server may already have retired it.
     */
    data class Refreshing(val outgoingExpiresAtEpochSeconds: Long) : HarnessSessionState

    /**
     * Recoverable. The user pairs again from Termux and carries on.
     *
     * The cause is kept because the sentence shown differs, and because "your session
     * ended" and "this app is talking to a Harness that restarted" are different events
     * even though the remedy is the same.
     */
    data class PairAgain(val cause: PairAgainCause) : HarnessSessionState
}

/**
 * Why a session ended.
 *
 * Every one of these is answered by pairing again. They are distinguished because a
 * message that cannot tell a restarted Harness from a revoked session teaches the user
 * nothing about whether it will happen again.
 */
enum class PairAgainCause {

    /** The window was missed: the token expired before a rotation completed. */
    EXPIRED,

    /** The Harness withdrew this session deliberately. */
    REVOKED,

    /** The token was rejected as malformed or unsigned. */
    INVALID,

    /**
     * A rotation started and its outcome is unknown.
     *
     * Treated as terminal ON PURPOSE. The server may have retired the old token while
     * issuing a successor this client never received, so the old one cannot be assumed
     * usable. Retrying with it risks a client that believes it is connected and is not.
     */
    LOST_DURING_ROTATION,

    /**
     * The token was issued by a previous Harness process.
     *
     * The signing key is regenerated per server instance, so a restart invalidates every
     * outstanding session. Nothing is wrong with the token or the user; the other end is
     * simply no longer the process that issued it.
     */
    PREVIOUS_SERVER_INSTANCE,
    ;

    /** Shown verbatim, so written as a sentence, and never blaming the user. */
    val explanation: String
        get() = when (this) {
            EXPIRED -> "The session expired before it could be renewed."
            REVOKED -> "The Knowledge Foundry ended this session."
            INVALID -> "The session was refused."
            LOST_DURING_ROTATION ->
                "A session renewal did not complete, so the old session can no longer " +
                    "be trusted."
            PREVIOUS_SERVER_INSTANCE ->
                "The Knowledge Foundry restarted, which ends every session it had issued."
        }
}
