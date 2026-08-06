package dev.cwtf.hidandseek.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
