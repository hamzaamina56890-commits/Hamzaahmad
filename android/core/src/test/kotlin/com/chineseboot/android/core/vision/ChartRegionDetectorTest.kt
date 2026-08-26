package com.chineseboot.android.core.vision

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartRegionDetectorTest {

    @Test
    fun `blank frame has no chart region`() {
        val frame = TestChartImages.blankFrame()
        assertNull(ChartRegionDetector.detect(frame))
    }

    @Test
    fun `synthetic candlestick chart is detected as a region`() {
        val frame = TestChartImages.candlestickChart()
        val region = ChartRegionDetector.detect(frame)
        assertNotNull(region)
        assertTrue(region.score > 0.3)
        // Region should roughly bound the candle area, not the whole frame edge-to-edge.
        assertTrue(region.width in 50..120)
        assertTrue(region.height in 20..60)
    }

    @Test
    fun `isolated noise blob does not score as a chart region`() {
        val frame = TestChartImages.noiseBlobFrame()
        assertNull(ChartRegionDetector.detect(frame))
    }

    @Test
    fun `too-small frame is rejected`() {
        val frame = FrameBuffer(width = 2, height = 2, pixels = intArrayOf(0, 0, 0, 0), timestampMillis = 1L)
        assertNull(ChartRegionDetector.detect(frame))
    }
}
