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
private val Sans = FontFamily.Default
private val Mono = FontFamily.Monospace

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB000),          // terminal amber
    onPrimary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0E0E0E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFBDBDBD),
    error = Color(0xFFE22639),
)

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

private val ConformanceTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp),
    // Data is monospace so digits align in a column.
    bodySmall = TextStyle(fontFamily = Mono, fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp),
)

@Composable
fun LocalmindTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ConformanceTypography,
        content = content,
    )
}
