package com.chineseboot.android.core.vision

/**
 * Reads visible chart price-axis labels and timeframe text (e.g. via OCR).
 * Implementations must return `null`/empty whenever they are not confident
 * — never a guess. The default implementation always returns nothing
 * because on-device OCR (e.g. ML Kit Text Recognition) is not wired up yet;
 * see project notes for remaining work.
 */
interface PriceLabelDetector {
    fun detectPriceLabels(frame: FrameBuffer, region: ChartRegion): List<DetectedPriceLabel>
    fun detectTimeframeLabel(frame: FrameBuffer, region: ChartRegion): String?
}

object NoOpPriceLabelDetector : PriceLabelDetector {
    override fun detectPriceLabels(frame: FrameBuffer, region: ChartRegion): List<DetectedPriceLabel> = emptyList()
    override fun detectTimeframeLabel(frame: FrameBuffer, region: ChartRegion): String? = null
}
