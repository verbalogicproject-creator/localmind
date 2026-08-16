package com.verbalogix.assistant.data.capability

import com.verbalogix.assistant.di.CapabilityModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that keeps unfinished Foundry features off a user's screen.
 *
 * These assert the DEFAULT, because the default is what ships. A test that constructed
 * a capability source and checked it behaved as constructed would prove nothing about
 * the app; these go through the same Hilt module the application graph uses.
 */
class CapabilitiesTest {

    @Test
    fun `release default denies every capability`() {
        val caps = Capabilities.NONE
        assertTrue(caps.expertLibrary is CapabilityState.Unavailable)
        assertTrue(caps.evidenceQuery is CapabilityState.Unavailable)
        assertTrue(caps.toolProposals is CapabilityState.Unavailable)
    }

    @Test
    fun `every unavailable state carries a reason and a required capability`() {
        // `unavailable-actions-render-reason-and-required-capability` from
        // state-catalog.json. A blank reason would satisfy the type and fail the rule,
        // so the content is asserted rather than the shape.
        val states = listOf(
            Capabilities.NONE.expertLibrary,
            Capabilities.NONE.evidenceQuery,
            Capabilities.NONE.toolProposals,
        )
        for (state in states) {
            val unavailable = state as CapabilityState.Unavailable
            assertTrue(
                "reason must be a sentence, not a placeholder",
                unavailable.reason.length > 20,
            )
            assertFalse(unavailable.requiredCapability.isBlank())
            // A required capability is an operation id to quote, not prose.
            assertFalse(
                "requiredCapability must be an id, not a sentence",
                unavailable.requiredCapability.contains(" "),
            )
        }
    }

    @Test
    fun `the production Hilt binding yields the unavailable source`() {
        val source = CapabilityModule.provideCapabilitySource()
        assertTrue(source is UnavailableCapabilitySource)
        val emitted = runBlocking { source.capabilities().first() }
        assertEquals(Capabilities.NONE, emitted)
    }

    @Test
    fun `the production source stays unavailable however often it is asked`() {
        // Guards against a source that degrades open -- reporting unavailable once and
        // then defaulting to available on a later read.
        val source = UnavailableCapabilitySource()
        repeat(5) {
            val emitted = runBlocking { source.capabilities().first() }
            assertEquals(Capabilities.NONE, emitted)
        }
    }

    /**
     * The isolation claim, stated as a test so it is not merely a comment.
     *
     * Debug fixtures live in `src/debug`, which a release build does not compile, so a
     * leak would be a compile error rather than a runtime state. That guarantee cannot
     * be asserted from a unit test -- unit tests run against the DEBUG classpath, where
     * the fixtures are present by design. What CAN be asserted, and is what actually
     * matters, is that the fixtures are not reachable through the dependency graph: the
     * module hands back the real source even in a debug build.
     */
    @Test
    fun `debug fixtures are not wired into the graph`() {
        val source = CapabilityModule.provideCapabilitySource()
        assertEquals(
            "the graph must be identical in debug and release",
            UnavailableCapabilitySource::class.java,
            source.javaClass,
        )
        assertFalse(
            "no fixture type may appear in the injected graph",
            source.javaClass.name.contains("Fake", ignoreCase = true),
        )
    }
}
