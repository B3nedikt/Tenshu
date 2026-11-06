package org.tenshu.sync.proxy

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.sync.RuntimeIdentity
import org.tenshu.sync.SyncService

/**
 * Publish this runtime's [RuntimeIdentity] row — *which binary is hosting this sync runtime*
 * (`core-schema-skew-surface.md` SK-2, §8.4, §8.7).
 *
 * ⚠⚠ **This writer's row, keyed on [appId]** — never a shared key. Under iOS route 1 several
 * apps run this over one App-Group replica; on a singleton row the last app to launch would
 * overwrite every sibling's answer. Android and desktop have one writer, Control, so there the
 * relation holds exactly one row.
 *
 * ⚠⚠ **Its own row, not a field on [org.tenshu.sync.SchemaFenceStatus]**, for the same reason
 * `SyncProxyInitialization` gives twice —
 * *"SyncHealthStatus is a positional composite that cannot grow fields for a version-skewed
 * Drive"* and *"its own row, because ContentTransferStatus is positional and cannot grow fields"*.
 * For the fence row the argument is even stronger, because the bump that would make an appended
 * field safe would itself pull-fence every not-yet-updated device in the realm — see
 * [RuntimeIdentity]'s KDoc.
 *
 * **Written once, not per tick.** Unlike the health and transfer rows this describes the *process*,
 * which cannot change while the process lives. One upsert at init is the whole publication.
 *
 * ⚠ Skipped entirely when the host declares no [appId]. Such a wiring publishes nothing rather than a placeholder, so a client reads `null` — *"I do not know who hosts
 * this runtime"* — instead of a confident wrong answer about which app to update.
 */
fun ExecutionContext.initRuntimeIdentity(
    appId: String?,
    appVersion: String = RuntimeIdentity.UNKNOWN_VERSION,
) {
    if (appId.isNullOrBlank()) return

    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = context,
            type = TList.ID,
            relationId = SyncService.Relations.RUNTIME_IDENTITY,
        ),
        isEntity = false,
        elementSchemaId = RuntimeIdentity.ID,
    )
    val rowId = DataId.StringDataId.create(
        context = context,
        type = RuntimeIdentity.ID,
        keyValue = appId,
    )
    val row = RuntimeIdentity.create(
        context = context,
        appId = appId,
        appVersion = appVersion.ifBlank { RuntimeIdentity.UNKNOWN_VERSION },
    )
    // Remove-then-add of THIS writer's row: it survives a process restart in the store, and a
    // relaunch with a new version must replace it rather than accumulate a second element.
    database.transaction {
        database.removeFromArray(ref, rowId)
        database.addToArray(ref, row)
    }
}
