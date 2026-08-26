package com.chineseboot.android.core.vision

import kotlin.math.abs

/**
 * Extracts individual candle geometry (body + wick bounds) from a chart region.
 */
object CandleDetector {

    const val MIN_CANDLE_WIDTH = 2
    const val MAX_CANDLE_WIDTH = 80
    const val BODY_MAJORITY_THRESHOLD = 0.5
    const val MIN_CANDLE_CONFIDENCE = 0.3

    fun detect(
        frame: FrameBuffer,
        region: ChartRegion,
        excludeRegions: List<PixelRect> = emptyList(),
    ): List<PixelCandle> = detect(frame, region.rect, excludeRegions)

    fun detect(
        frame: FrameBuffer,
        rect: PixelRect,
        excludeRegions: List<PixelRect> = emptyList(),
    ): List<PixelCandle> {
        if (rect.width <= 0 || rect.height <= 0 || frame.isEmpty) return emptyList()

        val background = ColumnProfiler.estimateBackgroundLuminance(frame, rect, excludeRegions)
        val columns = (rect.x until rect.right).map { x ->
            ColumnProfiler.foregroundRange(frame, rect, x, background, excludeRegions)
        }
        val segments = ColumnProfiler.runLengthSegments(columns)

        val raw = segments.mapNotNull { seg -> buildCandle(frame, rect, columns, seg) }
        if (raw.isEmpty()) return emptyList()

        val medianWidth = raw.map { it.width }.sorted()[raw.size / 2]
        return raw.mapNotNull { candidate -> finalizeConfidence(candidate, medianWidth, frame.timestampMillis) }
    }

    private data class RawCandidate(
        val xCenter: Int,
        val width: Int,
        val bodyTop: Int,
        val bodyBottom: Int,
        val wickTop: Int,
        val wickBottom: Int,
        val rawColor: Int,
    )

    private fun buildCandle(
        frame: FrameBuffer,
        rect: PixelRect,
        columns: List<IntRange?>,
        seg: IntRange,
    ): RawCandidate? {
        val width = seg.last - seg.first + 1
        if (width < MIN_CANDLE_WIDTH || width > MAX_CANDLE_WIDTH) return null

        val segColumns = (seg.first..seg.last).mapNotNull { columns[it] }
        if (segColumns.isEmpty()) return null

        var wickTop = Int.MAX_VALUE
        var wickBottom = Int.MIN_VALUE
        for (c in segColumns) {
            if (c.first < wickTop) wickTop = c.first
            if (c.last > wickBottom) wickBottom = c.last
        }

        var bodyTop = -1
        var bodyBottom = -1
        val threshold = (segColumns.size * BODY_MAJORITY_THRESHOLD).toInt().coerceAtLeast(1)
        for (y in wickTop..wickBottom) {
            val count = segColumns.count { y in it }
            if (count >= threshold) {
                if (bodyTop == -1) bodyTop = y
                bodyBottom = y
            }
        }
        if (bodyTop == -1) {
            bodyTop = wickTop
            bodyBottom = wickBottom
        }

        // Reject candles whose body touches the frame edge or spans the entire region height (clipped)
        val isClippedAtFrameEdge = bodyTop <= 0 || bodyBottom >= frame.height - 1
        val spansEntireRegion = (bodyBottom - bodyTop + 1) >= (rect.height - 1)
        if (isClippedAtFrameEdge || (spansEntireRegion && rect.height > 15)) return null

        val xCenter = rect.x + seg.first + width / 2

        // Sample raw color from body
        val sampledColors = mutableListOf<Int>()
        val startX = (rect.x + seg.first).coerceIn(0, frame.width - 1)
        val endX = (rect.x + seg.last).coerceIn(0, frame.width - 1)
        val startY = bodyTop.coerceIn(0, frame.height - 1)
        val endY = bodyBottom.coerceIn(0, frame.height - 1)
        for (x in startX..endX) {
            for (y in startY..endY) {
                sampledColors.add(frame.pixelAt(x, y))
            }
        }
        val rawColor = ColorUtils.averageColor(sampledColors)

        return RawCandidate(xCenter, width, bodyTop, bodyBottom, wickTop, wickBottom, rawColor)
    }

    private fun finalizeConfidence(candidate: RawCandidate, medianWidth: Int, frameTimestampMillis: Long): PixelCandle? {
        val widthScore = (1.0 - abs(candidate.width - medianWidth).toDouble() / medianWidth.coerceAtLeast(1))
            .coerceIn(0.0, 1.0)
        val totalHeight = (candidate.wickBottom - candidate.wickTop + 1).coerceAtLeast(1)
        val bodyHeight = (candidate.bodyBottom - candidate.bodyTop + 1).coerceAtLeast(1)
        val bodyRatioScore = (bodyHeight.toDouble() / totalHeight).coerceIn(0.0, 1.0)

        val confidence = (widthScore * 0.4 + bodyRatioScore * 0.6).coerceIn(0.0, 1.0)
        if (confidence < MIN_CANDLE_CONFIDENCE) return null

        return PixelCandle(
            xPosition = candidate.xCenter,
            width = candidate.width,
            bodyTop = candidate.bodyTop,
            bodyBottom = candidate.bodyBottom,
            wickTop = candidate.wickTop,
            wickBottom = candidate.wickBottom,
            direction = PixelCandleDirection.UNKNOWN,
            detectionConfidence = confidence,
            sourceFrameTimestampMillis = frameTimestampMillis,
            rawColor = candidate.rawColor,
        )
    }
}
