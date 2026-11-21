package com.danilkinkin.buckwheat.slip.ocr

import kotlin.math.floor

internal object ImageOps {
    /**
     * Crops a region of [image], resizes it to [outW] x [outH] (bilinear, with area averaging when
     * shrinking, like Pillow) and returns a CHW float tensor in BGR channel order, as PaddleOCR models
     * expect. [normalize] gets the channel (0 = B, 1 = G, 2 = R) and the 0..255 value.
     */
    fun toTensor(
        image: OcrImage,
        cropX: Int,
        cropY: Int,
        cropW: Int,
        cropH: Int,
        outW: Int,
        outH: Int,
        normalize: (channel: Int, value: Float) -> Float,
    ): FloatArray {
        val xs = weights(cropW, outW)
        val ys = weights(cropH, outH)
        val plane = outW * outH
        val out = FloatArray(3 * plane)

        // Resize rows first into a temporary buffer, then columns (separable filter)
        val tmp = FloatArray(3 * cropH * outW)
        for (y in 0 until cropH) {
            val row = (cropY + y) * image.width + cropX
            for (x in 0 until outW) {
                var b = 0f
                var g = 0f
                var r = 0f
                val w = xs[x]
                for (k in w.indices) {
                    val p = image.argb[row + w.first + k]
                    val wk = w.weights[k]
                    r += (p shr 16 and 0xFF) * wk
                    g += (p shr 8 and 0xFF) * wk
                    b += (p and 0xFF) * wk
                }
                val i = (y * outW + x) * 3
                tmp[i] = b
                tmp[i + 1] = g
                tmp[i + 2] = r
            }
        }
        for (y in 0 until outH) {
            val w = ys[y]
            for (x in 0 until outW) {
                for (c in 0 until 3) {
                    var v = 0f
                    for (k in w.indices) v += tmp[((w.first + k) * outW + x) * 3 + c] * w.weights[k]
                    out[c * plane + y * outW + x] = normalize(c, v.coerceIn(0f, 255f))
                }
            }
        }
        return out
    }

    class Weights(val first: Int, val weights: FloatArray) {
        val indices get() = weights.indices
    }

    /** Triangle filter weights, widened by the scale when shrinking (Pillow's BILINEAR resize) */
    private fun weights(inSize: Int, outSize: Int): Array<Weights> {
        val scale = inSize.toDouble() / outSize
        val support = if (scale > 1) scale else 1.0
        return Array(outSize) { o ->
            val center = (o + 0.5) * scale
            val from = maxOf(0, floor(center - support).toInt())
            val to = minOf(inSize, floor(center + support).toInt() + 1)
            val raw = DoubleArray(to - from) { i ->
                val d = (from + i + 0.5 - center) / support
                maxOf(0.0, 1.0 - kotlin.math.abs(d))
            }
            val sum = raw.sum().takeIf { it > 0 } ?: 1.0
            Weights(from, FloatArray(raw.size) { (raw[it] / sum).toFloat() })
        }
    }
}
