package com.chineseboot.android.core.analysis

/**
 * Wilder's smoothed RSI, ported from `backend/analysis/signal_engine.py` so the
 * on-device analysis matches the server-side algorithm. Returns null when there
 * is not enough history (period + 1 closes) — never a fabricated value.
 */
object RsiCalculator {
    fun compute(closes: List<Double>, period: Int = 14): Double? {
        if (closes.size < period + 1) return null

        val gains = DoubleArray(closes.size - 1)
        val losses = DoubleArray(closes.size - 1)
        for (i in 1 until closes.size) {
            val change = closes[i] - closes[i - 1]
            if (change >= 0) {
                gains[i - 1] = change
            } else {
                losses[i - 1] = -change
            }
        }

        var avgGain = gains.take(period).sum() / period
        var avgLoss = losses.take(period).sum() / period

        for (i in period until gains.size) {
            avgGain = (avgGain * (period - 1) + gains[i]) / period
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }
}
