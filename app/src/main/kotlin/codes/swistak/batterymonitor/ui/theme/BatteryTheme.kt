/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.theme

import android.app.Activity
import android.content.ContextWrapper
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class ColorSource { Dynamic, BatteryBlue }
enum class Brightness { System, Light, Dark, TrueBlack }

internal fun isDark(brightness: Brightness, systemDark: Boolean): Boolean = when (brightness) {
    Brightness.System -> systemDark
    Brightness.Light -> false
    Brightness.Dark, Brightness.TrueBlack -> true
}

internal fun useDynamicColors(source: ColorSource, sdkInt: Int): Boolean =
    source == ColorSource.Dynamic && sdkInt >= Build.VERSION_CODES.S

private val BlueLight = lightColorScheme(
    primary = Color(0xFF006493),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEFFD),
    onPrimaryContainer = Color(0xFF12384F),
    secondary = Color(0xFF4D606F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFEAF2),
    onSecondaryContainer = Color(0xFF172F3C),
    tertiary = Color(0xFF66597A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEBDDF6),
    onTertiaryContainer = Color(0xFF332641),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F9FC),
    onBackground = Color(0xFF17232D),
    surface = Color.White,
    onSurface = Color(0xFF17232D),
    surfaceVariant = Color(0xFFEDF3F8),
    onSurfaceVariant = Color(0xFF4D606F),
    outline = Color(0xFF637785),
    outlineVariant = Color(0xFFCBD9E3),
    inverseSurface = Color(0xFF27333D),
    inverseOnSurface = Color(0xFFF1F4F7),
    inversePrimary = Color(0xFF8BCDFF),
    surfaceTint = Color(0xFF006493),
    scrim = Color.Black,
    primaryFixed = Color(0xFFDDEFFD),
    primaryFixedDim = Color(0xFF8BCDFF),
    onPrimaryFixed = Color(0xFF001E30),
    onPrimaryFixedVariant = Color(0xFF005073),
    secondaryFixed = Color(0xFFDFEAF2),
    secondaryFixedDim = Color(0xFFACBCC9),
    onSecondaryFixed = Color(0xFF142B38),
    onSecondaryFixedVariant = Color(0xFF344A58),
    tertiaryFixed = Color(0xFFEBDDF6),
    tertiaryFixedDim = Color(0xFFD1BCE2),
    onTertiaryFixed = Color(0xFF281738),
    onTertiaryFixedVariant = Color(0xFF514060),
    surfaceDim = Color(0xFFD8E1E8),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F6FA),
    surfaceContainer = Color(0xFFEDF3F8),
    surfaceContainerHigh = Color(0xFFE6EDF3),
    surfaceContainerHighest = Color(0xFFDDE7EF)
)

private val BlueDark = darkColorScheme(
    primary = Color(0xFF8BCDFF),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF16384D),
    onPrimaryContainer = Color(0xFFD6EEFF),
    secondary = Color(0xFFACBCC9),
    onSecondary = Color(0xFF243845),
    secondaryContainer = Color(0xFF344A58),
    onSecondaryContainer = Color(0xFFD9E7F0),
    tertiary = Color(0xFFD1BCE2),
    onTertiary = Color(0xFF382849),
    tertiaryContainer = Color(0xFF514060),
    onTertiaryContainer = Color(0xFFEDDDF8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF1A2027),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF242E38),
    onSurfaceVariant = Color(0xFFACBCC9),
    outline = Color(0xFF8A9EAB),
    outlineVariant = Color(0xFF3E5363),
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF253139),
    inversePrimary = Color(0xFF006493),
    surfaceTint = Color(0xFF8BCDFF),
    scrim = Color.Black,
    primaryFixed = Color(0xFFDDEFFD),
    primaryFixedDim = Color(0xFF8BCDFF),
    onPrimaryFixed = Color(0xFF001E30),
    onPrimaryFixedVariant = Color(0xFF005073),
    secondaryFixed = Color(0xFFDFEAF2),
    secondaryFixedDim = Color(0xFFACBCC9),
    onSecondaryFixed = Color(0xFF142B38),
    onSecondaryFixedVariant = Color(0xFF344A58),
    tertiaryFixed = Color(0xFFEBDDF6),
    tertiaryFixedDim = Color(0xFFD1BCE2),
    onTertiaryFixed = Color(0xFF281738),
    onTertiaryFixedVariant = Color(0xFF514060),
    surfaceDim = Color(0xFF101418),
    surfaceBright = Color(0xFF35414A),
    surfaceContainerLowest = Color(0xFF0B1014),
    surfaceContainerLow = Color(0xFF151B21),
    surfaceContainer = Color(0xFF1A2027),
    surfaceContainerHigh = Color(0xFF242E38),
    surfaceContainerHighest = Color(0xFF2F3A45)
)

private val BlueBlack = BlueDark.copy(
    background = Color.Black,
    surface = Color(0xFF10151B),
    primaryContainer = Color(0xFF102B3B),
    surfaceVariant = Color(0xFF1A242D),
    outlineVariant = Color(0xFF354B5B),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF2C3942),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B1015),
    surfaceContainer = Color(0xFF10151B),
    surfaceContainerHigh = Color(0xFF1A242D),
    surfaceContainerHighest = Color(0xFF26343E)
)

object BatterySpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val normal = 16.dp
    val content = 20.dp
    val lg = 24.dp
    val xl = 32.dp
    val touch = 48.dp
}

private val BatteryShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp)
)

private val BatteryTypography = Typography(
    displayLarge = TextStyle(fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(
        fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
)

data class BatterySemanticColors(
    val warning: Color,
    val onWarning: Color,
    val measurementOne: Color,
    val measurementTwo: Color,
    val measurementThree: Color
)

val LocalBatterySemanticColors = staticCompositionLocalOf {
    BatterySemanticColors(
        Color.Unspecified,
        Color.Unspecified,
        Color.Unspecified,
        Color.Unspecified,
        Color.Unspecified
    )
}

@Composable
fun BatteryTheme(
    colorSource: ColorSource = ColorSource.Dynamic,
    brightness: Brightness = Brightness.System,
    content: @Composable () -> Unit
) {
    val dark = isDark(brightness, isSystemInDarkTheme())
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity =
                generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>()
                    .firstOrNull()
            activity?.let {
                WindowCompat.getInsetsController(it.window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    val dynamic = useDynamicColors(colorSource, Build.VERSION.SDK_INT)
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(
            context
        )

        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        brightness == Brightness.TrueBlack -> BlueBlack
        dark -> BlueDark
        else -> BlueLight
    }.let { colors ->
        if (brightness == Brightness.TrueBlack && dynamic) colors.copy(
            background = Color.Black,
            surface = Color(0xFF10151B),
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0B1015),
            surfaceContainer = Color(0xFF10151B),
            surfaceContainerHigh = Color(0xFF1A242D),
            surfaceContainerHighest = Color(0xFF26343E)
        ) else colors
    }
    val semantic = BatterySemanticColors(
        warning = if (dark) Color(0xFFFFC56E) else Color(0xFF805600),
        onWarning = if (dark) Color(0xFF412B00) else Color.White,
        measurementOne = scheme.primary,
        measurementTwo = scheme.tertiary,
        measurementThree = scheme.secondary
    )
    CompositionLocalProvider(LocalBatterySemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = scheme,
            typography = BatteryTypography,
            shapes = BatteryShapes,
            content = content
        )
    }
}
