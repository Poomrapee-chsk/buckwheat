package com.danilkinkin.buckwheat.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.ButtonRow

/** Asks whether to export only the current period or every period */
@Composable
fun ExportChooserDialog(
    onCurrentPeriod: () -> Unit,
    onAllPeriods: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.export_to_csv)) },
        text = {
            Column {
                ButtonRow(
                    icon = painterResource(R.drawable.ic_calendar),
                    text = stringResource(R.string.export_current_period),
                    description = stringResource(R.string.export_current_period_description),
                    onClick = {
                        onClose()
                        onCurrentPeriod()
                    },
                )
                ButtonRow(
                    icon = painterResource(R.drawable.ic_file_download),
                    text = stringResource(R.string.export_all_periods),
                    description = stringResource(R.string.export_all_to_csv_description),
                    onClick = {
                        onClose()
                        onAllPeriods()
                    },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
        },
    )
}
