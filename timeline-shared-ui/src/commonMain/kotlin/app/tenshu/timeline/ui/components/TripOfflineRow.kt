package app.tenshu.timeline.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.data.TripOfflineOffer
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_trip_offline_downloading
import app.tenshu.timeline.shared.resources.timeline_trip_offline_failed
import app.tenshu.timeline.shared.resources.timeline_trip_offline_failed_hint
import app.tenshu.timeline.shared.resources.timeline_trip_offline_hint
import app.tenshu.timeline.shared.resources.timeline_trip_offline_keep_many
import app.tenshu.timeline.shared.resources.timeline_trip_offline_keep_one
import app.tenshu.timeline.shared.resources.timeline_trip_offline_kept
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * "Keep this trip's map areas offline" (`maps-region-delivery.md` §7.1 track 3d).
 *
 * A trip is a **journey**, so unlike a place it can cross several regions — the offer is the whole set, and
 * the price quoted is the **sum of the ones not yet resident** (a region already on disk is not charged for
 * twice). Offering only the region under the route's centroid would leave the far end of the trip still
 * needing the network — precisely the case Timeline exists for.
 *
 * ⚠ Deliberately not phrased as a fix: the route above is already drawn on a streaming basemap (D10). The
 * download buys availability with **no network at all**, and no per-viewport request pattern reaching the
 * tile host (T-MR-03).
 */
@Composable
fun TripOfflineRow(
    offer: TripOfflineOffer,
    downloading: Boolean,
    progress: Float?,
    failed: Boolean,
    onKeepOffline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val missing = offer.missing
    val resident = offer.fullyResident
    // A failed download stays tappable — that IS the retry, and it only re-fetches what still failed.
    val actionable = !resident && !downloading && missing.isNotEmpty()

    Row(
        modifier.let { if (actionable) it.clickable(onClick = onKeepOffline) else it },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            // Determinate over the WHOLE set: the ring is the real bytes on disk, so a stalled download
            // visibly stalls rather than spinning a lie.
            downloading && progress != null -> CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            downloading -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Icon(
                imageVector = if (resident) Icons.Outlined.Lock else Icons.Outlined.CloudDownload,
                contentDescription = null,
                tint = when {
                    failed -> MaterialTheme.colorScheme.error
                    resident -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(14.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = when {
                    downloading -> stringResource(
                        Res.string.timeline_trip_offline_downloading,
                        ((progress ?: 0f) * 100).roundToInt(),
                    )
                    resident -> stringResource(Res.string.timeline_trip_offline_kept)
                    failed -> stringResource(Res.string.timeline_trip_offline_failed)
                    // One region reads better by name; several are a count, because listing them would
                    // bury the thing that matters (what it costs).
                    missing.size == 1 -> stringResource(Res.string.timeline_trip_offline_keep_one, missing.first().name)
                    else -> stringResource(Res.string.timeline_trip_offline_keep_many, missing.size)
                },
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    failed -> MaterialTheme.colorScheme.error
                    actionable -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            when {
                // A failure is a disappointment, not a breakage — say so, so nobody thinks the map died.
                failed -> Text(
                    text = stringResource(Res.string.timeline_trip_offline_failed_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                actionable -> Text(
                    // Only the MISSING bytes: a region already resident costs nothing to "keep".
                    text = stringResource(Res.string.timeline_trip_offline_hint, formatRegionSize(offer.missingBytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Coarse, honest size for region bundles — GB above 1024 MB, else whole MB. */
private fun formatRegionSize(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib >= 1024.0) "${kotlin.math.round(mib / 1024.0 * 10) / 10.0} GB" else "${mib.toLong()} MB"
}
