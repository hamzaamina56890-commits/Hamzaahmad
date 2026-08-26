package com.chineseboot.android.core.vision

/**
 * Result of turning raw [CalibratedCandle] candidates into a trustworthy,
 * chronologically-ordered series. Every rejection/adjustment is counted so
 * callers (and tests) can verify the pipeline is being honest rather than
 * silently dropping or duplicating data.
 */
data class VerifiedCandleSeries(
    val candles: List<CalibratedCandle>,
    val rejectedCount: Int,
    val duplicatesRemoved: Int,
    val gapsDetected: Int,
    val seriesConfidence: Double,
) {
    val isSufficient: Boolean get() = candles.size >= CandleSeriesBuilder.MIN_VERIFIED_CANDLES
}

/**
 * Builds a verified, chronologically-sorted candle series from raw
 * calibrated candidates: sorts left-to-right, drops duplicate/overlapping
 * candles (keeping the higher-confidence one), rejects candles with
 * impossible geometry, and flags gaps in otherwise-uniform spacing.
 */
object CandleSeriesBuilder {
    const val MIN_VERIFIED_CANDLES = 15

    /** A gap is flagged when spacing exceeds this multiple of the median spacing. */
    private const val GAP_FACTOR = 1.75

    fun build(candidates: List<CalibratedCandle>): VerifiedCandleSeries {
        if (candidates.isEmpty()) {
            return VerifiedCandleSeries(emptyList(), rejectedCount = 0, duplicatesRemoved = 0, gapsDetected = 0, seriesConfidence = 0.0)
        }

        val sorted = candidates.sortedBy { it.pixel.xPosition }

        var rejected = 0
        val geometryValid = sorted.filter { c ->
            val ok = isGeometryValid(c)
            if (!ok) rejected++
            ok
        }

        val deduped = mutableListOf<CalibratedCandle>()
        var duplicatesRemoved = 0
        for (candidate in geometryValid) {
            val previous = deduped.lastOrNull()
            val overlapThreshold = (previous?.pixel?.width ?: 0).coerceAtLeast(1) / 2
            if (previous != null && candidate.pixel.xPosition - previous.pixel.xPosition <= overlapThreshold) {
                // Same candle seen twice (e.g. overlapping detections) — keep the more confident one.
                if (candidate.detectionConfidence > previous.detectionConfidence) {
                    deduped[deduped.size - 1] = candidate
                }
                duplicatesRemoved++
            } else {
                deduped.add(candidate)
            }
        }

        var gapsDetected = 0
        if (deduped.size >= 3) {
            val spacings = deduped.zipWithNext { a, b -> b.pixel.xPosition - a.pixel.xPosition }
            val median = spacings.sorted()[spacings.size / 2].coerceAtLeast(1)
            gapsDetected = spacings.count { it > median * GAP_FACTOR }
        }

        val seriesConfidence = if (deduped.isEmpty()) {
            0.0
        } else {
            deduped.map { it.detectionConfidence * it.calibrationConfidence }.average()
        }

        return VerifiedCandleSeries(deduped, rejected, duplicatesRemoved, gapsDetected, seriesConfidence)
    }

    private fun isGeometryValid(c: CalibratedCandle): Boolean {
        if (c.high < c.low) return false
        if (c.high < maxOf(c.open, c.close) - 1e-9) return false
        if (c.low > minOf(c.open, c.close) + 1e-9) return false
        if (c.detectionConfidence <= 0.0 || c.calibrationConfidence <= 0.0) return false
        return true
    }
}
