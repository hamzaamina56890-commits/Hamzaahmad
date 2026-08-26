package com.chineseboot.android.analysis

import com.chineseboot.android.core.model.AnalysisSnapshot
import com.chineseboot.android.core.model.Candle

/**
 * Turns raw captured frames into candle data and hands the result to the
 * [com.chineseboot.android.core.analysis.SignalEngine].
 *
 * NOTE: chart-region detection and candle/pixel extraction are not implemented
 * yet (see project README under android/ for remaining work). This stub never
 * invents an asset, price or candle — it only reports that no chart has been
 * detected, which is the contractually safe default until real detection is
 * implemented.
 */
class ChartCaptureAnalyzer {
    private val candles = mutableListOf<Candle>()

    /**
     * Process one captured frame. Returns the current [AnalysisSnapshot].
     * Today this always yields "chart not detected" because pixel-level chart
     * detection is not implemented in this stage of the project.
     */
    fun onFrame(timestampMillis: Long): AnalysisSnapshot {
        return AnalysisSnapshot.notDetected(timestampMillis)
    }

    fun reset() {
        candles.clear()
    }
}
