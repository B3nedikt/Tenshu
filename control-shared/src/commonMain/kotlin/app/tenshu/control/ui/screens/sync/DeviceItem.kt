package app.tenshu.control.ui.screens.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.device_item_added_by
import app.tenshu.control.shared.resources.device_item_approve
import app.tenshu.control.shared.resources.device_item_pending_approval
import app.tenshu.control.shared.resources.device_item_policy_keep_offline
import app.tenshu.control.shared.resources.device_item_previous_identity
import app.tenshu.control.shared.resources.device_item_previous_identity_detail
import app.tenshu.control.shared.resources.device_item_policy_online_only
import app.tenshu.control.shared.resources.device_item_readmit
import app.tenshu.control.shared.resources.device_item_revoke
import app.tenshu.control.shared.resources.device_item_this_device
import app.tenshu.ui.preview.PreviewBox
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.tenshu.core.Data
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.deviceDisplayLabel

@Composable
fun DeviceItem(
    device: Device,
    currentDeviceId: String,
    policy: SyncPolicy? = null,
    admittedByName: String? = null,
    /**
     * Wall-clock ms of the newest write this device has ingested from the row's device, or null
     * when it has heard nothing. Rendered for every row *except* this device — "last seen" about
     * the device you are holding is noise.
     */
    lastSeenMs: Long? = null,
    nowMs: Long = 0L,
    /** The row device's self-published hardware model (`DeviceHardware`), or null when unknown. */
    hardwareName: String? = null,
    /**
     * This row is **this device's own earlier identity** — same public key, older `deviceId`
     * (`previousIdentitiesOfThisDevice`, `drive-upload-stop-and-reclamation.md` §4.23/D14).
     *
     * ⚠ Rendered as a badge plus an explanation, never as an automatic action: the app cannot
     * distinguish this from a second machine restored from a backup, and `Device` rows have no
     * regeneration path, so removing the wrong twin is unrecoverable. The existing revoke
     * confirmation is the decision point.
     */
    isPreviousIdentity: Boolean = false,
    onRevoke: () -> Unit,
    onApprove: () -> Unit,
    /**
     * Restore this peer's relay registration (`ReadmitDevice`).
     *
     * ⚠ Offered on **every** peer row, not only ones that look stranded: a refused device is the
     * only party that can observe its own `401`, and from here "stranded" and "offline for a
     * while" are the same absent [lastSeenMs]. The op is idempotent, so a speculative tap is a
     * no-op — and it refuses, with a sentence, when this device holds no master identity.
     */
    onReadmit: () -> Unit
) {
    val isAuthorized = device.isAuthorized.get()
    val isCurrent = device.deviceId.get() == currentDeviceId

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (!isAuthorized) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Devices,
                contentDescription = null,
                tint = if (!isAuthorized) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = deviceDisplayLabel(device.name.get(), hardwareName),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                        color = if (!isAuthorized) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                    if (isCurrent) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.device_item_this_device),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    if (isPreviousIdentity) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.device_item_previous_identity),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    if (!isAuthorized) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.device_item_pending_approval),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                Text(
                    text = device.deviceId.get(),
                    style = MaterialTheme.typography.bodySmall
                )
                // Last-seen (tech-debt: "a `Device` row that outlives the identity it describes").
                // A row that has never written anything is what a dead identity looks like from
                // here — and, indistinguishably, what a long-offline peer looks like, which is why
                // this informs rather than decides.
                if (!isCurrent && nowMs > 0L) {
                    Text(
                        text = formatLastSeen(lastSeenMs, nowMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Read-only offline-sync policy ("what this device keeps", §4.6).
                // Shown only when the device has an explicit policy (policy != null).
                val policyLabel = when (policy) {
                    SyncPolicy.KEEP_OFFLINE -> stringResource(Res.string.device_item_policy_keep_offline)
                    SyncPolicy.ONLINE_ONLY -> stringResource(Res.string.device_item_policy_online_only)
                    null -> null
                }
                if (policyLabel != null) {
                    Text(
                        text = policyLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // ⚠⚠ Why the row is worth acting on, not just labelling: an identity nothing runs
                // as can never publish an HLC tip, and the reclamation barrier waits for every
                // rostered device to publish one past a tombstone — so one orphan blocks content
                // reclamation for the whole realm, for ever (§4.23).
                if (isPreviousIdentity) {
                    Text(
                        text = stringResource(Res.string.device_item_previous_identity_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Admission lineage (delegated-device-admission-authority.md §5):
                // which device vouched for this one at pairing time. Absent for the
                // genesis device and rows written before lineage existed.
                if (admittedByName != null) {
                    Text(
                        text = stringResource(Res.string.device_item_added_by, admittedByName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!isAuthorized) {
                IconButton(onClick = onApprove) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(Res.string.device_item_approve),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (isAuthorized && !isCurrent) {
                IconButton(onClick = onReadmit) {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = stringResource(Res.string.device_item_readmit),
                    )
                }
                IconButton(onClick = onRevoke) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.device_item_revoke),
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun DeviceItemCurrentPreview() = PreviewBox {
    DeviceItem(
        device = mockDevice("current-id", "MacBook Pro", true),
        currentDeviceId = "current-id",
        onRevoke = {},
        onApprove = {},
        onReadmit = {}
    )
}

@Preview
@Composable
private fun DeviceItemOtherPreview() = PreviewBox {
    DeviceItem(
        device = mockDevice("other-id", "Google Pixel 8", true),
        currentDeviceId = "current-id",
        onRevoke = {},
        onApprove = {},
        onReadmit = {}
    )
}

@Preview
@Composable
private fun DeviceItemPendingPreview() = PreviewBox {
    DeviceItem(
        device = mockDevice("pending-id", "New Tablet", false),
        currentDeviceId = "current-id",
        onRevoke = {},
        onApprove = {},
        onReadmit = {}
    )
}

internal fun mockDevice(
    id: String,
    name: String,
    isAuthorized: Boolean
): Device = object : Device {
    override val deviceId = id.toTString()
    override val name = name.toTString()
    override val publicKey = byteArrayOf().toTByteArray()
    override val isAuthorized = isAuthorized.toTBoolean()
    override val schema get() = Device.Schema
    override fun <T : Data> getField(reference: String): T {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            Device.Fields.DEVICE_ID -> deviceId
            Device.Fields.NAME -> name
            Device.Fields.PUBLIC_KEY -> publicKey
            Device.Fields.IS_AUTHORIZED -> isAuthorized
            else -> error("Field not mocked: $reference")
        } as T
    }

    override fun <T : Data> getOptionalField(reference: String): T? = null
}
