package com.example.scamdetect.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** The ScamGuard purple, matching the iOS app (see Color.kt). */
private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    onPrimary = OnPurple40,
    primaryContainer = PurpleContainer90,
    onPrimaryContainer = OnPurpleContainer10,
    inversePrimary = Purple80,
    secondary = PurpleGrey40,
    onSecondary = OnPurple40,
    secondaryContainer = PurpleGreyContainer90,
    onSecondaryContainer = OnPurpleGreyContainer10,
    tertiary = Pink40,
    onTertiary = OnPurple40,
    tertiaryContainer = PinkContainer90,
    onTertiaryContainer = OnPinkContainer10,
    background = NeutralLight99,
    onBackground = NeutralLight10,
    surface = NeutralLight99,
    onSurface = NeutralLight10,
    surfaceVariant = NeutralVariantLight90,
    onSurfaceVariant = NeutralVariantLight30,
    surfaceTint = Purple40,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    error = ErrorLight,
    onError = OnPurple40,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    surfaceBright = SurfaceBrightLight,
    surfaceDim = SurfaceDimLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    onPrimary = OnPurple80,
    primaryContainer = PurpleContainer30,
    onPrimaryContainer = OnPurpleContainer90,
    inversePrimary = Purple40,
    secondary = PurpleGrey80,
    onSecondary = OnPurpleGrey80,
    secondaryContainer = PurpleGreyContainer30,
    onSecondaryContainer = PurpleGreyContainer90,
    tertiary = Pink80,
    onTertiary = OnPink80,
    tertiaryContainer = PinkContainer30,
    onTertiaryContainer = PinkContainer90,
    background = NeutralDark6,
    onBackground = NeutralDark90,
    surface = NeutralDark6,
    onSurface = NeutralDark90,
    surfaceVariant = NeutralVariantDark30,
    onSurfaceVariant = NeutralVariantDark80,
    surfaceTint = Purple80,
    inverseSurface = NeutralDark90,
    inverseOnSurface = InverseSurfaceLight,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    outline = OutlineDark,
    outlineVariant = NeutralVariantDark30,
    surfaceBright = SurfaceBrightDark,
    surfaceDim = NeutralDark6,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
)

@Composable
fun ScamDetectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off: on Android 12+ dynamic colour replaces the ScamGuard purple with the wallpaper's colours (that is what
    // made the app look blue). Kept as a parameter so it can still be tried.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
