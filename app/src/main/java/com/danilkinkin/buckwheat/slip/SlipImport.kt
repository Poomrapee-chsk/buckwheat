package com.danilkinkin.buckwheat.slip

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.base.ButtonRow
import com.danilkinkin.buckwheat.slip.watch.SLIP_FOLDERS_SHEET
import com.danilkinkin.buckwheat.slip.watch.SlipFoldersViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.util.toDate
import java.time.LocalDate

const val SLIP_IMPORT_SHEET = "slipImport"

private const val MAX_SLIPS = 50

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlipImport(
    activityResultRegistryOwner: ActivityResultRegistryOwner?,
    slipImportViewModel: SlipImportViewModel = hiltViewModel(),
    foldersViewModel: SlipFoldersViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val navigationBarHeight =
        LocalWindowInsets.current.calculateBottomPadding().coerceAtLeast(16.dp)
    val candidates = slipImportViewModel.candidates
    val context = LocalContext.current

    val watchEnabled by foldersViewModel.watchEnabled.observeAsState(false)
    val watchedFolders by foldersViewModel.watchedFolders.observeAsState(emptyList())

    // Slips found in watched folders, e.g. after tapping a notification
    LaunchedEffect(Unit) { slipImportViewModel.loadPending() }

    // A background folder scan: its progress, and the slips it found so far
    val scanWork by slipImportViewModel.scanWork.observeAsState(emptyList())
    val scan = scanWork.firstOrNull()
    val scanRunning = scan != null && !scan.state.isFinished
    val scanData = if (scanRunning) scan!!.progress else scan?.outputData
    val scanFound = scanData?.getInt(com.danilkinkin.buckwheat.slip.watch.SlipScanWorker.KEY_FOUND, 0) ?: 0
    val scanOmitted = scanData?.getInt(com.danilkinkin.buckwheat.slip.watch.SlipScanWorker.KEY_OMITTED, 0) ?: 0
    var wasScanRunning by remember { mutableStateOf(false) }
    var emptyScanOmitted by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(scanRunning, scanFound, scanOmitted) {
        if (scanRunning) {
            wasScanRunning = true
            emptyScanOmitted = null
        } else if (wasScanRunning) {
            wasScanRunning = false
            emptyScanOmitted = if (scanFound == 0) scanOmitted else null
        }
        slipImportViewModel.loadPending()
    }

    val pickSlips: () -> Unit = if (activityResultRegistryOwner == null) {
        {}
    } else {
        var launch: () -> Unit = {}
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides activityResultRegistryOwner) {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.PickMultipleVisualMedia(MAX_SLIPS)
            ) { uris -> slipImportViewModel.readSlips(uris) }

            launch = {
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }
        launch
    }

    val selectedCount = slipImportViewModel.selectedCount
    val coroutineScope = rememberCoroutineScope()
    var showPastBudgetDialog by remember { mutableStateOf(false) }
    var pastBudgetText by remember { mutableStateOf("") }
    val addSlips = { pastBudget: java.math.BigDecimal? ->
        slipImportViewModel.addSelected(pastBudget) { count ->
            appViewModel.showSnackbar(
                context.resources.getQuantityString(R.plurals.slip_import_added, count, count)
            )
            onClose()
        }
    }

    if (showPastBudgetDialog) {
        val pastCount = slipImportViewModel.selectedPastCount
        val budget = pastBudgetText.replace(",", "").toBigDecimalOrNull()?.takeIf { it > java.math.BigDecimal.ZERO }
        AlertDialog(
            onDismissRequest = { showPastBudgetDialog = false },
            title = { Text(stringResource(R.string.slip_past_budget_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pluralStringResource(R.plurals.slip_past_budget_description, pastCount, pastCount))
                    OutlinedTextField(
                        value = pastBudgetText,
                        onValueChange = { pastBudgetText = it },
                        label = { Text(stringResource(R.string.slip_past_budget_label)) },
                        singleLine = true,
                        isError = budget == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = budget != null, onClick = {
                    showPastBudgetDialog = false
                    addSlips(budget)
                }) { Text(stringResource(R.string.slip_past_budget_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showPastBudgetDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    slipImportViewModel.clear()
                    onClose()
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.slip_import_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navigationBarHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (candidates.isEmpty() && !slipImportViewModel.isReading && !scanRunning) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        text = stringResource(R.string.slip_import_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                    if (watchEnabled && watchedFolders.isNotEmpty()) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(
                                R.string.slip_import_watching,
                                watchedFolders.joinToString(", ") { it.name },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    emptyScanOmitted?.let { omitted ->
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = if (omitted > 0) {
                                pluralStringResource(R.plurals.slip_scan_all_imported, omitted, omitted)
                            } else {
                                stringResource(R.string.slip_scan_none_new)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (watchedFolders.isNotEmpty()) {
                        // Scan the chosen folders for today's new slips, omitting already-imported ones
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            onClick = {
                                emptyScanOmitted = null
                                slipImportViewModel.scanFolders(
                                    watchedFolders.map { it.bucketId },
                                    LocalDate.now().toDate(),
                                    omitImported = true,
                                )
                            },
                        ) {
                            Text(
                                stringResource(
                                    R.string.slip_import_scan_folders,
                                    watchedFolders.joinToString(", ") { it.name },
                                )
                            )
                        }
                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            onClick = { appViewModel.openSheet(PathState(SLIP_FOLDERS_SHEET)) },
                        ) {
                            Text(stringResource(R.string.slip_import_more_options))
                        }
                    } else {
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            onClick = { appViewModel.openSheet(PathState(SLIP_FOLDERS_SHEET)) },
                        ) {
                            Text(stringResource(R.string.slip_import_choose_folders))
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        onClick = pickSlips,
                    ) {
                        Text(stringResource(R.string.slip_import_choose))
                    }
                }

                if (slipImportViewModel.skippedCount > 0 && !slipImportViewModel.isReading) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.slip_import_skipped,
                            slipImportViewModel.skippedCount,
                            slipImportViewModel.skippedCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }

                slipImportViewModel.ocrError?.let { error ->
                    Text(
                        text = stringResource(R.string.slip_import_ocr_error, error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (scanRunning) {
                    val read = scanData?.getInt(com.danilkinkin.buckwheat.slip.watch.SlipScanWorker.KEY_READ, 0) ?: 0
                    val total = scanData?.getInt(com.danilkinkin.buckwheat.slip.watch.SlipScanWorker.KEY_TOTAL, 0) ?: 0
                    Text(
                        text = if (total == 0) stringResource(R.string.slip_scan_title)
                        else stringResource(R.string.slip_scan_progress, read, total, scanFound),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { read.toFloat() / total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.slip_scan_background_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                    OutlinedButton(onClick = { slipImportViewModel.stopReading() }) {
                        Text(stringResource(R.string.slip_import_stop))
                    }
                }

                if (slipImportViewModel.isReading) {
                    Text(
                        text = stringResource(
                            R.string.slip_import_reading,
                            slipImportViewModel.readCount + 1,
                            slipImportViewModel.totalCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = {
                            slipImportViewModel.readCount.toFloat() /
                                    slipImportViewModel.totalCount.coerceAtLeast(1)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { slipImportViewModel.stopReading() }) {
                        Text(stringResource(R.string.slip_import_stop))
                    }
                }

                val ticked = candidates.count { it.selected && it.canBeAdded }
                val unticked = candidates.count { !(it.selected && it.canBeAdded && it.amount != null) }
                if ((ticked > 0 || unticked > 0) && !slipImportViewModel.isReading) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ticked > 0) {
                            TextButton(onClick = { slipImportViewModel.discardSelected() }) {
                                Text(pluralStringResource(R.plurals.slip_import_discard_selected, ticked, ticked))
                            }
                        }
                        if (unticked > 0) {
                            TextButton(onClick = { slipImportViewModel.discardUnselected() }) {
                                Text(pluralStringResource(R.plurals.slip_import_discard_unticked, unticked, unticked))
                            }
                        }
                    }
                }

                candidates.forEach { candidate ->
                    SlipCandidateCard(
                        candidate = candidate,
                        onChange = { slipImportViewModel.update(it) },
                        onDiscard = { slipImportViewModel.discard(candidate) },
                    )
                }

                if (candidates.isNotEmpty() && !slipImportViewModel.isReading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            modifier = Modifier.heightIn(min = 56.dp),
                            onClick = pickSlips,
                        ) {
                            Text(stringResource(R.string.slip_import_choose_more))
                        }
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp),
                            enabled = selectedCount > 0,
                            onClick = {
                                if (slipImportViewModel.selectedPastCount > 0) {
                                    // Ask the budget of past months that get created for old slips
                                    coroutineScope.launch {
                                        pastBudgetText = slipImportViewModel.currentBudget()
                                            .stripTrailingZeros().toPlainString()
                                        showPastBudgetDialog = true
                                    }
                                } else {
                                    addSlips(null)
                                }
                            },
                        ) {
                            Text(
                                pluralStringResource(
                                    R.plurals.slip_import_add,
                                    selectedCount,
                                    selectedCount,
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlipCandidateCard(
    candidate: SlipCandidate,
    onChange: (SlipCandidate) -> Unit,
    onDiscard: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = candidate.selected && candidate.canBeAdded,
                enabled = candidate.canBeAdded,
                onCheckedChange = { onChange(candidate.copy(selected = it)) },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = prettyDate(candidate.date, showTime = true, forceShowDate = true),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(modifier = Modifier.size(32.dp), onClick = onDiscard) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.slip_import_discard),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                val details = listOfNotNull(candidate.payee, candidate.note).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = candidate.amountText,
                        onValueChange = { onChange(candidate.copy(amountText = it)) },
                        label = { Text(stringResource(R.string.slip_import_amount)) },
                        singleLine = true,
                        isError = candidate.amount == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1.4f),
                        value = candidate.tag,
                        onValueChange = { onChange(candidate.copy(tag = it)) },
                        label = { Text(stringResource(R.string.slip_import_tag)) },
                        // Empty: saved with the default tag
                        placeholder = { Text(stringResource(R.string.slip_default_tag)) },
                        singleLine = true,
                    )
                }
                candidate.warnings.forEach { warning ->
                    Text(
                        text = stringResource(warning.message()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                candidate.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // What OCR read: helps to see why an amount or date wasn't found
                var showText by rememberSaveable(candidate.id) { mutableStateOf(false) }
                TextButton(
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    onClick = { showText = !showText },
                ) {
                    Text(
                        stringResource(
                            if (showText) R.string.slip_import_hide_text else R.string.slip_import_show_text
                        )
                    )
                }
                if (showText) {
                    SelectionContainer {
                        Text(
                            text = candidate.rawText.ifBlank { stringResource(R.string.slip_import_no_text) },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

private fun SlipWarning.message(): Int = when (this) {
    SlipWarning.UNREADABLE -> R.string.slip_warning_unreadable
    SlipWarning.NO_AMOUNT -> R.string.slip_warning_no_amount
    SlipWarning.NO_DATE -> R.string.slip_warning_no_date
    SlipWarning.OUTSIDE_PERIOD -> R.string.slip_warning_outside_period
    SlipWarning.FUTURE -> R.string.slip_warning_future
    SlipWarning.DUPLICATE -> R.string.slip_warning_duplicate
}
