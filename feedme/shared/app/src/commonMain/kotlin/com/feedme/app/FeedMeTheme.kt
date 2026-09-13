package com.feedme.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared native translation of the approved FeedMe design tokens. */
object FeedMeColors {
    val Blue = Color(0xFF304FFE)
    val Lime = Color(0xFFD4FF5A)
    val Coral = Color(0xFFFF7657)
    val Lilac = Color(0xFFD7C9FF)
    val Ink = Color(0xFF161917)
    val Paper = Color(0xFFF7F7F2)
    val Muted = Color(0xFF60665E)
    val Line = Color(0xFFE4E6DF)
}

@Composable
fun FeedMeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = FeedMeColors.Blue,
            onPrimary = Color.White,
            primaryContainer = FeedMeColors.Lilac,
            onPrimaryContainer = FeedMeColors.Ink,
            secondary = FeedMeColors.Ink,
            onSecondary = Color.White,
            secondaryContainer = FeedMeColors.Lime,
            onSecondaryContainer = FeedMeColors.Ink,
            tertiary = FeedMeColors.Coral,
            onTertiary = FeedMeColors.Ink,
            background = FeedMeColors.Paper,
            onBackground = FeedMeColors.Ink,
            surface = FeedMeColors.Paper,
            onSurface = FeedMeColors.Ink,
            surfaceVariant = FeedMeColors.Line,
            onSurfaceVariant = FeedMeColors.Muted,
            outline = FeedMeColors.Muted,
            outlineVariant = FeedMeColors.Line,
            error = Color(0xFFA42424),
        ),
        typography = Typography(
            displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 52.sp, lineHeight = 52.sp, letterSpacing = (-2).sp),
            displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 42.sp, lineHeight = 44.sp, letterSpacing = (-1.5).sp),
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 37.sp, letterSpacing = (-1).sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 27.sp, lineHeight = 30.sp, letterSpacing = (-0.6).sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 21.sp),
            labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.8.sp),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp),
        ),
        content = content,
    )
}
