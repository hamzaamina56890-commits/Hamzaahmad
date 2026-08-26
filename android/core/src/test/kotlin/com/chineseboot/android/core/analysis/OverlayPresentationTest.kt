package com.chineseboot.android.core.analysis

import com.chineseboot.android.core.model.ChartDetectionState
import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayPresentationTest {
    @Test
    fun `maps every detection state to its exact overlay status word`() {
        assertEquals("CHART NOT DETECTED", OverlayPresentation.statusWord(ChartDetectionState.CHART_NOT_DETECTED))
        assertEquals("SCANNING", OverlayPresentation.statusWord(ChartDetectionState.SCANNING))
        assertEquals("WAITING", OverlayPresentation.statusWord(ChartDetectionState.WAITING_FOR_MORE_DATA))
        assertEquals("ANALYZING", OverlayPresentation.statusWord(ChartDetectionState.READY))
    }

    @Test
    fun `maps trend strings to BULLISH-BEARISH-NEUTRAL`() {
        assertEquals("BULLISH", OverlayPresentation.trendWord("UPTREND"))
        assertEquals("BEARISH", OverlayPresentation.trendWord("DOWNTREND"))
        assertEquals("NEUTRAL", OverlayPresentation.trendWord("SIDEWAYS"))
        assertEquals("NEUTRAL", OverlayPresentation.trendWord(null))
    }
}
