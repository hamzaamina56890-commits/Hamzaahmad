package com.chineseboot.android.core.analysis

import com.chineseboot.android.core.model.AnalysisSnapshot
import com.chineseboot.android.core.model.Candle
import com.chineseboot.android.core.model.CandleDirection
import com.chineseboot.android.core.model.ChartDetectionState
import com.chineseboot.android.core.model.Signal
import kotlin.math.abs
import kotlin.math.min

/**
 * Turns a real, detected candle sequence into an [AnalysisSnapshot].
 *
 * This never fabricates data: if [candles] is empty the caller should use
 * [AnalysisSnapshot.notDetected] instead of calling [analyze]. If there isn't
 * enough candle history to compute an indicator, that field is left null and
 * the overall state is [ChartDetectionState.WAITING_FOR_MORE_DATA].
 */
object SignalEngine {
    private const val MIN_CANDLES_FOR_SIGNAL = 15
    private const val RSI_PERIOD = 14

    fun analyze(
        candles: List<Candle>,
        asset: String?,
        timeframeSeconds: Int?,
        timestampMillis: Long,
    ): AnalysisSnapshot {
        if (candles.isEmpty()) {
            return AnalysisSnapshot.notDetected(timestampMillis)
        }

        val last = candles.last()
        val direction = when {
            last.close > last.open -> CandleDirection.UP
            last.close < last.open -> CandleDirection.DOWN
            else -> CandleDirection.FLAT
        }
        val range = last.high - last.low
        val strength = if (range > 0) min(1.0, abs(last.close - last.open) / range) else 0.0

        val closes = candles.map { it.close }
        val rsi = RsiCalculator.compute(closes, RSI_PERIOD)
        val trend = TrendAnalyzer.trend(candles)
        val levels = TrendAnalyzer.supportResistance(candles)

        if (candles.size < MIN_CANDLES_FOR_SIGNAL || rsi == null) {
            return AnalysisSnapshot(
                state = ChartDetectionState.WAITING_FOR_MORE_DATA,
                asset = asset,
                timeframeSeconds = timeframeSeconds,
                price = last.close,
                direction = direction,
                strength = strength,
                rsi = rsi,
                trend = trend,
                support = levels?.first,
                resistance = levels?.second,
                analysisWindow = candles.size,
                timestampMillis = timestampMillis,
            )
        }

        val signal = when {
            rsi > 60.0 -> Signal.UP
            rsi < 40.0 -> Signal.DOWN
            else -> Signal.WAIT
        }
        val confidence = when (signal) {
            Signal.WAIT -> 50
            else -> min(95, 50 + abs(rsi - 50.0).toInt())
        }
        val reason = when (signal) {
            Signal.UP -> "RSI ${"%.1f".format(rsi)} above 60"
            Signal.DOWN -> "RSI ${"%.1f".format(rsi)} below 40"
            Signal.WAIT -> "RSI ${"%.1f".format(rsi)} neutral"
        }

        return AnalysisSnapshot(
            state = ChartDetectionState.READY,
            asset = asset,
            timeframeSeconds = timeframeSeconds,
            price = last.close,
            direction = direction,
            strength = strength,
            rsi = rsi,
            trend = trend,
            support = levels?.first,
            resistance = levels?.second,
            signal = signal,
            confidencePercent = confidence,
            reason = reason,
            analysisWindow = candles.size,
            timestampMillis = timestampMillis,
        )
    }
}
