package com.chineseboot.android.core.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandleDetectorTest {

    @Test
    fun `extracts body and wick geometry from a synthetic chart`() {
        val frame = TestChartImages.candlestickChart(candleCount = 8)
        val region = ChartRegionDetector.detect(frame)
        requireNotNull(region)

        val candles = CandleDetector.detect(frame, region)
        assertTrue(candles.size >= 6, "expected most of the 8 drawn candles to be detected, got ${candles.size}")

        for (candle in candles) {
            // Wick must fully contain the body (wick top above/equal body top, wick bottom below/equal body bottom).
            assertTrue(candle.wickTop <= candle.bodyTop)
            assertTrue(candle.wickBottom >= candle.bodyBottom)
            assertTrue(candle.detectionConfidence > 0.0)
        }
    }

    @Test
    fun `rejects a chart region with no valid candle structure`() {
        val region = ChartRegion(x = 0, y = 0, width = 10, height = 10, score = 0.5)
        val frame = TestChartImages.blankFrame(width = 10, height = 10)
        val candles = CandleDetector.detect(frame, region)
        assertEquals(0, candles.size)
    }

    @Test
    fun `zero-size region yields no candles`() {
        val frame = TestChartImages.candlestickChart()
        val region = ChartRegion(x = 0, y = 0, width = 0, height = 0, score = 0.0)
        assertEquals(0, CandleDetector.detect(frame, region).size)
    }
}
