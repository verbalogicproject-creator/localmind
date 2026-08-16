package com.verbalogix.assistant.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.verbalogix.assistant.data.Provider
import com.verbalogix.assistant.data.ServerStatus
import com.verbalogix.assistant.ui.providers.ModelsProvidersScreen
import com.verbalogix.assistant.ui.providers.TAG_ADD_ENDPOINT
import com.verbalogix.assistant.ui.providers.TAG_MODELS_PROVIDERS
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The provider surface after the move out of ChatScreen.
 *
 * The behaviour being protected is not new. Selection, seeding, delete protection and
 * URL validation all still live in `ProviderRepository` and `EndpointUrl`; what these
 * assert is that the new screen still REACHES them the same way, and in particular that
 * delete protection survived the move -- a seeded row that could be deleted would come
 * back on next launch, which is worse than no delete at all.
 */
class ModelsProvidersTest {

    @get:Rule val compose = createComposeRule()

    private val seeded = Provider(1, "LFM2.5 8B", "http://127.0.0.1:8090", model = "lfm-8b")
    private val userAdded = Provider(2, "Laptop", "https://box.local:8080", model = "")

    private fun screen(
        providers: List<Provider> = listOf(seeded, userAdded),
        active: Provider? = seeded,
        status: ServerStatus = ServerStatus(reachable = true, model = "lfm-8b"),
        onSelect: (Long) -> Unit = {},
        onSave: (Long?, String, String, String) -> Unit = { _, _, _, _ -> },
        onDelete: (Provider) -> Unit = {},
        isDefault: (Provider) -> Boolean = { it.id == 1L },
    ) {
        compose.setContent {
            LocalmindTheme(darkTheme = true) {
                ModelsProvidersScreen(
                    providers = providers,
                    provider = active,
                    status = status,
                    onSelectProvider = onSelect,
                    onSaveEndpoint = onSave,
                    onDeleteEndpoint = onDelete,
                    isDefaultProvider = isDefault,
                    onRetryStatus = {},
                )
            }
        }
    }

    @Test
    fun every_provider_is_listed_with_its_url() {
        screen()
        // The seeded provider is named TWICE, and that is correct: once by the panel
        // reporting the ACTIVE endpoint, once as its row in the list. `onNodeWithText`
        // demands exactly one match, so this assertion could only ever fail -- it was
        // asserting an incidental node count, not the fact the test is named for.
        compose.onAllNodesWithText("LFM2.5 8B").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Laptop").assertIsDisplayed()
        // The URL is visible, not hidden behind the name -- when two providers point at
        // the same port this is the only place the difference shows.
        compose.onNodeWithText("127.0.0.1:8090").assertIsDisplayed()
    }

    @Test
    fun selecting_a_provider_reports_its_id() {
        var selected: Long? = null
        screen(onSelect = { selected = it })
        compose.onNodeWithText("Laptop").performClick()
        assertEquals(2L, selected)
    }

    @Test
    fun a_seeded_provider_is_marked_as_undeletable() {
        screen()
        compose.onAllNodesWithText("cannot be deleted", substring = true).assertCountEquals(1)
    }

    /** Delete protection: the affordance is not even offered for a seeded row. */
    @Test
    fun the_editor_offers_no_delete_for_a_seeded_provider() {
        var deleted: Provider? = null
        screen(onDelete = { deleted = it })
        // "Edit <name>" is a contentDescription; the button's visible text is just
        // "Edit". `onNodeWithText` does not read contentDescription, so the original
        // form could never resolve a node and never reached the assertion below.
        compose.onNodeWithContentDescription("Edit LFM2.5 8B").performClick()
        compose.onAllNodesWithText("Delete").assertCountEquals(0)
        assertNull(deleted)
    }

    @Test
    fun the_editor_offers_delete_for_a_user_added_provider() {
        screen()
        compose.onNodeWithContentDescription("Edit Laptop").performClick()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun editing_persists_the_normalised_url() {
        var savedUrl: String? = null
        var savedId: Long? = null
        screen(onSave = { id, _, url, _ -> savedId = id; savedUrl = url })

        compose.onNodeWithContentDescription("Edit Laptop").performClick()
        // Address the FIELD by its label, not by its current value. The URL is on screen
        // twice once the editor opens -- in the list row behind the dialog, and in the
        // dialog's own text field -- so matching the value finds two nodes and refuses
        // to type into either. The label is unambiguous and does not change as the user
        // edits, which is the more durable handle regardless.
        compose.onNodeWithText("Base URL").performTextClearance()
        compose.onNodeWithText("Base URL").performTextInput("https://box.local:9000/")
        compose.onNodeWithText("Save").performClick()

        // The trailing slash is stripped by EndpointUrl before it is stored: the client
        // appends its own path, so "…/" would request "//v1/models".
        assertEquals("https://box.local:9000", savedUrl)
        assertEquals(2L, savedId)
    }

    @Test
    fun a_cleartext_lan_address_is_refused_with_the_reason() {
        screen()
        compose.onNodeWithTag(TAG_ADD_ENDPOINT).performClick()
        compose.onNodeWithText("Base URL").performTextClearance()
        compose.onNodeWithText("Base URL").performTextInput("http://192.168.1.5:8080")
        // The platform blocks this and the failure is indistinguishable from a server
        // being down, so the dialog has to say which it is.
        //
        // MATCH THE HOST, NOT THE PHRASE. This screen carries permanent help text
        // reading "...Android blocks plain http to anything but this device", so the
        // bare substring is on screen before anything is typed. The original assertion
        // matched that help text and would have passed against a validator that did
        // nothing at all -- it failed here only because the real verdict pushed the
        // count from 1 to 2. Naming the host is what proves the verdict was computed.
        compose.onAllNodesWithText("Android blocks plain http to 192.168.1.5", substring = true)
            .assertCountEquals(1)
        // The other half of the refusal: it offers the fix, rather than only the reason.
        compose.onNodeWithText("Use https instead").assertIsDisplayed()
    }

    /**
     * Mode and routing are separate facts and both are shown.
     *
     * Observed on a device: the seeded `:8090` endpoints are `mode direct` *and*
     * swap-routed, so a panel printing only the mode said "mode direct" under a
     * llama-swap proxy and read as simply wrong. It was not wrong — it was one of two
     * true things, which is worse, because there is nothing on screen to suggest the
     * other exists.
     */
    @Test
    fun a_swap_routed_endpoint_says_so_as_well_as_its_mode() {
        // model non-empty == the request names a model for a proxy to start.
        screen(active = seeded.copy(model = "lfm-8b"))
        compose.onAllNodesWithText("routed by model name", substring = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText("mode direct", substring = true).assertCountEquals(1)
    }

    @Test
    fun a_plain_server_shows_its_mode_and_claims_no_routing() {
        // Empty model == "whatever this server has loaded", which is how a bare
        // llama-server works. Claiming routing here would be the inverse lie.
        screen(active = seeded.copy(model = ""))
        compose.onAllNodesWithText("routed by model name", substring = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("mode direct", substring = true).assertCountEquals(1)
    }

    @Test
    fun an_unreachable_endpoint_offers_a_retry() {
        screen(status = ServerStatus(reachable = false, error = "connection refused"))
        compose.onNodeWithText("connection refused").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun no_cloud_provider_or_credential_affordance_exists() {
        screen()
        compose.onAllNodesWithText("API key", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Cloud", substring = true).assertCountEquals(0)
    }

    @Test
    fun reports_only_what_the_server_said() {
        // No RAM, no accelerator, no "device optimized" -- all present in the mock and
        // measured nowhere.
        screen(status = ServerStatus(reachable = true, model = "lfm-8b"))
        compose.onAllNodesWithText("RAM", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Hexagon", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("NPU", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("optimized", substring = true).assertCountEquals(0)
        // context is absent because the server did not report one
        compose.onAllNodesWithText("context", substring = true).assertCountEquals(0)
    }

    @Test
    fun survives_process_recreation() {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            LocalmindTheme(darkTheme = true) {
                ModelsProvidersScreen(
                    providers = listOf(seeded, userAdded),
                    provider = seeded,
                    status = ServerStatus(reachable = true, model = "lfm-8b"),
                    onSelectProvider = {},
                    onSaveEndpoint = { _, _, _, _ -> },
                    onDeleteEndpoint = {},
                    isDefaultProvider = { it.id == 1L },
                    onRetryStatus = {},
                )
            }
        }
        compose.onNodeWithText("Laptop").assertIsDisplayed()
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(TAG_MODELS_PROVIDERS).assertIsDisplayed()
        compose.onNodeWithText("Laptop").assertIsDisplayed()
    }
}
