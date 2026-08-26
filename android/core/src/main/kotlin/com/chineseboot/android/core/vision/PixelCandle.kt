package com.chineseboot.android.core.vision

/** Bullish/bearish classification derived purely from detected chart pixel colors. */
enum class PixelCandleDirection { BULLISH, BEARISH, UNKNOWN }

/**
 * A candle as extracted directly from pixels — geometry only, no market
 * meaning attached. [detectionConfidence] reflects how consistent/clean the
 * extracted shape was, not whether the "price" is accurate (there is no
 * price here at all).
 */
data class PixelCandle(
    val xPosition: Int,
    val width: Int,
    val bodyTop: Int,
    val bodyBottom: Int,
    val wickTop: Int,
    val wickBottom: Int,
    val direction: PixelCandleDirection = PixelCandleDirection.UNKNOWN,
    val detectionConfidence: Double = 1.0,
    val sourceFrameTimestampMillis: Long = 0L,
    val rawColor: Int = 0,
) {
    val xCenter: Int get() = xPosition
    val color: PixelCandleDirection get() = direction
    val confidence: Double get() = detectionConfidence
}

/**
 * A candle with real OHLC values. Can only be constructed from a
 * [PixelCandle] plus a verified [PriceScaleCalibration] — there is
 * intentionally no path that fabricates one from geometry alone. Geometry
 * that is impossible (e.g. high below the body) is rejected by returning
 * `null` rather than silently producing a corrupted candle.
 */
data class CalibratedCandle(
    val pixel: PixelCandle,
    val openTimeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val calibrationConfidence: Double,
) {
    val detectionConfidence: Double get() = pixel.detectionConfidence
    val bullish: Boolean get() = close >= open
    val bodySize: Double get() = kotlin.math.abs(close - open)
    val upperWick: Double get() = high - maxOf(open, close)
    val lowerWick: Double get() = minOf(open, close) - low

    companion object {
        private const val MIN_PIXEL_HEIGHT = 1

        fun from(pixel: PixelCandle, calibration: PriceScaleCalibration, openTimeMillis: Long): CalibratedCandle? {
            // Reject candles with degenerate pixel geometry before trusting them.
            if (pixel.wickBottom - pixel.wickTop < MIN_PIXEL_HEIGHT) return null
            if (pixel.bodyTop > pixel.bodyBottom) return null

            // Candle drawing convention: the top of the wick is the highest traded
            // price, the bottom of the wick is the lowest. Open/close come from the
            // body edges; which edge is "open" vs "close" depends on direction.
            val high = calibration.priceAt(pixel.wickTop)
            val low = calibration.priceAt(pixel.wickBottom)
            val bodyEdgeA = calibration.priceAt(pixel.bodyTop)
            val bodyEdgeB = calibration.priceAt(pixel.bodyBottom)
            val (open, close) = when (pixel.direction) {
                PixelCandleDirection.BULLISH -> bodyEdgeB to bodyEdgeA
                PixelCandleDirection.BEARISH -> bodyEdgeA to bodyEdgeB
                PixelCandleDirection.UNKNOWN -> bodyEdgeB to bodyEdgeA
            }

            // Reject impossible geometry rather than fabricating a "close enough" candle.
            if (high < low) return null
            if (high < maxOf(open, close) - 1e-9) return null
            if (low > minOf(open, close) + 1e-9) return null

            return CalibratedCandle(pixel, openTimeMillis, open, high, low, close, calibration.confidence)
        }
    }
}
