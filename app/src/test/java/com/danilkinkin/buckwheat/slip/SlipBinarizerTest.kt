package com.danilkinkin.buckwheat.slip

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/**
 * english.rgb.gz: a rendered slip as raw RGB (big-endian width and height, then 3 bytes a pixel).
 * english_binarized.mask.gz: the reference black/white output the OCR fixtures were produced
 * with (width, height, then 1 bit a pixel, 1 = black).
 */
class SlipBinarizerTest {
    private fun read(name: String): Triple<Int, Int, ByteArray> {
        val stream = javaClass.classLoader!!.getResourceAsStream("slips/images/$name")!!
        DataInputStream(GZIPInputStream(stream)).use {
            val width = it.readInt()
            val height = it.readInt()
            return Triple(width, height, it.readBytes())
        }
    }

    @Test
    fun matchesReferenceOutput() {
        val (width, height, rgb) = read("english.rgb.gz")
        val (_, _, mask) = read("english_binarized.mask.gz")

        val pixels = IntArray(width * height) { i ->
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = SlipBinarizer.binarize(pixels, width, height)

        val different = (0 until width * height).count { i ->
            val expectedBlack = (mask[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
            val actualBlack = result[i] and 0xFF < 128
            expectedBlack != actualBlack
        }
        // Allow for floating point rounding at the threshold
        assertTrue("$different of ${width * height} pixels differ", different <= width * height / 1000)
    }
}
