package com.chineseboot.android.core.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColorConventionDetectorTest {

    @Test
    fun `green vs red is confidently classified as bullish vs bearish`() {
        val green = ColorUtils.rgb(0, 180, 0)
        val red = ColorUtils.rgb(200, 0, 0)
        val colors = List(10) { if (it % 2 == 0) green else red }

        val convention = ColorConventionDetector.detect(colors)
        requireNotNull(convention)
        assertEquals(PixelCandleDirection.BULLISH, convention.classify(green))
        assertEquals(PixelCandleDirection.BEARISH, convention.classify(red))
    }

    @Test
    fun `ambiguous palette (blue vs gray) is reported unknown`() {
        val blue = ColorUtils.rgb(30, 60, 200)
        val gray = ColorUtils.rgb(120, 120, 120)
        val colors = List(10) { if (it % 2 == 0) blue else gray }

        assertNull(ColorConventionDetector.detect(colors))
    }

    @Test
    fun `single dominant color cannot establish a convention`() {
        val colors = List(10) { ColorUtils.rgb(10, 200, 10) }
        assertNull(ColorConventionDetector.detect(colors))
    }

    @Test
    fun `empty and tiny inputs are handled without crashing`() {
        assertNull(ColorConventionDetector.detect(emptyList()))
        assertNull(ColorConventionDetector.detect(listOf(ColorUtils.rgb(1, 2, 3))))
    }
}
