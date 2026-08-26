package com.chineseboot.android.core.analysis

import com.chineseboot.android.core.model.Candle
import com.chineseboot.android.core.model.ChartDetectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrendAnalyzerTest {
    private fun candle(open: Double, close: Double, high: Double, low: Double) =
        Candle(openTimeMillis = 0, open = open, high = high, low = low, close = close)

    @Test
    fun `trend is null with too few candles`() {
        val candles = listOf(candle(1.0, 1.01, 1.02, 0.99))
        assertNull(TrendAnalyzer.trend(candles))
    }

    @Test
    fun `detects uptrend`() {
        val candles = (0..5).map { candle(1.0 + it * 0.01, 1.01 + it * 0.01, 1.02 + it * 0.01, 0.99 + it * 0.01) }
        assertEquals("UPTREND", TrendAnalyzer.trend(candles))
    }

    @Test
    fun `support resistance null with too few candles`() {
        val candles = (0..3).map { candle(1.0, 1.0, 1.0, 1.0) }
        assertNull(TrendAnalyzer.supportResistance(candles))
    }

    @Test
    fun `support resistance computed from window highs and lows`() {
        val candles = (0..15).map { candle(1.0, 1.0, 1.0 + it * 0.001, 1.0 - it * 0.001) }
        val levels = TrendAnalyzer.supportResistance(candles)
        requireNotNull(levels)
        assertEquals(candles.takeLast(10).minOf { it.low }, levels.first)
        assertEquals(candles.takeLast(10).maxOf { it.high }, levels.second)
    }
}
