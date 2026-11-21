package com.danilkinkin.buckwheat.slip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Fixtures in resources/slips are real Tesseract output (tha+eng, tessdata_fast, PSM 6,
 * after the same adaptive threshold as [SlipTextRecognizer]), so they contain genuine OCR
 * mistakes. `*_real_*` come from real bank app screenshots with decorated backgrounds;
 * personal names in them are replaced with placeholders.
 */
class SlipParserTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("slips/$name.txt")!!.readText()

    @Test
    fun kbank() {
        val slip = SlipParser.parse(fixture("kbank"))
        assertEquals(BigDecimal("120.00"), slip.amount)
        assertEquals(LocalDateTime.of(2026, 11, 21, 14, 32), slip.dateTime)
        assertEquals("ร้าน กาแฟดี", slip.payee)
        assertEquals("กาแฟเย็น", slip.note)
    }

    @Test
    fun scb() {
        val slip = SlipParser.parse(fixture("scb"))
        assertEquals(BigDecimal("65.00"), slip.amount)
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 15), slip.dateTime)
        assertEquals("7-Eleven", slip.payee)
        assertEquals("ข้าวเช้า", slip.note)
    }

    @Test
    fun english() {
        val slip = SlipParser.parse(fixture("english"))
        assertEquals(BigDecimal("245.50"), slip.amount)
        assertEquals(LocalDateTime.of(2026, 11, 19, 18, 45), slip.dateTime)
        assertEquals("GRAB TAXI", slip.payee)
        assertEquals("Grab home", slip.note)
    }

    @Test
    fun krungthaiWithoutNote() {
        val slip = SlipParser.parse(fixture("ktb"))
        assertEquals(BigDecimal("1250.00"), slip.amount)
        assertEquals(LocalDateTime.of(2026, 11, 18, 12, 1), slip.dateTime)
        // "ผู้รับ" was read as "ผู้วับ"
        assertEquals("ร้านข้าวมันไก่", slip.payee)
        assertNull(slip.note)
    }

    @Test
    fun realKbankWithFloralBackground() {
        val slip = SlipParser.parse(fixture("kbank_real_matcha"))
        assertEquals(BigDecimal("120.00"), slip.amount)
        assertEquals(LocalDateTime.of(2026, 11, 21, 16, 39), slip.dateTime)
        assertEquals("Kawa Matcha Teahouse", slip.payee)
        assertNull(slip.note)
    }

    @Test
    fun realKbankWithBlurredAccount() {
        val slip = SlipParser.parse(fixture("kbank_real_amazon"))
        assertEquals(BigDecimal("55.00"), slip.amount)
        assertEquals(LocalDateTime.of(2026, 11, 19, 10, 24), slip.dateTime)
        assertEquals("อเมซอน-สาขาหน้าวัดไตรมิตร", slip.payee)
        assertNull(slip.note)
    }

    @Test
    fun realMakeByKbankEnglish() {
        val slip = SlipParser.parse(fixture("make_real_itcity"))
        assertEquals(BigDecimal("1330.00"), slip.amount)
        assertEquals(LocalDateTime.of(2026, 10, 27, 19, 10), slip.dateTime)
        assertEquals("IT CITY PUBLIC", slip.payee)
        assertNull(slip.note)
    }

    @Test
    fun valueOnNextLineAndDroppedVowels() {
        val slip = SlipParser.parse(
            """
            ทํารายการสําเร็จ
            3 ธค 68 08:05
            จำนวนเงิน
            1,000.50 บาท
            บนทกชวยจา
            ค่าเช่า
            """.trimIndent()
        )
        assertEquals(BigDecimal("1000.50"), slip.amount)
        assertEquals(LocalDateTime.of(2025, 12, 3, 8, 5), slip.dateTime)
        assertEquals("ค่าเช่า", slip.note)
    }

    @Test
    fun amountFallsBackToCurrencyAndSkipsFee() {
        val slip = SlipParser.parse(
            """
            Payment completed
            05/11/2026 10:20
            ค่าธรรมเนียม 15.00 บาท
            89.00 บาท
            """.trimIndent()
        )
        assertEquals(BigDecimal("89.00"), slip.amount)
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 20), slip.dateTime)
    }

    @Test
    fun nothingReadable() {
        val slip = SlipParser.parse("aA ๐ ๕\n\n")
        assertNull(slip.amount)
        assertNull(slip.dateTime)
        assertNull(slip.note)
    }
}
