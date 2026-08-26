package com.chineseboot.android.core.vision

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Locates the most likely candlestick-chart region in a captured frame using
 * visual characteristics (repeated vertical bar structures with fairly
 * regular width/spacing) — never hard-coded screen coordinates.
 */
object ChartRegionDetector {

    const val MIN_REGION_SCORE = 0.3
    private const val MIN_CANDLE_COUNT = 4
    private const val MIN_BAR_HEIGHT = 3

    fun detect(frame: FrameBuffer, excludeRegions: List<PixelRect> = emptyList()): ChartRegion? {
        if (frame.isEmpty || frame.width < 10 || frame.height < 10) return null

        val bandHeightFractions = listOf(0.35, 0.5, 0.7, 0.9, 1.0)
        val usableTop = excludeRegions.filter { it.y == 0 }.maxOfOrNull { it.bottom } ?: 0
        val usableBottom = frame.height - (excludeRegions.filter { it.bottom >= frame.height }.maxOfOrNull { frame.height - it.y } ?: 0)
        val usableHeight = usableBottom - usableTop
        if (usableHeight < 10) return null

        var best: ChartRegion? = null
        for (fraction in bandHeightFractions) {
            val bandHeight = (usableHeight * fraction).toInt().coerceAtLeast(MIN_BAR_HEIGHT + 2)
            val bandY = usableTop + (usableHeight - bandHeight) / 2
            val candidateRect = PixelRect(0, bandY, frame.width, bandHeight)
            val scored = scoreBand(frame, candidateRect, excludeRegions) ?: continue
            if (best == null || scored.score > best.score) best = scored
        }
        return best?.takeIf { it.score >= MIN_REGION_SCORE }
    }

    private fun scoreBand(frame: FrameBuffer, rect: PixelRect, excludeRegions: List<PixelRect>): ChartRegion? {
        val background = ColumnProfiler.estimateBackgroundLuminance(frame, rect, excludeRegions)
        val columns = (rect.x until rect.right).map { x ->
            ColumnProfiler.foregroundRange(frame, rect, x, background, excludeRegions)
        }
        val segments = ColumnProfiler.runLengthSegments(columns)
            .filter { seg ->
                val segCols = (seg.first..seg.last).mapNotNull { columns[it] }
                segCols.any { range -> (range.last - range.first + 1) >= MIN_BAR_HEIGHT }
            }
        if (segments.size < MIN_CANDLE_COUNT) return null

        val widths = segments.map { it.last - it.first + 1 }
        val gaps = segments.zipWithNext { a, b -> b.first - a.last - 1 }.filter { it > 0 }

        val widthConsistency = consistency(widths)
        val gapConsistency = if (gaps.isEmpty()) 0.5 else consistency(gaps)
        val totalBarWidth = widths.sum()
        val coverageRatio = totalBarWidth.toDouble() / rect.width
        val coverageScore = (1.0 - abs(coverageRatio - 0.5) / 0.5).coerceIn(0.0, 1.0)

        val avgBarHeight = segments.map { seg ->
            val segCols = (seg.first..seg.last).mapNotNull { columns[it] }
            if (segCols.isEmpty()) 0 else segCols.maxOf { it.last } - segCols.minOf { it.first } + 1
        }.average()

        val heightRatio = avgBarHeight / rect.height
        val heightScore = when {
            heightRatio > 0.88 -> 0.2
            heightRatio < 0.1 -> 0.2
            else -> 1.0
        }

        val score = (widthConsistency * 0.3 + gapConsistency * 0.3 + coverageScore * 0.2 + heightScore * 0.2)
            .coerceIn(0.0, 1.0)

        val trimmedX = segments.first().first
        val trimmedWidth = segments.last().last - trimmedX + 1
        val trimmedRect = PixelRect(rect.x + trimmedX, rect.y, trimmedWidth, rect.height)
        return ChartRegion(trimmedRect, score)
    }

    private fun consistency(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        if (mean <= 0.0) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val stddev = sqrt(variance)
        return (1.0 - (stddev / mean)).coerceIn(0.0, 1.0)
    }
}
