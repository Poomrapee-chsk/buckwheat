package com.danilkinkin.buckwheat.slip

import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDateTime

/**
 * What could be read from one payment slip. Every field is optional:
 * OCR is noisy, so the user reviews and fixes the result before saving.
 */
data class SlipData(
    val amount: BigDecimal? = null,
    val dateTime: LocalDateTime? = null,
    val payee: String? = null,
    val note: String? = null,
)

/**
 * Extracts amount, date, payee and note from OCR text of Thai / English bank transfer slips.
 *
 * Built to survive typical Tesseract mistakes on Thai text: dropped or extra upper/lower
 * vowels and tone marks, "ำ" split into "ํา", lost dots in month abbreviations ("พ.ย." -> "พุย.")
 * and one wrong consonant in a label ("ผู้รับ" -> "ผู้วับ").
 */
object SlipParser {

    private val AMOUNT_LABELS = listOf(
        "จำนวนเงิน", "จำนวน", "ยอดเงิน", "ยอดชำระ", "ยอดรวม", "ยอดโอน", "ยอดที่ชำระ",
        "amount", "total", "total amount",
    )
    private val FEE_LABELS = listOf("ค่าธรรมเนียม", "fee", "fees", "ค่าบริการ")
    private val NOTE_LABELS = listOf(
        "บันทึกช่วยจำ", "บันทึก", "หมายเหตุ", "ข้อความ", "รายละเอียด", "memo", "note", "notes",
    )
    private val PAYEE_LABELS = listOf(
        "ไปยัง", "ไปที่", "ผู้รับ", "ผู้รับเงิน", "ถึง", "ร้านค้า", "ชื่อร้าน",
        "to", "recipient", "payee", "merchant", "pay to",
    )
    private val OTHER_LABELS = listOf(
        "จาก", "ผู้โอน", "ผู้จ่าย", "from", "เลขที่รายการ", "รหัสอ้างอิง", "เลขที่อ้างอิง",
        "ref", "ref no", "reference", "transaction id", "วันที่", "date", "เวลา", "time",
    )

    private val THAI_MONTHS = listOf(
        listOf("มกราคม", "มค"), listOf("กุมภาพันธ์", "กพ"), listOf("มีนาคม", "มีค"),
        listOf("เมษายน", "เมย"), listOf("พฤษภาคม", "พค"), listOf("มิถุนายน", "มิย"),
        listOf("กรกฎาคม", "กค"), listOf("สิงหาคม", "สค"), listOf("กันยายน", "กย"),
        listOf("ตุลาคม", "ตค"), listOf("พฤศจิกายน", "พย"), listOf("ธันวาคม", "ธค"),
    )
    private val EN_MONTHS = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    )

    private val NUMBER = Regex("""(?<![\d.,])(\d{1,3}(?:,\d{3})+|\d+)(\.\d{1,2})?(?![\d,])""")
    private val CURRENCY = Regex("""บาท|thb|฿""", RegexOption.IGNORE_CASE)
    private val TEXT_DATE = Regex(
        """(\d{1,2})\s*([ก-๛A-Za-z][ก-๛A-Za-z.\s]{1,14}?)\s*(\d{4}|\d{2})(?!\d)(?:\D{0,5}?(\d{1,2})\s*[:.]\s*(\d{2})(?!\d))?"""
    )
    private val NUMERIC_DATE = Regex(
        """(?<!\d)(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4}|\d{2})(?!\d)(?:\D{0,5}?(\d{1,2})\s*:\s*(\d{2})(?!\d))?"""
    )
    // Masked account or PromptPay number: "xxx-x-x1234-x", "XXX-xxx-5678", "123-4-56789-0"
    private val ACCOUNT_LIKE = Regex("""(?:[xX*]{2,}|\d{3,})-[xX*\d]""")

    fun parse(rawText: String): SlipData {
        val lines = rawText.lines().map { normalize(it) }.filter { it.isNotBlank() }

        return SlipData(
            amount = findAmount(lines),
            dateTime = findDateTime(lines),
            payee = findLabeledValue(lines, PAYEE_LABELS) ?: findUnlabeledPayee(lines),
            note = findLabeledValue(lines, NOTE_LABELS),
        )
    }

    // ---- text helpers ----

    internal fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .replace("ํา", "ำ") // "ํา" -> "ำ"
        .replace(Regex("[​-‍﻿]"), "")
        .replace('\t', ' ')
        .trim()

    /** Thai text without upper/lower vowels, tone marks and dots: what OCR reads most reliably. */
    internal fun skeleton(text: String): String = text.lowercase()
        .replace("ำ", "า") // "ำ" -> "า"
        .replace(Regex("[ัิ-ฺ็-๎.:\\s]"), "")

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun labelMatches(candidate: String, label: String): Boolean {
        val c = skeleton(candidate)
        val l = skeleton(label)
        if (c.isEmpty() || l.isEmpty()) return false
        if (c == l) return true
        return l.length >= 3 && c.length >= 3 && levenshtein(c, l) <= 1
    }

    /** Splits "label: value" or "label      value" into its parts. */
    private fun splitLabel(line: String): Pair<String, String> {
        val colon = line.indexOf(':')
        // A colon inside a time ("14:32") is not a label separator
        if (colon > 0 && !line[colon - 1].isDigit()) {
            return line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        val gap = Regex("""\s{2,}""").find(line)
        if (gap != null) {
            return line.substring(0, gap.range.first).trim() to line.substring(gap.range.last + 1).trim()
        }
        return line to ""
    }

    /** Label part of the line, if it is one of [labels] (longest label wins). */
    private fun lineLabel(line: String, labels: List<String>): String? {
        val (label, _) = splitLabel(line)
        return labels
            .sortedByDescending { it.length }
            .firstOrNull { labelMatches(label, it) }
    }

    private fun isAnyLabel(line: String): Boolean =
        lineLabel(line, AMOUNT_LABELS + FEE_LABELS + NOTE_LABELS + PAYEE_LABELS + OTHER_LABELS) != null

    private fun findLabeledValue(lines: List<String>, labels: List<String>): String? {
        lines.forEachIndexed { index, line ->
            if (lineLabel(line, labels) == null) return@forEachIndexed

            val value = splitLabel(line).second
            if (value.isNotBlank()) return cleanValue(value)

            // Value is on the next line
            val next = lines.getOrNull(index + 1) ?: return null
            if (!isAnyLabel(next)) return cleanValue(next)
        }
        return null
    }

    private fun cleanValue(value: String): String? =
        value.replace(Regex("""\s{2,}"""), " ").trim().takeIf { it.length >= 2 }

    // ---- amount ----

    private fun parseNumber(match: MatchResult): BigDecimal? =
        (match.groupValues[1].replace(",", "") + match.groupValues[2]).toBigDecimalOrNull()

    private fun amountsIn(text: String): List<Pair<BigDecimal, Boolean>> =
        NUMBER.findAll(text).mapNotNull { match ->
            val value = parseNumber(match) ?: return@mapNotNull null
            val hasDecimals = match.groupValues[2].isNotEmpty()
            val after = text.substring(match.range.last + 1).trimStart()
            val hasCurrency = CURRENCY.matchesAt(after, 0) ||
                    CURRENCY.containsMatchIn(text.substring(0, match.range.first).takeLast(2))
            if (hasDecimals || hasCurrency) value to hasCurrency else null
        }.toList()

    private fun findAmount(lines: List<String>): BigDecimal? {
        // 1. Number on a line labeled as amount (or on the line right after it)
        lines.forEachIndexed { index, line ->
            if (lineLabel(line, AMOUNT_LABELS) == null) return@forEachIndexed

            amountsIn(splitLabel(line).second).firstOrNull()?.let { return it.first }
            lines.getOrNull(index + 1)?.let { next ->
                if (!isAnyLabel(next)) amountsIn(next).firstOrNull()?.let { return it.first }
            }
        }

        // 2. Largest number followed by a currency, skipping fees
        return lines
            .filter { lineLabel(it, FEE_LABELS) == null }
            .flatMap { amountsIn(it) }
            .filter { it.second && it.first > BigDecimal.ZERO }
            .maxOfOrNull { it.first }
    }

    // ---- date ----

    private fun thaiMonth(token: String): Int? {
        val s = skeleton(token)
        if (s.isEmpty()) return null
        THAI_MONTHS.forEachIndexed { index, names ->
            if (names.any { skeleton(it) == s }) return index + 1
        }
        // One wrong letter in a full month name ("พฤศจิกายน" read as "พฤศจิกายบ")
        THAI_MONTHS.forEachIndexed { index, names ->
            if (s.length >= 5 && levenshtein(s, skeleton(names[0])) <= 1) return index + 1
        }
        return null
    }

    private fun englishMonth(token: String): Int? {
        val t = token.lowercase().replace(".", "").trim()
        if (t.length < 3) return null
        val index = EN_MONTHS.indexOf(t.take(3))
        return if (index >= 0) index + 1 else null
    }

    private fun toYear(raw: String, thai: Boolean): Int {
        val year = raw.toInt()
        return when {
            raw.length == 4 && year >= 2400 -> year - 543 // Buddhist era
            raw.length == 4 -> year
            thai -> 2500 + year - 543 // "69" means 2569 BE
            else -> 2000 + year
        }
    }

    private fun buildDate(year: Int, month: Int, day: Int, hour: String?, minute: String?): LocalDateTime? =
        try {
            LocalDateTime.of(
                year, month, day,
                hour?.toIntOrNull()?.takeIf { it in 0..23 } ?: 0,
                minute?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0,
            )
        } catch (e: Exception) {
            null
        }

    private fun findDateTime(lines: List<String>): LocalDateTime? {
        for (line in lines) {
            for (match in TEXT_DATE.findAll(line)) {
                val (day, monthToken, year, hour, minute) = match.destructured
                val thai = thaiMonth(monthToken)
                val month = thai ?: englishMonth(monthToken) ?: continue
                buildDate(toYear(year, thai != null), month, day.toInt(), hour, minute)
                    ?.let { return it }
            }
        }
        for (line in lines) {
            for (match in NUMERIC_DATE.findAll(line)) {
                val (day, month, year, hour, minute) = match.destructured
                // Thai apps use Buddhist years in numeric dates too ("21/11/69")
                val thaiYear = year.length == 2 && year.toInt() >= 60
                buildDate(toYear(year, thaiYear), month.toInt(), day.toInt(), hour, minute)
                    ?.let { return it }
            }
        }
        return null
    }

    // ---- payee without a label (e.g. KBank: sender block, then recipient block) ----

    private fun isNameLike(line: String): Boolean {
        if (isAnyLabel(line) || ACCOUNT_LIKE.containsMatchIn(line)) return false
        val letters = line.count { it.isLetter() }
        val digits = line.count { it.isDigit() }
        return letters >= 3 && digits <= 2 && skeleton(line).length >= 3 &&
                !Regex("สำเร็จ|successful|success", RegexOption.IGNORE_CASE).containsMatchIn(line)
    }

    private fun findUnlabeledPayee(lines: List<String>): String? {
        val firstAccount = lines.indexOfFirst { ACCOUNT_LIKE.containsMatchIn(it) && !isAnyLabel(it) }
        if (firstAccount < 0) return null

        return lines
            .drop(firstAccount + 1)
            .takeWhile { !isAnyLabel(it) }
            .firstOrNull { isNameLike(it) }
            ?.let { cleanValue(it) }
    }
}
