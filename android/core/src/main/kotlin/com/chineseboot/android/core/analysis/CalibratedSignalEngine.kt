package com.chineseboot.android.core.analysis

import com.chineseboot.android.core.model.AnalysisSnapshot
import com.chineseboot.android.core.model.Candle
import com.chineseboot.android.core.model.CandleDirection
import com.chineseboot.android.core.model.ChartDetectionState
import com.chineseboot.android.core.model.Signal
import com.chineseboot.android.core.vision.VerifiedCandleSeries
import kotlin.math.abs
import kotlin.math.min

/**
 * Final analysis stage: turns a [VerifiedCandleSeries] (real, calibrated
 * OHLC candles derived from actual detected pixels + a verified price-axis
 * calibration) into a UP/DOWN/WAIT [AnalysisSnapshot].
 *
 * Conservative by design:
 *  - WAIT whenever calibration/candle-recognition quality is low.
 *  - WAIT whenever there isn't enough verified history.
 *  - WAIT whenever indicators disagree (no majority confirmation).
 *  - UP/DOWN only when at least [MIN_CONFIRMATIONS] independent signals agree
 *    with no opposing votes.
 */
object CalibratedSignalEngine {
    const val MIN_VERIFIED_CANDLES = 15
    const val MIN_CONFIRMATIONS = 3
    const val RSI_PERIOD = 14
    const val SMA_SHORT_PERIOD = 20
    const val SMA_LONG_PERIOD = 50
    const val EMA_PERIOD = 9

    /** Minimum chart/candle/calibration quality required before even considering a signal. */
    private const val MIN_QUALITY_FOR_SIGNAL = 0.5

    fun analyze(
        series: VerifiedCandleSeries,
        chartConfidence: Double,
        candleQuality: Double,
        asset: String?,
        timeframeSeconds: Int?,
        timestampMillis: Long,
    ): AnalysisSnapshot {
        val candles = series.candles

        if (candles.size < MIN_VERIFIED_CANDLES) {
            return AnalysisSnapshot(
                state = ChartDetectionState.WAITING_FOR_MORE_DATA,
                asset = asset,
                timeframeSeconds = timeframeSeconds,
                signal = Signal.WAIT,
                confidencePercent = 0,
                reason = "Insufficient verified candles (${candles.size}/$MIN_VERIFIED_CANDLES)",
                analysisWindow = candles.size,
                timestampMillis = timestampMillis,
                candleColorKnown = true,
                calibrationQuality = series.seriesConfidence,
            )
        }

        val modelCandles = candles.map { c ->
            Candle(openTimeMillis = c.openTimeMillis, open = c.open, high = c.high, low = c.low, close = c.close)
        }
        val closes = modelCandles.map { it.close }
        val last = modelCandles.last()

        val rsi = RsiCalculator.compute(closes, RSI_PERIOD)
        val sma20 = SmaCalculator.compute(closes, SMA_SHORT_PERIOD)
        val sma50 = SmaCalculator.compute(closes, SMA_LONG_PERIOD)
        val ema9 = EmaCalculator.compute(closes, EMA_PERIOD)
        val trend = TrendAnalyzer.trend(modelCandles)
        val levels = TrendAnalyzer.supportResistance(modelCandles)

        val direction = when {
            last.close > last.open -> CandleDirection.UP
            last.close < last.open -> CandleDirection.DOWN
            else -> CandleDirection.FLAT
        }
        val range = last.high - last.low
        val strength = if (range > 0) min(1.0, abs(last.close - last.open) / range) else 0.0

        val bullishVotes = mutableListOf<String>()
        val bearishVotes = mutableListOf<String>()
        var indicatorsConsidered = 0

        if (rsi != null) {
            indicatorsConsidered++
            if (rsi > 60.0) bullishVotes += "RSI-14 (${fmt(rsi)}) supports bullish momentum"
            else if (rsi < 40.0) bearishVotes += "RSI-14 (${fmt(rsi)}) supports bearish momentum"
        }
        if (ema9 != null && sma20 != null) {
            indicatorsConsidered++
            if (ema9 > sma20) bullishVotes += "EMA-9 above SMA-20"
            else if (ema9 < sma20) bearishVotes += "EMA-9 below SMA-20"
        }
        if (sma20 != null && sma50 != null) {
            indicatorsConsidered++
            if (sma20 > sma50) bullishVotes += "SMA-20 above SMA-50"
            else if (sma20 < sma50) bearishVotes += "SMA-20 below SMA-50"
        }
        if (trend != null) {
            indicatorsConsidered++
            when (trend) {
                "UPTREND" -> bullishVotes += "recent candles show bullish continuation"
                "DOWNTREND" -> bearishVotes += "recent candles show bearish continuation"
            }
        }
        if (levels != null) {
            indicatorsConsidered++
            val (support, resistance) = levels
            val zone = (resistance - support) * 0.15
            if (zone > 0.0) {
                // Only counted as corroborating evidence in the direction the
                // level implies — never let "near support" and "near
                // resistance" fire simultaneously for the same candle.
                if (direction == CandleDirection.UP && last.close - support in 0.0..zone) {
                    bullishVotes += "price holding above detected support"
                } else if (direction == CandleDirection.DOWN && resistance - last.close in 0.0..zone) {
                    bearishVotes += "price rejecting near detected resistance"
                }
            }
        }

        val qualityOk = chartConfidence >= MIN_QUALITY_FOR_SIGNAL &&
            candleQuality >= MIN_QUALITY_FOR_SIGNAL &&
            series.seriesConfidence >= MIN_QUALITY_FOR_SIGNAL
        val hasConflict = bullishVotes.isNotEmpty() && bearishVotes.isNotEmpty()

        val signal = when {
            !qualityOk -> Signal.WAIT
            hasConflict -> Signal.WAIT
            bullishVotes.size >= MIN_CONFIRMATIONS -> Signal.UP
            bearishVotes.size >= MIN_CONFIRMATIONS -> Signal.DOWN
            else -> Signal.WAIT
        }

        val reasons = when (signal) {
            Signal.UP -> bullishVotes
            Signal.DOWN -> bearishVotes
            Signal.WAIT -> waitReasons(qualityOk, hasConflict, bullishVotes, bearishVotes, chartConfidence, candleQuality, series)
        }

        val agreementCount = when (signal) {
            Signal.UP -> bullishVotes.size
            Signal.DOWN -> bearishVotes.size
            Signal.WAIT -> 0
        }
        val confidence = ConfidenceCalculator.compute(
            chartConfidence = chartConfidence,
            candleQuality = candleQuality,
            calibrationConfidence = series.seriesConfidence,
            verifiedCandleCount = candles.size,
            minVerifiedCandles = MIN_VERIFIED_CANDLES,
            indicatorAgreementCount = agreementCount,
            totalIndicatorsConsidered = indicatorsConsidered,
            trendAgrees = (trend == "UPTREND" && signal == Signal.UP) || (trend == "DOWNTREND" && signal == Signal.DOWN),
            signal = signal,
        )

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
            reason = reasons.joinToString("; "),
            analysisWindow = candles.size,
            timestampMillis = timestampMillis,
            candleColorKnown = true,
            calibrationQuality = series.seriesConfidence,
        )
    }

    private fun waitReasons(
        qualityOk: Boolean,
        hasConflict: Boolean,
        bullishVotes: List<String>,
        bearishVotes: List<String>,
        chartConfidence: Double,
        candleQuality: Double,
        series: VerifiedCandleSeries,
    ): List<String> {
        val notes = mutableListOf<String>()
        if (!qualityOk) {
            if (chartConfidence < MIN_QUALITY_FOR_SIGNAL) notes += "chart detection confidence too low"
            if (candleQuality < MIN_QUALITY_FOR_SIGNAL) notes += "candle recognition confidence too low"
            if (series.seriesConfidence < MIN_QUALITY_FOR_SIGNAL) notes += "price calibration confidence too low"
        }
        if (hasConflict) notes += "indicators disagree (${bullishVotes.size} bullish vs ${bearishVotes.size} bearish)"
        if (notes.isEmpty()) notes += "not enough independent confirmations agree"
        return notes
    }

    private fun fmt(value: Double): String = "%.1f".format(value)
}
