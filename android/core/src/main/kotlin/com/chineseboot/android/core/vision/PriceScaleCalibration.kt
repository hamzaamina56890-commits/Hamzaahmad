package com.chineseboot.android.core.vision

import kotlin.math.abs

/**
 * A verified pixelY -> price transformation fitted from real detected price
 * labels on the chart's own axis. Never constructed from a guess: see
 * [PriceScaleCalibrator.calibrate], the only way to obtain one.
 *
 * `price = scale * pixelY + offset`
 */
data class PriceScaleCalibration(
    val scale: Double,
    val offset: Double,
    val confidence: Double,
    val timestampMillis: Long,
    val labelCount: Int,
    val decimalPrecision: Int,
) {
    fun priceAt(pixelY: Int): Double = scale * pixelY + offset
}

/**
 * Fits a robust pixelY -> price transformation from OCR-detected price
 * labels. Refuses to produce a calibration ("fails safe") whenever the
 * evidence isn't good enough — callers must treat a `null` result as
 * "calibration unavailable", never fall back to a fabricated scale.
 */
object PriceScaleCalibrator {
    const val MIN_VALID_LABELS = 2
    const val MIN_OCR_CONFIDENCE = 0.55
    const val MAX_RESIDUAL_FRACTION = 0.08

    fun calibrate(labels: List<DetectedPriceLabel>, timestampMillis: Long): PriceScaleCalibration? {
        val parsed = labels.mapNotNull { label ->
            if (label.ocrConfidence < MIN_OCR_CONFIDENCE) return@mapNotNull null
            val price = PriceLabelParser.parse(label.rawText) ?: return@mapNotNull null
            Triple(label.pixelY, price.value, price.decimalPlaces)
        }

        // Deduplicate by pixelY (keep first) and require distinct pixel rows.
        val distinctByPixelY = parsed.distinctBy { it.first }
        if (distinctByPixelY.size < MIN_VALID_LABELS) return null

        val xs = distinctByPixelY.map { it.first.toDouble() }
        val ys = distinctByPixelY.map { it.second }

        // All labels must share a consistent scale direction: as pixelY moves
        // in one direction, price must consistently move one way (or stay
        // flat) — a sign-flipping set of labels means OCR is unreliable.
        val n = xs.size
        val meanX = xs.average()
        val meanY = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (xs[i] - meanX) * (ys[i] - meanY)
            sxx += (xs[i] - meanX) * (xs[i] - meanX)
        }
        if (sxx == 0.0) return null // all labels at the same pixel row — cannot fit a scale

        val scale = sxy / sxx
        val offset = meanY - scale * meanX

        // Residual error: how far actual label prices deviate from the fitted line.
        val priceRange = (ys.maxOrNull() ?: 0.0) - (ys.minOrNull() ?: 0.0)
        if (priceRange <= 0.0) return null

        var maxResidual = 0.0
        for (i in 0 until n) {
            val predicted = scale * xs[i] + offset
            maxResidual = maxOf(maxResidual, abs(predicted - ys[i]))
        }
        val residualFraction = maxResidual / priceRange
        if (residualFraction > MAX_RESIDUAL_FRACTION) return null

        val avgOcrConfidence = labels.filter { it.ocrConfidence >= MIN_OCR_CONFIDENCE }
            .map { it.ocrConfidence }
            .average()
        val fitQuality = (1.0 - (residualFraction / MAX_RESIDUAL_FRACTION)).coerceIn(0.0, 1.0)
        val labelCountBonus = ((n - MIN_VALID_LABELS).toDouble() / 3.0).coerceIn(0.0, 1.0)
        val confidence = (avgOcrConfidence * 0.5 + fitQuality * 0.4 + labelCountBonus * 0.1).coerceIn(0.0, 1.0)

        val decimalPrecision = distinctByPixelY.maxOf { it.third }

        return PriceScaleCalibration(
            scale = scale,
            offset = offset,
            confidence = confidence,
            timestampMillis = timestampMillis,
            labelCount = n,
            decimalPrecision = decimalPrecision,
        )
    }
}
