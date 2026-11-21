package com.danilkinkin.buckwheat.wallet

import com.danilkinkin.buckwheat.data.entities.Period
import com.danilkinkin.buckwheat.data.entities.SlipDetails
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live period, which has no [Period] row yet */
data class CurrentPeriodInfo(
    val startDate: Date,
    val finishDate: Date?,
    val currency: String,
)

/**
 * All spends of all periods (current and archived) as one CSV, e.g. for a backup or a spreadsheet.
 * Dates are ISO-like and amounts plain, so the file sorts and parses the same in every locale.
 */
object HistoryCsv {
    val HEADER = listOf(
        "date", "amount", "currency", "tag", "treat", "period_start", "period_end", "period",
        "payee", "reference", "note",
    )

    /** Excel needs it to read the file as UTF-8, otherwise Thai text is garbled */
    private const val UTF8_BOM = "﻿"

    fun rows(
        spends: List<Transaction>,
        periods: List<Period>,
        current: CurrentPeriodInfo?,
        /** Details of spends imported from slips, keyed by transaction uid */
        slipDetails: Map<Int, SlipDetails> = emptyMap(),
    ): List<List<String>> {
        val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val periodsById = periods.associateBy { it.uid }

        return spends
            .filter { it.type == TransactionType.SPENT }
            .sortedWith(compareBy<Transaction> { it.date }.thenBy { it.uid })
            .map { spent ->
                val period = spent.periodId?.let { periodsById[it] }
                val isCurrent = spent.periodId == null

                val start = if (isCurrent) current?.startDate else period?.startDate
                val end = if (isCurrent) {
                    current?.finishDate
                } else {
                    period?.actualFinishDate ?: period?.finishDate
                }
                val currency = if (isCurrent) current?.currency else period?.currency

                listOf(
                    dateTime.format(spent.date),
                    spent.value.toPlainString(),
                    currency.orEmpty(),
                    spent.comment,
                    when {
                        spent.manualTreat -> "manual"
                        spent.automaticTreat -> "automatic"
                        else -> ""
                    },
                    start?.let { day.format(it) }.orEmpty(),
                    end?.let { day.format(it) }.orEmpty(),
                    if (isCurrent) "current" else "past",
                    slipDetails[spent.uid]?.payee.orEmpty(),
                    slipDetails[spent.uid]?.reference.orEmpty(),
                    slipDetails[spent.uid]?.note.orEmpty(),
                )
            }
    }

    fun write(writer: Writer, rows: List<List<String>>) {
        writer.write(UTF8_BOM)
        CSVPrinter(writer, CSVFormat.Builder.create().setHeader(*HEADER.toTypedArray()).build()).use { printer ->
            rows.forEach { printer.printRecord(it) }
        }
    }
}
