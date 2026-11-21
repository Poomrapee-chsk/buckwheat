package com.danilkinkin.buckwheat.slip.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class SlipLayoutTest {
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int = top + 30) =
        OcrLine(text, 0.99f, left, top, right, bottom)

    @Test
    fun rowsColumnsGapsAndDecorations() {
        val text = SlipLayout.toText(
            listOf(
                line("120.00 บาท", 450, 400, 700),
                line("จำนวน:", 50, 400, 200),
                line("Kawa Matcha Teahouse", 170, 200, 660),
                line("มีสุข", 700, 260, 900),          // theme art on the right: dropped
                line("ตรวจสอบสลิป", 760, 500, 990),    // QR caption: dropped
                line("1,330.00", 760, 550, 990),       // right-aligned value with digits: kept
                line("ชำระเงินสำเร็จ", 170, 40, 450),
            ),
            imageWidth = 1000,
        )

        assertEquals(
            "ชำระเงินสำเร็จ\n" +
                    "\n" +                               // big gap before the next block
                    "Kawa Matcha Teahouse\n" +
                    "\n" +
                    "จำนวน:     120.00 บาท\n" +          // same row, wide gap -> column separator
                    "\n" +
                    "1,330.00\n",
            text,
        )
    }

    @Test
    fun empty() = assertEquals("", SlipLayout.toText(emptyList(), 1000))
}
