package com.chineseboot.android.core.vision

/**
 * Attempts to read the visible asset name / selected timeframe from chart
 * UI text. Implementations must return `null` whenever they are not
 * confident — never a guess. The default implementation always returns
 * `null` because on-device OCR (e.g. ML Kit Text Recognition) is not wired
 * up yet; see project notes for remaining work.
 */
interface AssetTimeframeDetector {
    fun detectAsset(frame: FrameBuffer, region: ChartRegion): String?
    fun detectTimeframeSeconds(frame: FrameBuffer, region: ChartRegion): Int?
}

object NoOpAssetTimeframeDetector : AssetTimeframeDetector {
    override fun detectAsset(frame: FrameBuffer, region: ChartRegion): String? = null
    override fun detectTimeframeSeconds(frame: FrameBuffer, region: ChartRegion): Int? = null
}

/** Timeframes the product supports recognizing (in seconds), per the spec. */
object SupportedTimeframes {
    val SECONDS = listOf(5, 10, 15, 30, 60, 120, 180, 300)
}
