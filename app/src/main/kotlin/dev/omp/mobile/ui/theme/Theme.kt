package dev.omp.mobile.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.omp.mobile.data.ThemeMode

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFB9C8FF),
    onPrimary = Color(0xFF1D2C5E),
    primaryContainer = Color(0xFF34446F),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFFC6C5DD),
    secondaryContainer = Color(0xFF3E3F55),
    tertiary = Color(0xFFE7BAD4),
    tertiaryContainer = Color(0xFF5C3E52),
    surface = Color(0xFF121318),
    surfaceVariant = Color(0xFF45464F),
    background = Color(0xFF121318),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OmpTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        supportsDynamic && dark -> dynamicDarkColorScheme(context)
        supportsDynamic -> dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> expressiveLightColorScheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
