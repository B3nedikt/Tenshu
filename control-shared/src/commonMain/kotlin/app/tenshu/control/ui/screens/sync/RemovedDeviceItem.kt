package app.tenshu.control.ui.screens.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.control.data.RemovedDevice
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.removed_device_by_itself
import app.tenshu.control.shared.resources.removed_device_by_phrase
import app.tenshu.control.shared.resources.removed_device_relay_owed
import app.tenshu.control.shared.resources.removed_device_relay_unknown
import app.tenshu.control.shared.resources.removed_device_relay_needs_update
import app.tenshu.control.shared.resources.removed_device_relay_refused
import app.tenshu.control.shared.resources.removed_device_relay_superseded
import app.tenshu.control.shared.resources.removed_device_days_ago
import app.tenshu.control.shared.resources.removed_device_today
import app.tenshu.control.shared.resources.removed_device_unnamed
import app.tenshu.control.shared.resources.removed_device_yesterday
import org.jetbrains.compose.resources.stringResource

/**
 * One row of Manage devices' "Removed" section (`sync-entity-identity.md` §5b R6c). Read-only: a removed
 * device comes back only by pairing again, a deliberate decision, never a tap here.
 *
 * ⚠ The removed device's roster row — and with it its user-chosen name — is gone, so the label is its
 * hardware model when that row survives, else a short id.
 */
@Composable
fun RemovedDeviceItem(removed: RemovedDevice, hardwareName: String?, nowMs: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hardwareName ?: stringResource(Res.string.removed_device_unnamed, removed.deviceId.take(8)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val days = removedAgeDays(removed.removedAtMillis, nowMs)
                Text(
                    text = when {
                        days <= 0L -> stringResource(Res.string.removed_device_today)
                        days == 1L -> stringResource(Res.string.removed_device_yesterday)
                        else -> stringResource(Res.string.removed_device_days_ago, days.toInt())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        if (removed.removedItself) Res.string.removed_device_by_itself else Res.string.removed_device_by_phrase,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // ⚠⚠ Never plainly "removed" while the relay has not applied it (maintainer decision 2026-11-12).
                relayStatusText(removed)?.let { (text, isError) ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Whole days since [removedAtMillis]; coarse on purpose, as `formatLastSeen` is (author clock skew). */
internal fun removedAgeDays(removedAtMillis: Long, nowMs: Long): Long =
    ((nowMs - removedAtMillis).coerceAtLeast(0L)) / 86_400_000L

/** The relay's side of [removed], or `null` when there is nothing to add; `true` = the access is still open. */
@Composable
private fun relayStatusText(removed: RemovedDevice): Pair<String, Boolean>? {
    val state = org.tenshu.sync.common.PendingDeviceAccessRevocation.State
    return when (removed.relayState) {
        state.OWED -> stringResource(Res.string.removed_device_relay_owed) to false
        state.RELAY_UNKNOWN -> stringResource(Res.string.removed_device_relay_unknown) to true
        state.NEEDS_UPDATE -> stringResource(Res.string.removed_device_relay_needs_update) to true
        state.REFUSED -> stringResource(Res.string.removed_device_relay_refused, removed.relayDetail) to true
        state.SUPERSEDED -> stringResource(Res.string.removed_device_relay_superseded) to true
        else -> null
    }
}
