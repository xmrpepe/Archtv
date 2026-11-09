package dev.archdock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Tokyo Night, from Omarchy's themes/tokyo-night/colors.toml. */
object TokyoNight {
    val bg = Color(0xFF1A1B26)
    val bgDark = Color(0xFF13141C)
    val bgDarker = Color(0xFF0E0E14)
    val bgLight = Color(0xFF24283B)
    val selection = Color(0xFF292E42)
    val muted = Color(0xFF414868)
    val fg = Color(0xFFA9B1D6)
    val fgDark = Color(0xFF565F89)
    val fgBright = Color(0xFFC0CAF5)
    val accent = Color(0xFF7AA2F7)
    val red = Color(0xFFF7768E)
    val orange = Color(0xFFFF9E64)
    val yellow = Color(0xFFE0AF68)
    val green = Color(0xFF9ECE6A)
    val cyan = Color(0xFF7DCFFF)
    val magenta = Color(0xFFBB9AF7)
}

private val scheme = darkColorScheme(
    primary = TokyoNight.accent,
    onPrimary = TokyoNight.bgDark,
    primaryContainer = TokyoNight.selection,
    onPrimaryContainer = TokyoNight.fgBright,
    secondary = TokyoNight.magenta,
    onSecondary = TokyoNight.bgDark,
    secondaryContainer = TokyoNight.bgLight,
    onSecondaryContainer = TokyoNight.fgBright,
    tertiary = TokyoNight.cyan,
    background = TokyoNight.bg,
    onBackground = TokyoNight.fg,
    surface = TokyoNight.bg,
    onSurface = TokyoNight.fgBright,
    surfaceVariant = TokyoNight.bgLight,
    onSurfaceVariant = TokyoNight.fg,
    surfaceContainer = TokyoNight.bgDark,
    surfaceContainerHigh = TokyoNight.bgLight,
    surfaceContainerHighest = TokyoNight.selection,
    surfaceContainerLow = TokyoNight.bgDark,
    surfaceContainerLowest = TokyoNight.bgDarker,
    outline = TokyoNight.muted,
    outlineVariant = TokyoNight.selection,
    error = TokyoNight.red,
    onError = TokyoNight.bgDark,
)

val Mono = FontFamily.Monospace

private val typography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, color = TokyoNight.fgBright),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp),
    )
}

@Composable
fun ArchDockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
