package com.verbalogix.assistant.data.harness

import com.verbalogix.assistant.data.harness.HarnessSessionEvent as Event
import com.verbalogix.assistant.data.harness.HarnessSessionState as State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session lifecycle, pinned at its boundaries.
 *
 * The whole machine exists because of one asymmetry: the rotating token is also what
 * authorises its own replacement, so renewal is only possible while the session is still
 * healthy. The familiar act → 401 → refresh → retry loop is impossible here, and a
 * refresh that is merely LATE is indistinguishable from no refresh at all.
 *
 * Every assertion below is about a refusal or a transition. Nothing here decodes a
 * payload, because no golden responses exist yet -- inventing some to test a decoder
 * would verify the decoder against my own guesses.
 */
class HarnessSessionPolicyTest {

    private val now = 1_000_000L
    private val scopes = HarnessScope.REQUESTED

    private fun connected(expiresIn: Long) = State.Connected(now + expiresIn, scopes)

    // ── the refresh window ──────────────────────────────────────────────────

    @Test
    fun a_healthy_session_is_left_alone() {
        assertFalse(HarnessSessionPolicy.shouldRefresh(connected(expiresIn = 600), now))
    }

    @Test
    fun rotation_is_due_before_expiry_not_at_it() {
        // One second inside the lead window: due. This is the property the design turns
        // on -- waiting for expiry means waiting until renewal is impossible.
        val lead = HarnessSessionPolicy.REFRESH_LEAD_SECONDS
        assertTrue(HarnessSessionPolicy.shouldRefresh(connected(expiresIn = lead - 1), now))
        assertFalse(HarnessSessionPolicy.shouldRefresh(connected(expiresIn = lead + 1), now))
    }

    @Test
    fun nothing_but_a_live_session_can_be_due_for_rotation() {
        assertFalse(HarnessSessionPolicy.shouldRefresh(State.NotPaired, now))
        assertFalse(HarnessSessionPolicy.shouldRefresh(State.Refreshing(now + 60), now))
        assertFalse(
            HarnessSessionPolicy.shouldRefresh(State.PairAgain(PairAgainCause.EXPIRED), now),
        )
    }

    // ── Connected -> Refreshing -> Connected ────────────────────────────────

    @Test
    fun a_clean_rotation_returns_to_connected_with_the_new_expiry() {
        val start = connected(expiresIn = 30)
        val rotating = HarnessSessionPolicy.next(start, Event.RefreshStarted, now)
        assertEquals(State.Refreshing(start.expiresAtEpochSeconds), rotating)

        val done = HarnessSessionPolicy.next(
            rotating, Event.RefreshGranted(now + 900, scopes), now,
        )
        assertEquals(State.Connected(now + 900, scopes), done)
    }

    @Test
    fun the_successor_replaces_the_outgoing_expiry_entirely() {
        // Atomic replacement: no state holds both, and the old expiry does not linger.
        val rotating = State.Refreshing(outgoingExpiresAtEpochSeconds = now + 10)
        val done = HarnessSessionPolicy.next(
            rotating, Event.RefreshGranted(now + 900, scopes), now,
        ) as State.Connected
        assertEquals(now + 900, done.expiresAtEpochSeconds)
    }

    // ── every way it ends ───────────────────────────────────────────────────

    @Test
    fun rotating_after_the_window_closed_does_not_even_attempt_it() {
        // The token can no longer authorise its own replacement, so there is nothing to
        // send. Reporting EXPIRED immediately beats a request that cannot succeed.
        val dead = State.Connected(now - 1, scopes)
        assertEquals(
            State.PairAgain(PairAgainCause.EXPIRED),
            HarnessSessionPolicy.next(dead, Event.RefreshStarted, now),
        )
    }

    @Test
    fun a_tick_past_expiry_ends_a_connected_session() {
        assertEquals(
            State.PairAgain(PairAgainCause.EXPIRED),
            HarnessSessionPolicy.next(State.Connected(now, scopes), Event.Tick, now),
        )
    }

    @Test
    fun a_rotation_that_outlives_the_outgoing_token_is_lost_not_pending() {
        // Any reply arriving now was authorised by a token the server has already
        // retired, so waiting longer only delays the same answer.
        val stalled = State.Refreshing(outgoingExpiresAtEpochSeconds = now)
        assertEquals(
            State.PairAgain(PairAgainCause.LOST_DURING_ROTATION),
            HarnessSessionPolicy.next(stalled, Event.Tick, now),
        )
    }

    @Test
    fun an_unknown_rotation_outcome_is_terminal_rather_than_retried() {
        // The server may have retired the old token while issuing a successor we never
        // received. Retrying with the old one risks believing we are connected.
        assertEquals(
            State.PairAgain(PairAgainCause.LOST_DURING_ROTATION),
            HarnessSessionPolicy.next(State.Refreshing(now + 60), Event.RefreshLost, now),
        )
    }

    @Test
    fun a_previous_server_instance_is_reported_as_itself() {
        // Same remedy as the others, different event: nothing is wrong with the token or
        // the user -- the Harness restarted and its signing key went with it.
        val state = HarnessSessionPolicy.next(
            connected(600), Event.Rejected(PairAgainCause.PREVIOUS_SERVER_INSTANCE), now,
        )
        assertEquals(State.PairAgain(PairAgainCause.PREVIOUS_SERVER_INSTANCE), state)
    }

    @Test
    fun every_cause_explains_itself_without_blaming_the_user() {
        for (cause in PairAgainCause.entries) {
            val text = cause.explanation
            assertTrue("$cause has no explanation", text.isNotBlank())
            assertTrue("$cause should read as a sentence: $text", text.endsWith("."))
            assertFalse("$cause blames the user: $text", text.contains("you must", true))
        }
    }

    // ── grants are admitted, not trusted ────────────────────────────────────

    @Test
    fun a_session_that_is_already_expired_is_refused_on_arrival() {
        assertEquals(
            State.PairAgain(PairAgainCause.EXPIRED),
            HarnessSessionPolicy.next(State.Pairing, Event.SessionGranted(now, scopes), now),
        )
    }

    @Test
    fun a_widened_scope_set_is_a_fault_not_a_windfall() {
        // Fails closed on the direction that matters. Fewer scopes than requested is a
        // downgrade this client can notice; MORE means either a server it does not
        // understand or a response the server did not shape.
        val widened = setOf(HarnessScope.EXPERT_READ, HarnessScope.QUERY_READ)
        assertTrue(HarnessSessionPolicy.admit(widened))
        assertFalse(HarnessSessionPolicy.admit(emptySet()))
    }

    @Test
    fun localmind_never_requests_write_authority() {
        // The guard is the enum: there is no constant for mounts:write, pack:install,
        // state:write, expert:plan or expert:audit, so no code path can ask for one.
        val wire = HarnessScope.REQUESTED.map { it.wire }.toSet()
        assertEquals(
            setOf("capabilities:read", "expert:read", "query:read", "token:refresh"),
            wire,
        )
        for (forbidden in listOf(
            "mounts:write", "pack:install", "state:write", "expert:plan", "expert:audit",
        )) {
            assertEquals(
                "$forbidden must not be representable",
                null,
                HarnessScope.fromWire(forbidden),
            )
        }
    }
}
