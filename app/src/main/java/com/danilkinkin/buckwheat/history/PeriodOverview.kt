package com.danilkinkin.buckwheat.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.analytics.MinMaxSpentCard
import com.danilkinkin.buckwheat.analytics.SpendsCalendar
import com.danilkinkin.buckwheat.analytics.SpendsCountCard
import com.danilkinkin.buckwheat.analytics.StatCard
import com.danilkinkin.buckwheat.analytics.WholeBudgetCard
import com.danilkinkin.buckwheat.analytics.categoriesChart.CategoriesChartCard
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal

const val PERIOD_OVERVIEW_SHEET = "periodOverview"

@Composable
fun PeriodOverview(
    periodId: Int,
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val context = LocalContext.current

    val periods by spendsViewModel.periods.observeAsState(emptyList())
    val period = remember(periods, periodId) { periods.firstOrNull { it.uid == periodId } }

    val spendsSource = remember(periodId) { spendsViewModel.getPeriodSpends(periodId) }
    val spends by spendsSource.observeAsState(emptyList())

    // The calendar also needs INCOME & SET_DAILY_BUDGET records, not only spends
    val transactionsSource = remember(periodId) { spendsViewModel.getPeriodTransactions(periodId) }
    val transactions by transactionsSource.observeAsState(emptyList())
    // Data migrated from before transactions existed has no INCOME record,
    // the calendar can't be rebuilt for it (same rule as on the analytics screen)
    val canShowCalendar = remember(transactions) {
        transactions.any { it.type == TransactionType.INCOME }
    }

    var showList by remember { mutableStateOf(false) }

    val navigationBarHeight =
        LocalWindowInsets.current.calculateBottomPadding().coerceAtLeast(16.dp)

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { if (showList) showList = false else onClose() }) {
                    Icon(
                        painter = painterResource(
                            if (showList) R.drawable.ic_arrow_back else R.drawable.ic_close
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = stringResource(
                        if (showList) R.string.history_title else R.string.period_overview_title
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.size(48.dp))
            }

            if (period == null) return@Column

            val currency = remember(period) { ExtendCurrency.getInstance(period.currency) }

            if (showList) {
                History(
                    readOnly = true,
                    transactionsSource = spendsSource,
                )
                return@Column
            }

            val spentTotal = remember(spends) {
                spends.fold(BigDecimal.ZERO) { acc, t -> acc + t.value }
            }
            val rest = remember(spentTotal, period) { period.budget - spentTotal }

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                WholeBudgetCard(
                    budget = period.budget,
                    currency = currency,
                    startDate = period.startDate,
                    finishDate = period.finishDate,
                    actualFinishDate = period.actualFinishDate,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    StatCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        value = numberFormat(context, spentTotal, currency = currency),
                        label = stringResource(R.string.spent_budget),
                    )
                    Spacer(Modifier.width(16.dp))
                    StatCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        value = numberFormat(context, rest, currency = currency),
                        label = stringResource(R.string.rest_budget),
                    )
                }
                if (spends.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        MinMaxSpentCard(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            isMin = true,
                            spends = spends,
                            currency = currency,
                        )
                        Spacer(Modifier.width(16.dp))
                        MinMaxSpentCard(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            isMin = false,
                            spends = spends,
                            currency = currency,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    SpendsCountCard(
                        modifier = Modifier.fillMaxWidth(),
                        count = spends.size,
                        onClick = { showList = true },
                    )
                    if (canShowCalendar) {
                        Spacer(Modifier.height(36.dp))
                        SpendsCalendar(
                            modifier = Modifier.zIndex(-1f),
                            budget = period.budget,
                            transactions = transactions,
                            startDate = period.startDate,
                            finishDate = period.finishDate,
                            actualFinishDate = period.actualFinishDate,
                            currency = currency,
                        )
                    }
                    Spacer(Modifier.height(36.dp))
                    CategoriesChartCard(
                        modifier = Modifier.fillMaxWidth(),
                        spends = spends,
                        currency = currency,
                    )
                }
                Spacer(Modifier.height(navigationBarHeight + 16.dp))
            }
        }
    }
}
