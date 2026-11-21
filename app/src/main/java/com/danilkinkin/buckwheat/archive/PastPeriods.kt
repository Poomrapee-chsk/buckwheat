package com.danilkinkin.buckwheat.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.ButtonRow
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.ArchiveViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyDate
import java.util.Date

const val PAST_PERIODS_SHEET = "pastPeriods"

@Composable
fun PastPeriods(
    archiveViewModel: ArchiveViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val periods by archiveViewModel.periods.observeAsState(emptyList())

    val navigationBarHeight =
        LocalWindowInsets.current.calculateBottomPadding().coerceAtLeast(16.dp)

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            ArchiveSheetHeader(
                title = stringResource(R.string.past_periods_title),
                icon = painterResource(R.drawable.ic_close),
                onClose = onClose,
            )
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = navigationBarHeight)
            ) {
                if (periods.isEmpty()) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 48.dp),
                        text = stringResource(R.string.past_periods_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }

                periods.forEach { period ->
                    PastPeriodRow(
                        period = period,
                        onClick = {
                            appViewModel.openSheet(
                                PathState(
                                    name = PAST_PERIOD_SHEET,
                                    args = mapOf("periodId" to period.uid),
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PastPeriodRow(
    period: BudgetPeriod,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val currency = ExtendCurrency.getInstance(period.currency)

    ButtonRow(
        icon = painterResource(R.drawable.ic_calendar),
        endIcon = painterResource(R.drawable.ic_arrow_right),
        text = periodDateRange(period),
        description = stringResource(
            R.string.past_period_spent_of,
            numberFormat(context, period.spent, currency = currency),
            numberFormat(context, period.budget, currency = currency),
        ),
        onClick = onClick,
    )
}

@Composable
internal fun periodDateRange(period: BudgetPeriod): String {
    val finishDate = period.actualFinishDate?.let { Date(it) } ?: period.finishDate

    return stringResource(
        R.string.past_period_date_range,
        prettyDate(period.startDate, pattern = "dd MMM yyyy", simplifyIfToday = false),
        prettyDate(finishDate, pattern = "dd MMM yyyy", simplifyIfToday = false),
    )
}

@Composable
internal fun ArchiveSheetHeader(
    title: String,
    icon: Painter,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = { onClose() }) {
            Icon(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.weight(1F))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.weight(1F))
        Spacer(Modifier.width(48.dp))
    }
}
