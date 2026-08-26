package com.chineseboot.android.core.analysis

import com.chineseboot.android.core.model.Candle
import com.chineseboot.android.core.model.ChartDetectionState
import com.chineseboot.android.core.model.Signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalEngineTest {
    private fun candle(open: Double, close: Double) =
        Candle(openTimeMillis = 0, open = open, high = maxOf(open, close) + 0.001, low = minOf(open, close) - 0.001, close = close)

    @Test
    fun `empty candles never fabricate a result`() {
        val snapshot = SignalEngine.analyze(emptyList(), asset = null, timeframeSeconds = null, timestampMillis = 1L)
        assertEquals(ChartDetectionState.CHART_NOT_DETECTED, snapshot.state)
        assertNull(snapshot.signal)
        assertNull(snapshot.rsi)
    }

    @Test
    fun `waits for more data below minimum candle count`() {
        val candles = (0..4).map { candle(1.0, 1.01) }
        val snapshot = SignalEngine.analyze(candles, asset = "EUR/USD", timeframeSeconds = 60, timestampMillis = 2L)
        assertEquals(ChartDetectionState.WAITING_FOR_MORE_DATA, snapshot.state)
        assertNull(snapshot.signal)
    }

    @Test
    fun `produces a signal once enough rising candles are present`() {
        val candles = (0..20).map { candle(1.0 + it * 0.001, 1.001 + it * 0.001) }
        val snapshot = SignalEngine.analyze(candles, asset = "EUR/USD", timeframeSeconds = 60, timestampMillis = 3L)
        assertEquals(ChartDetectionState.READY, snapshot.state)
        assertEquals(Signal.UP, snapshot.signal)
        assertEquals("EUR/USD", snapshot.asset)
    }
}
