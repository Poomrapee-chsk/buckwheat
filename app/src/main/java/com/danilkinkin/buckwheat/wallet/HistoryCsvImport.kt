package com.danilkinkin.buckwheat.wallet

import org.apache.commons.csv.CSVFormat
import java.io.Reader
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** One spend from an "all periods" history export, see [HistoryCsv] */
data class HistoryRow(
    val date: LocalDateTime,
    val amount: BigDecimal,
    val currency: String,
    val tag: String,
    val manualTreat: Boolean,
    val automaticTreat: Boolean,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    /** The budget of the row's period, in exports since it was added */
    val periodBudget: BigDecimal?,
    val payee: String?,
    val reference: String?,
    val note: String?,
    /** From a slip: everything OCR read, and its image */
    val rawText: String = "",
    val mediaId: Long? = null,
)

class NotAHistoryFileException : Exception("Not a Buckwheat history export")

/** Reads the CSV written by "Export all history"; columns are found by name, so older exports work too */
object HistoryCsvImport {
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** @throws NotAHistoryFileException when the file isn't an "all periods" export */
    fun parse(reader: Reader): List<HistoryRow> {
        val parser = CSVFormat.Builder.create()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build()
            .parse(reader)

        // A UTF-8 byte order mark may be glued to the first column's name
        val columns = parser.headerNames.map { it.removePrefix("﻿").trim().lowercase() }
        if ("date" !in columns || "amount" !in columns) throw NotAHistoryFileException()

        return parser.records.mapNotNull { record ->
            fun cell(name: String): String? =
                columns.indexOf(name).takeIf { it >= 0 && it < record.size() }?.let { record[it] }?.takeIf { it.isNotBlank() }

            val date = cell("date")?.let { runCatching { LocalDateTime.parse(it, DATE_TIME) }.getOrNull() }
                ?: return@mapNotNull null
            val amount = cell("amount")?.replace(",", "")?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
                ?: return@mapNotNull null
            val treat = cell("treat")

            HistoryRow(
                date = date,
                amount = amount,
                currency = cell("currency").orEmpty(),
                tag = cell("tag").orEmpty(),
                manualTreat = treat == "manual",
                automaticTreat = treat == "automatic",
                periodStart = cell("period_start")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                periodEnd = cell("period_end")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                periodBudget = cell("period_budget")?.toBigDecimalOrNull(),
                payee = cell("payee"),
                reference = cell("reference"),
                note = cell("note"),
            )
        }
    }
}

/**
 * Which rows to add, and where. Duplicates of what's here (same slip reference, or same minute,
 * amount and tag) and future rows are left out. Rows from the current period's start on go to the
 * current period; older rows go to their past period from the CSV, kept clear of the current period,
 * or to their calendar month when the CSV doesn't say.
 */
class HistoryImportPlan(
    val toCurrent: List<HistoryRow>,
    /** Keyed by first and last day */
    val toPast: Map<Pair<LocalDate, LocalDate>, List<HistoryRow>>,
    val duplicates: Int,
    /** Dated in the future */
    val skipped: Int,
    /** The current period's new start, moved back to take in this month's earlier slips; null to keep it */
    val newCurrentStart: LocalDate? = null,
) {
    val added get() = toCurrent.size + toPast.values.sumOf { it.size }

    companion object {
        /** What makes two spends the same: minute, amount and tag */
        fun key(date: LocalDateTime, amount: BigDecimal, tag: String) =
            Triple(date.withSecond(0).withNano(0), amount.stripTrailingZeros(), tag.trim())

        /**
         * How far back the current period can take in earlier slips: the 1st of its month, but not into
         * a past period. Null when it can't move back (it starts on the 1st, or a past period ends just before)
         */
        fun extendableStart(currentStart: LocalDate, existingPeriods: List<Pair<LocalDate, LocalDate>>): LocalDate? {
            val monthStart = currentStart.withDayOfMonth(1)
            val afterPast = existingPeriods.map { it.second }.filter { it.isBefore(currentStart) }.maxOrNull()?.plusDays(1)
            val start = listOfNotNull(monthStart, afterPast).max()
            return start.takeIf { it.isBefore(currentStart) }
        }

        fun make(
            rows: List<HistoryRow>,
            existing: Set<Triple<LocalDateTime, BigDecimal, String>>,
            usedReferences: Set<String>,
            /** First day of the current period, null when there's no budget */
            currentStart: LocalDate?,
            now: LocalDateTime,
            /** First and last day of the past periods already here */
            existingPeriods: List<Pair<LocalDate, LocalDate>> = emptyList(),
            /**
             * Rows without a period of their own (slips) from this day up to [currentStart] join the
             * current period, which then starts here: as if it had been tracked since. See [extendableStart]
             */
            currentExtendableStart: LocalDate? = null,
        ): HistoryImportPlan {
            var newCurrentStart: LocalDate? = null
            val seen = existing.toMutableSet()
            val references = usedReferences.toMutableSet()
            val toCurrent = mutableListOf<HistoryRow>()
            val toPast = linkedMapOf<Pair<LocalDate, LocalDate>, MutableList<HistoryRow>>()
            var duplicates = 0
            var skipped = 0
            val lastPastDay = currentStart?.minusDays(1)
            val clamp = { day: LocalDate -> if (lastPastDay != null && day.isAfter(lastPastDay)) lastPastDay else day }

            rows.sortedBy { it.date }.forEach { row ->
                if (row.date.isAfter(now)) {
                    skipped++
                    return@forEach
                }
                val key = key(row.date, row.amount, row.tag)
                if (key in seen || (row.reference != null && row.reference in references)) {
                    duplicates++
                    return@forEach
                }
                seen += key
                row.reference?.let { references += it }

                val day = row.date.toLocalDate()
                if (currentStart != null && !day.isBefore(currentStart)) {
                    toCurrent += row
                    return@forEach
                }
                if (currentStart != null && currentExtendableStart != null && row.periodStart == null &&
                    !day.isBefore(currentExtendableStart)
                ) {
                    toCurrent += row
                    newCurrentStart = currentExtendableStart
                    return@forEach
                }

                var start = row.periodStart
                var end = row.periodEnd?.let(clamp)
                if (start == null || end == null || start.isAfter(end) || day.isBefore(start) || day.isAfter(end)) {
                    // A past period already here that covers the day, else the day's month,
                    // shrunk so it doesn't overlap past periods already here
                    val covering = existingPeriods.firstOrNull { (s, e) -> !day.isBefore(s) && !day.isAfter(e) }
                    if (covering != null) {
                        start = covering.first
                        end = covering.second
                    } else {
                        val month = YearMonth.from(day)
                        start = month.atDay(1)
                        end = clamp(month.atEndOfMonth())
                        existingPeriods.forEach { (s, e) ->
                            if (s.isAfter(day) && !s.isAfter(end!!)) end = s.minusDays(1)
                            if (e.isBefore(day) && !e.isBefore(start!!)) start = e.plusDays(1)
                        }
                    }
                }
                toPast.getOrPut(start!! to end!!) { mutableListOf() } += row
            }

            return HistoryImportPlan(toCurrent, toPast, duplicates, skipped, newCurrentStart)
        }
    }
}
