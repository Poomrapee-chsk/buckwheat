package com.danilkinkin.buckwheat.wallet

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.base.datePicker.DatePicker
import com.danilkinkin.buckwheat.base.datePicker.model.CalendarSelectionMode
import com.danilkinkin.buckwheat.base.datePicker.model.CalendarState
import com.danilkinkin.buckwheat.base.datePicker.model.selectedDatesFormatted
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.countDays
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import java.time.LocalDate
import java.util.*

const val FINISH_DATE_SELECTOR_SHEET = "finishDateSelector"

@Composable
/**
 * The budget period in one calendar: past days (and today) pick the start, future days the end.
 * Today is ringed. A start before today lets spends of the period's past days count in it.
 */
fun FinishDateSelector(
    selectStartDate: Date? = null,
    selectDate: Date? = null,
    /** Earliest start: the day after the last past period */
    minStartDate: LocalDate? = null,
    /** Latest start: today, or the first spend of a running period */
    maxStartDate: LocalDate? = null,
    onBackPressed: () -> Unit,
    onApply: (startDate: Date, finishDate: Date) -> Unit,
) {
    val context = LocalContext.current
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val start = selectStartDate ?: Date()

    Surface(modifier = Modifier.fillMaxSize().padding(top = localBottomSheetScrollState.topPadding)) {
        val calendarState = remember {
            CalendarState(
                context,
                selectionMode = CalendarSelectionMode.PERIOD,
                selectDate = start,
                selectEndDate = selectDate,
                disableBeforeDate = minStartDate?.toDate(),
                startNotAfter = maxStartDate,
                // Without a past period to stop at, offer a couple of years back
                firstMonth = java.time.YearMonth.from(
                    minStartDate ?: start.toLocalDate().minusYears(2)
                ),
            )
        }

        FinishDateSelectorContent(
            calendarState = calendarState,
            initialMonth = java.time.YearMonth.from(start.toLocalDate()),
            onDayClicked = { calendarState.setSelectedDay(it) },
            onBackPressed = onBackPressed,
            onApply = {
                val state = calendarState.calendarUiState.value
                onApply(state.selectedStartDate!!.toDate(), state.selectedEndDate!!.toDate())
            }
        )
    }
}

@Composable
private fun FinishDateSelectorContent(
    calendarState: CalendarState,
    initialMonth: java.time.YearMonth?,
    onDayClicked: (LocalDate) -> Unit,
    onBackPressed: () -> Unit,
    onApply: () -> Unit,
) {
    Column {
        FinishDateSelectorTopAppBar(calendarState, onBackPressed, onApply)
        DatePicker(
            calendarState = calendarState,
            onDayClicked = onDayClicked,
            initialMonth = initialMonth,
        )
    }
}

@Composable
private fun FinishDateSelectorTopAppBar(
    calendarState: CalendarState,
    onBackPressed: () -> Unit,
    onApply: () -> Unit,
) {
    Surface {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                IconButton(
                    onClick = { onBackPressed() }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                    )
                }
                Spacer(Modifier.weight(1F))
                Button(
                    modifier = Modifier.padding(end = 4.dp),
                    onClick = { onApply() },
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    enabled = calendarState.calendarUiState.value.hasSelectedDates,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_apply),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)

                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(text = stringResource(R.string.apply))
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 24.dp,
                    )
            ) {
                Column {
                    Text(
                        text = if (!calendarState.calendarUiState.value.hasSelectedDates) {
                            stringResource(R.string.select_period_title)
                        } else {
                            selectedDatesFormatted(calendarState)
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val days = if (calendarState.calendarUiState.value.hasSelectedDates) {
                        countDays(
                            calendarState.calendarUiState.value.selectedEndDate!!.toDate(),
                            calendarState.calendarUiState.value.selectedStartDate!!.toDate(),
                        )
                    } else {
                        0
                    }

                    Text(
                        text = String.format(
                            pluralStringResource(
                                id = R.plurals.days_count,
                                count = days,
                            ),
                            days,
                        ),
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = if (calendarState.calendarUiState.value.hasSelectedDates) 0.6f else 0f),
                        ),
                    )
                }
            }
        }
    }
}


@Preview(name = "Default")
@Preview(name = "Default (Night mode)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewDefault(){
    BuckwheatTheme {
        FinishDateSelector(onBackPressed = {}, onApply = { _, _ -> })
    }
}