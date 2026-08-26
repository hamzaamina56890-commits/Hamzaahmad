package com.chineseboot.android.core.vision

/**
 * Full recognition pipeline: chart-region detection -> candle extraction ->
 * confidence filtering -> color-convention classification -> asset/timeframe
 * lookup. Every stage can bail out to an honest "not detected"/"not
 * reliable" result; nothing downstream is ever allowed to treat uncertain
 * geometry as verified market data.
 */
class ChartRecognitionPipeline(
    private val assetTimeframeDetector: AssetTimeframeDetector = NoOpAssetTimeframeDetector,
) {
    companion object {
        const val MIN_CANDLES_FOR_RELIABLE = 3
        const val MIN_CANDLE_CONFIDENCE = 0.3
    }

    fun analyze(frame: FrameBuffer, excludeRegions: List<PixelRect> = emptyList()): ChartRecognitionResult {
        if (frame.isEmpty || frame.width < 10 || frame.height < 10) {
            return ChartRecognitionResult.chartNotDetected(frame.timestampMillis)
        }

        val region = ChartRegionDetector.detect(frame, excludeRegions)
            ?: return ChartRecognitionResult.chartNotDetected(frame.timestampMillis)

        val rawCandles = CandleDetector.detect(frame, region, excludeRegions)
        val reliableCandles = rawCandles.filter { it.detectionConfidence >= MIN_CANDLE_CONFIDENCE }

        if (reliableCandles.size < MIN_CANDLES_FOR_RELIABLE) {
            return ChartRecognitionResult.candlesNotReliable(region, rawCandles, frame.timestampMillis)
        }

        val convention = ColorConventionDetector.detect(reliableCandles.map { it.rawColor })
        val classifiedCandles = reliableCandles.map { candle ->
            val direction = convention?.classify(candle.rawColor) ?: PixelCandleDirection.UNKNOWN
            candle.copy(direction = direction)
        }

        val candleColorKnown = convention != null
        val state = if (candleColorKnown) RecognitionState.READY else RecognitionState.CANDLE_COLOR_UNKNOWN

        val avgQuality = if (classifiedCandles.isEmpty()) 0.0 else classifiedCandles.map { it.detectionConfidence }.average()
        val overallConfidence = (region.score * 0.4 + avgQuality * 0.6).coerceIn(0.0, 1.0)

        return ChartRecognitionResult(
            state = state,
            region = region,
            candles = classifiedCandles,
            candleColorKnown = candleColorKnown,
            asset = assetTimeframeDetector.detectAsset(frame, region),
            timeframeSeconds = assetTimeframeDetector.detectTimeframeSeconds(frame, region),
            candleQuality = avgQuality,
            overallConfidence = overallConfidence,
            frameTimestampMillis = frame.timestampMillis,
        )
    }
}
