package com.verbalogix.assistant.ui.tools

import com.verbalogix.assistant.data.capability.CapabilityState

/**
 * A governed request to act on something outside this app.
 *
 * NOTHING IN THIS APP CAN CREATE ONE, AND THAT IS THE FEATURE.
 *
 * The constructor is `internal`, so it is reachable from `src/debug` previews and the
 * test source sets and from nowhere a release build compiles. More importantly there is
 * no code path anywhere -- in any source set -- that turns model output into one of
 * these. That is the specific attack this shape exists to refuse: a model can be talked
 * into emitting text that looks exactly like a tool call, so if prose could become a
 * proposal, then prose could become an action, and the approval sheet would be a
 * confirmation dialog for whatever the model was persuaded to write.
 *
 * A proposal therefore has to be ISSUED BY A SERVER, typed, and carry its own preview,
 * expiry and decision identity. None of that contract exists yet -- see
 * `docs/ui/api-bindings.json`, where `governed-tool-proposal-decision-receipt` sits
 * under `planned_not_implemented` -- so the correct number of proposals a shipping
 * build can hold is zero, which is what [NoToolProposalSource] returns.
 *
 * The fields below are the shape such a contract would need, recorded so the gap is
 * legible. They are NOT a claim that the contract is agreed.
 */
data class ToolProposal internal constructor(
    val proposalId: String,
    val sessionId: String,
    /** What is being asked for, in the server's words. Never parsed by this app. */
    val action: String,
    /** The system that would be touched. */
    val target: String,
    /**
     * Exactly what would happen, rendered verbatim and never summarised.
     *
     * `state-catalog.json` requires `protected-actions-require-preview-confirmation-and-receipt`.
     * A preview the client paraphrases is a preview of the client's understanding, not
     * of the action, and the difference is the entire value of showing one.
     */
    val preview: String,
    val requiredPermission: String,
    /**
     * When the server's offer lapses, as epoch millis.
     *
     * An approval without an expiry is a standing authorisation, which is a different
     * and much larger thing than the one-time permission the sheet appears to ask for.
     */
    val expiresAtEpochMillis: Long,
)

/** What a user chose. Recorded as a type so a decision can carry a receipt later. */
enum class ToolDecision { APPROVE_ONCE, DENY }

/**
 * What the approval surface can be showing.
 *
 * [Unavailable] is the only state reachable at runtime today.
 */
sealed interface ToolApprovalState {

    /**
     * No governed tool contract exists. Carries the reason and the capability that
     * would supply it, per `unavailable-actions-render-reason-and-required-capability`.
     */
    data class Unavailable(val reason: String, val requiredCapability: String) : ToolApprovalState

    /**
     * A server-issued proposal is waiting on a decision.
     *
     * Constructible only where [ToolProposal] is, which is previews and tests. No
     * production code produces this value, and the sheet is written so that reaching
     * it still would not execute anything -- see [ToolApprovalSheet].
     */
    data class Awaiting(val proposal: ToolProposal) : ToolApprovalState
}

/**
 * Where proposals would come from.
 *
 * The seam is declared now so the screen is written against an interface rather than
 * against a `TODO`, and so the day a Harness declares the contract, one implementation
 * appears and no screen changes.
 */
interface ToolProposalSource {
    fun stateFor(sessionId: String, proposalId: String): ToolApprovalState
}

/**
 * The production source. Returns [ToolApprovalState.Unavailable] for every input,
 * including inputs that look entirely well-formed.
 *
 * It ignores its arguments on purpose. Looking them up would imply there is somewhere
 * to look, and a future reader might supply that somewhere without also supplying the
 * decision-and-receipt contract that makes approval meaningful.
 */
class NoToolProposalSource : ToolProposalSource {
    override fun stateFor(sessionId: String, proposalId: String): ToolApprovalState {
        val gate = com.verbalogix.assistant.data.capability.Capabilities.NONE.toolProposals
        return ToolApprovalState.Unavailable(
            reason = (gate as? CapabilityState.Unavailable)?.reason
                ?: "Tool approval is unavailable.",
            requiredCapability = (gate as? CapabilityState.Unavailable)?.requiredCapability
                ?: "governed-tool-proposal-decision-receipt",
        )
    }
}
