package com.chineseboot.android.core.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameBufferTest {
    @Test
    fun `mismatched pixel array size is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            FrameBuffer(width = 4, height = 4, pixels = IntArray(3), timestampMillis = 0L)
        }
    }

    @Test
    fun `downsample reduces dimensions by the given factor`() {
        val frame = TestChartImages.candlestickChart(width = 100, height = 50)
        val down = frame.downsample(2)
        assertEquals(50, down.width)
        assertEquals(25, down.height)
    }

    @Test
    fun `masked regions are overwritten with the fill color`() {
        val frame = TestChartImages.candlestickChart()
        val fill = ColorUtils.rgb(1, 2, 3)
        val masked = frame.withMaskedRegions(listOf(PixelRect(0, 0, 10, 10)), fill)
        for (y in 0 until 10) {
            for (x in 0 until 10) {
                assertEquals(fill, masked[x, y])
            }
        }
    }
}
