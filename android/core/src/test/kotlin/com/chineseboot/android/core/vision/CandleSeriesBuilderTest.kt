package com.chineseboot.android.core.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandleSeriesBuilderTest {
    private val calibration = PriceScaleCalibration(scale = -0.001, offset = 1.3, confidence = 0.9, timestampMillis = 1L, labelCount = 3, decimalPrecision = 4)

    private fun candidate(x: Int, confidence: Double = 0.9): CalibratedCandle {
        val pixel = PixelCandle(
            xPosition = x, width = 5, bodyTop = 40, bodyBottom = 60, wickTop = 30, wickBottom = 70,
            direction = PixelCandleDirection.BULLISH, detectionConfidence = confidence,
        )
        return CalibratedCandle.from(pixel, calibration, x.toLong())!!
    }

    @Test
    fun `sorts candles chronologically left-to-right`() {
        val series = CandleSeriesBuilder.build(listOf(candidate(30), candidate(10), candidate(20)))
        assertEquals(listOf(10, 20, 30), series.candles.map { it.pixel.xPosition })
    }

    @Test
    fun `removes duplicate or overlapping candles keeping the higher-confidence one`() {
        val a = candidate(10, confidence = 0.4)
        val b = candidate(12, confidence = 0.9) // overlaps a (width 5 -> half-width threshold 2)
        val series = CandleSeriesBuilder.build(listOf(a, b))
        assertEquals(1, series.candles.size)
        assertEquals(1, series.duplicatesRemoved)
        assertEquals(0.9, series.candles.first().detectionConfidence)
    }

    @Test
    fun `detects a gap in otherwise uniform spacing`() {
        val xs = listOf(0, 10, 20, 30, 100, 110, 120)
        val series = CandleSeriesBuilder.build(xs.map { candidate(it) })
        assertTrue(series.gapsDetected >= 1)
    }

    @Test
    fun `rejects candles with impossible geometry`() {
        val badPixel = PixelCandle(
            xPosition = 5, width = 5, bodyTop = 40, bodyBottom = 60, wickTop = 30, wickBottom = 70,
            direction = PixelCandleDirection.BULLISH, detectionConfidence = 0.9,
        )
        // Manually construct a corrupted CalibratedCandle (high < low) to simulate corruption
        // that must be caught by the series builder even if it slipped past construction.
        val corrupted = CalibratedCandle(badPixel, 0L, open = 1.0, high = 0.9, low = 1.1, close = 1.0, calibrationConfidence = 0.9)
        val good = candidate(50)
        val series = CandleSeriesBuilder.build(listOf(corrupted, good))
        assertEquals(1, series.candles.size)
        assertEquals(1, series.rejectedCount)
    }

    @Test
    fun `reports insufficient history when below the minimum verified count`() {
        val series = CandleSeriesBuilder.build((0 until 5).map { candidate(it * 10) })
        assertTrue(!series.isSufficient)
    }

    @Test
    fun `empty candidates produce an empty, zero-confidence series`() {
        val series = CandleSeriesBuilder.build(emptyList())
        assertTrue(series.candles.isEmpty())
        assertEquals(0.0, series.seriesConfidence)
    }
}
