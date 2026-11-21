package com.danilkinkin.buckwheat.archive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
import com.danilkinkin.buckwheat.data.ArchiveViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.history.HistoryDateDivider
import com.danilkinkin.buckwheat.history.SpentItem
import com.danilkinkin.buckwheat.history.TotalPerDay
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.toLocalDate
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Date

const val PAST_PERIOD_SHEET = "pastPeriod"

@Composable
fun PastPeriod(
    periodId: Int,
    archiveViewModel: ArchiveViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val context = LocalContext.current
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val periodLiveData = remember(periodId) { archiveViewModel.getPeriod(periodId) }
    val transactionsLiveData = remember(periodId) { archiveViewModel.getTransactions(periodId) }
    val period by periodLiveData.observeAsState(null)
    val transactions by transactionsLiveData.observeAsState(emptyList())

    val spends = remember(transactions) {
        transactions.filter { it.type == TransactionType.SPENT }
    }
    // Periods created before transactions existed have no INCOME & SET_DAILY_BUDGET records,
    // calendar can't be restored for them (same as on analytics screen)
    val hasFullTransactions = remember(transactions) {
        transactions.any { it.type == TransactionType.INCOME }
    }
    val spendsListOffset = remember { mutableIntStateOf(0) }

    val navigationBarHeight =
        LocalWindowInsets.current.calculateBottomPadding().coerceAtLeast(16.dp)

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            ArchiveSheetHeader(
                title = period?.let { periodDateRange(it) }
                    ?: stringResource(R.string.past_period_title),
                icon = painterResource(R.drawable.ic_arrow_back),
                onClose = onClose,
            )

            period?.let { currentPeriod ->
                val currency = ExtendCurrency.getInstance(currentPeriod.currency)
                val actualFinishDate = currentPeriod.actualFinishDate?.let { Date(it) }

                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .padding(bottom = navigationBarHeight)
                ) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        WholeBudgetCard(
                            budget = currentPeriod.budget,
                            currency = currency,
                            startDate = currentPeriod.startDate,
                            finishDate = currentPeriod.finishDate,
                            actualFinishDate = actualFinishDate,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                        ) {
                            StatCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                label = stringResource(R.string.spent_budget),
                                value = numberFormat(context, currentPeriod.spent, currency = currency),
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            StatCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                label = stringResource(R.string.rest_budget),
                                value = numberFormat(
                                    context,
                                    currentPeriod.budget - currentPeriod.spent,
                                    currency = currency,
                                ),
                            )
                        }

                        if (spends.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
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
                                Spacer(modifier = Modifier.width(16.dp))
                                MinMaxSpentCard(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    isMin = false,
                                    spends = spends,
                                    currency = currency,
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            SpendsCountCard(
                                modifier = Modifier.fillMaxWidth(),
                                count = spends.size,
                                onClick = {
                                    coroutineScope.launch {
                                        scrollState.animateScrollTo(spendsListOffset.intValue)
                                    }
                                },
                            )
                            if (hasFullTransactions) {
                                Spacer(modifier = Modifier.height(36.dp))
                                SpendsCalendar(
                                    modifier = Modifier.zIndex(-1f),
                                    budget = currentPeriod.budget,
                                    transactions = transactions,
                                    startDate = currentPeriod.startDate,
                                    finishDate = currentPeriod.finishDate,
                                    actualFinishDate = actualFinishDate,
                                    currency = currency,
                                )
                            }
                            Spacer(modifier = Modifier.height(36.dp))
                            CategoriesChartCard(
                                modifier = Modifier.fillMaxWidth(),
                                spends = spends,
                                currency = currency,
                            )
                        }
                    }

                    if (spends.isNotEmpty()) {
                        Text(
                            modifier = Modifier
                                .onGloballyPositioned {
                                    spendsListOffset.intValue = it.positionInParent().y.toInt()
                                }
                                .padding(start = 24.dp, end = 24.dp, top = 36.dp),
                            text = stringResource(R.string.past_period_spends_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        SpendsList(spends = spends, currency = currency)
                    }
                }
            }
        }
    }
}

/**
 * Read-only list of spends grouped by day, oldest first (same order as in the history screen)
 */
@Composable
private fun SpendsList(spends: List<Transaction>, currency: ExtendCurrency) {
    val days = remember(spends) {
        spends
            .sortedBy { it.date }
            .groupBy { it.date.toLocalDate() }
            .toList()
    }

    days.forEach { (day, daySpends) ->
        HistoryDateDivider(day)
        daySpends.forEach { spent ->
            SpentItem(
                transaction = spent,
                currency = currency,
            )
        }
        TotalPerDay(
            spentPerDay = daySpends.fold(BigDecimal.ZERO) { acc, spent -> acc + spent.value },
            currency = currency,
        )
    }
}
