package com.verbalogix.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Fonts are system families. NEVER a downloadable-font provider.
//
// A previous app in this lineage used Google Fonts resolved through the Play Services
// provider, backed by a res/values/font_certs.xml whose certificate was fabricated --
// its DER header declared a 1095-byte structure over a 1041-byte body, and the `dev`
// and `prod` entries were byte-identical where a genuine generated file carries two
// different certs. Provider verification failed, Compose threw resolving the first
// glyph, and since the theme applies typography that was the first composition. The
// app died at launch, through eleven green builds.
//
// A resource can be perfectly valid to AAPT and still be semantic garbage.
//
// The Amber contract AGREES rather than merely permitting this: `typography` in
// docs/ui/design-tokens.json names `FontFamily.Default` and `FontFamily.Monospace`
// literally. The approved screenshots were rendered with a webfont this app does not
// get to use, and matching them by adding a font provider would trade a real crash for
// a visual resemblance.
private val Sans = FontFamily.Default
private val Mono = FontFamily.Monospace

/**
 * The Amber tokens mapped onto Material 3 roles.
 *
 * The mapping is the interesting part, so it is written as one and kept in one place.
 * Two decisions in it are load-bearing:
 *
 * `primary` is [AmberTokens.accentPrimary], the LIGHT amber -- because this app uses
 * `colorScheme.primary` overwhelmingly as a FOREGROUND: link text, live stats, the
 * selected provider. A fill-weight amber there would fail contrast as text.
 *
 * `onPrimary` is [AmberTokens.canvas] rather than pure black, which is what makes a
 * filled Button render dark-on-amber -- the approved primary action on the setup and
 * tool-approval surfaces. M3 uses `primary` as the container and `onPrimary` as the
 * label, so this one pair decides both readings at once.
 *
 * The five surface containers map to the five surface tokens IN ORDER. That is what
 * lets elevation be expressed as a surface step plus a hairline outline, which is what
 * `dense-flat-amber-technical-no-decorative-gradients` asks for -- no shadows, no
 * gradients, no ornament.
 */
private val AmberDarkColors = darkColorScheme(
    primary = AmberTokens.accentPrimary,
    onPrimary = AmberTokens.canvas,
    primaryContainer = AmberTokens.surfaceRaised,
    onPrimaryContainer = AmberTokens.accentPrimary,

    secondary = AmberTokens.accentDim,
    onSecondary = AmberTokens.canvas,
    secondaryContainer = AmberTokens.surfaceHigh,
    onSecondaryContainer = AmberTokens.textPrimary,

    tertiary = AmberTokens.accentActive,
    onTertiary = AmberTokens.canvas,

    background = AmberTokens.background,
    onBackground = AmberTokens.textPrimary,

    surface = AmberTokens.surface,
    onSurface = AmberTokens.textPrimary,
    surfaceVariant = AmberTokens.surfaceHigh,
    onSurfaceVariant = AmberTokens.textMuted,

    surfaceContainerLowest = AmberTokens.canvas,
    surfaceContainerLow = AmberTokens.surfaceLow,
    surfaceContainer = AmberTokens.surfaceRaised,
    surfaceContainerHigh = AmberTokens.surfaceHigh,
    surfaceContainerHighest = AmberTokens.surfaceHighest,

    error = AmberTokens.error,
    onError = AmberTokens.canvas,
    errorContainer = AmberTokens.errorContainer,
    onErrorContainer = AmberTokens.error,

    outline = AmberTokens.outline,
    outlineVariant = AmberTokens.outlineVariant,
)

/**
 * Unchanged, and deliberately so.
 *
 * The approved contract is a DARK vocabulary -- it defines one `background`, one
 * `canvas` and five dark surfaces, and no light counterpart exists to transcribe.
 * Inventing one would be exactly the fabrication this project keeps catching, so the
 * light scheme stays as it shipped and light mode renders what it rendered before.
 * `isSystemInDarkTheme()` still decides, so nothing changes for a user on light.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5200),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF424242),
    error = Color(0xFFB31B2B),
)

/**
 * Every size here is `sp`, never `dp`, so the whole interface tracks the system font
 * setting. The suite asserts 1.3x and 2.0x because "it scales" is easy to claim and
 * easy to break: a fixed-height row containing scaling text clips at 2.0x and looks
 * perfectly fine at 1.0x.
 */
private val LocalmindTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp),
    // Data is monospace so digits align in a column.
    bodySmall = TextStyle(fontFamily = Mono, fontSize = 13.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp),
)

@Composable
fun LocalmindTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AmberDarkColors else LightColors,
        typography = LocalmindTypography,
        content = content,
    )
}
