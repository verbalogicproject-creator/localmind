package com.verbalogix.assistant.data.capability

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Whether one operation may be offered, and if not, why not.
 *
 * The two states are not a boolean with a message attached. `state-catalog.json` lists
 * `unavailable-actions-render-reason-and-required-capability` as a presentation rule,
 * which means an unavailable action owes the user TWO facts -- what is missing and what
 * would supply it -- and a Boolean can carry neither. Modelling it as a sealed pair
 * makes the reason impossible to forget, because there is no way to construct the
 * unavailable case without writing one.
 */
sealed interface CapabilityState {

    /**
     * The Harness has declared this operation. Nothing in the app can currently produce
     * this value at runtime; see [UnavailableCapabilitySource].
     */
    data object Available : CapabilityState

    /**
     * @param reason shown to the user verbatim, so it is written as a sentence.
     * @param requiredCapability the operation id from `docs/ui/api-bindings.json` that
     *   would have to be declared. Named rather than described, so that when a Harness
     *   does arrive the gap is greppable instead of guessable.
     */
    data class Unavailable(
        val reason: String,
        val requiredCapability: String,
    ) : CapabilityState
}

/**
 * What the Knowledge Foundry Harness has declared this client may do.
 *
 * Three operations, because those are the three surfaces this slice gates. They are
 * separate fields and not one flag: a Harness that serves retrieval but declares no
 * tool contract is a perfectly ordinary state, and collapsing them would either hide
 * evidence that exists or offer tools that do not.
 */
data class Capabilities(
    val expertLibrary: CapabilityState,
    val evidenceQuery: CapabilityState,
    val toolProposals: CapabilityState,
) {
    companion object {

        /**
         * THE RELEASE DEFAULT, and the only value any shipping build can hold today.
         *
         * Every field is unavailable. This is not a placeholder waiting to be flipped
         * to true when the screens look finished -- it is the honest report of a
         * client that has never spoken to a Harness, and it stays this way until a real
         * Foundry client supplies capabilities from
         * `GET /v1/capabilities`. Anything else would be the app claiming a feature it
         * does not have, which is the failure this whole project is organised against.
         */
        val NONE = Capabilities(
            expertLibrary = CapabilityState.Unavailable(
                reason = "Knowledge Foundry is not connected, so no expert packs can be listed.",
                requiredCapability = "mount.list",
            ),
            evidenceQuery = CapabilityState.Unavailable(
                reason = "Knowledge Foundry is not connected, so evidence cannot be re-queried. " +
                    "Evidence already stored with a message is still shown.",
                requiredCapability = "query.retrieve",
            ),
            toolProposals = CapabilityState.Unavailable(
                reason = "Tool approval stays unavailable until the Harness declares a typed " +
                    "proposal, preview, decision and receipt contract.",
                requiredCapability = "governed-tool-proposal-decision-receipt",
            ),
        )
    }
}

/**
 * Where capabilities come from.
 *
 * An interface with exactly one production implementation may look like ceremony. It is
 * the seam: when a Foundry client exists it implements this and nothing else in the app
 * changes, which is the same trade `ProviderRepository` already made for endpoints. It
 * also means the gate can be exercised in a test without a server.
 *
 * `Flow` rather than a plain getter because capabilities are a property of a live
 * connection -- a Harness that goes away must be able to withdraw them, and a screen
 * that read them once at startup would keep offering an action that stopped working.
 */
interface CapabilitySource {
    fun capabilities(): Flow<Capabilities>
}

/**
 * The production source, and the only one bound in `main`.
 *
 * It reports [Capabilities.NONE] forever. There is no branch, no build-type check and
 * no flag to set: a source that could return anything else would have to be written,
 * and the place to write it is a real Foundry client.
 *
 * HOW DEBUG FAKES ARE KEPT OUT OF RELEASE. The fake lives in `src/debug/`, which is not
 * part of the release source set at all, so a release build cannot reference it -- the
 * guarantee is a compile error rather than a runtime check someone can forget to run.
 * That is why this file contains no `BuildConfig.DEBUG` branch: a conditional here
 * would put fake success data in the release binary and rely on a flag to hide it.
 */
class UnavailableCapabilitySource : CapabilitySource {
    override fun capabilities(): Flow<Capabilities> = flowOf(Capabilities.NONE)
}
