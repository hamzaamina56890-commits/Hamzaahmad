package com.chineseboot.android.analysis.ocr

import android.graphics.Bitmap
import android.graphics.Color
import com.chineseboot.android.core.vision.ChartRegion
import com.chineseboot.android.core.vision.DetectedPriceLabel
import com.chineseboot.android.core.vision.FrameBuffer
import com.chineseboot.android.core.vision.PixelRect
import com.chineseboot.android.core.vision.PriceLabelDetection
import com.chineseboot.android.core.vision.PriceLabelDetector
import com.chineseboot.android.core.vision.PriceLabelParser
import com.chineseboot.android.core.vision.TimeframeCalibrator
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production OCR adapter for a MediaProjection [FrameBuffer]. It sends only
 * the chart's price-axis strip to ML Kit and converts bounding-box centers
 * back into captured-frame coordinates. No label, timeframe, or confidence is
 * synthesized when OCR has insufficient evidence.
 */
class PriceScaleOcrDetector : PriceLabelDetector {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun detect(frame: FrameBuffer, region: ChartRegion): PriceLabelDetection = withContext(Dispatchers.Default) {
        val axis = priceAxisRect(frame, region) ?: return@withContext PriceLabelDetection()
        val bitmap = bitmapFor(frame, axis)
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            val labels = text.textBlocks.flatMap { block -> block.lines.flatMap { it.elements } }
                .mapNotNull { element ->
                    val bounds = element.boundingBox ?: return@mapNotNull null
                    val rawText = element.text.trim()
                    if (PriceLabelParser.parse(rawText) == null) return@mapNotNull null
                    val confidence = element.confidence?.toDouble() ?: return@mapNotNull null
                    if (!confidence.isFinite() || confidence !in 0.55..1.0) return@mapNotNull null
                    DetectedPriceLabel(axis.y + bounds.centerY(), rawText, confidence)
                }
                .distinctBy { it.pixelY to it.rawText }
                .sortedBy { it.pixelY }

            val timeframe = text.textBlocks.asSequence()
                .flatMap { it.lines.asSequence() }
                .map { it.text.trim() }
                .firstOrNull { TimeframeCalibrator.fromLabel(it) != null }
            PriceLabelDetection(labels, timeframe)
        } catch (_: Exception) {
            PriceLabelDetection()
        } finally {
            bitmap.recycle()
        }
    }

    private fun priceAxisRect(frame: FrameBuffer, region: ChartRegion): PixelRect? {
        val chart = region.rect
        val startX = (chart.right - chart.width / 6).coerceIn(0, frame.width)
        val endX = (chart.right + chart.width / 3).coerceIn(startX, frame.width)
        val top = chart.y.coerceIn(0, frame.height)
        val bottom = chart.bottom.coerceIn(top, frame.height)
        val width = endX - startX
        val height = bottom - top
        return if (width >= 8 && height >= 8) PixelRect(startX, top, width, height) else null
    }

    private fun bitmapFor(frame: FrameBuffer, rect: PixelRect): Bitmap {
        val pixels = IntArray(rect.width * rect.height)
        var destination = 0
        for (y in rect.y until rect.bottom) {
            for (x in rect.x until rect.right) {
                val color = frame.pixelAt(x, y)
                val luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
                // Preserve alpha while increasing grayscale contrast for small axis text.
                val enhanced = if (luminance >= 128) 255 else 0
                pixels[destination++] = Color.argb(Color.alpha(color), enhanced, enhanced, enhanced)
            }
        }
        return Bitmap.createBitmap(pixels, rect.width, rect.height, Bitmap.Config.ARGB_8888)
    }
}