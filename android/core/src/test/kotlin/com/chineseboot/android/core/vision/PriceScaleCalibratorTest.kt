package com.chineseboot.android.core.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceScaleCalibratorTest {
    private fun label(pixelY: Int, price: String, confidence: Double = 0.9) =
        DetectedPriceLabel(pixelY, price, confidence)

    @Test
    fun `fits a clean linear scale from well-formed labels`() {
        // Chart convention: price decreases as pixelY increases (top of screen = higher price).
        val labels = listOf(
            label(10, "1.2010"),
            label(50, "1.2000"),
            label(90, "1.1990"),
        )
        val calibration = PriceScaleCalibrator.calibrate(labels, timestampMillis = 1L)
        assertNotNull(calibration)
        assertTrue(calibration.confidence > 0.5)
        assertEquals(1.2000, calibration.priceAt(50), 1e-6)
        assertEquals(1.2010, calibration.priceAt(10), 1e-6)
    }

    @Test
    fun `rejects calibration with fewer than the minimum valid labels`() {
        val labels = listOf(label(10, "1.2010"))
        assertNull(PriceScaleCalibrator.calibrate(labels, 1L))
    }

    @Test
    fun `rejects labels with low OCR confidence`() {
        val labels = listOf(
            label(10, "1.2010", confidence = 0.2),
            label(50, "1.2000", confidence = 0.3),
            label(90, "1.1990", confidence = 0.1),
        )
        assertNull(PriceScaleCalibrator.calibrate(labels, 1L))
    }

    @Test
    fun `rejects unparsable OCR text`() {
        val labels = listOf(
            label(10, "BUY"),
            label(50, "SELL"),
        )
        assertNull(PriceScaleCalibrator.calibrate(labels, 1L))
    }

    @Test
    fun `rejects an inconsistent scale with excessive residual error`() {
        val labels = listOf(
            label(10, "1.2010"),
            label(50, "1.1500"), // wildly inconsistent with the other two
            label(90, "1.1990"),
        )
        assertNull(PriceScaleCalibrator.calibrate(labels, 1L))
    }

    @Test
    fun `rejects labels that all share the same pixel row`() {
        val labels = listOf(
            label(50, "1.2000"),
            label(50, "1.2000"),
        )
        assertNull(PriceScaleCalibrator.calibrate(labels, 1L))
    }

    @Test
    fun `rejects non-monotonic labels despite a possible regression fit`() {
        val labels = listOf(
            label(10, "1.2010"),
            label(50, "1.1990"),
            label(90, "1.2000"),
        )
        assertNull(PriceScaleCalibrator.calibrate(labels, 1L))
    }
}
