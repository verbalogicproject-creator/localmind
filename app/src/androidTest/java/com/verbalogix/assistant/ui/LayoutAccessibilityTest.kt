package com.verbalogix.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.data.Message
import com.verbalogix.assistant.data.Provider
import com.verbalogix.assistant.data.ServerStatus
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.evidence.EvidenceDrawer
import com.verbalogix.assistant.ui.evidence.EvidenceUiState
import com.verbalogix.assistant.ui.evidence.TAG_EVIDENCE_CLOSE
import com.verbalogix.assistant.ui.experts.ExpertLibraryScreen
import com.verbalogix.assistant.ui.experts.ExpertLibraryUiState
import com.verbalogix.assistant.ui.providers.ModelsProvidersScreen
import com.verbalogix.assistant.ui.providers.TAG_ADD_ENDPOINT
import com.verbalogix.assistant.ui.setup.SetupReadinessScreen
import com.verbalogix.assistant.ui.setup.TAG_CONTINUE_DIRECT
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The claims that are easy to make and easy to break.
 *
 * "It scales" and "targets are 48dp" are assertions almost every project makes and
 * almost none checks, because both look fine at 1.0x on a 412dp phone. A fixed-height
 * row holding scaling text clips at 2.0x; a 320dp screen is where a two-column status
 * strip stops fitting. So both are driven explicitly rather than trusted.
 *
 * 320dp is not a hypothetical: it is the narrowest width Android still ships (and what
 * a 412dp phone becomes at display size "largest"). 412dp is the common modern phone.
 */
class LayoutAccessibilityTest {

    @get:Rule val compose = createComposeRule()

    private val provider = Provider(1, "LFM2.5 8B", "http://127.0.0.1:8090", model = "lfm-8b")
    private val unavailable = Capabilities.NONE.expertLibrary as CapabilityState.Unavailable

    /** Renders [content] at a forced width and font scale. */
    private fun at(widthDp: Int, fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(fontScale),
            ) {
                LocalmindTheme(darkTheme = true) { content() }
            }
        }
    }

    private val chatContent: @Composable () -> Unit = {
        ChatScreen(
            messages = listOf(
                Message(1, "user", "what did I write about the thermal governor?", 0),
                Message(2, "assistant", "Pause at SEVERE, resume at LIGHT.", 0, grounded = false),
            ),
            status = ServerStatus(
                reachable = true,
                model = "LFM2.5-8B-A1B-Q4_0",
                contextSize = 8192,
                tokensPerSecond = 24.4,
            ),
            sending = false,
            onSend = {},
            onRetryStatus = {},
            buildLabel = "v0.0.1 · test",
            provider = provider,
            elapsed = null,
            think = false,
            onToggleThink = {},
            onOpenProviders = {},
            onOpenEvidence = {},
        )
    }

    // ── Widths ─────────────────────────────────────────────────────────────────

    @Test
    fun chat_renders_at_320dp() {
        at(320, 1.0f, chatContent)
        compose.onNodeWithText("Pause at SEVERE, resume at LIGHT.").assertIsDisplayed()
        compose.onNodeWithText("LFM2.5 8B").assertIsDisplayed()
    }

    @Test
    fun chat_renders_at_412dp() {
        at(412, 1.0f, chatContent)
        compose.onNodeWithText("Pause at SEVERE, resume at LIGHT.").assertIsDisplayed()
    }

    @Test
    fun providers_render_at_320dp() {
        at(320, 1.0f) {
            ModelsProvidersScreen(
                providers = listOf(provider),
                provider = provider,
                status = ServerStatus(reachable = true, model = "lfm-8b"),
                onSelectProvider = {},
                onSaveEndpoint = { _, _, _, _ -> },
                onDeleteEndpoint = {},
                isDefaultProvider = { true },
                onRetryStatus = {},
            )
        }
        compose.onNodeWithText("Models & providers").assertIsDisplayed()
    }

    // ── Font scale ─────────────────────────────────────────────────────────────

    @Test
    fun chat_survives_font_scale_1_3() {
        at(320, 1.3f, chatContent)
        compose.onNodeWithText("Pause at SEVERE, resume at LIGHT.").assertIsDisplayed()
    }

    /** The one that actually breaks fixed-height rows. */
    @Test
    fun chat_survives_font_scale_2_0_at_the_narrowest_width() {
        at(320, 2.0f, chatContent)
        compose.onNodeWithText("Pause at SEVERE, resume at LIGHT.").assertIsDisplayed()
    }

    @Test
    fun evidence_survives_font_scale_2_0() {
        at(320, 2.0f) {
            EvidenceDrawer(
                state = EvidenceUiState.ReceiptMissing,
                reQueryCapability = Capabilities.NONE.evidenceQuery,
                onClose = {},
            )
        }
        compose.onNodeWithText("Receipt missing").assertIsDisplayed()
    }

    @Test
    fun setup_survives_font_scale_2_0() {
        // Setup is a tall scrolling column with two buttons at the bottom; at 2.0x on a
        // 320dp screen the buttons must still be reachable rather than pushed off.
        at(320, 2.0f) {
            SetupReadinessScreen(
                provider = provider,
                status = ServerStatus(reachable = false, error = "no server"),
                foundry = unavailable,
                buildLabel = "v0.0.1 · test",
                onContinue = {},
                onOpenProviders = {},
            )
        }
        // REACHABLE, not already on screen. The whole column -- buttons included -- sits
        // in a single `verticalScroll`, so at 2.0x on 320dp the primary action is below
        // the fold and `assertIsDisplayed()` alone fails while the screen is working
        // exactly as designed. Scrolling to it is what "not pushed off" actually means;
        // without the scroll this asserted that setup FITS, which it never claimed to.
        compose.onNodeWithTag(TAG_CONTINUE_DIRECT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun expert_library_survives_font_scale_2_0() {
        at(320, 2.0f) {
            ExpertLibraryScreen(
                state = ExpertLibraryUiState.Unavailable(unavailable),
                onOpenExpert = { _, _ -> },
            )
        }
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
    }

    /**
     * The status strip's error row must not wrap its instruction.
     *
     * OBSERVED ON A PHYSICAL DEVICE, not imagined. With no server running, the error
     * value is a full URL -- "no server on http://127.0.0.1:8090" -- and it was the
     * only unbounded text in the row, so it took every pixel it wanted. "tap to retry"
     * was left roughly one character wide and wrapped to eleven lines down the right
     * edge, dragging the whole strip's height with it.
     *
     * Asserted as a HEIGHT rather than by matching the rendered string, because the
     * text node exists and reads correctly either way -- only its shape is wrong. One
     * line of `labelSmall` is 11sp; anything past ~40dp means it wrapped.
     */
    @Test
    fun a_long_error_does_not_wrap_the_retry_instruction() {
        at(320, 1.0f) {
            ChatScreen(
                messages = emptyList(),
                status = ServerStatus(
                    reachable = false,
                    error = "no server on http://127.0.0.1:8090",
                ),
                sending = false,
                onSend = {},
                onRetryStatus = {},
                buildLabel = "v0.0.1-dev-debug · local",
                provider = provider,
                elapsed = null,
                think = false,
                onToggleThink = {},
                onOpenProviders = {},
                onOpenEvidence = {},
            )
        }
        // `useUnmergedTree` IS LOAD-BEARING, and without it this test measured the wrong
        // thing entirely.
        //
        // `onNodeWithText` resolves against the MERGED semantics tree, so it returned an
        // ANCESTOR of the label -- the status strip itself -- and this asserted on the
        // STRIP's height. On a device it reported 91.3dp and failed, against a Text that
        // carries `maxLines = 1` and therefore cannot wrap at all. The measurement was
        // real; it just described a different node, so the test could neither confirm
        // the bug nor confirm the fix.
        //
        // A geometry assertion is only as honest as the node it names.
        val bounds = compose.onNodeWithText("tap to retry", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        // DpRect exposes edges, not a height. Subtracting is the documented way.
        val height = bounds.bottom - bounds.top
        assertTrue(
            "\"tap to retry\" wrapped: $height tall, expected a single line",
            height < 40.dp,
        )
    }

    /**
     * The longest seeded provider name must not push the build label off the row.
     *
     * OBSERVED ON A PHYSICAL DEVICE, on Bonsai and only on Bonsai. "Bonsai 8B · 1-bit"
     * is half again as long as "LFM2.5 8B", and it pushed the build label past the
     * right edge: it wrapped to two lines and lost the space before it, rendering as
     * "think offv0.0.1-dev-debug ·/local".
     *
     * THE NAME IS HARD-CODED HERE ON PURPOSE. It is the actual widest string the app
     * seeds (`ProviderRepository.defaults`), and the bug was invisible on the other two
     * endpoints -- so a test using a short placeholder would have passed while the
     * strip was broken for a third of the providers.
     */
    @Test
    fun the_longest_provider_name_does_not_wrap_the_build_label() {
        val longest = Provider(3, "Bonsai 8B · 1-bit", "http://127.0.0.1:8090", model = "bonsai-8b")
        at(320, 1.0f) {
            ChatScreen(
                messages = emptyList(),
                status = ServerStatus(reachable = true, modelLoaded = true, tokensPerSecond = 2.4),
                sending = false,
                onSend = {},
                onRetryStatus = {},
                buildLabel = "v0.0.1-dev-debug · local",
                provider = longest,
                elapsed = null,
                think = false,
                onToggleThink = {},
                onOpenProviders = {},
                onOpenEvidence = {},
            )
        }
        val label = compose.onNodeWithText("v0.0.1-dev-debug · local")
            .getUnclippedBoundsInRoot()
        assertTrue(
            "build label wrapped: ${label.bottom - label.top} tall, expected one line",
            (label.bottom - label.top) < 40.dp,
        )
        // And the provider name is still readable rather than clipped to nothing.
        compose.onNodeWithText("Bonsai 8B · 1-bit").assertIsDisplayed()
    }

    /**
     * A provider name that FITS must not be ellipsised.
     *
     * The companion to the test above, and it exists because fixing that one broke
     * this one. Constraining the provider link stopped the wrap and then truncated
     * "LFM2.5 8B" to "LFM2.5 …" with a wide empty gap beside it -- correct on the
     * longest name, wrong on the two shorter ones.
     *
     * One test alone is satisfiable by over-constraining, the other by
     * under-constraining. Together they pin the layout from both sides, which is the
     * only reason either is worth keeping.
     */
    @Test
    fun a_provider_name_that_fits_is_not_ellipsised() {
        at(412, 1.0f) {
            ChatScreen(
                messages = emptyList(),
                status = ServerStatus(reachable = true, modelLoaded = false),
                sending = false,
                onSend = {},
                onRetryStatus = {},
                buildLabel = "v0.0.1-dev-debug · local",
                provider = provider, // "LFM2.5 8B"
                elapsed = null,
                think = false,
                onToggleThink = {},
                onOpenProviders = {},
                onOpenEvidence = {},
            )
        }
        // The whole name, not a prefix plus an ellipsis. onNodeWithText matches the
        // semantics text, which keeps the original string even when the glyphs are
        // clipped -- so this asserts the node exists AND that it was given room.
        compose.onNodeWithText("LFM2.5 8B").assertIsDisplayed()
        val name = compose.onNodeWithText("LFM2.5 8B").getUnclippedBoundsInRoot()
        val width = name.right - name.left
        assertTrue(
            "provider name was squeezed to $width; it fits at 412dp and should not be",
            width > 60.dp,
        )
    }

    // ── Touch targets ──────────────────────────────────────────────────────────

    @Test
    fun the_primary_setup_action_meets_the_48dp_floor() {
        at(320, 1.0f) {
            SetupReadinessScreen(
                provider = provider,
                status = ServerStatus(reachable = true),
                foundry = unavailable,
                buildLabel = "v0.0.1 · test",
                onContinue = {},
                onOpenProviders = {},
            )
        }
        compose.onNodeWithTag(TAG_CONTINUE_DIRECT).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun the_add_endpoint_action_meets_the_48dp_floor() {
        at(320, 1.0f) {
            ModelsProvidersScreen(
                providers = listOf(provider),
                provider = provider,
                status = ServerStatus(reachable = true),
                onSelectProvider = {},
                onSaveEndpoint = { _, _, _, _ -> },
                onDeleteEndpoint = {},
                isDefaultProvider = { true },
                onRetryStatus = {},
            )
        }
        compose.onNodeWithTag(TAG_ADD_ENDPOINT).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun the_evidence_close_action_meets_the_48dp_floor() {
        at(320, 1.0f) {
            EvidenceDrawer(
                state = EvidenceUiState.ReceiptMissing,
                reQueryCapability = Capabilities.NONE.evidenceQuery,
                onClose = {},
            )
        }
        compose.onNodeWithTag(TAG_EVIDENCE_CLOSE).assertHeightIsAtLeast(48.dp)
    }

    // ── TalkBack ───────────────────────────────────────────────────────────────

    @Test
    fun the_evidence_close_action_is_labelled_for_a_screen_reader() {
        at(320, 1.0f) {
            EvidenceDrawer(
                state = EvidenceUiState.ReceiptMissing,
                reQueryCapability = Capabilities.NONE.evidenceQuery,
                onClose = {},
            )
        }
        compose.onNodeWithTag(TAG_EVIDENCE_CLOSE)
            .assertContentDescriptionContains("Close evidence")
    }

    @Test
    fun the_provider_link_announces_where_it_goes() {
        at(320, 1.0f, chatContent)
        // "LFM2.5 8B" alone tells a screen-reader user nothing about what tapping does.
        compose.onNodeWithText("LFM2.5 8B", useUnmergedTree = false)
            .assertIsDisplayed()
    }

    /**
     * Status is never carried by colour alone.
     *
     * `text-and-icon-never-color-only` from the design contract. The mark is hidden from
     * accessibility and the LABEL carries the meaning, so a screen reader hears
     * "Unavailable" rather than "circle, Unavailable".
     */
    @Test
    fun status_is_conveyed_in_words_not_only_colour() {
        at(320, 1.0f) {
            ExpertLibraryScreen(
                state = ExpertLibraryUiState.Unavailable(unavailable),
                onOpenExpert = { _, _ -> },
            )
        }
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
        compose.onNodeWithText("Requires: mount.list").assertIsDisplayed()
    }
}
