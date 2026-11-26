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
    /** Bank transaction reference, unique per transfer */
    val reference: String? = null,
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
        "to", "recipient", "payee", "pay to",
    )
    private val REFERENCE_LABELS = listOf(
        "เลขที่รายการ", "รหัสอ้างอิง", "เลขที่อ้างอิง", "หมายเลขอ้างอิง", "รหัสรายการ",
        "transaction id", "transaction no", "ref no", "ref", "reference", "reference no",
    )
    private val OTHER_LABELS = listOf(
        "จาก", "ผู้โอน", "ผู้จ่าย", "from", "เลขที่รายการ", "รหัสอ้างอิง", "เลขที่อ้างอิง",
        "ref", "ref no", "reference", "transaction id", "วันที่", "date", "เวลา", "time",
        // On K PLUS / Make slips a merchant *number*, not a name
        "merchant", "merchant no", "merchantno", "merchant id",
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
        // Day may have an "O" read for zero before a Thai month: "1Oม.ย."; a stray mark may sit
        // between day and month: "22.ม.ย.", "2☐ม.ย." (a stray Thai letter is handled in [month]).
        // Month may start with a zero read for "O": "27 0ct 2026"
        """(?<!\d)(\d(?:\d|[Oo](?=\s*[ก-๛]))?)\s*(?:[^\s\dก-๛A-Za-z]\s*)?([ก-๛A-Za-z0][ก-๛A-Za-z.\s]{1,14}?)\s*(\d{4}|\d{2})(?!\d)(?:\D{0,5}?(\d{1,2})\s*[:.]\s*(\d{2})(?!\d))?"""
    )

    /** Month, year and time with the day lost by OCR: "ม.ย. 68 12:18 น." */
    private val DATE_WITHOUT_DAY = Regex(
        """(?<![\dก-๛A-Za-z])([ก-๛A-Za-z][ก-๛A-Za-z.]{1,12})\s*(\d{4}|\d{2})(?!\d)\D{0,5}?(\d{1,2})\s*[:.]\s*(\d{2})(?!\d)"""
    )

    /** How far apart a slip's time and its image's time may be to trust the image's day */
    private const val IMAGE_TIME_TOLERANCE_MINUTES = 15L
    private val NUMERIC_DATE = Regex(
        """(?<!\d)(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4}|\d{2})(?!\d)(?:\D{0,5}?(\d{1,2})\s*:\s*(\d{2})(?!\d))?"""
    )
    // Masked account or PromptPay number: "xxx-x-x1234-x", "XXX-xxx-5678", "123-4-56789-0"
    private val ACCOUNT_LIKE = Regex("""(?:[xX*]{2,}|\d{3,})-[xX*\d]""")

    /**
     * @param imageDate when the slip image was saved (MediaStore date): bank apps save the slip at
     *   payment time, so it fills in or corrects a day that OCR lost or misread, see [reconcileDate]
     */
    fun parse(rawText: String, imageDate: LocalDateTime? = null): SlipData {
        // Empty lines mark large gaps between blocks, only used to find the payee
        val withGaps = rawText.lines().map { normalize(it) }
        val lines = withGaps.filter { it.isNotBlank() }

        return SlipData(
            amount = findAmount(lines),
            dateTime = reconcileDate(findDateTime(lines), findDateWithoutDay(lines), imageDate),
            // A payee is a name: "RE" under a blurred "MERCHANT NO" line is not
            payee = findLabeledValue(lines, PAYEE_LABELS)
                ?.takeIf { value -> looksLikeName(value) }
                ?: findUnlabeledPayee(withGaps),
            note = findLabeledValue(lines, NOTE_LABELS) ?: findNoteAfterReference(lines),
            reference = findLabeledValue(lines, REFERENCE_LABELS)?.let { referenceIn(it) },
        )
    }

    private val SLIP_KEYWORDS = listOf(
        "สำเร็จ", "ค่าธรรมเนียม", "เลขที่รายการ", "รหัสอ้างอิง", "เลขที่อ้างอิง", "บันทึกช่วยจำ",
    )
    private val SLIP_KEYWORDS_LATIN = Regex(
        "successful|completed|transaction id|ref(erence)? ?no|fee", RegexOption.IGNORE_CASE,
    )

    /**
     * Whether OCR text is a payment slip and not any other screenshot or photo in the folder:
     * it needs an amount, a date and at least one word typical for slips.
     */
    fun looksLikeSlip(rawText: String, imageDate: LocalDateTime? = null): Boolean {
        val slip = parse(rawText, imageDate)
        if (slip.amount == null || slip.dateTime == null) return false

        val text = normalize(rawText)
        val textSkeleton = skeleton(text)
        return SLIP_KEYWORDS_LATIN.containsMatchIn(text) ||
                SLIP_KEYWORDS.any { textSkeleton.contains(skeleton(it)) }
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
        // One wrong letter is a typical OCR mistake in Thai. Latin text is read reliably,
        // and short English labels ("fee", "to") would otherwise match background noise
        val thai = l.any { it in '\u0E00'..'\u0E7F' }
        return thai && l.length >= 3 && c.length >= 3 && levenshtein(c, l) <= 1
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
        lineLabel(line, AMOUNT_LABELS + FEE_LABELS + NOTE_LABELS + PAYEE_LABELS + REFERENCE_LABELS + OTHER_LABELS) != null

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

    // ---- reference ----

    private val REFERENCE_TOKEN = Regex("[A-Za-z0-9]{8,}")

    /** The ID in a reference value: 8+ letters and digits, at least 4 of them digits */
    private fun referenceIn(value: String): String? =
        REFERENCE_TOKEN.findAll(value)
            .map { it.value }
            .filter { token -> token.count { it.isDigit() } >= 4 }
            .maxByOrNull { it.length }
            ?.uppercase()

    // ---- amount ----

    private fun parseNumber(match: MatchResult): BigDecimal? {
        val whole = match.groupValues[1]
        // "00.00" is "100.00" with its first digit lost: better no amount than a wrong one
        if (whole.length > 1 && whole.startsWith("0")) return null
        return (whole.replace(",", "") + match.groupValues[2]).toBigDecimalOrNull()
    }

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
        val t = token.lowercase().replace(".", "").replace('0', 'o').trim()
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

    /** Thai month, also with one stray letter read before it: "าม.ย.", "กม.ย." */
    private fun thaiMonthLenient(token: String): Int? =
        thaiMonth(token) ?: token.trim().takeIf { it.length >= 3 && it[0] in 'ก'..'๛' }?.let { thaiMonth(it.drop(1)) }

    private fun findDateTime(lines: List<String>): LocalDateTime? {
        for (line in lines) {
            for (match in TEXT_DATE.findAll(line)) {
                val (day, monthToken, year, hour, minute) = match.destructured
                val thai = thaiMonthLenient(monthToken)
                val month = thai ?: englishMonth(monthToken) ?: continue
                val dayNumber = day.replace('O', '0').replace('o', '0').toInt()
                buildDate(toYear(year, thai != null), month, dayNumber, hour, minute)
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

    private class DateWithoutDay(val year: Int, val month: Int, val hour: Int, val minute: Int)

    private fun findDateWithoutDay(lines: List<String>): DateWithoutDay? {
        for (line in lines) {
            for (match in DATE_WITHOUT_DAY.findAll(line)) {
                val (monthToken, year, hour, minute) = match.destructured
                val thai = thaiMonthLenient(monthToken)
                val month = thai ?: englishMonth(monthToken) ?: continue
                val h = hour.toInt().takeIf { it in 0..23 } ?: continue
                val m = minute.toInt().takeIf { it in 0..59 } ?: continue
                return DateWithoutDay(toYear(year, thai != null), month, h, m)
            }
        }
        return null
    }

    private fun minutesApart(aHour: Int, aMinute: Int, b: LocalDateTime): Long =
        kotlin.math.abs((aHour * 60L + aMinute) - (b.hour * 60L + b.minute))

    /**
     * OCR often loses or garbles the day next to a Thai month ("มิ.ย." with its vowel lost):
     * the day is missing, or a digit dropped ("2" for "24"). Bank apps save the slip image at payment
     * time, so when the slip's month and year match the image's and its time is within
     * [IMAGE_TIME_TOLERANCE_MINUTES] of the image's, the image's day is used.
     */
    private fun reconcileDate(found: LocalDateTime?, withoutDay: DateWithoutDay?, imageDate: LocalDateTime?): LocalDateTime? {
        if (imageDate == null) return found

        if (found != null) {
            val sameMonth = found.year == imageDate.year && found.monthValue == imageDate.monthValue
            if (sameMonth && found.dayOfMonth != imageDate.dayOfMonth &&
                minutesApart(found.hour, found.minute, imageDate) <= IMAGE_TIME_TOLERANCE_MINUTES
            ) {
                return found.withDayOfMonth(imageDate.dayOfMonth)
            }
            return found
        }

        val partial = withoutDay ?: return null
        if (partial.year == imageDate.year && partial.month == imageDate.monthValue &&
            minutesApart(partial.hour, partial.minute, imageDate) <= IMAGE_TIME_TOLERANCE_MINUTES
        ) {
            return LocalDateTime.of(partial.year, partial.month, imageDate.dayOfMonth, partial.hour, partial.minute)
        }
        return null
    }

    // ---- note without a label ----
    // Make by KBank prints the description alone under "Transaction ID", with no "Memo" label
    // and no keyword to find it by: only its position gives it away.

    // The slip's own footer: QR caption, merchant number, not something the user typed
    private val FOOTER_WORDS = Regex("scan|verify|merchant|qr", RegexOption.IGNORE_CASE)
    private val FOOTER_WORDS_THAI = listOf("สแกน", "ตรวจสอบ")
    private const val MAX_UNLABELED_NOTE_LENGTH = 60

    /**
     * The line right below the transaction reference when it is plain text: the description a
     * Make by KBank slip shows without a label. Null when that line is a number, a label or
     * the slip's footer, so a slip with no description doesn't get a wrong one.
     */
    private fun findNoteAfterReference(lines: List<String>): String? {
        val refIndex = lines.indexOfFirst { lineLabel(it, REFERENCE_LABELS) != null }
        if (refIndex < 0) return null

        var index = refIndex + 1
        // The ID itself may sit on the line below its label
        if (splitLabel(lines[refIndex]).second.isBlank() && lines.getOrNull(index)?.let { referenceIn(it) } != null) {
            index++
        }

        val line = lines.getOrNull(index) ?: return null
        if (FOOTER_WORDS.containsMatchIn(line)) return null
        val lineSkeleton = skeleton(line)
        if (FOOTER_WORDS_THAI.any { lineSkeleton.contains(skeleton(it)) }) return null

        // A note is a short, mostly-letters line with a single wide gap-free run of text
        return nameOnLine(line)
            ?.takeIf { it.length <= MAX_UNLABELED_NOTE_LENGTH && referenceIn(it) == null }
    }

    // ---- payee without a label ----
    // KBank / Make slips have no "to" label: a sender block (name, bank, account),
    // then a recipient block (shop name, branch, owner), then the labeled amount.
    // Account numbers are often blurred, so: the recipient is the first line of the
    // last block of name-like lines before the first label.

    private val SUCCESS = Regex("completed|successful|success", RegexOption.IGNORE_CASE)

    // The sender's bank and account type, not the payee: "ธ.กสิกรไทย", "by KBank", "พร้อมเพย์"
    private val BANK_LINE = Regex("^ธ\\.|ธนาคาร|bank|พร้อมเพย์|promptpay", RegexOption.IGNORE_CASE)

    /** At least 3 letters, and mostly letters: "RE ./บอหชบมอบน~" is OCR noise, not a name */
    private fun looksLikeName(value: String): Boolean {
        val wordChars = value.count { isWordChar(it) }
        val other = value.count { !isWordChar(it) && !it.isWhitespace() && !it.isDigit() && it !in "-.()&'" }
        return wordChars >= 3 && other <= 1 && wordChars >= value.count { !it.isWhitespace() } * 0.7
    }

    /** Letters plus Thai vowel and tone marks, which are not letters for [Char.isLetter] */
    private fun isWordChar(c: Char): Boolean = c.isLetter() ||
            Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt()

    /** Mostly OCR noise from backgrounds and icons: "ว%", "#", "ง.", "0๓๐ง" */
    private fun isJunkToken(token: String): Boolean =
        token.length <= 1 || token.count { isWordChar(it) } < token.length * 0.6

    /** The name on this line without surrounding noise, or null when it isn't a name */
    private fun nameOnLine(line: String): String? {
        if (isAnyLabel(line) || ACCOUNT_LIKE.containsMatchIn(line)) return null
        if (SUCCESS.containsMatchIn(line) || skeleton(line).contains(skeleton("สำเร็จ"))) return null
        if (BANK_LINE.containsMatchIn(line)) return null

        // Decorations on the right are separated from the text by a wide gap
        val tokens = line.split(Regex("""\s{2,}""")).first()
            .split(' ').filter { it.isNotBlank() }.toMutableList()
        while (tokens.isNotEmpty() && isJunkToken(tokens.first())) tokens.removeAt(0)
        while (tokens.isNotEmpty() && isJunkToken(tokens.last())) tokens.removeAt(tokens.lastIndex)

        val name = tokens.joinToString(" ")
        val wordChars = name.count { isWordChar(it) }
        val digits = name.count { it.isDigit() }

        return name.takeIf { wordChars >= 3 && digits <= 2 && skeleton(it).length >= 3 }
    }

    /** @param lines may contain empty lines: gaps between blocks */
    private fun findUnlabeledPayee(lines: List<String>): String? {
        val firstLabel = lines.indexOfFirst { it.isNotBlank() && isAnyLabel(it) }.let { if (it < 0) lines.size else it }

        var lastBlock: String? = null
        var inBlock = false
        lines.take(firstLabel).forEach { line ->
            val name = if (line.isBlank()) null else nameOnLine(line)
            if (name != null && !inBlock) lastBlock = name
            inBlock = name != null
        }

        return lastBlock
    }
}
