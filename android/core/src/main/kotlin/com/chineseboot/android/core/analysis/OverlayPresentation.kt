package com.chineseboot.android.core.analysis

import com.chineseboot.android.core.model.ChartDetectionState

/**
 * Pure, unit-testable mapping from analysis state to the exact overlay
 * display strings required by the product spec. Kept out of the Android
 * `OverlayService` so it can be verified with plain JVM tests.
 */
object OverlayPresentation {
    fun statusWord(state: ChartDetectionState): String = when (state) {
        ChartDetectionState.CHART_NOT_DETECTED -> "CHART NOT DETECTED"
        ChartDetectionState.SCANNING -> "SCANNING"
        ChartDetectionState.WAITING_FOR_MORE_DATA -> "WAITING"
        ChartDetectionState.READY -> "ANALYZING"
    }

    fun trendWord(trend: String?): String = when (trend) {
        "UPTREND" -> "BULLISH"
        "DOWNTREND" -> "BEARISH"
        else -> "NEUTRAL"
    }
}
