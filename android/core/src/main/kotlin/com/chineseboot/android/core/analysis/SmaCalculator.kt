package com.chineseboot.android.core.analysis

/**
 * Simple Moving Average. Returns `null` when there isn't enough history —
 * never a fabricated/partial average.
 */
object SmaCalculator {
    fun compute(closes: List<Double>, period: Int): Double? {
        if (period <= 0 || closes.size < period) return null
        return closes.takeLast(period).average()
    }
}
