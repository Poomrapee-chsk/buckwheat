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
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.errorForReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Returns a function that asks for an "all periods" CSV export and adds what's missing from it. */
@Composable
fun rememberImportHistoryCSV(
    appViewModel: AppViewModel = hiltViewModel(),
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
): () -> Unit {
    if (activityResultRegistryOwner === null) return {}

    var launcher: ManagedActivityResultLauncher<Array<String>, Uri?>? = null
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    CompositionLocalProvider(LocalActivityResultRegistryOwner provides activityResultRegistryOwner) {
        launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri === null) return@rememberLauncherForActivityResult

            coroutineScope.launch {
                val message = try {
                    val rows = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)!!.reader(Charsets.UTF_8).use {
                            HistoryCsvImport.parse(it)
                        }
                    }
                    val result = spendsViewModel.importHistory(rows)
                    context.resources.getQuantityString(R.plurals.import_history_done, result.added, result.added, result.duplicates)
                } catch (e: NotAHistoryFileException) {
                    context.getString(R.string.import_history_not_history)
                } catch (e: Exception) {
                    context.errorForReport = e.stackTraceToString()
                    context.getString(R.string.import_history_failed)
                }
                appViewModel.showSnackbar(message)
            }
        }
    }

    return {
        // CSV files come with many MIME types (text/csv, text/comma-separated-values, octet-stream...)
        launcher?.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
    }
}
