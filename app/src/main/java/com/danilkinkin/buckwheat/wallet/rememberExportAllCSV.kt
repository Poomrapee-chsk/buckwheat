package com.danilkinkin.buckwheat.wallet

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.asFlow
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.errorForReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Returns a function that asks where to save, then exports every spend of every period. */
@Composable
fun rememberExportAllCSV(
    appViewModel: AppViewModel = hiltViewModel(),
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
): () -> Unit {
    if (activityResultRegistryOwner === null) return {}

    var launcher: ManagedActivityResultLauncher<String, Uri?>? = null

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val successMessage = stringResource(R.string.export_to_csv_success)
    val failedMessage = stringResource(R.string.export_to_csv_failed)
    val fileName = stringResource(R.string.export_all_to_csv_file_name, LocalDate.now().toString())

    CompositionLocalProvider(
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner
    ) {
        launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv")
        ) { uri ->
            // Cancelled by the user
            if (uri === null) return@rememberLauncherForActivityResult

            coroutineScope.launch {
                try {
                    val spends = spendsViewModel.spendingHistory.asFlow().first()
                    val periods = spendsViewModel.periods.asFlow().first()
                    val budget = spendsViewModel.budget.asFlow().first()
                    // No current period after a full reset or before the first budget
                    val current = if (budget.signum() > 0) {
                        CurrentPeriodInfo(
                            startDate = spendsViewModel.startPeriodDate.asFlow().first(),
                            finishDate = spendsViewModel.finishPeriodDate.asFlow().first(),
                            currency = spendsViewModel.currency.asFlow().first().value.orEmpty(),
                        )
                    } else {
                        null
                    }

                    withContext(Dispatchers.IO) {
                        val rows = HistoryCsv.rows(spends, periods, current)
                        context.contentResolver.openOutputStream(uri)!!.writer().use {
                            HistoryCsv.write(it, rows)
                        }
                    }

                    appViewModel.showSnackbar(successMessage)
                } catch (e: Exception) {
                    context.errorForReport = e.stackTraceToString()
                    appViewModel.showSnackbar(failedMessage)
                }
            }
        }
    }

    return {
        try {
            launcher?.launch("$fileName.csv")
        } catch (e: Exception) {
            context.errorForReport = e.stackTraceToString()
            appViewModel.showSnackbar(failedMessage)
        }
    }
}
