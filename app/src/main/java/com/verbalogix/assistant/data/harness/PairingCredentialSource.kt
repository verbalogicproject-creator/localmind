package com.verbalogix.assistant.data.harness

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a one-use pairing credential comes from.
 *
 * AN INTERFACE BECAUSE THE ANSWER IS NOT SETTLED. The Foundry side is building a
 * Termux→Localmind pairing-launch bridge; until its contract is observed, the only honest
 * implementation is the one below, where a person deliberately hands the credential over.
 * Keeping the seam means that bridge arrives as a second implementation rather than as a
 * rewrite of everything that consumes a session.
 *
 * WHAT NO IMPLEMENTATION MAY DO, and these are exclusions rather than omissions:
 *
 *  - **Read the clipboard.** An app that silently inspects the clipboard for something
 *    token-shaped is reading every copy the user makes, and Android 12+ shows a paste
 *    toast for exactly this reason. The user pastes INTO a field; the app does not reach
 *    out and take it.
 *  - **Read a file, or any path.** A credential on disk outlives the sixty seconds it is
 *    valid for and turns a one-use secret into a stored one.
 *  - **Read process arguments or the environment.** Not available to an Android app in
 *    any supported way, and reaching for one would mean inventing launcher behaviour.
 *  - **Bootstrap itself unauthenticated.** There is no "just ask the Harness nicely"
 *    path. Every route is authenticated, and a client that could mint its own credential
 *    would make the pairing step decorative.
 *  - **Accept a credential from an Intent** until the bridge contract exists and says so.
 *    An exported receiver taking a token from any app on the device is the largest hole
 *    this design could accidentally open.
 */
interface PairingCredentialSource {

    /**
     * Credentials as they are offered, one at a time.
     *
     * A [Flow] rather than a suspending read: pairing is an event the user causes, at a
     * moment they choose, and possibly more than once when a session ends. A pull-based
     * API would have to block on a person.
     */
    fun credentials(): Flow<String>
}

/**
 * The only implementation today: the user pastes a line and presses Pair.
 *
 * OPERATOR-MEDIATED, which is what the Harness itself says it requires -- its refusal
 * text reads "a fresh operator-mediated pairing credential is required". The credential
 * is written once by the Foundry to a file descriptor in Termux, the operator conveys it,
 * and it is valid for about a minute.
 *
 * Holds NOTHING. [offer] pushes the value straight through a rendezvous channel to
 * whoever is collecting; there is no field, no cache and no replay, so a credential that
 * is not consumed immediately is simply gone. That is correct for a single-use secret:
 * the alternative is a buffer holding a live credential for an unbounded time.
 */
@Singleton
class ManualPairingCredentialSource @Inject constructor() : PairingCredentialSource {

    // RENDEZVOUS, not buffered or conflated. A buffered channel would retain the value
    // until collection; a conflated one would retain the LATEST indefinitely. Neither is
    // acceptable for a secret whose whole design is that it is spent once.
    private val offers = Channel<String>(Channel.RENDEZVOUS)

    override fun credentials(): Flow<String> = offers.receiveAsFlow()

    /**
     * Offer a credential the user just entered.
     *
     * Validated for SHAPE before it travels, so an obviously wrong paste fails in front of
     * the user instead of becoming a request the Harness refuses. Shape is all this can
     * check -- whether it is live and unspent is the Harness's answer to give.
     *
     * @return false when the line is not a pairing credential at all.
     */
    suspend fun offer(line: String): Boolean {
        val parsed = HarnessPairing.parsePairingLine(line) ?: return false
        offers.send(parsed)
        return true
    }
}
