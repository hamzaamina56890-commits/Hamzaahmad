package com.chineseboot.android.core.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibratedCandleTest {
    private val calibration = PriceScaleCalibration(scale = -0.001, offset = 1.3, confidence = 0.9, timestampMillis = 1L, labelCount = 3, decimalPrecision = 4)

    private fun pixelCandle(
        bodyTop: Int = 40,
        bodyBottom: Int = 60,
        wickTop: Int = 30,
        wickBottom: Int = 70,
        direction: PixelCandleDirection = PixelCandleDirection.BULLISH,
    ) = PixelCandle(
        xPosition = 10, width = 5, bodyTop = bodyTop, bodyBottom = bodyBottom,
        wickTop = wickTop, wickBottom = wickBottom, direction = direction, detectionConfidence = 0.9,
    )

    @Test
    fun `builds a bullish calibrated candle from valid geometry`() {
        val candle = CalibratedCandle.from(pixelCandle(direction = PixelCandleDirection.BULLISH), calibration, 0L)
        assertNotNull(candle)
        assertTrue(candle.bullish)
        assertEquals(calibration.priceAt(60), candle.open)
        assertEquals(calibration.priceAt(40), candle.close)
        assertEquals(calibration.priceAt(30), candle.high)
        assertEquals(calibration.priceAt(70), candle.low)
    }

    @Test
    fun `rejects degenerate zero-height wick geometry`() {
        val candle = CalibratedCandle.from(
            pixelCandle(bodyTop = 50, bodyBottom = 50, wickTop = 50, wickBottom = 50),
            calibration,
            0L,
        )
        assertNull(candle)
    }

    @Test
    fun `rejects inverted body geometry`() {
        val candle = CalibratedCandle.from(
            pixelCandle(bodyTop = 60, bodyBottom = 40),
            calibration,
            0L,
        )
        assertNull(candle)
    }

    @Test
    fun `propagates calibration confidence onto the candle`() {
        val candle = CalibratedCandle.from(pixelCandle(), calibration, 0L)
        assertEquals(0.9, candle!!.calibrationConfidence)
    }
}
