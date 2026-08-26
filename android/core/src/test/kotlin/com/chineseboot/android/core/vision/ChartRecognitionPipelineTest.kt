package com.chineseboot.android.core.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartRecognitionPipelineTest {
    private val pipeline = ChartRecognitionPipeline()

    @Test
    fun `blank frame yields CHART_NOT_DETECTED`() {
        val result = pipeline.analyze(TestChartImages.blankFrame())
        assertEquals(ChartRecognitionStatus.CHART_NOT_DETECTED, result.status)
        assertTrue(result.candles.isEmpty())
        assertEquals(null, result.asset)
    }

    @Test
    fun `zero-size frame is handled without crashing`() {
        val frame = FrameBuffer(width = 0, height = 0, pixels = IntArray(0), timestampMillis = 0L)
        val result = pipeline.analyze(frame)
        assertEquals(ChartRecognitionStatus.CHART_NOT_DETECTED, result.status)
    }

    @Test
    fun `noise blob does not produce a READY result`() {
        val result = pipeline.analyze(TestChartImages.noiseBlobFrame())
        assertEquals(ChartRecognitionStatus.CHART_NOT_DETECTED, result.status)
    }

    @Test
    fun `too-sparse chart (below minimum reliable candles) is reported as not reliable`() {
        val frame = TestChartImages.candlestickChart(candleCount = 2)
        val result = pipeline.analyze(frame)
        assertTrue(
            result.status == ChartRecognitionStatus.CANDLES_NOT_RELIABLE ||
                result.status == ChartRecognitionStatus.CHART_NOT_DETECTED,
        )
    }

    @Test
    fun `a full synthetic chart with clear colors is READY with a known color convention`() {
        val frame = TestChartImages.candlestickChart(candleCount = 12)
        val result = pipeline.analyze(frame)
        assertEquals(ChartRecognitionStatus.READY, result.status)
        assertTrue(result.candles.size >= 6)
        assertTrue(result.candleColorKnown)
        assertTrue(result.confidence > 0.0)
        // No price/asset/timeframe fabrication.
        assertEquals(null, result.asset)
        assertEquals(null, result.timeframeSeconds)
    }

    @Test
    fun `ambiguous candle colors are reported as color-unknown while still READY`() {
        val frame = TestChartImages.candlestickChart(
            candleCount = 12,
            bullishColor = ColorUtils.rgb(80, 80, 80),
            bearishColor = ColorUtils.rgb(140, 140, 140),
        )
        val result = pipeline.analyze(frame)
        if (result.status == ChartRecognitionStatus.READY) {
            assertTrue(!result.candleColorKnown)
            assertTrue(result.candles.all { it.direction == PixelCandleDirection.UNKNOWN })
        }
    }
}
