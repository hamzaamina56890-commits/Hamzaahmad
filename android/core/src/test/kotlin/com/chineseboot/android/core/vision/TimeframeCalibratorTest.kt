package com.chineseboot.android.core.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeframeCalibratorTest {
    @Test
    fun `parses supported timeframe labels`() {
        assertEquals(5, TimeframeCalibrator.fromLabel("5s"))
        assertEquals(30, TimeframeCalibrator.fromLabel("30 sec"))
        assertEquals(60, TimeframeCalibrator.fromLabel("1m"))
        assertEquals(300, TimeframeCalibrator.fromLabel("5min"))
        assertEquals(180, TimeframeCalibrator.fromLabel("3 minutes"))
    }

    @Test
    fun `returns null (TIMEFRAME_UNKNOWN) for unreadable or unsupported text`() {
        assertNull(TimeframeCalibrator.fromLabel(null))
        assertNull(TimeframeCalibrator.fromLabel(""))
        assertNull(TimeframeCalibrator.fromLabel("gibberish"))
        assertNull(TimeframeCalibrator.fromLabel("7m")) // not a supported timeframe
    }

    @Test
    fun `resolve prefers label text over any fallback`() {
        assertEquals(60, TimeframeCalibrator.resolve("1m", fallbackTimeframeSeconds = 300))
    }

    @Test
    fun `resolve falls back when label text is unreadable`() {
        assertEquals(300, TimeframeCalibrator.resolve(null, fallbackTimeframeSeconds = 300))
        assertNull(TimeframeCalibrator.resolve(null, fallbackTimeframeSeconds = null))
    }

    @Test
    fun `spacing consistency is high for uniformly spaced candles`() {
        val candles = (0 until 10).map {
            PixelCandle(xPosition = it * 10, width = 5, bodyTop = 0, bodyBottom = 1, wickTop = 0, wickBottom = 1)
        }
        assertEquals(1.0, TimeframeCalibrator.spacingConsistency(candles), 1e-9)
    }

    @Test
    fun `spacing consistency is low for irregular spacing`() {
        val positions = listOf(0, 5, 40, 42, 100)
        val candles = positions.map {
            PixelCandle(xPosition = it, width = 5, bodyTop = 0, bodyBottom = 1, wickTop = 0, wickBottom = 1)
        }
        assert(TimeframeCalibrator.spacingConsistency(candles) < 0.7)
    }
}
