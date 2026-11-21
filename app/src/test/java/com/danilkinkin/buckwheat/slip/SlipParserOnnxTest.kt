package com.danilkinkin.buckwheat.slip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Fixtures in resources/slips/onnx are the output of the app's own OCR pipeline
 * ([com.danilkinkin.buckwheat.slip.ocr.SlipOcrEngine] + SlipLayout, run with desktop ONNX Runtime).
 * `*_real_*` come from real K PLUS / Make screenshots with decorated backgrounds; personal names in
 * them are replaced with placeholders.
 */
class SlipParserOnnxTest {
    private fun parse(name: String): SlipData {
        val text = javaClass.classLoader!!.getResource("slips/onnx/$name.txt")!!.readText()
        assertTrue(name, SlipParser.looksLikeSlip(text))
        return SlipParser.parse(text)
    }

    private fun check(name: String, amount: String, date: LocalDateTime, payee: String, note: String? = null) {
        val slip = parse(name)
        assertEquals(name, BigDecimal(amount), slip.amount)
        assertEquals(name, date, slip.dateTime)
        assertEquals(name, payee, slip.payee)
        assertEquals(name, note, slip.note)
    }

    @Test
    fun realKplusDecoratedBackground() = check(
        "kbank_real_matcha", "120.00", LocalDateTime.of(2026, 11, 21, 16, 39), "Kawa Matcha Teahouse",
    )

    @Test
    fun realKplusBlurredSender() = check(
        "kbank_real_amazon", "55.00", LocalDateTime.of(2026, 11, 19, 10, 24), "อเมซอน-สาขาหน้าวัดไตรมิตร",
    )

    @Test
    fun realMakeZeroForO() = check(
        // "27 0ct": zero read for the letter O; "MERCHANT" + "RE" must not become the payee
        "make_real_itcity", "1330.00", LocalDateTime.of(2026, 10, 27, 19, 10), "IT CITY PUBLIC",
    )

    @Test
    fun synthetic() {
        check("kbank", "120.00", LocalDateTime.of(2026, 11, 21, 14, 32), "ร้าน กาแฟดี", "กาแฟเย็น")
        check("scb", "65.00", LocalDateTime.of(2026, 11, 20, 9, 15), "7-Eleven", "ข้าวเช้า")
        check("english", "245.50", LocalDateTime.of(2026, 11, 19, 18, 45), "GRAB TAXI", "Grab home")
        check("ktb", "1250.00", LocalDateTime.of(2026, 11, 18, 12, 1), "ร้านข้าวมันไก่")
    }
}
