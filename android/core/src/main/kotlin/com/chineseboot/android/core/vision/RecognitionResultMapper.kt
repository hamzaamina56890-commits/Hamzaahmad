package com.chineseboot.android.core.vision

import com.chineseboot.android.core.model.AnalysisSnapshot
import com.chineseboot.android.core.model.CandleDirection
import com.chineseboot.android.core.model.ChartDetectionState
import com.chineseboot.android.core.model.Signal

/**
 * Maps a [RecognitionResult] to the [AnalysisSnapshot] the overlay renders.
 * Deliberately never sets [AnalysisSnapshot.price] or a real UP/DOWN
 * [AnalysisSnapshot.signal] — real OHLC prices require a verified
 * [PriceScaleCalibration], which this stage does not yet produce (see
 * `PriceScaleCalibration` / remaining work notes). Until then, a detected
 * chart is reported as [Signal.WAIT].
 */
fun RecognitionResult.toAnalysisSnapshot(): AnalysisSnapshot = when (status) {
    ChartRecognitionStatus.CHART_NOT_DETECTED -> AnalysisSnapshot.notDetected(frameTimestampMillis)

    ChartRecognitionStatus.CANDLES_NOT_RELIABLE ->
        AnalysisSnapshot.waitingForData(candles.size, frameTimestampMillis)

    ChartRecognitionStatus.READY -> AnalysisSnapshot(
        state = ChartDetectionState.READY,
        asset = asset,
        timeframeSeconds = timeframeSeconds,
        price = null,
        direction = candles.lastOrNull()?.direction?.toCandleDirectionOrNull(),
        signal = Signal.WAIT,
        confidencePercent = (confidence * 100).toInt(),
        reason = buildReason(),
        analysisWindow = candles.size,
        timestampMillis = frameTimestampMillis,
        candleColorKnown = candleColorKnown,
    )
}

private fun PixelCandleDirection.toCandleDirectionOrNull(): CandleDirection? = when (this) {
    PixelCandleDirection.BULLISH -> CandleDirection.UP
    PixelCandleDirection.BEARISH -> CandleDirection.DOWN
    PixelCandleDirection.UNKNOWN -> null
}

private fun RecognitionResult.buildReason(): String {
    val notes = mutableListOf<String>()
    if (asset == null) notes += "ASSET_UNKNOWN"
    if (timeframeSeconds == null) notes += "TIMEFRAME_UNKNOWN"
    if (!candleColorKnown) notes += "CANDLE_COLOR_UNKNOWN"
    return if (notes.isEmpty()) {
        "Chart recognized; awaiting verified price calibration for a trading signal"
    } else {
        notes.joinToString(", ")
    }
}
