package com.feedme.app.mealflow

import androidx.compose.ui.graphics.Color
import com.feedme.app.FeedMeColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Token-pair contrast only, not a claim that every rendered state is accessible.
 * Reference: https://www.w3.org/TR/WCAG22/#contrast-minimum
 */
class FeedMeVisualTokensTest {
    @Test fun contrastCalculationMatchesBlackWhiteAndEqualColorBounds() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.00001)
        assertEquals(1.0, contrast(FeedMeColors.Blue, FeedMeColors.Blue), 0.00001)
    }

    @Test fun bodyTextHasNormalTextContrastOnEveryNeutralSurface() {
        for (surface in listOf(FeedMeColors.Paper, FeedMeColors.Surface, FeedMeColors.SoftBlue, FeedMeColors.SoftLime)) {
            assertReadable(FeedMeColors.Ink, surface)
            assertReadable(FeedMeColors.Muted, surface)
        }
    }

    @Test fun primaryButtonAndWordmarkColorsRemainReadable() {
        assertReadable(Color.White, FeedMeColors.Blue)
        assertReadable(FeedMeColors.Lime, FeedMeColors.Blue)
        assertReadable(FeedMeColors.Blue, FeedMeColors.Paper)
    }

    @Test fun accentCardsUseDarkTextRatherThanLowContrastWhite() {
        for (surface in listOf(FeedMeColors.Lime, FeedMeColors.Coral, FeedMeColors.Lilac))
            assertReadable(FeedMeColors.Ink, surface)
    }

    private fun assertReadable(text: Color, surface: Color) {
        val ratio = contrast(text, surface)
        assertTrue(ratio >= 4.5, "Expected normal-text contrast >=4.5:1; actual=$ratio")
    }

    private fun contrast(first: Color, second: Color): Double {
        fun luminance(color: Color): Double {
            fun linear(value: Float): Double = value.toDouble().let {
                if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
        }
        val a = luminance(first); val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}
