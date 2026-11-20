package dev.wbrowser.admin.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class ThemeMode(val label: String) { Auto("Auto"), Light("Light"), Dark("Dark") }

/**
 * Design tokens. The look is "water crystal glass": a soft gradient + drifting light orbs behind,
 * translucent glass cards on top (fill + bright rim + sheen). Light and dark each have their own tuning.
 */
data class Palette(
    // base text / surfaces
    val bg: Color, val card: Color, val ink: Color, val mut: Color, val line: Color, val soft: Color,
    val pri: Color, val pri2: Color, val priSoft: Color,
    val ok: Color, val okSoft: Color, val warn: Color, val warnSoft: Color, val bad: Color, val badSoft: Color,
    val cyan: Color, val violet: Color,
    // glass
    val glassFill: Color, val glassRimTop: Color, val glassRimBottom: Color, val glassSheen: Color, val glassShadow: Color,
    // animated background
    val bgTop: Color, val bgMid: Color, val bgBottom: Color, val orbA: Color, val orbB: Color, val orbC: Color, val water: Color,
    val dark: Boolean,
)

private val LightPalette = Palette(
    bg = Color(0xFFEEF1FB), card = Color(0xFFFFFFFF), ink = Color(0xFF111426), mut = Color(0xFF5E6781), line = Color(0x1A5B5BF0), soft = Color(0x145B5BF0),
    pri = Color(0xFF5456E8), pri2 = Color(0xFF8B5CF6), priSoft = Color(0x225B5BF0),
    ok = Color(0xFF0E9F6E), okSoft = Color(0x220E9F6E), warn = Color(0xFFD97706), warnSoft = Color(0x24D97706), bad = Color(0xFFE11D48), badSoft = Color(0x1FE11D48),
    cyan = Color(0xFF0891B2), violet = Color(0xFF7C3AED),
    glassFill = Color(0xA3FFFFFF), glassRimTop = Color(0xF2FFFFFF), glassRimBottom = Color(0x2E5B5BF0), glassSheen = Color(0x66FFFFFF), glassShadow = Color(0x405B5BF0),
    bgTop = Color(0xFFEAF0FF), bgMid = Color(0xFFF3EEFF), bgBottom = Color(0xFFE6FAFF),
    orbA = Color(0xFF7C83FF), orbB = Color(0xFF22D3EE), orbC = Color(0xFFF0A6FF), water = Color(0xFF5BC8FF),
    dark = false,
)

private val DarkPalette = Palette(
    bg = Color(0xFF070A14), card = Color(0xFF121626), ink = Color(0xFFF1F3FC), mut = Color(0xFF9AA5C0), line = Color(0x24FFFFFF), soft = Color(0x14FFFFFF),
    pri = Color(0xFF8E90FF), pri2 = Color(0xFFA78BFA), priSoft = Color(0x338E90FF),
    ok = Color(0xFF34D399), okSoft = Color(0x2634D399), warn = Color(0xFFFBBF24), warnSoft = Color(0x26FBBF24), bad = Color(0xFFFB7185), badSoft = Color(0x26FB7185),
    cyan = Color(0xFF22D3EE), violet = Color(0xFFB794FF),
    glassFill = Color(0x14FFFFFF), glassRimTop = Color(0x4DFFFFFF), glassRimBottom = Color(0x0FFFFFFF), glassSheen = Color(0x1AFFFFFF), glassShadow = Color(0x66000000),
    bgTop = Color(0xFF060914), bgMid = Color(0xFF0A1024), bgBottom = Color(0xFF07121F),
    orbA = Color(0xFF5B5FFF), orbB = Color(0xFF13B8D8), orbC = Color(0xFF9333EA), water = Color(0xFF38BDF8),
    dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
@ReadOnlyComposable
fun palette(): Palette = LocalPalette.current

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
)

@Composable
fun AdminTheme(mode: ThemeMode = ThemeMode.Auto, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.Auto -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        primary = p.pri, onPrimary = p.bg, background = p.bg, onBackground = p.ink, surface = p.card, onSurface = p.ink,
        surfaceVariant = p.soft, onSurfaceVariant = p.mut, outline = p.line, error = p.bad,
    ) else lightColorScheme(
        primary = p.pri, onPrimary = Color.White, background = p.bg, onBackground = p.ink, surface = p.card, onSurface = p.ink,
        surfaceVariant = p.soft, onSurfaceVariant = p.mut, outline = p.line, error = p.bad,
    )
    // status / navigation bar icons follow the chosen theme (not only the system one)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
