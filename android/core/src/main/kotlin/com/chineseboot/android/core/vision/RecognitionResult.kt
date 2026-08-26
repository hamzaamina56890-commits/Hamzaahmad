package com.chineseboot.android.core.vision

enum class RecognitionState {
    CHART_NOT_DETECTED,
    CANDLES_NOT_RELIABLE,
    CANDLE_COLOR_UNKNOWN,
    READY,
}

enum class ChartRecognitionStatus {
    CHART_NOT_DETECTED,
    CANDLES_NOT_RELIABLE,
    READY,
}

/**
 * Structured, honest result of analyzing one frame. Fields that could not be
 * reliably determined are `null`/`false` rather than guessed — callers must
 * treat that as "unknown", never as zero or a default value.
 */
data class ChartRecognitionResult(
    val state: RecognitionState,
    val region: ChartRegion?,
    val candles: List<PixelCandle>,
    val candleColorKnown: Boolean,
    val asset: String?,
    val timeframeSeconds: Int?,
    val candleQuality: Double,
    val overallConfidence: Double,
    val frameTimestampMillis: Long,
) {
    val status: ChartRecognitionStatus get() = when (state) {
        RecognitionState.CHART_NOT_DETECTED -> ChartRecognitionStatus.CHART_NOT_DETECTED
        RecognitionState.CANDLES_NOT_RELIABLE -> ChartRecognitionStatus.CANDLES_NOT_RELIABLE
        RecognitionState.CANDLE_COLOR_UNKNOWN -> ChartRecognitionStatus.READY
        RecognitionState.READY -> ChartRecognitionStatus.READY
    }

    val confidence: Double get() = overallConfidence

    companion object {
        fun chartNotDetected(timestampMillis: Long): ChartRecognitionResult = ChartRecognitionResult(
            state = RecognitionState.CHART_NOT_DETECTED,
            region = null,
            candles = emptyList(),
            candleColorKnown = false,
            asset = null,
            timeframeSeconds = null,
            candleQuality = 0.0,
            overallConfidence = 0.0,
            frameTimestampMillis = timestampMillis,
        )

        fun candlesNotReliable(
            region: ChartRegion,
            candles: List<PixelCandle>,
            timestampMillis: Long,
        ): ChartRecognitionResult {
            val avgQuality = if (candles.isEmpty()) 0.0 else candles.map { it.detectionConfidence }.average()
            return ChartRecognitionResult(
                state = RecognitionState.CANDLES_NOT_RELIABLE,
                region = region,
                candles = candles,
                candleColorKnown = false,
                asset = null,
                timeframeSeconds = null,
                candleQuality = avgQuality,
                overallConfidence = avgQuality * 0.5,
                frameTimestampMillis = timestampMillis,
            )
        }
    }
}

typealias RecognitionResult = ChartRecognitionResult
