package com.chineseboot.android.core.analysis

import com.chineseboot.android.core.model.Signal

/**
 * Combines every independent piece of verified evidence into a single
 * 0-100 confidence score. Deliberately conservative: any weak input pulls
 * the whole score down rather than being averaged away.
 */
object ConfidenceCalculator {
    fun compute(
        chartConfidence: Double,
        candleQuality: Double,
        calibrationConfidence: Double,
        verifiedCandleCount: Int,
        minVerifiedCandles: Int,
        indicatorAgreementCount: Int,
        totalIndicatorsConsidered: Int,
        trendAgrees: Boolean,
        signal: Signal,
    ): Int {
        if (signal == Signal.WAIT) {
            // A WAIT signal is never reported as high-confidence "prediction" —
            // its confidence reflects how strong the case for waiting is.
            val base = (1.0 - ((chartConfidence + candleQuality + calibrationConfidence) / 3.0)).coerceIn(0.0, 1.0)
            return (base * 60).toInt().coerceIn(0, 60)
        }

        val historyFactor = (verifiedCandleCount.toDouble() / (minVerifiedCandles * 2)).coerceIn(0.0, 1.0)
        val agreementFactor = if (totalIndicatorsConsidered <= 0) {
            0.0
        } else {
            indicatorAgreementCount.toDouble() / totalIndicatorsConsidered
        }
        val trendFactor = if (trendAgrees) 1.0 else 0.5

        val score = (
            chartConfidence * 0.20 +
                candleQuality * 0.20 +
                calibrationConfidence * 0.20 +
                historyFactor * 0.10 +
                agreementFactor * 0.20 +
                trendFactor * 0.10
            ).coerceIn(0.0, 1.0)

        return (score * 100).toInt().coerceIn(0, 100)
    }
}
