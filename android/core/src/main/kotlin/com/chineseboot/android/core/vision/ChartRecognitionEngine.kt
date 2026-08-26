package com.chineseboot.android.core.vision

/**
 * Entry point for analyzing a frame buffer against exclusion regions.
 */
class ChartRecognitionEngine(
    private val pipeline: ChartRecognitionPipeline = ChartRecognitionPipeline(),
) {
    fun analyze(frame: FrameBuffer, excludeRegions: List<PixelRect> = emptyList()): ChartRecognitionResult =
        pipeline.analyze(frame, excludeRegions)
}
