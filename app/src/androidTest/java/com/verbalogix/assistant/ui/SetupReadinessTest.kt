package com.verbalogix.assistant.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.verbalogix.assistant.data.Provider
import com.verbalogix.assistant.data.ServerStatus
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.settings.SetupPreferences
import com.verbalogix.assistant.ui.setup.SetupReadinessScreen
import com.verbalogix.assistant.ui.setup.TAG_CONTINUE_DIRECT
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * First run, and the promise that the user is never stuck on it.
 *
 * The load-bearing assertion is [continue_is_enabled_even_with_nothing_reachable].
 * Direct llama.cpp is the only path proven end to end on a device, and it must keep
 * working with no Foundry, no Harness and no packs -- so a readiness screen that gated
 * its exit on readiness would strand exactly the user it was meant to help.
 */
class SetupReadinessTest {

    @get:Rule val compose = createComposeRule()

    private val provider = Provider(1, "LFM2.5 8B", "http://127.0.0.1:8090", model = "lfm-8b")
    private val foundryOff = Capabilities.NONE.expertLibrary

    private fun setup(
        status: ServerStatus,
        onContinue: () -> Unit = {},
    ) {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                SetupReadinessScreen(
                    provider = provider,
                    status = status,
                    foundry = foundryOff,
                    buildLabel = "v0.0.1 · test",
                    onContinue = onContinue,
                    onOpenProviders = {},
                )
            }
        }
    }

    @Test
    fun continue_is_enabled_even_with_nothing_reachable() {
        setup(ServerStatus(reachable = false, error = "no server on 127.0.0.1:8090"))
        // Setup is one scrolling column and the buttons sit at its foot, so on a short
        // viewport the action is below the fold and `assertIsDisplayed` fails while the
        // screen works exactly as designed. Note `assertIsEnabled` passed either way --
        // it does not care about visibility -- so only the second line ever caught this.
        compose.onNodeWithTag(TAG_CONTINUE_DIRECT).assertIsEnabled()
        compose.onNodeWithTag(TAG_CONTINUE_DIRECT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun continuing_reports_once() {
        var continued = 0
        setup(ServerStatus(reachable = false), onContinue = { continued++ })
        // Scroll first. `performClick` on a node below the fold does NOT throw -- it
        // dispatches at a coordinate outside the viewport and nothing happens, so this
        // failed as `expected:<1> but was:<0>`: a silent miss reported as a wrong count,
        // which reads like a broken callback rather than a test that never clicked.
        compose.onNodeWithTag(TAG_CONTINUE_DIRECT).performScrollTo().performClick()
        assertEquals(1, continued)
    }

    @Test
    fun an_unreachable_server_is_reported_without_alarm_and_with_a_way_forward() {
        setup(ServerStatus(reachable = false, error = "no server on 127.0.0.1:8090"))
        compose.onNodeWithText("no server on 127.0.0.1:8090").assertIsDisplayed()
        compose.onAllNodesWithText("you can carry on now", substring = true)
            .assertCountEquals(1)
    }

    /** Nothing is asserted that was not observed. */
    @Test
    fun no_readiness_or_hardware_claim_is_fabricated() {
        setup(ServerStatus(reachable = false))
        for (invented in listOf(
            "Ready offline",
            "System integrity verified",
            "Device optimized",
            "NPU",
            "Hexagon",
            "RAM",
            "8,192",
            "LFM2.5-8B-A1B-Q4_0",
            "Ready locally",
        )) {
            compose.onAllNodesWithText(invented, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun a_reported_model_is_shown_only_when_the_server_reported_it() {
        setup(ServerStatus(reachable = true, model = "lfm-8b", contextSize = 4096))
        compose.onNodeWithText("reports model lfm-8b").assertIsDisplayed()
        compose.onNodeWithText("reports context 4096").assertIsDisplayed()
    }

    @Test
    fun a_missing_model_field_renders_nothing_rather_than_a_placeholder() {
        setup(ServerStatus(reachable = true))
        compose.onAllNodesWithText("reports model", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("reports context", substring = true).assertCountEquals(0)
    }

    @Test
    fun the_foundry_absence_is_stated_with_its_required_capability() {
        setup(ServerStatus(reachable = true))
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onAllNodesWithText("Requires: mount.list", substring = true)
            .assertCountEquals(1)
    }

    /**
     * The only thing setup persists.
     *
     * Deliberately SharedPreferences, not Room: adding a fifth schema version and a
     * migration to record "this person has seen a screen" would risk a user's
     * conversation for a disposable UI flag.
     */
    @Test
    fun setup_completion_is_persisted_and_nothing_else_is() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Start from a known state; this is app-private storage for the test APK's
        // target, so clearing it affects nothing a user owns.
        context.getSharedPreferences("localmind.setup", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        val prefs = SetupPreferences(context)
        assertFalse("a fresh install has not completed setup", prefs.isSetupCompleted())

        prefs.markSetupCompleted()
        assertTrue(prefs.isSetupCompleted())

        // Survives a new instance, which is what "persisted" has to mean.
        assertTrue(SetupPreferences(context).isSetupCompleted())

        val all = context
            .getSharedPreferences("localmind.setup", android.content.Context.MODE_PRIVATE)
            .all
        assertEquals("setup must persist exactly one key", 1, all.size)
        assertTrue(all.containsKey("setup_completed"))
    }
}
