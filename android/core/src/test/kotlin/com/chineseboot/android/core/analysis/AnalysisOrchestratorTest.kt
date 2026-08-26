package com.chineseboot.android.core.analysis

import com.chineseboot.android.core.model.ChartDetectionState
import com.chineseboot.android.core.model.Signal
import com.chineseboot.android.core.vision.ChartRecognitionResult
import com.chineseboot.android.core.vision.ChartRegion
import com.chineseboot.android.core.vision.DetectedPriceLabel
import com.chineseboot.android.core.vision.PixelCandle
import com.chineseboot.android.core.vision.PixelCandleDirection
import com.chineseboot.android.core.vision.RecognitionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisOrchestratorTest {
    @Test
    fun `chart not detected passes through untouched`() {
        val recognition = ChartRecognitionResult.chartNotDetected(1L)
        val snapshot = AnalysisOrchestrator.analyze(recognition)
        assertEquals(ChartDetectionState.CHART_NOT_DETECTED, snapshot.state)
    }

    @Test
    fun `unreliable candles report WAIT with the candle-data-unreliable reason`() {
        val region = ChartRegion(0, 0, 100, 100, 0.8)
        val recognition = ChartRecognitionResult.candlesNotReliable(region, emptyList(), 1L)
        val snapshot = AnalysisOrchestrator.analyze(recognition)
        assertEquals(Signal.WAIT, snapshot.signal)
        assertTrue(snapshot.reason!!.contains("CANDLE DATA UNRELIABLE"))
    }

    @Test
    fun `ready recognition without price labels reports price-scale-unavailable`() {
        val recognition = readyRecognition()
        val snapshot = AnalysisOrchestrator.analyze(recognition, priceLabels = emptyList())
        assertEquals(Signal.WAIT, snapshot.signal)
        assertTrue(snapshot.reason!!.contains("PRICE SCALE UNAVAILABLE"))
    }

    @Test
    fun `ready recognition with valid calibration produces a full analysis`() {
        val recognition = readyRecognition(candleCount = 60)
        val labels = listOf(
            DetectedPriceLabel(10, "1.3000", 0.9),
            DetectedPriceLabel(90, "1.2000", 0.9),
        )
        val snapshot = AnalysisOrchestrator.analyze(recognition, priceLabels = labels, timeframeLabelText = "1m")
        assertEquals(60, snapshot.timeframeSeconds)
        assertTrue(snapshot.analysisWindow > 0)
    }

    private fun readyRecognition(candleCount: Int = 20): ChartRecognitionResult {
        val region = ChartRegion(0, 0, 200, 100, 0.9)
        val candles = (0 until candleCount).map { i ->
            PixelCandle(
                xPosition = i * 5, width = 3, bodyTop = 40, bodyBottom = 60, wickTop = 30, wickBottom = 70,
                direction = PixelCandleDirection.BULLISH, detectionConfidence = 0.9,
            )
        }
        return ChartRecognitionResult(
            state = RecognitionState.READY,
            region = region,
            candles = candles,
            candleColorKnown = true,
            asset = "EUR/USD",
            timeframeSeconds = null,
            candleQuality = 0.9,
            overallConfidence = 0.9,
            frameTimestampMillis = 1L,
        )
    }
}
