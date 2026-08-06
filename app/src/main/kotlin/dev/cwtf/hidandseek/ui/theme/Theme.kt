package dev.cwtf.hidandseek.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.cwtf.hidandseek.data.AppearanceSettings
import dev.cwtf.hidandseek.data.MotionIntensity
import dev.cwtf.hidandseek.data.ThemeMode

private val SeedPrimary = Color(0xFF4F6BED)

private val FallbackLight = lightColorScheme(primary = SeedPrimary)
private val FallbackDark = darkColorScheme(primary = SeedPrimary)

/**
 * Material 3 Expressive theming.
 *
 * Dynamic colour is available unconditionally because minSdk is 31 — there is
 * no pre-Material-You fallback path to maintain.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HidAndSeekTheme(
    appearance: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

    var colorScheme: ColorScheme = when {
        appearance.dynamicColor && dark -> dynamicDarkColorScheme(context)
        appearance.dynamicColor -> dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }

    // AMOLED trades the elevation cue for a true black that costs no power on
    // an OLED panel, so surfaces collapse to black rather than dark grey.
    if (appearance.themeMode == ThemeMode.AMOLED) {
        colorScheme = colorScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
        )
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = when (appearance.motionIntensity) {
            MotionIntensity.FULL -> MotionScheme.expressive()
            MotionIntensity.REDUCED, MotionIntensity.NONE -> MotionScheme.standard()
        },
        content = content,
    )
}
