package com.chineseboot.android.analysis

import com.chineseboot.android.core.analysis.AnalysisOrchestrator
import com.chineseboot.android.core.model.AnalysisSnapshot
import com.chineseboot.android.core.vision.ChartRecognitionEngine
import com.chineseboot.android.core.vision.ChartRecognitionResult
import com.chineseboot.android.core.vision.FrameBuffer
import com.chineseboot.android.core.vision.NoOpPriceLabelDetector
import com.chineseboot.android.core.vision.PixelRect
import com.chineseboot.android.core.vision.PriceLabelDetector

/**
 * Runs the full recognition -> calibration -> analysis pipeline against a
 * captured frame: chart-region/candle/color-convention recognition
 * (`core.vision`), price/timeframe label reading via [priceLabelDetector],
 * then [AnalysisOrchestrator] for calibration + the final UP/DOWN/WAIT
 * signal. Never fabricates a result: any stage that isn't confident enough
 * causes the final snapshot to be WAIT (or CHART_NOT_DETECTED), reported
 * honestly through [Result.snapshot].
 */
class ChartCaptureAnalyzer(
    private val engine: ChartRecognitionEngine = ChartRecognitionEngine(),
    private val priceLabelDetector: PriceLabelDetector = NoOpPriceLabelDetector,
) {
    data class Result(val recognition: ChartRecognitionResult, val snapshot: AnalysisSnapshot)

    fun analyze(frame: FrameBuffer, excludeRegions: List<PixelRect>): Result {
        val recognition = engine.analyze(frame, excludeRegions)
        val region = recognition.region

        val priceLabels = if (region != null) priceLabelDetector.detectPriceLabels(frame, region) else emptyList()
        val timeframeLabel = region?.let { priceLabelDetector.detectTimeframeLabel(frame, it) }

        val snapshot = AnalysisOrchestrator.analyze(recognition, priceLabels, timeframeLabel)
        return Result(recognition, snapshot)
    }
}
