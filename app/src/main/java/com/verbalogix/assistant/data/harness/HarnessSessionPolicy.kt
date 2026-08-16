package com.verbalogix.assistant.data.harness

/**
 * When to rotate, and what every outcome means.
 *
 * A PURE FUNCTION OF (state, event, now), deliberately. Session lifetimes are the kind
 * of logic that is normally spread across a coroutine, an interceptor and a retry
 * handler, where the interesting cases -- the rotation that hangs past its own expiry,
 * the reply that widens scope -- are reachable only by contriving a server to produce
 * them. Here they are ordinary function calls with ordinary assertions.
 *
 * No clock is read inside. Callers pass `now`, so a test can sit one second either side
 * of a boundary without sleeping.
 */
object HarnessSessionPolicy {

    /**
     * How early to rotate.
     *
     * Refresh is authorised by the token being replaced, so this margin is not a nicety
     * -- it is the entire window in which renewal is possible. It must cover a request
     * on loopback plus the drift between two clocks that are the same clock, so it is
     * generous relative to the work involved and cheap to be wrong about in this
     * direction. Rotating too early costs one request; rotating too late costs the
     * session.
     */
    const val REFRESH_LEAD_SECONDS: Long = 60L

    /**
     * True when a live session should start rotating NOW.
     *
     * Deliberately also true once already past expiry: a caller polling on a slow tick
     * should still be told "act", and [next] is what decides that the window was missed.
     * Splitting "should act" from "what does acting mean" keeps the boundary in one place.
     */
    fun shouldRefresh(state: HarnessSessionState, nowEpochSeconds: Long): Boolean =
        state is HarnessSessionState.Connected &&
            nowEpochSeconds >= state.expiresAtEpochSeconds - REFRESH_LEAD_SECONDS

    /**
     * Whether a granted scope set may be admitted.
     *
     * Fails closed on WIDENING, which is the case worth checking. A session returning
     * fewer scopes than requested is a downgrade the client can notice and live with; a
     * session returning MORE is either a server this client does not understand or a
     * response that was not shaped by the server at all. Neither is a windfall.
     */
    fun admit(granted: Set<HarnessScope>): Boolean =
        granted.isNotEmpty() && granted.all { it in HarnessScope.REQUESTED }

    fun next(
        state: HarnessSessionState,
        event: HarnessSessionEvent,
        nowEpochSeconds: Long,
    ): HarnessSessionState = when (event) {

        HarnessSessionEvent.PairingStarted -> HarnessSessionState.Pairing

        is HarnessSessionEvent.SessionGranted -> when {
            // A session that is already expired, or expired before it could ever be
            // rotated, is not a session. Accepting it would show a connected UI that
            // fails on its first request.
            event.expiresAtEpochSeconds <= nowEpochSeconds ->
                HarnessSessionState.PairAgain(PairAgainCause.EXPIRED)

            !admit(event.scopes) ->
                HarnessSessionState.PairAgain(PairAgainCause.INVALID)

            else -> HarnessSessionState.Connected(event.expiresAtEpochSeconds, event.scopes)
        }

        HarnessSessionEvent.PairingRefused ->
            HarnessSessionState.PairAgain(PairAgainCause.INVALID)

        HarnessSessionEvent.RefreshStarted -> when (state) {
            // Only a live session can rotate, because only a live token carries the
            // authority to. Starting a rotation from anywhere else is a bug in the
            // caller, and answering it with PairAgain keeps the app honest rather than
            // silently doing nothing.
            is HarnessSessionState.Connected ->
                if (nowEpochSeconds >= state.expiresAtEpochSeconds) {
                    // The window closed before we acted. The token can no longer
                    // authorise its own replacement, so there is nothing to attempt.
                    HarnessSessionState.PairAgain(PairAgainCause.EXPIRED)
                } else {
                    HarnessSessionState.Refreshing(state.expiresAtEpochSeconds)
                }

            else -> HarnessSessionState.PairAgain(PairAgainCause.EXPIRED)
        }

        is HarnessSessionEvent.RefreshGranted -> when {
            event.expiresAtEpochSeconds <= nowEpochSeconds ->
                HarnessSessionState.PairAgain(PairAgainCause.EXPIRED)

            !admit(event.scopes) ->
                HarnessSessionState.PairAgain(PairAgainCause.INVALID)

            // ATOMIC REPLACEMENT. The successor is adopted whole or not at all; there is
            // no intermediate state in which both tokens are held, and no path that
            // keeps the outgoing one "just in case". Rotation retired it.
            else -> HarnessSessionState.Connected(event.expiresAtEpochSeconds, event.scopes)
        }

        HarnessSessionEvent.RefreshLost ->
            HarnessSessionState.PairAgain(PairAgainCause.LOST_DURING_ROTATION)

        is HarnessSessionEvent.Rejected -> HarnessSessionState.PairAgain(event.cause)

        is HarnessSessionEvent.Tick -> when (state) {
            is HarnessSessionState.Connected ->
                if (nowEpochSeconds >= state.expiresAtEpochSeconds) {
                    HarnessSessionState.PairAgain(PairAgainCause.EXPIRED)
                } else {
                    state
                }

            // A rotation still in flight past the outgoing token's expiry cannot be
            // completed: any reply arriving now was authorised by a token the server has
            // already retired. Waiting longer only delays the same answer.
            is HarnessSessionState.Refreshing ->
                if (nowEpochSeconds >= state.outgoingExpiresAtEpochSeconds) {
                    HarnessSessionState.PairAgain(PairAgainCause.LOST_DURING_ROTATION)
                } else {
                    state
                }

            else -> state
        }
    }
}

/** Everything that can move a session. */
sealed interface HarnessSessionEvent {

    data object PairingStarted : HarnessSessionEvent

    /** A one-use pairing credential was exchanged successfully. */
    data class SessionGranted(
        val expiresAtEpochSeconds: Long,
        val scopes: Set<HarnessScope>,
    ) : HarnessSessionEvent

    data object PairingRefused : HarnessSessionEvent

    data object RefreshStarted : HarnessSessionEvent

    data class RefreshGranted(
        val expiresAtEpochSeconds: Long,
        val scopes: Set<HarnessScope>,
    ) : HarnessSessionEvent

    /**
     * A rotation whose outcome is unknown -- a dropped connection, a timeout, a reply
     * that could not be read. Distinct from a rotation the server REFUSED, because a
     * refusal tells you the old token still stands and this does not.
     */
    data object RefreshLost : HarnessSessionEvent

    /** The Harness declined a request for a reason that ends the session. */
    data class Rejected(val cause: PairAgainCause) : HarnessSessionEvent

    /** Time passed. Carries no data; `now` is a parameter of the transition. */
    data object Tick : HarnessSessionEvent
}
