package com.danilkinkin.buckwheat.slip.watch

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.editor.dateTimeEdit.DatePickerDialog
import com.danilkinkin.buckwheat.slip.SLIP_IMPORT_SHEET
import com.danilkinkin.buckwheat.slip.SlipImportViewModel
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import java.time.LocalDate
import java.util.Date

const val SLIP_FOLDERS_SHEET = "slipFolders"

/** "All slips": far enough back for any slip */
private val ALL_TIME = LocalDate.of(2000, 1, 1)

@Composable
fun SlipFolders(
    activityResultRegistryOwner: ActivityResultRegistryOwner?,
    foldersViewModel: SlipFoldersViewModel = hiltViewModel(),
    slipImportViewModel: SlipImportViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val navigationBarHeight =
        LocalWindowInsets.current.calculateBottomPadding().coerceAtLeast(16.dp)

    val watchEnabled by foldersViewModel.watchEnabled.observeAsState(false)
    val watched by foldersViewModel.watchedFolders.observeAsState(emptyList())
    val periodStart by foldersViewModel.periodStart.observeAsState(Date())
    val watchedIds = watched.map { it.bucketId }.toSet()

    val today = LocalDate.now()
    val minScanDate = periodStart.toLocalDate()
    var scanFrom by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    // Any day: slips before the current period go into past periods
    val scanFromDate = (scanFrom ?: minScanDate).coerceAtMost(today)
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { foldersViewModel.refresh() }

    var requestPhotos: () -> Unit = {}
    var requestNotifications: () -> Unit = {}
    if (activityResultRegistryOwner != null) {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides activityResultRegistryOwner) {
            val photos = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { foldersViewModel.refresh() }
            val notifications = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }

            requestPhotos = { photos.launch(SlipMedia.readPermission) }
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.slip_folders_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = if (foldersViewModel.hasAccess) 16.dp else navigationBarHeight),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!foldersViewModel.hasAccess) {
                    Text(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        text = stringResource(R.string.slip_folders_access_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .heightIn(min = 56.dp),
                        onClick = requestPhotos,
                    ) {
                        Text(stringResource(R.string.slip_folders_allow_access))
                    }
                    return@Column
                }

                // Watch for new slips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!watchEnabled) requestNotifications()
                            foldersViewModel.setWatchEnabled(!watchEnabled)
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.slip_folders_watch),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.slip_folders_watch_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Switch(
                        checked = watchEnabled,
                        onCheckedChange = {
                            if (it) requestNotifications()
                            foldersViewModel.setWatchEnabled(it)
                        },
                    )
                }
                if (watchEnabled) {
                    TextButton(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        onClick = { foldersViewModel.checkNow() },
                    ) {
                        Text(stringResource(R.string.slip_folders_check_now))
                    }
                }

                Text(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
                    text = stringResource(R.string.slip_folders_choose),
                    style = MaterialTheme.typography.titleMedium,
                )

                if (foldersViewModel.folders.isEmpty()) {
                    Text(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(R.string.slip_folders_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }

                foldersViewModel.folders.forEach { folder ->
                    val checked = folder.bucketId in watchedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { foldersViewModel.setFolderWatched(folder, !checked) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { foldersViewModel.setFolderWatched(folder, it) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = folder.name.ifEmpty { folder.relativePath },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = listOfNotNull(
                                    folder.relativePath.trimEnd('/'),
                                    pluralStringResource(R.plurals.slip_folders_images, folder.imageCount, folder.imageCount),
                                    if (folder.suggested) stringResource(R.string.slip_folders_bank_app) else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

            }

            // Always visible, however long the folder list: import slips already in the folders
            if (foldersViewModel.hasAccess) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = navigationBarHeight),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.slip_folders_scan_past),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                modifier = Modifier.heightIn(min = 48.dp),
                                onClick = { showDatePicker = true },
                            ) {
                                Text(
                                    if (scanFrom == ALL_TIME) {
                                        stringResource(R.string.slip_folders_scan_all)
                                    } else {
                                        stringResource(
                                            R.string.slip_folders_scan_from,
                                            prettyDate(scanFromDate.toDate(), pattern = "dd MMM yyyy", simplifyIfToday = false),
                                        )
                                    }
                                )
                            }
                            if (scanFrom != ALL_TIME) {
                                TextButton(onClick = { scanFrom = ALL_TIME }) {
                                    Text(stringResource(R.string.slip_folders_scan_all))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            enabled = watchedIds.isNotEmpty() && !slipImportViewModel.isReading,
                            onClick = {
                                slipImportViewModel.scanFolders(watchedIds, scanFromDate.toDate())
                                appViewModel.openSheet(PathState(SLIP_IMPORT_SHEET))
                                onClose()
                            },
                        ) {
                            Text(
                                pluralStringResource(R.plurals.slip_folders_scan_n, watchedIds.size, watchedIds.size)
                            )
                        }
                    }
                }
            }
        }
    }

    val askImport = foldersViewModel.askImportPast
    if (askImport.isNotEmpty() && !showDatePicker) {
        AlertDialog(
            onDismissRequest = { foldersViewModel.answeredImportPast() },
            title = { Text(stringResource(R.string.slip_folders_import_past_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.slip_folders_import_past_description,
                            askImport.joinToString(", ") { it.name.ifEmpty { it.relativePath } },
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showDatePicker = true }) {
                            Text(
                                stringResource(
                                    R.string.slip_folders_scan_from,
                                    prettyDate(scanFromDate.toDate(), pattern = "dd MMM yyyy", simplifyIfToday = false),
                                )
                            )
                        }
                        TextButton(onClick = { scanFrom = ALL_TIME }) {
                            Text(stringResource(R.string.slip_folders_scan_all))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    slipImportViewModel.scanFolders(askImport.map { it.bucketId }, scanFromDate.toDate())
                    foldersViewModel.answeredImportPast()
                    appViewModel.openSheet(PathState(SLIP_IMPORT_SHEET))
                    onClose()
                }) {
                    Text(stringResource(R.string.slip_folders_import_past_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { foldersViewModel.answeredImportPast() }) {
                    Text(stringResource(R.string.slip_folders_import_past_skip))
                }
            },
        )
    }

    if (showDatePicker) {
        DatePickerDialog(
            initDate = scanFromDate,
            disableBeforeDate = null,
            disableAfterDate = today,
            onSelect = {
                scanFrom = it
                showDatePicker = false
            },
            onClose = { showDatePicker = false },
        )
    }
}
