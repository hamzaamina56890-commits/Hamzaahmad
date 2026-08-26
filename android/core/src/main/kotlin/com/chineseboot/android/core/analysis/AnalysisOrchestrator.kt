package com.chineseboot.android.core.analysis

import com.chineseboot.android.core.model.AnalysisSnapshot
import com.chineseboot.android.core.model.ChartDetectionState
import com.chineseboot.android.core.model.RecognitionStatus
import com.chineseboot.android.core.model.Signal
import com.chineseboot.android.core.vision.CalibratedCandle
import com.chineseboot.android.core.vision.CandleSeriesBuilder
import com.chineseboot.android.core.vision.ChartRecognitionResult
import com.chineseboot.android.core.vision.ChartRecognitionStatus
import com.chineseboot.android.core.vision.DetectedPriceLabel
import com.chineseboot.android.core.vision.PriceScaleCalibrator
import com.chineseboot.android.core.vision.TimeframeCalibrator

/**
 * Top-level entry point: pixel chart recognition + OCR price/timeframe
 * evidence -> a final honest [AnalysisSnapshot]. This is the only place that
 * connects calibration to the signal engine — every stage can bail out to
 * WAIT rather than ever fabricating prices, candles, or a signal.
 */
object AnalysisOrchestrator {
    fun analyze(
        recognition: ChartRecognitionResult,
        priceLabels: List<DetectedPriceLabel> = emptyList(),
        timeframeLabelText: String? = null,
    ): AnalysisSnapshot {
        val timestamp = recognition.frameTimestampMillis
        val timeframeSeconds = TimeframeCalibrator.resolve(timeframeLabelText, recognition.timeframeSeconds)

        return when (recognition.status) {
            ChartRecognitionStatus.CHART_NOT_DETECTED -> AnalysisSnapshot.notDetected(timestamp)

            ChartRecognitionStatus.CANDLES_NOT_RELIABLE -> AnalysisSnapshot(
                state = ChartDetectionState.WAITING_FOR_MORE_DATA,
                asset = recognition.asset,
                timeframeSeconds = timeframeSeconds,
                signal = Signal.WAIT,
                confidencePercent = 0,
                reason = "WAIT \u2014 CANDLE DATA UNRELIABLE",
                analysisWindow = recognition.candles.size,
                timestampMillis = timestamp,
                recognitionStatus = RecognitionStatus.CANDLES_NOT_DETECTED,
            )

            ChartRecognitionStatus.READY -> {
                val calibration = PriceScaleCalibrator.calibrate(priceLabels, timestamp)
                if (calibration == null) {
                    AnalysisSnapshot(
                        state = ChartDetectionState.WAITING_FOR_MORE_DATA,
                        asset = recognition.asset,
                        timeframeSeconds = timeframeSeconds,
                        signal = Signal.WAIT,
                        confidencePercent = 0,
                        reason = "WAIT \u2014 PRICE SCALE UNAVAILABLE",
                        analysisWindow = recognition.candles.size,
                        timestampMillis = timestamp,
                        candleColorKnown = recognition.candleColorKnown,
                        recognitionStatus = RecognitionStatus.CALIBRATION_FAILED,
                    )
                } else {
                    val calibratedCandidates = recognition.candles.mapNotNull { pixel ->
                        CalibratedCandle.from(pixel, calibration, openTimeMillis = pixel.sourceFrameTimestampMillis)
                    }
                    val series = CandleSeriesBuilder.build(calibratedCandidates)
                    CalibratedSignalEngine.analyze(
                        series = series,
                        chartConfidence = recognition.region?.confidence ?: 0.0,
                        candleQuality = recognition.candleQuality,
                        asset = recognition.asset,
                        timeframeSeconds = timeframeSeconds,
                        timestampMillis = timestamp,
                    ).let { snapshot ->
                        snapshot.copy(
                            recognitionStatus = when (snapshot.signal) {
                                Signal.UP, Signal.DOWN -> RecognitionStatus.SIGNAL_READY
                                Signal.WAIT -> if (snapshot.analysisWindow < CalibratedSignalEngine.MIN_VERIFIED_CANDLES) {
                                    RecognitionStatus.INSUFFICIENT_DATA
                                } else {
                                    RecognitionStatus.VERIFIED
                                }
                                null -> RecognitionStatus.VERIFIED
                            },
                        )
                    }
                }
            }
        }
    }
}
