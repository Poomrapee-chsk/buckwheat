package com.danilkinkin.buckwheat.slip

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

const val SLIP_IMPORT_SHEET = "slipImport"

private const val MAX_SLIPS = 50

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
                if (candidates.isEmpty() && !slipImportViewModel.isReading) {
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
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        onClick = { appViewModel.openSheet(PathState(SLIP_FOLDERS_SHEET)) },
                    ) {
                        Text(stringResource(R.string.slip_import_choose_folders))
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
                }

                candidates.forEach { candidate ->
                    SlipCandidateCard(
                        candidate = candidate,
                        onChange = { slipImportViewModel.update(it) },
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
                                slipImportViewModel.addSelected { count ->
                                    appViewModel.showSnackbar(
                                        context.resources.getQuantityString(
                                            R.plurals.slip_import_added, count, count
                                        )
                                    )
                                    onClose()
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
                Text(
                    text = prettyDate(candidate.date, showTime = true, forceShowDate = true),
                    style = MaterialTheme.typography.labelLarge,
                )
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
