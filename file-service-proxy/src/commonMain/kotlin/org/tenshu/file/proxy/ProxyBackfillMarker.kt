package org.tenshu.file.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.file.sync.ContentBackfillMarker
import org.tenshu.preference.StringListAdapter

/**
 * The content-declaration backfill's completion marker, read and written proxy-side
 * (`content-plane-refcount.md` §9.7, CR-4d) — through the device-setting seam
 * ([readDeviceSettingData]), because both sides of this already live on the proxy side of the IPC
 * boundary.
 *
 * ⚠ Compared against [ContentBackfillMarker.BACKFILL_FORMAT] rather than read as a boolean,
 * so bumping the format re-runs the pass on every device instead of leaving them latched on a
 * description that is no longer sufficient.
 */
fun ExecutionContext.readBackfillComplete(deviceId: String): Boolean {
    val stored = StringListAdapter.fromData(readDeviceSettingData(deviceId, BACKFILL_ID))
        ?: return false
    return ContentBackfillMarker.BACKFILL_FORMAT in stored
}

/**
 * Record — or clear — this device's backfill completion.
 *
 * ⚠⚠ Only ever called with `true` after a **clean** pass. See
 * [ContentBackfillMarker.BackfillComplete]: the pass runs once by design, so a marker over a
 * degraded run strands whatever it missed for ever.
 */
suspend fun ExecutionContext.writeBackfillComplete(deviceId: String, complete: Boolean) =
    writeDeviceSettingValue(
        deviceId = deviceId,
        preferenceId = BACKFILL_ID,
        value = StringListAdapter.toData(
            context = context,
            value = if (complete) listOf(ContentBackfillMarker.BACKFILL_FORMAT) else emptyList(),
        ),
    )

private val BACKFILL_ID: String = ContentBackfillMarker.BackfillComplete.id
