package com.verbalogix.assistant.data.harness

import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilitySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the session: pairing, rotation, and what the app is therefore allowed to offer.
 *
 * THE ROTATION LOOP IS THE POINT. The access token is what authorises its own
 * replacement, so renewal is only possible while the session is still valid. There is no
 * act-fail-refresh-retry path anywhere in this class, because by the time a request is
 * refused the credential needed to fix it is already dead. A timer refreshes ahead of
 * expiry; anything that misses that window is a re-pair.
 *
 * SINGLETON, MEMORY ONLY. Nothing here is written to Room, SharedPreferences or a
 * `SavedStateHandle`. Process death is indistinguishable from never having paired, which
 * is the correct behaviour for a credential the Foundry issues for at most 900 seconds
 * and re-keys on every restart.
 */
@Singleton
class HarnessSessionRepository @Inject constructor(
    private val client: HarnessClient,
    private val credentials: PairingCredentialSource,
) : CapabilitySource {

    private val store = HarnessCredentials()
    private val mutex = Mutex()

    private val _session = MutableStateFlow<HarnessSessionState>(HarnessSessionState.NotPaired)
    val session: StateFlow<HarnessSessionState> = _session.asStateFlow()

    private val _capabilities = MutableStateFlow(Capabilities.NONE)

    /**
     * A fresh identity per process, never persisted.
     *
     * Regenerated every launch on purpose: a stored client id would outlive the credential
     * it accompanies and become the one durable thing linking sessions together, in an app
     * whose whole design is to keep nothing.
     */
    private val clientInstanceId = HarnessPairing.newClientInstanceId()

    override fun capabilities(): Flow<Capabilities> = _capabilities.asStateFlow()

    /**
     * Start collecting offered credentials and keep the session rotating.
     *
     * Takes a scope rather than creating one: the caller owns the lifetime, and a
     * repository that spawned its own `GlobalScope` would keep a rotation timer alive
     * past the thing that wanted it.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            credentials.credentials().collect { credential -> pair(credential) }
        }
        scope.launch { rotateForever() }
    }

    private suspend fun pair(credential: String) = mutex.withLock {
        transition(HarnessSessionEvent.PairingStarted)
        when (val exchange = client.exchange(credential, clientInstanceId)) {
            is TokenExchange.Decoded -> adopt(exchange.result)

            is TokenExchange.Rejected -> {
                // A refusal names a cause when the adapter gave a code, and falls back to
                // INVALID rather than guessing -- an unknown code must not become a
                // different, more reassuring story.
                val cause = HarnessErrorCodes.pairAgainCause(exchange.errorCode)
                    ?: PairAgainCause.INVALID
                fail(cause)
            }

            is TokenExchange.Unreachable ->
                // Nothing answered, so no session ended and none was created. Reported as
                // a re-pair because that is the action available, not because the
                // credential was bad.
                fail(PairAgainCause.INVALID)
        }
    }

    /**
     * Adopt a granted token, then -- and only then -- read what it may do.
     *
     * THE ORDER IS THE FIX. Capabilities used to be fetched by the view model right after
     * it offered a credential, and `offer` returns when the RENDEZVOUS RECEIVER TAKES the
     * value, not when the exchange completes. So the read raced the network: it usually
     * ran with no token held at all, got a refusal, and reported Capabilities.NONE over
     * a session that was about to succeed. The screen then showed "unavailable" for a
     * connected Harness until something else happened to re-read it.
     *
     * Doing it here removes the race by construction rather than by timing: there is no
     * moment at which a caller can ask before the token exists, because the only path to
     * the fetch runs through adoption.
     */
    private suspend fun adopt(result: TokenDecodeResult) {
        when (result) {
            is TokenDecodeResult.Granted -> {
                store.replace(result.token)
                transition(
                    HarnessSessionEvent.SessionGranted(result.expiresAtEpochSeconds, result.scopes),
                )
                readCapabilities(result.token.value)
            }
            // Both remaining cases end with no credential held. `Disabled` cannot occur
            // now that the goldens exist, and is handled rather than assumed away.
            is TokenDecodeResult.Refused -> fail(PairAgainCause.INVALID)
            TokenDecodeResult.Disabled -> fail(PairAgainCause.INVALID)
        }
    }

    private fun fail(cause: PairAgainCause) {
        store.clear()
        _capabilities.value = Capabilities.NONE
        _session.value = HarnessSessionState.PairAgain(cause)
    }

    /**
     * The proactive rotation loop.
     *
     * Ticks often relative to the lead window so a device that slept through part of it
     * still notices before expiry. The policy decides everything; this only supplies time.
     */
    private suspend fun rotateForever() {
        while (true) {
            delay(TICK_MILLIS)
            mutex.withLock {
                val now = nowSeconds()
                val current = _session.value
                if (HarnessSessionPolicy.shouldRefresh(current, now)) {
                    rotate(now)
                } else {
                    transition(HarnessSessionEvent.Tick)
                }
            }
        }
    }

    private suspend fun rotate(now: Long) {
        val token = store.peek() ?: return fail(PairAgainCause.EXPIRED)
        transition(HarnessSessionEvent.RefreshStarted)
        if (_session.value !is HarnessSessionState.Refreshing) return

        when (val exchange = client.refresh(token.value, clientInstanceId)) {
            is TokenExchange.Decoded -> adopt(exchange.result)

            is TokenExchange.Rejected -> fail(
                HarnessErrorCodes.pairAgainCause(exchange.errorCode) ?: PairAgainCause.INVALID,
            )

            // UNKNOWN OUTCOME IS TERMINAL. The server may have retired the old token while
            // issuing a successor this client never received, so keeping the old one risks
            // a client that believes it is connected and is not.
            is TokenExchange.Unreachable -> fail(PairAgainCause.LOST_DURING_ROTATION)
        }
    }

    private fun transition(event: HarnessSessionEvent) {
        _session.value = HarnessSessionPolicy.next(_session.value, event, nowSeconds())
    }

    /**
     * Read the Harness's declared operations and translate them into UI gates.
     *
     * PRIVATE, and reached only from [adopt]. It was public and called by the view model,
     * which is what allowed the race described above; making it unreachable from outside
     * is what stops that from being reintroduced.
     *
     * A failure here does NOT end the session: not being able to read capabilities is a
     * different fact from not having authority, and conflating them would send the user
     * to re-pair over a decode problem.
     *
     * Takes the token as a parameter rather than re-reading the store, so it cannot
     * observe a different credential than the one just adopted.
     */
    private suspend fun readCapabilities(bearer: String) {
        when (val outcome = client.capabilities(bearer)) {
            is HarnessOutcome.Decoded ->
                _capabilities.value = HarnessCapabilityMapper.toCapabilities(outcome.value)

            is HarnessOutcome.Unsuccessful, is HarnessOutcome.Refused ->
                _capabilities.value = Capabilities.NONE
        }
    }

    /** The live token, for a caller that is about to make one request with it. */
    internal fun bearer(): String? = store.peek()?.value

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private companion object {
        /**
         * Fifteen seconds.
         *
         * Comfortably inside the 60-second lead so several ticks fall within it, which
         * matters because a doze-throttled tick that lands late must still find the window
         * open. Idle cost is one comparison.
         */
        const val TICK_MILLIS = 15_000L
    }
}
