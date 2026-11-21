package com.danilkinkin.buckwheat.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.ButtonRow
import com.danilkinkin.buckwheat.wallet.rememberExportAllCSV
import androidx.activity.result.ActivityResultRegistryOwner
import com.danilkinkin.buckwheat.analytics.WholeBudgetCard
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.ui.colorOnEditor

const val PERIODS_HISTORY_SHEET = "periodsHistory"

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PeriodsHistory(
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val periods by spendsViewModel.periods.observeAsState(emptyList())

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
                IconButton(onClick = { onClose() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.periods_history_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.size(48.dp))
            }

            val exportAllCSVLaunch = rememberExportAllCSV(
                activityResultRegistryOwner = activityResultRegistryOwner
            )
            val exportAllButton: @Composable () -> Unit = {
                ButtonRow(
                    icon = painterResource(R.drawable.ic_file_download),
                    text = stringResource(R.string.export_all_to_csv),
                    description = stringResource(R.string.export_all_to_csv_description),
                    onClick = { exportAllCSVLaunch() },
                )
            }

            if (periods.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_periods_history),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorOnEditor.copy(alpha = 0.7f),
                    )
                }
                exportAllButton()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = navigationBarHeight + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(periods, key = { it.uid }) { period ->
                        SwipeActions(
                            modifier = Modifier.animateItem(),
                            endActionsConfig = SwipeActionsConfig(
                                threshold = 0.4f,
                                background = MaterialTheme.colorScheme.errorContainer,
                                backgroundActive = MaterialTheme.colorScheme.error,
                                iconTint = MaterialTheme.colorScheme.onError,
                                icon = painterResource(R.drawable.ic_delete_forever),
                                stayDismissed = true,
                                onDismiss = { spendsViewModel.deletePeriod(period.uid) },
                            ),
                        ) {
                            WholeBudgetCard(
                                modifier = Modifier.clickable {
                                    appViewModel.openSheet(
                                        PathState(
                                            PERIOD_OVERVIEW_SHEET,
                                            args = mapOf("periodId" to period.uid),
                                        )
                                    )
                                },
                                budget = period.budget,
                                currency = ExtendCurrency.getInstance(period.currency),
                                startDate = period.startDate,
                                finishDate = period.finishDate,
                                actualFinishDate = period.actualFinishDate,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ),
                            )
                        }
                    }
                    item(key = "export-all") {
                        exportAllButton()
                    }
                }
            }
        }
    }
}
