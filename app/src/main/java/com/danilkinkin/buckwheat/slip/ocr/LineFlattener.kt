package com.danilkinkin.buckwheat.slip.ocr

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dark text on white from a line crop, whatever its background: each column's background is the
 * median of the crop's top and bottom margin rows, smoothed along the line, and text is whatever
 * differs from it, lighter or darker. The contrast is stretched so the strongest 2% is black.
 *
 * Port of `_flatten` from iApp's OpenThai2.1-OCR-Text runtime (Apache-2.0), which its recognizer
 * was trained with: https://huggingface.co/iapp/OpenThai2.1-OCR-Text-20261118
 */
object LineFlattener {
    /**
     * @param bgr CHW tensor of a resized line crop, channels B, G, R, values 0..255
     * @return CHW recognizer input: the flattened grey line in all 3 channels, scaled to -1..1
     */
    fun flattenToTensor(bgr: FloatArray, width: Int, height: Int): FloatArray {
        val plane = width * height
        val grey = IntArray(plane) { i ->
            (0.114f * bgr[i] + 0.587f * bgr[plane + i] + 0.299f * bgr[2 * plane + i]).roundToInt().coerceIn(0, 255)
        }
        val flat = flatten(grey, width, height)
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val v = (flat[i] / 255f - 0.5f) / 0.5f
            out[i] = v
            out[plane + i] = v
            out[2 * plane + i] = v
        }
        return out
    }

    /** @param grey 0..255 values row by row; returns flattened 0..255 values */
    fun flatten(grey: IntArray, width: Int, height: Int): IntArray {
        val margin = maxOf(2, height / 6)

        // Background of each column: median of its top and bottom margin rows
        val column = IntArray(2 * margin)
        val background = IntArray(width) { x ->
            for (k in 0 until margin) {
                column[k] = grey[k * width + x]
                column[margin + k] = grey[(height - 1 - k) * width + x]
            }
            column.sort()
            // numpy's median of an even count averages the two middle values; uint8 truncates
            (column[margin - 1] + column[margin]) / 2
        }

        // Smoothed along the line: running median, window about the line height, edges repeated
        val window = maxOf(3, minOf(height or 1, 255))
        val half = window / 2
        val values = IntArray(window)
        val smooth = IntArray(width) { x ->
            for (k in 0 until window) values[k] = background[(x - half + k).coerceIn(0, width - 1)]
            values.sort()
            values[half]
        }

        // Distance from the background, stretched so its 98th percentile becomes full contrast
        val diff = FloatArray(grey.size) { i -> abs((grey[i] - smooth[i % width]).toFloat()) }
        val histogram = IntArray(256)
        diff.forEach { histogram[it.toInt().coerceIn(0, 255)] += 1 }
        val target = 0.98 * diff.size
        var cumulative = 0
        var percentile = 255
        for (v in 0 until 256) {
            cumulative += histogram[v]
            if (cumulative >= target) {
                percentile = v
                break
            }
        }
        val high = maxOf(percentile.toFloat(), 24f)

        return IntArray(grey.size) { i -> (255f - (diff[i] * (255f / high)).coerceIn(0f, 255f)).toInt() }
    }
}
