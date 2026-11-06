package app.tenshu.timeline.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.data.geocode.PlaceGeocodingControl
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_import_geocoding_enable
import app.tenshu.timeline.shared.resources.timeline_import_geocoding_offer
import app.tenshu.timeline.shared.resources.timeline_import_not_now
import app.tenshu.timeline.shared.resources.timeline_import_ok
import app.tenshu.timeline.shared.resources.timeline_import_result_title
import org.jetbrains.compose.resources.stringResource

/**
 * Result of a Google-Timeline file import (I2b) — an honest count, or a hint when the file held
 * nothing recognisable — extended with the **place-name-lookup offer** (place-geocoding.md G3,
 * D5): when [geocodingControl] is passed (the caller does so only after a *successful* import
 * with lookups currently off), a short disclosure line plus an enable action replace the plain
 * OK button. Enabling flips the same persisted consent the Settings toggle drives; the
 * `GeocodingBackfillTrigger` observes the flag and starts the backfill by itself.
 */
@Composable
fun ImportResultDialog(
    message: String,
    geocodingControl: PlaceGeocodingControl?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.timeline_import_result_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                if (geocodingControl != null) {
                    Text(stringResource(Res.string.timeline_import_geocoding_offer))
                }
            }
        },
        confirmButton = {
            if (geocodingControl != null) {
                TextButton(
                    onClick = {
                        geocodingControl.setEnabled(true)
                        onDismiss()
                    },
                ) { Text(stringResource(Res.string.timeline_import_geocoding_enable)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.timeline_import_ok)) }
            }
        },
        dismissButton = if (geocodingControl != null) {
            {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.timeline_import_not_now)) }
            }
        } else {
            null
        },
    )
}
