package com.chineseboot.android.capture

import android.media.Image
import com.chineseboot.android.core.vision.FrameBuffer

/**
 * Converts an [Image] from MediaProjection to a downsampled [FrameBuffer].
 */
fun Image.toFrameBuffer(timestampMillis: Long, stride: Int = 1): FrameBuffer {
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride

    val srcWidth = width
    val srcHeight = height
    val targetWidth = (srcWidth / stride).coerceAtLeast(1)
    val targetHeight = (srcHeight / stride).coerceAtLeast(1)

    val argb = IntArray(targetWidth * targetHeight)

    var outIdx = 0
    for (y in 0 until targetHeight) {
        val srcY = y * stride
        val rowStart = srcY * rowStride
        for (x in 0 until targetWidth) {
            val srcX = x * stride
            val pixelStart = rowStart + srcX * pixelStride
            if (pixelStart + 3 < buffer.capacity()) {
                val r = buffer.get(pixelStart).toInt() and 0xFF
                val g = buffer.get(pixelStart + 1).toInt() and 0xFF
                val b = buffer.get(pixelStart + 2).toInt() and 0xFF
                val a = buffer.get(pixelStart + 3).toInt() and 0xFF
                argb[outIdx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                argb[outIdx] = 0xFF000000.toInt()
            }
            outIdx++
        }
    }

    return FrameBuffer(targetWidth, targetHeight, argb, timestampMillis)
}
