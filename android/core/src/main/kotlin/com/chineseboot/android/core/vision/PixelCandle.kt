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
 * Maps a pixel Y coordinate within a detected chart region to a real price,
 * and a candle index to a real open time. Only ever created once a genuine,
 * verifiable price/time scale has been read from the chart's own axis labels
 * — never inferred or guessed.
 */
class PriceScaleCalibration(
    private val pixelToPrice: (Int) -> Double,
    val timeframeSeconds: Int,
) {
    fun priceAt(pixelY: Int): Double = pixelToPrice(pixelY)
}

/**
 * A candle with real OHLC values. Can only be constructed from a
 * [PixelCandle] plus a verified [PriceScaleCalibration] — there is
 * intentionally no path that fabricates one from geometry alone.
 */
data class CalibratedCandle(
    val pixel: PixelCandle,
    val openTimeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
) {
    companion object {
        fun from(pixel: PixelCandle, calibration: PriceScaleCalibration, openTimeMillis: Long): CalibratedCandle {
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
            return CalibratedCandle(pixel, openTimeMillis, open, high, low, close)
        }
    }
}
