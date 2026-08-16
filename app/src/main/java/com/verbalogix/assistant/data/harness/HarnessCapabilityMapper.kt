package com.verbalogix.assistant.data.harness

import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.data.harness.wire.CapabilitiesResult

/**
 * Translates what the Harness DECLARES into what Localmind may OFFER.
 *
 * THESE ARE NOT THE SAME LIST, and the gap is the entire point of this file. The golden
 * capabilities response advertises all fifteen operations -- including `mount.activate`,
 * `mount.rollback`, `pack.install` and `state.initialize`. Every one of them is a thing
 * the Harness can do and a thing this client must never offer, because Localmind holds
 * read-only scopes and activation is a trust decision that belongs in Knowledge Studio.
 *
 * The naive mapping is `operations.contains(x) -> enable x`, and it would have wired an
 * activation button to a screen the corrected `api-bindings.json` explicitly maps to no
 * screen at all (`mount.activate -> screens: []`). So the allowlist below is the
 * authority, and the declared list can only ever SUBTRACT from it.
 */
object HarnessCapabilityMapper {

    /** Operations this client may act on, whatever else the Harness declares. */
    const val OP_EXPERT_CATALOG_LIST = "expert.catalog.list"
    const val OP_EXPERT_RELEASE_INSPECT = "expert.release.inspect"
    const val OP_QUERY_RETRIEVE = "query.retrieve"

    private val CONSUMABLE = setOf(
        OP_EXPERT_CATALOG_LIST, OP_EXPERT_RELEASE_INSPECT, OP_QUERY_RETRIEVE,
    )

    /**
     * The subset of declared operations this client is willing to use.
     *
     * Intersection, never union. An operation absent from [CONSUMABLE] is dropped even
     * when the Harness declares it and even when a token somehow carried authority for
     * it -- two independent refusals for the same thing, which is the correct number for
     * a boundary that grants write access if it fails.
     */
    fun consumable(declared: Collection<String>): Set<String> =
        declared.toSet() intersect CONSUMABLE

    /**
     * Derive the UI gates from a decoded capabilities document.
     *
     * Expert Detail stays gated on `expert.release.inspect` AND on this build having a
     * decoder for the detail payload. The Harness declaring the operation is necessary
     * and not sufficient: without a golden response there is no verified decoder, so
     * offering the screen would promise something that refuses on arrival.
     */
    internal fun toCapabilities(result: CapabilitiesResult): Capabilities {
        val usable = consumable(result.operations)

        val library = if (OP_EXPERT_CATALOG_LIST in usable) {
            CapabilityState.Available
        } else {
            CapabilityState.Unavailable(
                reason = "This Knowledge Foundry does not offer an expert catalog.",
                requiredCapability = OP_EXPERT_CATALOG_LIST,
            )
        }

        val evidence = if (OP_QUERY_RETRIEVE in usable) {
            CapabilityState.Available
        } else {
            CapabilityState.Unavailable(
                reason = "This Knowledge Foundry does not offer evidence retrieval.",
                requiredCapability = OP_QUERY_RETRIEVE,
            )
        }

        return Capabilities(
            expertLibrary = library,
            evidenceQuery = evidence,
            // UNCHANGED AND STILL SHUT. `governed-tool-proposal-decision-receipt` remains
            // planned-not-implemented in api-bindings.json: there is no typed proposal,
            // preview, decision or receipt contract, so there is nothing to gate ON.
            toolProposals = Capabilities.NONE.toolProposals,
        )
    }
}
