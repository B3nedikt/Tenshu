package org.tenshu.calendar.proxy.impl

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.TombstoneEmitter

/**
 * Deletes one Calendar row **and tombstones it** — the only way a Calendar delete may happen.
 *
 * ⚠⚠ `SyncObserver.onDataDeleted` is a deliberate no-op: a delete leaves this device only if the op
 * emits its own tombstone (`DeleteWorkout` and `DeleteFile` are the precedents). Before this helper
 * only *Stop syncing* did, so every other Calendar delete stayed on the device it was made on, and
 * the next write of the row from anywhere else brought it back (`CalendarDeleteSyncSystemTest`).
 *
 * ⚠ One call per row, cascades included: a cascade deletes **separate rows** (overrides, sidecars,
 * reminder states), and a tombstone for the parent says nothing about them.
 *
 * ⚠ A `null` emitter deletes locally only — the posture of a sync-less test proxy, never production.
 */
internal fun ExecutionContext.deleteRow(type: String, key: String, tombstoneEmitter: TombstoneEmitter?) {
    val dataId = DataId.StringDataId.create(context = context, type = type, keyValue = key)
    deleteData(dataId)
    // ⚠ The id form: since EI-1 only the emitter can derive the relay-visible name.
    tombstoneEmitter?.emit(targetEntityId = dataId, targetEntityType = type)
}
