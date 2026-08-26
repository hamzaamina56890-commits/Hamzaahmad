package com.chineseboot.android.core.analysis

import com.chineseboot.android.core.model.Signal
import com.chineseboot.android.core.vision.CalibratedCandle
import com.chineseboot.android.core.vision.CandleSeriesBuilder
import com.chineseboot.android.core.vision.PixelCandle
import com.chineseboot.android.core.vision.PixelCandleDirection
import com.chineseboot.android.core.vision.VerifiedCandleSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibratedSignalEngineTest {
    private fun candleAt(index: Int, open: Double, close: Double, confidence: Double = 0.9): CalibratedCandle {
        val pixel = PixelCandle(
            xPosition = index * 10, width = 5, bodyTop = 0, bodyBottom = 1, wickTop = 0, wickBottom = 1,
            direction = if (close >= open) PixelCandleDirection.BULLISH else PixelCandleDirection.BEARISH,
            detectionConfidence = confidence,
        )
        return CalibratedCandle(
            pixel = pixel,
            openTimeMillis = index.toLong(),
            open = open,
            high = maxOf(open, close) + 0.002,
            low = minOf(open, close) - 0.002,
            close = close,
            calibrationConfidence = confidence,
        )
    }

    /** Chains each candle's open to the previous close so bullish/bearish direction genuinely reflects the trend. */
    private fun seriesOf(closes: List<Double>, confidence: Double = 0.9): VerifiedCandleSeries {
        val candles = closes.mapIndexed { i, close ->
            val open = if (i == 0) close - 0.0005 else closes[i - 1]
            candleAt(i, open, close, confidence)
        }
        return CandleSeriesBuilder.build(candles)
    }

    @Test
    fun `produces UP when independent confirmations agree`() {
        val closes = (0 until 60).map { 1.0 + it * 0.002 }
        val series = seriesOf(closes)
        val snapshot = CalibratedSignalEngine.analyze(series, chartConfidence = 0.9, candleQuality = 0.9, asset = "EUR/USD", timeframeSeconds = 60, timestampMillis = 1L)
        assertEquals(Signal.UP, snapshot.signal)
        assertTrue((snapshot.confidencePercent ?: 0) > 0)
        assertTrue(snapshot.reason!!.isNotBlank())
    }

    @Test
    fun `produces DOWN when independent confirmations agree`() {
        val closes = (0 until 60).map { 1.4 - it * 0.002 }
        val series = seriesOf(closes)
        val snapshot = CalibratedSignalEngine.analyze(series, chartConfidence = 0.9, candleQuality = 0.9, asset = "EUR/USD", timeframeSeconds = 60, timestampMillis = 1L)
        assertEquals(Signal.DOWN, snapshot.signal)
    }

    @Test
    fun `waits when indicators conflict`() {
        // 55 large gains give RSI-14 an overwhelmingly bullish reading that
        // barely decays, while the final 5 candles tick down by a tiny
        // amount each — enough to flip the short-term trend vote to
        // DOWNTREND without meaningfully affecting RSI/EMA/SMA.
        val rising = (0 until 55).map { 1.0 + it * 0.02 }
        val peak = rising.last()
        val tinyDeclines = (1..5).map { peak - it * 0.0001 }
        val closes = rising + tinyDeclines
        val series = seriesOf(closes)
        val snapshot = CalibratedSignalEngine.analyze(series, chartConfidence = 0.9, candleQuality = 0.9, asset = "EUR/USD", timeframeSeconds = 60, timestampMillis = 1L)
        assertEquals(Signal.WAIT, snapshot.signal)
        assertTrue(snapshot.reason!!.contains("disagree"))
    }

    @Test
    fun `waits when calibration confidence is too low`() {
        val closes = (0 until 60).map { 1.0 + it * 0.002 }
        val series = seriesOf(closes, confidence = 0.9).let {
            // Force a low series (calibration) confidence directly.
            VerifiedCandleSeries(it.candles, it.rejectedCount, it.duplicatesRemoved, it.gapsDetected, seriesConfidence = 0.2)
        }
        val snapshot = CalibratedSignalEngine.analyze(series, chartConfidence = 0.9, candleQuality = 0.9, asset = null, timeframeSeconds = null, timestampMillis = 1L)
        assertEquals(Signal.WAIT, snapshot.signal)
        assertTrue(snapshot.reason!!.contains("calibration"))
    }

    @Test
    fun `waits when candle recognition confidence is too low`() {
        val closes = (0 until 60).map { 1.0 + it * 0.002 }
        val series = seriesOf(closes)
        val snapshot = CalibratedSignalEngine.analyze(series, chartConfidence = 0.9, candleQuality = 0.2, asset = null, timeframeSeconds = null, timestampMillis = 1L)
        assertEquals(Signal.WAIT, snapshot.signal)
        assertTrue(snapshot.reason!!.contains("candle recognition"))
    }

    @Test
    fun `waits when there is insufficient verified candle history`() {
        val closes = (0 until 5).map { 1.0 + it * 0.002 }
        val series = seriesOf(closes)
        val snapshot = CalibratedSignalEngine.analyze(series, chartConfidence = 0.9, candleQuality = 0.9, asset = null, timeframeSeconds = null, timestampMillis = 1L)
        assertEquals(Signal.WAIT, snapshot.signal)
        assertTrue(snapshot.reason!!.contains("Insufficient"))
    }
}
