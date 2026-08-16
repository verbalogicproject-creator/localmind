package com.verbalogix.assistant.ui.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Approve cannot fire, and this is the layer of that guarantee a JVM test can reach.
 *
 * The other two layers are structural and are checked by the compiler and by the
 * instrumented suite: [ToolProposal]'s constructor is `internal` with no production
 * factory, and the navigation graph passes a null decision sink. What is asserted here
 * is the first layer -- that the production source refuses every input, including the
 * well-formed ones, which is the case a reader is most likely to assume works.
 */
class ToolApprovalContractTest {

    private val source = NoToolProposalSource()

    @Test
    fun `production source refuses a well formed request`() {
        val state = source.stateFor(sessionId = "session1", proposalId = "proposal1")
        assertTrue(state is ToolApprovalState.Unavailable)
    }

    @Test
    fun `production source refuses every input shape`() {
        val inputs = listOf(
            "" to "",
            "session1" to "proposal1",
            "../etc" to "passwd",
            "a".repeat(200) to "b".repeat(200),
            "SESSION" to "PROPOSAL",
        )
        for ((session, proposal) in inputs) {
            val state = source.stateFor(session, proposal)
            assertTrue(
                "must never yield a proposal for <$session>/<$proposal>",
                state is ToolApprovalState.Unavailable,
            )
            assertFalse(state is ToolApprovalState.Awaiting)
        }
    }

    @Test
    fun `the refusal names why and what would change it`() {
        val state = source.stateFor("s", "p") as ToolApprovalState.Unavailable
        assertTrue(state.reason.isNotBlank())
        assertTrue(
            "the required capability must be the governed tool contract",
            state.requiredCapability == "governed-tool-proposal-decision-receipt",
        )
    }

    /**
     * The sheet has no execution path.
     *
     * A decision is DATA handed back to the caller, never an effect. This asserts the
     * shape that makes that true: [ToolDecision] is a closed set of two values, so
     * there is no variant carrying a command, a URL or an intent for something
     * downstream to execute.
     */
    @Test
    fun `a decision is data, not an instruction`() {
        val values = ToolDecision.entries.toSet()
        assertTrue(values == setOf(ToolDecision.APPROVE_ONCE, ToolDecision.DENY))
    }

    /**
     * There is no path from model text to a proposal.
     *
     * The specific attack: a model can be talked into emitting text that looks exactly
     * like a tool call. If prose could become a proposal, prose could become an action.
     * [ToolProposalSource] takes two identifier strings and nothing else -- it has no
     * overload accepting message content -- so this asserts the interface surface
     * itself, which is what a future edit would have to widen.
     */
    @Test
    fun `the proposal source accepts identifiers only, never message content`() {
        val methods = ToolProposalSource::class.java.declaredMethods
        assertTrue("expected exactly one entry point", methods.size == 1)
        val method = methods.single()
        assertTrue(method.name == "stateFor")
        assertTrue(
            "stateFor must take exactly two identifier arguments",
            method.parameterTypes.size == 2,
        )
        assertTrue(method.parameterTypes.all { it == String::class.java })
    }
}
