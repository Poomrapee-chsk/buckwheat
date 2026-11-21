package com.danilkinkin.buckwheat.wallet

import com.danilkinkin.buckwheat.data.entities.Period
import com.danilkinkin.buckwheat.data.entities.SlipDetails
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

class HistoryCsvImportTest {
    private fun date(t: LocalDateTime) = Date.from(t.atZone(ZoneId.systemDefault()).toInstant())

    private fun row(
        date: LocalDateTime,
        amount: String,
        tag: String = "",
        start: LocalDate? = null,
        end: LocalDate? = null,
        reference: String? = null,
    ) = HistoryRow(date, BigDecimal(amount), "THB", tag, false, false, start, end, null, null, reference, null)

    @Test
    fun exportThenImportGivesTheSameRows() {
        val past = Period(
            budget = BigDecimal("10000"),
            startDate = date(LocalDateTime.of(2026, 10, 1, 0, 0)),
            finishDate = date(LocalDateTime.of(2026, 10, 31, 23, 59)),
            actualFinishDate = null,
            currency = "THB",
            archivedAt = date(LocalDateTime.of(2026, 11, 1, 0, 0)),
        ).apply { uid = 3 }
        val spends = listOf(
            Transaction(TransactionType.SPENT, BigDecimal("1330.00"), date(LocalDateTime.of(2026, 10, 27, 19, 10)), "Gadgets, misc", manualTreat = true, periodId = 3).apply { uid = 1 },
            Transaction(TransactionType.SPENT, BigDecimal("120.00"), date(LocalDateTime.of(2026, 11, 21, 16, 39)), "Matcha").apply { uid = 2 },
        )
        val details = mapOf(2 to SlipDetails(2, payee = "Kawa Matcha Teahouse", reference = "016325143212ATF04567", note = "ชาเย็น"))
        val current = CurrentPeriodInfo(date(LocalDateTime.of(2026, 11, 1, 0, 0)), date(LocalDateTime.of(2026, 11, 30, 0, 0)), "THB", BigDecimal("9000"))

        val csv = StringWriter().also { HistoryCsv.write(it, HistoryCsv.rows(spends, listOf(past), current, details)) }.toString()
        val rows = HistoryCsvImport.parse(StringReader(csv))

        assertEquals(2, rows.size)
        rows[0].let {
            assertEquals(LocalDateTime.of(2026, 10, 27, 19, 10), it.date)
            assertEquals(BigDecimal("1330.00"), it.amount)
            assertEquals("Gadgets, misc", it.tag)
            assertTrue(it.manualTreat)
            assertEquals(LocalDate.of(2026, 10, 1), it.periodStart)
            assertEquals(LocalDate.of(2026, 10, 31), it.periodEnd)
            assertEquals(BigDecimal("10000"), it.periodBudget)
            assertNull(it.payee)
        }
        rows[1].let {
            assertEquals("Kawa Matcha Teahouse", it.payee)
            assertEquals("016325143212ATF04567", it.reference)
            assertEquals("ชาเย็น", it.note)
        }
    }

    @Test
    fun olderExportWithoutNewColumns() {
        val csv = "﻿date,amount,currency,tag,treat,period_start,period_end,period\n" +
                "2026-10-27 19:10,1330.00,THB,Gadgets,,2026-10-01,2026-10-28,past\n" +
                "not a date,5.00,THB,,,,,current\n"
        val rows = HistoryCsvImport.parse(StringReader(csv))

        assertEquals(1, rows.size)
        assertNull(rows[0].periodBudget)
        assertNull(rows[0].reference)
    }

    @Test(expected = NotAHistoryFileException::class)
    fun otherCsvIsRefused() {
        // The current-period export: localized dates, other columns
        HistoryCsvImport.parse(StringReader("amount,comment,commit_time\n120,Matcha,21/11/26 16:39\n"))
    }

    @Test
    fun planSkipsDuplicatesAndSplitsCurrentAndPast() {
        val now = LocalDateTime.of(2026, 11, 22, 12, 0)
        val currentStart = LocalDate.of(2026, 11, 1)
        val existing = setOf(HistoryImportPlan.key(LocalDateTime.of(2026, 11, 5, 9, 30), BigDecimal("45.0"), "Coffee"))

        val plan = HistoryImportPlan.make(
            rows = listOf(
                row(LocalDateTime.of(2026, 11, 5, 9, 30), "45.00", "Coffee"),                     // already here
                row(LocalDateTime.of(2026, 11, 6, 8, 0), "60.00", reference = "REF000012345"),    // reference already used
                row(LocalDateTime.of(2026, 11, 10, 12, 0), "100.00", "Lunch"),                  // current period
                row(LocalDateTime.of(2026, 11, 10, 12, 0), "100.00", "Lunch"),                  // twice in the file
                row(LocalDateTime.of(2026, 12, 1, 9, 0), "9.00"),                               // future
                row(LocalDateTime.of(2026, 10, 27, 19, 10), "1330.00", start = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 11, 3)),
                row(LocalDateTime.of(2025, 6, 12, 12, 18), "28.00"),                            // no period: its month
            ),
            existing = existing,
            usedReferences = setOf("REF000012345"),
            currentStart = currentStart,
            now = now,
        )

        assertEquals(3, plan.duplicates)
        assertEquals(1, plan.skipped)
        assertEquals(listOf(BigDecimal("100.00")), plan.toCurrent.map { it.amount })
        assertEquals(
            setOf(
                // Clamped so it doesn't overlap the current period
                LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31),
                LocalDate.of(2025, 6, 1) to LocalDate.of(2025, 6, 30),
            ),
            plan.toPast.keys,
        )
        assertEquals(3, plan.added)
    }

    @Test
    fun withoutBudgetEverythingIsPast() {
        val plan = HistoryImportPlan.make(
            rows = listOf(row(LocalDateTime.of(2026, 11, 10, 12, 0), "100.00")),
            existing = emptySet(),
            usedReferences = emptySet(),
            currentStart = null,
            now = LocalDateTime.of(2026, 11, 22, 12, 0),
        )
        assertEquals(0, plan.toCurrent.size)
        assertEquals(setOf(LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 30)), plan.toPast.keys)
    }
}
