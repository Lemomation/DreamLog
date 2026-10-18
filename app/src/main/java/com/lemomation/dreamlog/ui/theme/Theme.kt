package com.lemomation.dreamlog.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The complete DreamLog palette. Every visible color in the app must come from this object or the
 * equivalent Material slots configured below. There are no accents, gradients, or decorative hues.
 */
object DreamColors {
    val Background = Color(0xFF111111)
    val AlarmBlack = Color(0xFF000000)
    val Surface = Color(0xFF181818)
    val Border = Color(0xFF262626)
    val TextPrimary = Color(0xFFE5E5E5)
    val TextSecondary = Color(0xFF737373)
    val NodeDefault = Color(0xFF8C8C8C)
    val NodeSelected = Color(0xFFE5E5E5)
    val Edge = Color(0xFF3D3D3D)
    val GraphLabel = Color(0xFF9E9E9E)
}

private val DreamColorScheme = darkColorScheme(
    primary = DreamColors.TextPrimary,
    onPrimary = DreamColors.Background,
    primaryContainer = DreamColors.Surface,
    onPrimaryContainer = DreamColors.TextPrimary,
    secondary = DreamColors.TextSecondary,
    onSecondary = DreamColors.Background,
    secondaryContainer = DreamColors.Surface,
    onSecondaryContainer = DreamColors.TextPrimary,
    tertiary = DreamColors.TextPrimary,
    onTertiary = DreamColors.Background,
    tertiaryContainer = DreamColors.Surface,
    onTertiaryContainer = DreamColors.TextPrimary,
    background = DreamColors.Background,
    onBackground = DreamColors.TextPrimary,
    surface = DreamColors.Background,
    onSurface = DreamColors.TextPrimary,
    surfaceVariant = DreamColors.Surface,
    onSurfaceVariant = DreamColors.TextSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = DreamColors.TextPrimary,
    inverseOnSurface = DreamColors.Background,
    error = DreamColors.TextPrimary,
    onError = DreamColors.Background,
    errorContainer = DreamColors.Surface,
    onErrorContainer = DreamColors.TextPrimary,
    outline = DreamColors.Border,
    outlineVariant = DreamColors.Border,
    scrim = Color.Black,
)

private val DreamTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 38.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)

private val DreamShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

@Composable
fun DreamLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DreamColorScheme,
        typography = DreamTypography,
        shapes = DreamShapes,
        content = content,
    )
}
