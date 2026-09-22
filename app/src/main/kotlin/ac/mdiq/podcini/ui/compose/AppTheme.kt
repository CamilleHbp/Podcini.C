package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.upsertBlk
import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val TAG = "AppTheme"

val CustomTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 30.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    // Add other text styles as needed
)

object CustomTextStyles {
    val titleCustom = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
}

val borderColor: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.outlineVariant

val textColor: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurface

val buttonColor: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6B437C), onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E2EF), onPrimaryContainer = Color(0xFF30213D),
    secondary = Color(0xFF62586D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E2EF), onSecondaryContainer = Color(0xFF30213D),
    tertiary = Color(0xFF6B437C), tertiaryContainer = Color(0xFFE9E2EF),
    onTertiary = Color.White, onTertiaryContainer = Color(0xFF30213D),
    background = Color(0xFFF7F7FA), onBackground = Color(0xFF252136),
    surface = Color(0xFFF7F7FA), onSurface = Color(0xFF252136),
    surfaceVariant = Color(0xFFEAE6EF), onSurfaceVariant = Color(0xFF625B6C),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1EEF5),
    surfaceContainer = Color(0xFFEDE9F1), surfaceContainerHigh = Color(0xFFE7E2ED),
    surfaceContainerHighest = Color(0xFFE1DBE7),
    outline = Color(0xFF7C7485), outlineVariant = Color(0xFFD8D1DF)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD8B5EA), onPrimary = Color(0xFF3D224C),
    primaryContainer = Color(0xFF513560), onPrimaryContainer = Color(0xFFF0DBFA),
    secondary = Color(0xFFCFC0DB), onSecondary = Color(0xFF352D40),
    secondaryContainer = Color(0xFF44384F), onSecondaryContainer = Color(0xFFF0E3FA),
    tertiary = Color(0xFFD8B5EA), tertiaryContainer = Color(0xFF513560),
    onTertiary = Color(0xFF3D224C), onTertiaryContainer = Color(0xFFF0DBFA),
    background = Color(0xFF17141D), onBackground = Color(0xFFECE5F1),
    surface = Color(0xFF17141D), onSurface = Color(0xFFECE5F1),
    surfaceVariant = Color(0xFF49414F), onSurfaceVariant = Color(0xFFCDC3D4),
    surfaceContainerLowest = Color(0xFF121016), surfaceContainerLow = Color(0xFF211D28),
    surfaceContainer = Color(0xFF272230), surfaceContainerHigh = Color(0xFF312B3A),
    surfaceContainerHighest = Color(0xFF3C3446),
    outline = Color(0xFF998CA5), outlineVariant = Color(0xFF49414F)
)

enum class AppThemes {
    LIGHT, DARK, BLACK, SYSTEM
}

var appTheme: AppThemes
    get() = when (appPrefsFlow!!.value.theme) {
        "0" -> AppThemes.LIGHT
        "1" -> AppThemes.DARK
        else -> AppThemes.SYSTEM
    }
    set(theme) {
        val t = when (theme) {
            AppThemes.LIGHT -> "0"
            AppThemes.DARK -> "1"
            else -> "system"
        }
        upsertBlk(appPrefsFlow!!.value) { it.theme = t }
    }

@Composable
fun PodciniTheme(forceTheme: AppThemes? = null, content: @Composable () -> Unit) {
    val appPrefs by appPrefsFlow!!.collectAsStateWithLifecycle()

    val appThemes: AppThemes = if (forceTheme != null) forceTheme else appTheme
    val isDark = when (appThemes) {
        AppThemes.LIGHT -> false
        AppThemes.DARK, AppThemes.BLACK -> true
        AppThemes.SYSTEM -> isSystemInDarkTheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && appPrefs.useDynamicThemes -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark && (appThemes == AppThemes.BLACK || appPrefs.themeBlack) -> DarkColors.copy(surface = Color(0xFF000000))
        isDark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, shapes = Shapes, typography = Typography(), content = content)
}

fun isLightTheme(): Boolean {
    val curTheme: AppThemes = appTheme
    return when (curTheme) {
        AppThemes.LIGHT -> true
        AppThemes.DARK, AppThemes.BLACK -> false
        AppThemes.SYSTEM -> {
            val uiMode = getAppContext().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            uiMode != Configuration.UI_MODE_NIGHT_YES
        }
    }
}

fun distinctColorOf(colorA: Color, colorB: Color): Color {
    val hslA = FloatArray(3)
    val hslB = FloatArray(3)
    val argbA = colorA.toArgb()
    val argbB = colorB.toArgb()

    ColorUtils.colorToHSL(argbA, hslA)
    ColorUtils.colorToHSL(argbB, hslB)

    val avgHue = (hslA[0] + hslB[0]) / 2f
    val avgSaturation = (hslA[1] + hslB[1]) / 2f
//    val avgLightness = (hslA[2] + hslB[2]) / 2f

    val targetHue = (avgHue + 180f) % 360f
    val targetSaturation = (1.0f - avgSaturation).coerceIn(0.3f, 0.9f)

    val lumA = ColorUtils.calculateLuminance(argbA)
    val lumB = ColorUtils.calculateLuminance(argbB)
    val avgLuminance = (lumA + lumB) / 2.0

    var currentLightness = if (avgLuminance < 0.5) 0.9f else 0.1f

    val targetColorHSL = floatArrayOf(targetHue, targetSaturation, currentLightness)
    var resultColor = ColorUtils.HSLToColor(targetColorHSL)

    val minContrast = 4.5f
    var attempts = 0

    while (attempts < 10) {
        val contrastA = ColorUtils.calculateContrast(resultColor, argbA)
        val contrastB = ColorUtils.calculateContrast(resultColor, argbB)
        if (contrastA >= minContrast && contrastB >= minContrast) break

        currentLightness = if (avgLuminance < 0.5) (currentLightness + 0.05f).coerceAtMost(1.0f) else (currentLightness - 0.05f).coerceAtLeast(0.0f)

        targetColorHSL[2] = currentLightness
        resultColor = ColorUtils.HSLToColor(targetColorHSL)
        attempts++
    }
    return Color(resultColor)
}

fun complementaryColorOf(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + 180f) % 360f
    hsv[1] = if (hsv[1] < 0.5f) 0.7f else hsv[1]
    hsv[2] = if (hsv[2] > 0.5f) 0.2f else 0.9f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

fun contrastColorOf(color: Color): Color {
    val luminance = (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue)
    return if (luminance > 0.5) Color.Black else Color.White
}
