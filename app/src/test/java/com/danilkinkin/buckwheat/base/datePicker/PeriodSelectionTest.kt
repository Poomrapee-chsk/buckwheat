package com.danilkinkin.buckwheat.base.datePicker

import com.danilkinkin.buckwheat.base.datePicker.model.CalendarSelectionMode
import com.danilkinkin.buckwheat.base.datePicker.model.CalendarUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeriodSelectionTest {
    private val today = LocalDate.of(2026, 11, 22)
    private val base = CalendarUiState(
        selectionMode = CalendarSelectionMode.PERIOD,
        selectedStartDate = today,
        selectedEndDate = LocalDate.of(2026, 12, 21),
        disabledBefore = null,
    )

    @Test
    fun pastDayMovesTheStartFutureDayTheEnd() {
        val started = base.selectPeriodDay(LocalDate.of(2026, 11, 1), today)
        assertEquals(LocalDate.of(2026, 11, 1), started.selectedStartDate)
        assertEquals(LocalDate.of(2026, 12, 21), started.selectedEndDate)

        val ended = started.selectPeriodDay(LocalDate.of(2026, 11, 30), today)
        assertEquals(LocalDate.of(2026, 11, 1), ended.selectedStartDate)
        assertEquals(LocalDate.of(2026, 11, 30), ended.selectedEndDate)
        assertTrue(ended.isDateInSelectedPeriod(today))
    }

    @Test
    fun todayIsTheStartOrAOneDayPeriod() {
        val fromPast = base.copy(selectedStartDate = LocalDate.of(2026, 11, 1))
        assertEquals(today, fromPast.selectPeriodDay(today, today).selectedStartDate)

        val oneDay = base.selectPeriodDay(today, today)
        assertEquals(today, oneDay.selectedStartDate)
        assertEquals(today, oneDay.selectedEndDate)
    }

    @Test
    fun needsBothEnds() {
        assertFalse(base.copy(selectedEndDate = null).hasSelectedDates)
        assertTrue(base.hasSelectedDates)
    }

    @Test
    fun pastDaysAfterTheFirstSpendCantStartTheRunningPeriod() {
        val running = base.copy(startNotAfter = LocalDate.of(2026, 11, 5), disabledBefore = LocalDate.of(2026, 10, 1))
        assertFalse(running.isDisabledDay(LocalDate.of(2026, 11, 5)))
        assertTrue(running.isDisabledDay(LocalDate.of(2026, 11, 10)))
        assertFalse(running.isDisabledDay(LocalDate.now()))         // today is always fine
        assertTrue(running.isDisabledDay(LocalDate.of(2026, 9, 30))) // a past period's days
    }
}
