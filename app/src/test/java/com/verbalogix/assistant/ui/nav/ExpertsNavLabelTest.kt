package com.verbalogix.assistant.ui.nav

import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Experts tab explains itself, and names what it is waiting for.
 *
 * WHY THIS EXISTS. The tab shipped as `enabled = expertsAvailable`, directly above a
 * comment claiming the tap "says why". It could not: a disabled NavigationBarItem
 * absorbs the press and does nothing, so the only user-visible difference between "this
 * capability is missing" and "this app is broken" was a greyed label. The tab is now
 * always live and always navigates; the destination renders the reason.
 *
 * These assert the announcement, which is the part a sighted user never sees and a
 * TalkBack user relies on entirely.
 */
class ExpertsNavLabelTest {

    @Test
    fun an_available_capability_announces_only_the_destination() {
        assertEquals("Experts", expertsNavLabel(CapabilityState.Available))
    }

    @Test
    fun an_unavailable_capability_announces_the_reason_and_the_operation() {
        val label = expertsNavLabel(Capabilities.NONE.expertLibrary)

        assertTrue("must say it is unavailable: $label", label.contains("unavailable"))
        // The REASON, verbatim from the capability -- not a paraphrase written here.
        assertTrue(
            "must carry the capability's own reason: $label",
            label.contains("Knowledge Foundry is not connected"),
        )
        // And the operation id, which is what makes the gap greppable when a Harness
        // finally arrives. "Some feature is off" would be useless to everyone.
        assertTrue("must name the operation: $label", label.contains("mount.list"))
    }

    /**
     * The label is DERIVED, not duplicated.
     *
     * If someone edits `Capabilities.NONE` to name a different operation, this label has
     * to follow. A hard-coded "Requires mount.list" would keep passing while the screen
     * behind the tab asked for something else -- two surfaces disagreeing about which
     * capability is missing is worse than neither mentioning it.
     */
    @Test
    fun the_announcement_tracks_the_capability_rather_than_restating_it() {
        val invented = CapabilityState.Unavailable(
            reason = "Nothing is mounted.",
            requiredCapability = "mount.somethingElse",
        )
        val label = expertsNavLabel(invented)

        assertTrue("must follow the given reason: $label", label.contains("Nothing is mounted."))
        assertTrue("must follow the given id: $label", label.contains("mount.somethingElse"))
        assertTrue("must not hard-code the default: $label", !label.contains("mount.list"))
    }
}
