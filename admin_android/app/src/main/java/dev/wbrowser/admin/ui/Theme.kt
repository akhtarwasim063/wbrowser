package dev.wbrowser.admin.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Same palette as the web console, so the native app and the website look like one product. */
data class Palette(
    val bg: Color, val card: Color, val ink: Color, val mut: Color, val line: Color, val soft: Color,
    val pri: Color, val pri2: Color, val priSoft: Color,
    val ok: Color, val okSoft: Color, val warn: Color, val warnSoft: Color, val bad: Color, val badSoft: Color,
    val cyan: Color, val violet: Color, val dark: Boolean,
)

private val LightPalette = Palette(
    bg = Color(0xFFF3F4FA), card = Color(0xFFFFFFFF), ink = Color(0xFF12141F), mut = Color(0xFF6B7280), line = Color(0xFFE6E8F1), soft = Color(0xFFF6F7FC),
    pri = Color(0xFF5B5BF0), pri2 = Color(0xFF8B5CF6), priSoft = Color(0xFFEEF0FF),
    ok = Color(0xFF12A150), okSoft = Color(0xFFE5F7EC), warn = Color(0xFFD97706), warnSoft = Color(0xFFFEF3E2), bad = Color(0xFFE11D48), badSoft = Color(0xFFFFE9EE),
    cyan = Color(0xFF0EA5C6), violet = Color(0xFF8B5CF6), dark = false,
)
private val DarkPalette = Palette(
    bg = Color(0xFF0A0C13), card = Color(0xFF121623), ink = Color(0xFFEEF0F8), mut = Color(0xFF98A2B8), line = Color(0xFF232840), soft = Color(0xFF171C2D),
    pri = Color(0xFF8B8CFF), pri2 = Color(0xFFA78BFA), priSoft = Color(0xFF1C2142),
    ok = Color(0xFF34D399), okSoft = Color(0xFF10281F), warn = Color(0xFFFBBF24), warnSoft = Color(0xFF2B2210), bad = Color(0xFFFB7185), badSoft = Color(0xFF2C1119),
    cyan = Color(0xFF22D3EE), violet = Color(0xFFA78BFA), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
@ReadOnlyComposable
fun palette(): Palette = LocalPalette.current

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
)

@Composable
fun AdminTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = if (p.dark) darkColorScheme(
        primary = p.pri, onPrimary = p.bg, background = p.bg, onBackground = p.ink, surface = p.card, onSurface = p.ink,
        surfaceVariant = p.soft, onSurfaceVariant = p.mut, outline = p.line, error = p.bad,
    ) else lightColorScheme(
        primary = p.pri, onPrimary = Color.White, background = p.bg, onBackground = p.ink, surface = p.card, onSurface = p.ink,
        surfaceVariant = p.soft, onSurfaceVariant = p.mut, outline = p.line, error = p.bad,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
