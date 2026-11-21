package com.danilkinkin.buckwheat.slip.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class LineFlattenerTest {
    /**
     * flatten_reference.txt: a 90 x 48 grey line (gradient background, dark and light strokes)
     * and the output of iApp's Python `_flatten` for it.
     */
    @Test
    fun matchesReferenceImplementation() {
        val lines = javaClass.classLoader!!.getResource("slips/flatten_reference.txt")!!.readText().lines()
        val (width, height) = lines[0].split(' ').map { it.toInt() }
        val input = lines[1].split(' ').map { it.toInt() }.toIntArray()
        val expected = lines[2].split(' ').map { it.toInt() }

        val actual = LineFlattener.flatten(input, width, height)

        val worst = expected.indices.maxOf { kotlin.math.abs(expected[it] - actual[it]) }
        assertEquals("largest difference from the reference", 0, worst)
    }
}
