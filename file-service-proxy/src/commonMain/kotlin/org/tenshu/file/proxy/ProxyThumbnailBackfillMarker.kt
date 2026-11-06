package org.tenshu.file.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.file.ThumbnailPreferences
import org.tenshu.preference.StringListAdapter

/**
 * The thumbnail backfill's completion marker, read and written proxy-side through the same
 * device-setting seam as [readBackfillComplete] — and for the same reason: both sides of it live
 * on the proxy side of the IPC boundary.
 *
 * ⚠ The value is [ThumbnailClassBackfill.markerFor]'s hash of the wanted set and the mint format,
 * not a boolean — see [ThumbnailPreferences.BackfillMarker] for why, and
 * [ThumbnailClassBackfill] for the one condition under which it is written.
 */
fun ExecutionContext.readThumbnailBackfillMarker(deviceId: String): String? =
    StringListAdapter.fromData(readDeviceSettingData(deviceId, THUMBNAIL_MARKER_ID))?.singleOrNull()

suspend fun ExecutionContext.writeThumbnailBackfillMarker(deviceId: String, marker: String) =
    writeDeviceSettingValue(
        deviceId = deviceId,
        preferenceId = THUMBNAIL_MARKER_ID,
        value = StringListAdapter.toData(listOf(marker), context),
    )

private val THUMBNAIL_MARKER_ID: String = ThumbnailPreferences.BackfillMarker.id
