package com.danilkinkin.buckwheat.slip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Real OCR output of K PLUS slips dated June ("มิ.ย.", whose vowel OCR drops): the day next to the
 * month gets damaged. Lines are from a benchmark run on real slips (dates and amounts only).
 */
class SlipParserDayTest {
    private fun slip(dateLine: String, amount: String = "40.00 บาท") =
        "ชำระเงินสำเร็จ\n$dateLine\n\nจำนวน:\n$amount\nค่าธรรมเนียม:\n0.00 บาท\nเลขที่รายการ:\n"

    private fun date(dateLine: String, imageDate: LocalDateTime? = null) =
        SlipParser.parse(slip(dateLine), imageDate).dateTime

    @Test
    fun strayCharacterBetweenDayAndMonth() {
        assertEquals(LocalDateTime.of(2025, 6, 22, 10, 48), date("22าม.ย. 68 10:48 น."))
        assertEquals(LocalDateTime.of(2025, 6, 24, 8, 55), date("24กม.ย. 68 08:55 น."))
        assertEquals(LocalDateTime.of(2025, 6, 22, 10, 44), date("22.ม.ย. 68 10:44 น."))
        assertEquals(LocalDateTime.of(2025, 6, 2, 11, 46), date("2☐ม.ย. 68 11:46 น."))
        assertEquals(LocalDateTime.of(2025, 6, 10, 16, 58), date("10ม.ย. 68 16:58 น."))
    }

    @Test
    fun letterOReadForZeroInTheDay() {
        assertEquals(LocalDateTime.of(2025, 6, 10, 16, 58), date("1Oม.ย. 68 16:58 U."))
    }

    @Test
    fun lostDayComesFromTheImageDate() {
        // Without the image's date there's no way to know the day
        assertNull(date("ม.ย. 68 12:18 น."))
        assertEquals(
            LocalDateTime.of(2025, 6, 12, 12, 18),
            date("ม.ย. 68 12:18 น.", imageDate = LocalDateTime.of(2025, 6, 12, 12, 19)),
        )
        // Stray letter where the day was
        assertEquals(
            LocalDateTime.of(2025, 6, 13, 12, 33),
            date("ตม.ย. 68 12:33 น.", imageDate = LocalDateTime.of(2025, 6, 13, 12, 34)),
        )
        // Image saved hours later (e.g. a screenshot taken afterwards): don't guess
        assertNull(date("ม.ย. 68 12:18 น.", imageDate = LocalDateTime.of(2025, 6, 12, 20, 0)))
    }

    @Test
    fun droppedDigitIsCorrectedByTheImageDate() {
        // OCR read "24" as "2"; the image was saved a minute after the payment
        assertEquals(
            LocalDateTime.of(2025, 6, 24, 16, 6),
            date("2 ม.ย. 68 16:06 U.", imageDate = LocalDateTime.of(2025, 6, 24, 16, 7)),
        )
        // Image from much later the same month: keep what the slip says
        assertEquals(
            LocalDateTime.of(2025, 6, 2, 16, 6),
            date("2 ม.ย. 68 16:06 U.", imageDate = LocalDateTime.of(2025, 6, 24, 9, 0)),
        )
    }

    @Test
    fun amountWithLostFirstDigitIsNotTaken() {
        // "100.00" read as "00.00": better no amount (the user fills it in) than 0
        assertNull(SlipParser.parse(slip("19ม.ย. 68 12:07 น.", amount = "00.00 บาท")).amount)
        assertEquals(BigDecimal("100.00"), SlipParser.parse(slip("19ม.ย. 68 12:07 น.", amount = "100.00 บาท")).amount)
        assertEquals(BigDecimal("0.50"), SlipParser.parse(slip("19ม.ย. 68 12:07 น.", amount = "0.50 บาท")).amount)
    }
}
