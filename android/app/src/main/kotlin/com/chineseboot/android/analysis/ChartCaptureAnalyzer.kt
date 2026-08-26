package com.chineseboot.android.analysis

import com.chineseboot.android.core.vision.ChartRecognitionEngine
import com.chineseboot.android.core.vision.ChartRecognitionResult
import com.chineseboot.android.core.vision.FrameBuffer
import com.chineseboot.android.core.vision.PixelRect

/**
 * Runs the real chart-region/candle/color-convention recognition pipeline
 * (`core.vision`) against a captured frame. Never fabricates a result: an
 * absent/low-confidence chart region or unreliable candles are reported
 * honestly through [ChartRecognitionResult.state].
 */
class ChartCaptureAnalyzer(
    private val engine: ChartRecognitionEngine = ChartRecognitionEngine(),
) {
    fun analyze(frame: FrameBuffer, excludeRegions: List<PixelRect>): ChartRecognitionResult =
        engine.analyze(frame, excludeRegions)
}
