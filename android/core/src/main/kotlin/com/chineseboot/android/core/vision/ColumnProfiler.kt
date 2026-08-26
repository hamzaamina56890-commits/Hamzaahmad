package com.chineseboot.android.core.vision

import kotlin.math.abs

/**
 * Sampling and column analysis utilities used by region and candle detectors.
 */
object ColumnProfiler {

    /**
     * Estimates background luminance (0..255) by computing the modal luminance
     * across sample pixels within [rect] (excluding pixels in [excludeRegions]).
     */
    fun estimateBackgroundLuminance(
        frame: FrameBuffer,
        rect: PixelRect,
        excludeRegions: List<PixelRect> = emptyList(),
    ): Int {
        if (rect.width <= 0 || rect.height <= 0 || frame.isEmpty) return 255
        val hist = IntArray(256)
        val stepX = (rect.width / 40).coerceAtLeast(1)
        val stepY = (rect.height / 40).coerceAtLeast(1)

        val startX = rect.x.coerceIn(0, frame.width - 1)
        val endX = (rect.right - 1).coerceIn(0, frame.width - 1)
        val startY = rect.y.coerceIn(0, frame.height - 1)
        val endY = (rect.bottom - 1).coerceIn(0, frame.height - 1)

        for (x in startX..endX step stepX) {
            for (y in startY..endY step stepY) {
                if (excludeRegions.any { it.contains(x, y) }) continue
                val lum = frame.luminanceAt(x, y).coerceIn(0, 255)
                hist[lum]++
            }
        }

        var maxCount = -1
        var modeLum = 255
        for (i in 0..255) {
            if (hist[i] > maxCount) {
                maxCount = hist[i]
                modeLum = i
            }
        }
        return modeLum
    }

    /**
     * Returns the vertical foreground range [minY..maxY] for column [x] in [rect],
     * where foreground pixels differ from [backgroundLuminance] by at least [threshold].
     */
    fun foregroundRange(
        frame: FrameBuffer,
        rect: PixelRect,
        x: Int,
        backgroundLuminance: Int,
        excludeRegions: List<PixelRect> = emptyList(),
        threshold: Int = 20,
    ): IntRange? {
        if (x !in 0 until frame.width || frame.isEmpty) return null
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE

        val startY = rect.y.coerceIn(0, frame.height - 1)
        val endY = (rect.bottom - 1).coerceIn(0, frame.height - 1)

        for (y in startY..endY) {
            if (excludeRegions.any { it.contains(x, y) }) continue
            val lum = frame.luminanceAt(x, y)
            if (abs(lum - backgroundLuminance) >= threshold) {
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        return if (minY <= maxY) IntRange(minY, maxY) else null
    }

    /**
     * Identifies contiguous sequences (runs) of non-null column ranges in [columns].
     * Returns list of column index ranges (0-based indices into [columns]).
     */
    fun runLengthSegments(columns: List<IntRange?>): List<IntRange> {
        val segments = mutableListOf<IntRange>()
        var start = -1

        for (i in columns.indices) {
            val isFg = columns[i] != null
            if (isFg) {
                if (start == -1) start = i
            } else {
                if (start != -1) {
                    segments.add(IntRange(start, i - 1))
                    start = -1
                }
            }
        }
        if (start != -1) {
            segments.add(IntRange(start, columns.size - 1))
        }
        return segments
    }
}
