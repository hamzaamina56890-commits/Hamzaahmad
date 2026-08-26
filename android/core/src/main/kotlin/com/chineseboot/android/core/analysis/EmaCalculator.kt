package com.chineseboot.android.core.analysis

/**
 * Exponential Moving Average, seeded with an SMA of the first [period]
 * values. Returns `null` when there isn't enough history.
 */
object EmaCalculator {
    fun compute(closes: List<Double>, period: Int): Double? {
        if (period <= 0 || closes.size < period) return null

        val multiplier = 2.0 / (period + 1)
        var ema = closes.take(period).average()
        for (i in period until closes.size) {
            ema = (closes[i] - ema) * multiplier + ema
        }
        return ema
    }
}
