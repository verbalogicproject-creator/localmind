package com.verbalogix.assistant.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The approved Amber vocabulary, transcribed from `docs/ui/design-tokens.json`.
 *
 * TRANSCRIBED, NOT PARSED. The JSON is committed beside this file as the contract, and
 * these constants are the Kotlin form of it. Reading it at runtime would mean shipping
 * a parser, an asset and a failure mode, to learn values that cannot change without a
 * recompile anyway -- and a token that fails to load is a theme that renders wrong on a
 * device rather than a build that goes red.
 *
 * The trade this makes is that the two can drift. `AmberTokensTest` closes that: it
 * reads the committed JSON on the JVM rung and asserts every value here matches, so a
 * token changed in the contract and not in the code fails in seconds.
 *
 * Names are the token names, not Material role names. The mapping from these to a
 * ColorScheme happens once, in [LocalmindTheme], where it can be read as a mapping.
 */
internal object AmberTokens {

    // ── Colour ──────────────────────────────────────────────────────────────────
    //
    // Three accents, and they are NOT interchangeable brightness steps. `accentPrimary`
    // is the readable one -- it carries text on a dark surface and clears contrast on
    // its own. `accentActive` is a fill, bright enough to sit behind BLACK text, which
    // is why the approved primary button is dark-on-amber rather than the reverse.

    val accentActive = Color(0xFFF4B942)
    val accentDim = Color(0xFFF8BD45)
    val accentPrimary = Color(0xFFFFD999)

    val background = Color(0xFF131312)
    val canvas = Color(0xFF0E0E0D)

    val error = Color(0xFFFFB4AB)
    val errorContainer = Color(0xFF93000A)

    val outline = Color(0xFF4F4535)
    val outlineVariant = Color(0xFF3B362C)

    // Five surfaces, all within 0x18..0x35. The design direction is "dense flat", so
    // elevation is expressed as a surface STEP and a hairline outline, never as a
    // shadow or a gradient -- see `visual_direction` in the contract.
    val surface = Color(0xFF181714)
    val surfaceLow = Color(0xFF1C1C1A)
    val surfaceRaised = Color(0xFF211F1A)
    val surfaceHigh = Color(0xFF2A2A29)
    val surfaceHighest = Color(0xFF353533)

    val textPrimary = Color(0xFFE5E2E0)
    val textMuted = Color(0xFFD4C4AF)

    // ── Density ─────────────────────────────────────────────────────────────────

    val baseUnit: Dp = 8.dp
    val mobileMargin: Dp = 16.dp
    val panelPadding: Dp = 24.dp

    // ── Radii ───────────────────────────────────────────────────────────────────
    //
    // The contract lists 4, 5, 8, 12 and 14. Five steps is more than this app needs,
    // so the three actually used are named and the other two stay in the JSON rather
    // than being invented into the code.

    val radiusSmall: Dp = 4.dp
    val radiusMedium: Dp = 8.dp
    val radiusLarge: Dp = 12.dp

    // ── Accessibility ───────────────────────────────────────────────────────────

    /**
     * `android_minimum_target_dp` from the contract.
     *
     * This is the floor for anything tappable, and it is a MINIMUM SIZE rather than a
     * padding: a 24dp icon inside 12dp of padding measures 48dp and satisfies it, a
     * 24dp icon with a 48dp visual ring around it does not. Compose enforces 48dp on
     * IconButton by default and on almost nothing else, so custom clickables state it.
     */
    val minTouchTarget: Dp = 48.dp
}
