package com.chineseboot.android.core.vision

/**
 * Learned color mapping rule that classifies a pixel color as bullish, bearish, or unknown.
 */
class ColorConvention(
    val greenCount: Int,
    val redCount: Int,
) {
    fun classify(color: Int): PixelCandleDirection {
        val sat = ColorUtils.saturation(color)
        if (sat < 0.2) return PixelCandleDirection.UNKNOWN
        val hue = ColorUtils.hueDegrees(color)
        return when {
            hue in 70.0..170.0 -> PixelCandleDirection.BULLISH
            hue <= 25.0 || hue >= 330.0 -> PixelCandleDirection.BEARISH
            else -> PixelCandleDirection.UNKNOWN
        }
    }
}

/** Result of attempting to learn a chart's bullish/bearish color convention. */
data class ColorConventionResult(
    val candles: List<PixelCandle>,
    val conventionDetected: Boolean,
    val conventionConfidence: Double,
)

/**
 * Determines each candle's bullish/bearish color from the chart's own pixels.
 */
object ColorConventionDetector {

    private const val MIN_SATURATION = 0.20
    private const val MIN_CLUSTER_SIZE = 2

    private enum class Bucket { GREENISH, REDDISH, OTHER }

    fun detect(colors: List<Int>): ColorConvention? {
        if (colors.size < MIN_CLUSTER_SIZE * 2) return null

        val buckets = colors.map { bucketOfColor(it) }
        val greenCount = buckets.count { it == Bucket.GREENISH }
        val redCount = buckets.count { it == Bucket.REDDISH }

        if (greenCount >= MIN_CLUSTER_SIZE && redCount >= MIN_CLUSTER_SIZE) {
            return ColorConvention(greenCount, redCount)
        }
        return null
    }

    fun classify(frame: FrameBuffer, candles: List<PixelCandle>): ColorConventionResult {
        if (candles.isEmpty()) return ColorConventionResult(candles, conventionDetected = false, conventionConfidence = 0.0)

        val convention = detect(candles.map { it.rawColor })
        if (convention == null) {
            return ColorConventionResult(
                candles.map { it.copy(direction = PixelCandleDirection.UNKNOWN) },
                conventionDetected = false,
                conventionConfidence = 0.0,
            )
        }

        val updated = candles.map { candle ->
            candle.copy(direction = convention.classify(candle.rawColor))
        }
        val countKnown = updated.count { it.direction != PixelCandleDirection.UNKNOWN }
        val confidence = countKnown.toDouble() / candles.size
        return ColorConventionResult(updated, conventionDetected = true, conventionConfidence = confidence)
    }

    private fun bucketOfColor(color: Int): Bucket {
        val s = ColorUtils.saturation(color)
        if (s < MIN_SATURATION) return Bucket.OTHER
        val h = ColorUtils.hueDegrees(color)
        return when {
            h in 70.0..170.0 -> Bucket.GREENISH
            h <= 25.0 || h >= 330.0 -> Bucket.REDDISH
            else -> Bucket.OTHER
        }
    }
}
