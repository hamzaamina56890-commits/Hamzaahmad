package com.chineseboot.android.core.model

/**
 * A single OHLC candle extracted from the visibly captured chart.
 * Every field must come from real detected pixels — never fabricated.
 */
data class Candle(
    val openTimeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
)

enum class CandleDirection { UP, DOWN, FLAT }

enum class Signal { UP, DOWN, WAIT }

/** High-level state of the on-screen chart detection pipeline. */
enum class ChartDetectionState {
    /** Capture is running but no chart region has been reliably located yet. */
    CHART_NOT_DETECTED,

    /** A chart region is detected but not enough candle history has been collected yet. */
    WAITING_FOR_MORE_DATA,

    /** Enough candles are available and analysis has produced a result. */
    READY,

    /** Capture pipeline is actively sampling frames but hasn't classified anything yet. */
    SCANNING,
}

/**
 * Snapshot of everything the floating overlay panel needs to render.
 * Any field that could not be reliably detected must be `null`, never guessed.
 */
data class AnalysisSnapshot(
    val state: ChartDetectionState,
    val asset: String? = null,
    val timeframeSeconds: Int? = null,
    val price: Double? = null,
    val direction: CandleDirection? = null,
    val strength: Double? = null,
    val rsi: Double? = null,
    val trend: String? = null,
    val support: Double? = null,
    val resistance: Double? = null,
    val signal: Signal? = null,
    val confidencePercent: Int? = null,
    val reason: String? = null,
    val analysisWindow: Int = 0,
    val timestampMillis: Long = 0L,
) {
    companion object {
        fun notDetected(timestampMillis: Long): AnalysisSnapshot =
            AnalysisSnapshot(state = ChartDetectionState.CHART_NOT_DETECTED, timestampMillis = timestampMillis)

        fun waitingForData(candleCount: Int, timestampMillis: Long): AnalysisSnapshot =
            AnalysisSnapshot(
                state = ChartDetectionState.WAITING_FOR_MORE_DATA,
                analysisWindow = candleCount,
                timestampMillis = timestampMillis,
            )
    }
}
