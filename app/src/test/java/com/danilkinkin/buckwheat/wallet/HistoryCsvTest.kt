package com.danilkinkin.buckwheat.wallet

import com.danilkinkin.buckwheat.data.entities.Period
import com.danilkinkin.buckwheat.data.entities.SlipDetails
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

class HistoryCsvTest {
    private fun date(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        Date.from(LocalDateTime.of(y, m, d, h, min).atZone(ZoneId.systemDefault()).toInstant())

    private val pastPeriod = Period(
        budget = BigDecimal("10000"),
        startDate = date(2026, 10, 1),
        finishDate = date(2026, 10, 31),
        actualFinishDate = date(2026, 10, 28),
        currency = "THB",
        archivedAt = date(2026, 11, 1),
    ).apply { uid = 7 }

    private val current = CurrentPeriodInfo(
        startDate = date(2026, 11, 1),
        finishDate = date(2026, 11, 30),
        currency = "THB",
    )

    @Test
    fun allPeriodsSortedByDateWithPeriodInfo() {
        val spends = listOf(
            Transaction(TransactionType.SPENT, BigDecimal("120.00"), date(2026, 11, 21, 16, 39), "Matcha"),
            Transaction(
                TransactionType.SPENT, BigDecimal("1330.00"), date(2026, 10, 27, 19, 10), "Gadgets",
                manualTreat = true, periodId = 7,
            ),
            Transaction(TransactionType.INCOME, BigDecimal("9000"), date(2026, 11, 1)),
        )

        // Imported from a slip; uids as Room would assign them
        spends[0].uid = 11
        spends[1].uid = 5
        val details = mapOf(11 to SlipDetails(11, payee = "Kawa Matcha Teahouse", reference = "016325143212ATF04567"))

        val rows = HistoryCsv.rows(spends, listOf(pastPeriod), current, details)

        assertEquals(
            listOf(
                listOf("2026-10-27 19:10", "1330.00", "THB", "Gadgets", "manual", "2026-10-01", "2026-10-28", "past", "", "", ""),
                listOf(
                    "2026-11-21 16:39", "120.00", "THB", "Matcha", "", "2026-11-01", "2026-11-30", "current",
                    "Kawa Matcha Teahouse", "016325143212ATF04567", "",
                ),
            ),
            rows,
        )
    }

    @Test
    fun writesBomHeaderAndQuotesCommas() {
        val writer = StringWriter()
        HistoryCsv.write(writer, listOf(listOf("2026-11-21 16:39", "120.00", "THB", "ชา, นม", "", "", "", "current", "", "", "")))
        val text = writer.toString()

        assertTrue(text.startsWith("﻿date,amount,currency,tag,treat,period_start,period_end,period,payee,reference,note"))
        assertTrue(text.contains("\"ชา, นม\""))
    }
}
