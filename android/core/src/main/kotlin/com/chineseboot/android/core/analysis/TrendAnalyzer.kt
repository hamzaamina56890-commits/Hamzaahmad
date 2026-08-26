package com.chineseboot.android.core.analysis

import com.chineseboot.android.core.model.Candle

/**
 * Simple, data-driven trend and support/resistance detection.
 * Returns null fields whenever there isn't enough history to make a
 * reliable determination — it never guesses.
 */
object TrendAnalyzer {
    private const val MIN_CANDLES_FOR_TREND = 5
    private const val MIN_CANDLES_FOR_LEVELS = 10

    fun trend(candles: List<Candle>): String? {
        if (candles.size < MIN_CANDLES_FOR_TREND) return null
        val closes = candles.map { it.close }
        val recent = closes.takeLast(MIN_CANDLES_FOR_TREND)
        val rising = recent.zipWithNext().count { (a, b) -> b > a }
        val falling = recent.zipWithNext().count { (a, b) -> b < a }
        return when {
            rising > falling -> "UPTREND"
            falling > rising -> "DOWNTREND"
            else -> "SIDEWAYS"
        }
    }

    /** Returns support/resistance as (support, resistance), or null if not enough data. */
    fun supportResistance(candles: List<Candle>): Pair<Double, Double>? {
        if (candles.size < MIN_CANDLES_FOR_LEVELS) return null
        val window = candles.takeLast(MIN_CANDLES_FOR_LEVELS)
        val support = window.minOf { it.low }
        val resistance = window.maxOf { it.high }
        return support to resistance
    }
}
