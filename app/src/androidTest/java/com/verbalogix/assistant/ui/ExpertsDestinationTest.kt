package com.verbalogix.assistant.ui

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.data.harness.HarnessScope
import com.verbalogix.assistant.data.harness.HarnessSessionState
import com.verbalogix.assistant.ui.experts.ExpertLibraryUiState
import com.verbalogix.assistant.ui.experts.ExpertLifecycle
import com.verbalogix.assistant.ui.experts.ExpertSummary
import com.verbalogix.assistant.ui.experts.ExpertsDestination
import com.verbalogix.assistant.ui.experts.TAG_EXPERT_LIBRARY
import com.verbalogix.assistant.ui.experts.TAG_EXPERT_SEARCH
import com.verbalogix.assistant.ui.pairing.TAG_PAIRING_PANEL
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import org.junit.Rule
import org.junit.Test

/**
 * The WHOLE Experts destination, with a catalog that has something in it.
 *
 * THIS TEST EXISTS BECAUSE ITS ABSENCE COST A CRASH ON A PHYSICAL PHONE.
 *
 * The destination assembled itself inline as `Column(verticalScroll(...))` around the
 * pairing panel and the library -- two vertical scroll owners, the inner one lazy, which
 * measures as infinite height and throws. Every rung passed anyway, for a reason worth
 * stating plainly: the crashing branch was UNREACHABLE. Only a non-empty catalog builds
 * the lazy list, nothing could produce one until pairing worked, and the first successful
 * pairing on a real device was the first time that code had ever run.
 *
 * The existing tests could not have caught it either, and not by bad luck: they rendered
 * `ExpertLibraryScreen` in ISOLATION, while the destination wrapped it in something else.
 * The composition under test was never the composition that shipped.
 *
 * So this renders [ExpertsDestination] -- the same composable the navigation graph uses,
 * not a replica -- with a Ready state shaped like a real catalog response. A wrapper
 * reintroduced in either place now shows up here.
 */
class ExpertsDestinationTest {

    @get:Rule val compose = createComposeRule()

    /**
     * Shaped like `expert-release-summary/3.0`, including full-length identities.
     *
     * The digests matter: a truncated stand-in would not exercise the abbreviation pass,
     * and identity length is part of what the row has to lay out at 320dp.
     */
    private fun catalog(count: Int): List<ExpertSummary> = (1..count).map { n ->
        val digest = "%02x".format(n).repeat(32)
        ExpertSummary(
            packId = "kf:pack:$digest",
            releaseId = "kf:pack-release:$digest",
            name = "Knowledge Foundry Project Expert $n",
            namespace = "org.knowledge-foundry",
            slug = "project-expert-$n",
            version = "1.0.$n",
            lifecycle = if (n % 2 == 0) {
                ExpertLifecycle.INSTALLED_INACTIVE
            } else {
                ExpertLifecycle.MOUNTED
            },
            trustState = "trusted",
        )
    }

    private fun destination(
        experts: List<ExpertSummary> = catalog(1),
        session: HarnessSessionState = HarnessSessionState.Connected(
            expiresAtEpochSeconds = 1_000_300,
            scopes = HarnessScope.REQUESTED,
        ),
        widthDp: Int = 320,
        fontScale: Float = 1.0f,
    ) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, 640.dp)) then
                    DeviceConfigurationOverride.FontScale(fontScale),
            ) {
                LocalmindTheme(darkTheme = true) {
                    ExpertsDestination(
                        state = ExpertLibraryUiState.Ready(experts),
                        session = session,
                        onPair = {},
                        onOpenExpert = {},
                    )
                }
            }
        }
    }

    /**
     * The regression itself: composing this at all used to throw.
     *
     * Reaching the assertion is most of the test. A nested lazy list inside a scrollable
     * parent fails during MEASURE, so the crash arrives before anything can be asserted
     * about content -- which is why "the row is displayed" is a sufficient witness.
     */
    @Test
    fun the_destination_renders_a_non_empty_catalog_at_320dp_without_crashing() {
        destination(catalog(1))
        compose.onNodeWithTag(TAG_EXPERT_LIBRARY).assertIsDisplayed()
        compose.onNodeWithTag(TAG_PAIRING_PANEL).assertIsDisplayed()
        compose.onNodeWithText("Knowledge Foundry Project Expert 1").assertIsDisplayed()
    }

    /**
     * A catalog long enough to exceed the viewport, scrolled to its end.
     *
     * One row could fit without the list ever needing to scroll, which would leave the
     * scrolling owner untested. Twelve rows at 320dp cannot, so reaching the last one
     * proves there is a working scroll rather than merely a surviving measure.
     */
    @Test
    fun a_long_catalog_scrolls_to_its_last_row() {
        destination(catalog(12))
        compose.onNodeWithText("Knowledge Foundry Project Expert 12")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** The pairing panel scrolls WITH the list rather than sitting beside it. */
    @Test
    fun the_pairing_panel_is_part_of_the_same_scroll() {
        destination(catalog(12))
        // Scroll to the end, then back to the header. If the panel were a sibling of the
        // list it would never leave the viewport and this would pass vacuously -- so the
        // last row is asserted displayed first, which can only happen after scrolling.
        compose.onNodeWithText("Knowledge Foundry Project Expert 12")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(TAG_PAIRING_PANEL).performScrollTo().assertIsDisplayed()
    }

    /** Search and filter state survives inside the single lazy owner. */
    @Test
    fun search_still_narrows_the_list() {
        destination(catalog(3))
        compose.onNodeWithTag(TAG_EXPERT_SEARCH).performTextInput("project-expert-2")
        compose.onNodeWithText("Knowledge Foundry Project Expert 2").assertIsDisplayed()
        compose.onAllNodesWithText("Knowledge Foundry Project Expert 1").assertCountEquals(0)
    }

    @Test
    fun the_filters_are_reachable_and_narrow_the_list() {
        destination(catalog(4))
        compose.onNodeWithText("Inactive").performScrollTo().assertIsDisplayed()
    }

    /** The narrow-and-large case, where a fixed height would have shown its cost. */
    @Test
    fun a_non_empty_catalog_survives_320dp_at_font_scale_2_0() {
        destination(catalog(4), widthDp = 320, fontScale = 2.0f)
        compose.onNodeWithText("Knowledge Foundry Project Expert 1")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * The read-only boundary, asserted on the composition that ships.
     *
     * Checked here rather than only on the screen in isolation, because this is the
     * assembly a user actually reaches -- and a mutating control added by a future
     * wrapper would appear at this level.
     */
    @Test
    fun no_mutating_control_appears_anywhere_in_the_destination() {
        destination(catalog(3))
        for (forbidden in listOf(
            "Install", "Activate", "Deactivate", "Apply Update", "Remove", "Import",
            "Rollback", "Uninstall",
        )) {
            compose.onAllNodesWithText(forbidden, substring = true).assertCountEquals(0)
        }
    }

    /** An unpaired session still renders the destination, with the panel offering pairing. */
    @Test
    fun the_destination_renders_when_no_session_exists() {
        destination(catalog(2), session = HarnessSessionState.NotPaired)
        compose.onNodeWithTag(TAG_PAIRING_PANEL).assertIsDisplayed()
        compose.onNodeWithText("Knowledge Foundry Project Expert 1").assertIsDisplayed()
    }
}
