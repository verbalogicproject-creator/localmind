package com.verbalogix.assistant.ui.theme

import com.verbalogix.assistant.ui.nav.DestinationsTest.Companion.repoFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closes the gap that transcribing the tokens opened.
 *
 * `AmberTokens` is Kotlin constants rather than a runtime parse of the JSON, which is
 * the right trade -- a token that fails to load would be a theme that renders wrong on
 * someone's device, instead of a build that goes red. The cost of that trade is that
 * the two can drift, and this is what stops them: the committed contract is read on the
 * JVM rung and every value is compared.
 */
class AmberTokensTest {

    private val tokens by lazy {
        val file = repoFile("docs/ui/design-tokens.json")
        assertTrue("design-tokens.json not found at ${file.absolutePath}", file.exists())
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun color(name: String): String =
        tokens["color"]!!.jsonObject[name]!!.jsonPrimitive.content.lowercase()

    /**
     * Compose packs colour as float components; the contract writes `#rrggbb`.
     *
     * Rounded rather than truncated. `(0.957f * 255).toInt()` is 243, not 244, and a
     * truncating conversion would make this test fail on colours that are in fact
     * correct -- which is worse than not having it, because the fix would be to loosen
     * the check.
     */
    private fun hex(value: androidx.compose.ui.graphics.Color): String {
        val r = Math.round(value.red * 255f)
        val g = Math.round(value.green * 255f)
        val b = Math.round(value.blue * 255f)
        return "#%02x%02x%02x".format(r, g, b)
    }

    @Test
    fun `every colour token matches the committed contract`() {
        val pairs = listOf(
            "accent_active" to AmberTokens.accentActive,
            "accent_dim" to AmberTokens.accentDim,
            "accent_primary" to AmberTokens.accentPrimary,
            "background" to AmberTokens.background,
            "canvas" to AmberTokens.canvas,
            "error" to AmberTokens.error,
            "error_container" to AmberTokens.errorContainer,
            "outline" to AmberTokens.outline,
            "outline_variant" to AmberTokens.outlineVariant,
            "surface" to AmberTokens.surface,
            "surface_high" to AmberTokens.surfaceHigh,
            "surface_highest" to AmberTokens.surfaceHighest,
            "surface_low" to AmberTokens.surfaceLow,
            "surface_raised" to AmberTokens.surfaceRaised,
            "text_muted" to AmberTokens.textMuted,
            "text_primary" to AmberTokens.textPrimary,
        )
        for ((name, value) in pairs) {
            assertEquals("colour token $name drifted", color(name), hex(value))
        }
        // Guards the guard: if the JSON were unreadable, `pairs` would still iterate
        // but `color()` would throw rather than silently pass. Asserting the count
        // catches the opposite mistake -- a token added to the contract and never
        // transcribed here.
        assertEquals(
            "a colour was added to the contract and not transcribed",
            pairs.size,
            tokens["color"]!!.jsonObject.size,
        )
    }

    @Test
    fun `density and accessibility values match the contract`() {
        val density = tokens["density"]!!.jsonObject
        assertEquals(8, density["base_unit_px"]!!.jsonPrimitive.content.toInt())
        assertEquals(16, density["mobile_margin_px"]!!.jsonPrimitive.content.toInt())
        assertEquals(24, density["panel_padding_px"]!!.jsonPrimitive.content.toInt())

        assertEquals(8f, AmberTokens.baseUnit.value, 0.001f)
        assertEquals(16f, AmberTokens.mobileMargin.value, 0.001f)
        assertEquals(24f, AmberTokens.panelPadding.value, 0.001f)

        val a11y = tokens["accessibility"]!!.jsonObject
        assertEquals(
            a11y["android_minimum_target_dp"]!!.jsonPrimitive.content.toInt().toFloat(),
            AmberTokens.minTouchTarget.value,
            0.001f,
        )
    }

    @Test
    fun `the contract still names system font families`() {
        // If this ever changes, it is a decision to be made deliberately -- a
        // downloadable-font provider killed a previous app in this lineage at launch,
        // through eleven green builds.
        val typography = tokens["typography"]!!.jsonObject
        assertEquals("FontFamily.Default", typography["android_body"]!!.jsonPrimitive.content)
        assertEquals("FontFamily.Monospace", typography["android_mono"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the visual direction still forbids decorative gradients`() {
        assertEquals(
            "dense-flat-amber-technical-no-decorative-gradients",
            tokens["visual_direction"]!!.jsonPrimitive.content,
        )
    }
}
