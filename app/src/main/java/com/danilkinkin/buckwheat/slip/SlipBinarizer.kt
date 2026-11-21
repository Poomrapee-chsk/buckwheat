package com.danilkinkin.buckwheat.slip

/**
 * Turns a slip screenshot into black text on white before OCR.
 *
 * Bank apps put slips on decorated backgrounds (flowers, clouds, mascots) that Tesseract
 * reads as garbage, and some text is light grey on a coloured sky. A pixel is kept as text
 * when it is clearly darker than the average of its neighbourhood (adaptive mean threshold),
 * which removes smooth backgrounds whatever their colour.
 */
object SlipBinarizer {
    /** Pixel must be darker than this share of its neighbourhood's mean brightness */
    private const val DARKNESS = 0.70

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** @param argb pixels row by row, as returned by Bitmap.getPixels */
    fun binarize(argb: IntArray, width: Int, height: Int): IntArray {
        // About 1/16 of the width: wider than a character stroke, narrower than a background shape
        val window = maxOf(15, (width / 16) or 1)
        val radius = window / 2

        val luminance = DoubleArray(argb.size) { i ->
            val pixel = argb[i]
            0.299 * (pixel shr 16 and 0xFF) + 0.587 * (pixel shr 8 and 0xFF) + 0.114 * (pixel and 0xFF)
        }

        // Summed-area table, one extra row and column of zeros
        val stride = width + 1
        val integral = DoubleArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0.0
            for (x in 0 until width) {
                rowSum += luminance[y * width + x]
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
            }
        }

        val result = IntArray(argb.size)
        for (y in 0 until height) {
            val y0 = maxOf(0, y - radius)
            val y1 = minOf(height, y + radius + 1)
            for (x in 0 until width) {
                val x0 = maxOf(0, x - radius)
                val x1 = minOf(width, x + radius + 1)
                val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] -
                        integral[y1 * stride + x0] + integral[y0 * stride + x0]
                val mean = sum / ((y1 - y0) * (x1 - x0))
                result[y * width + x] = if (luminance[y * width + x] < mean * DARKNESS) BLACK else WHITE
            }
        }

        return result
    }
}
