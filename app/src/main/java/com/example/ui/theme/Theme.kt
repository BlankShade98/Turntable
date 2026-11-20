package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val MidnightDarkColorScheme = darkColorScheme(
    primary = EmeraldPulse,
    onPrimary = ObsidianNight,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = EmeraldPulse,
    secondary = AmberNeedle,
    onSecondary = ObsidianNight,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = AmberNeedle,
    tertiary = IvoryText,
    onTertiary = ObsidianNight,
    background = ObsidianNight,
    onBackground = IvoryText,
    surface = CharcoalSurface,
    onSurface = IvoryText,
    surfaceVariant = SlateElevated,
    onSurfaceVariant = MutedSilver,
    outline = SlateBorder,
    outlineVariant = SlateBorder,
    error = CoralAlert,
    onError = ObsidianNight,
    errorContainer = CoralContainer,
    onErrorContainer = CoralAlert,
)

private val StudioLightColorScheme = lightColorScheme(
    primary = EmeraldLightPrimary,
    onPrimary = WarmWhiteSurface,
    primaryContainer = ColorTokens.LightEmeraldWash,
    onPrimaryContainer = EmeraldDeep,
    secondary = AmberNeedle,
    onSecondary = InkBlack,
    secondaryContainer = ColorTokens.LightAmberWash,
    onSecondaryContainer = ColorTokens.LightAmberText,
    background = CreamPaper,
    onBackground = InkBlack,
    surface = WarmWhiteSurface,
    onSurface = InkBlack,
    surfaceVariant = SoftStoneSurface,
    onSurfaceVariant = SlateSecondaryText,
    outline = ColorTokens.LightBorder,
)

private object ColorTokens {
    val LightEmeraldWash = androidx.compose.ui.graphics.Color(0xFFD9F7E4)
    val LightAmberWash = androidx.compose.ui.graphics.Color(0xFFFFF0D4)
    val LightAmberText = androidx.compose.ui.graphics.Color(0xFF5B3A00)
    val LightBorder = androidx.compose.ui.graphics.Color(0xFFD9D6CE)
}

val TurntableShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun TurntableTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) MidnightDarkColorScheme else StudioLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TurntableTypography,
        shapes = TurntableShapes,
        content = content,
    )
}
