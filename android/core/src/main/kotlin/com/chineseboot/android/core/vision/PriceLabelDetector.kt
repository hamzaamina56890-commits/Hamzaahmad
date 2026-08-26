package com.chineseboot.android.core.vision

/**
 * Reads visible chart price-axis labels and timeframe text (e.g. via OCR).
 * Implementations must return `null`/empty whenever they are not confident
 * — never a guess. OCR is suspendable because on-device recognizers complete
 * asynchronously; callers must invoke it off the UI thread.
 */
interface PriceLabelDetector {
    suspend fun detect(frame: FrameBuffer, region: ChartRegion): PriceLabelDetection
}

data class PriceLabelDetection(
    val labels: List<DetectedPriceLabel> = emptyList(),
    val timeframeLabel: String? = null,
)

object NoOpPriceLabelDetector : PriceLabelDetector {
    override suspend fun detect(frame: FrameBuffer, region: ChartRegion): PriceLabelDetection = PriceLabelDetection()
}
