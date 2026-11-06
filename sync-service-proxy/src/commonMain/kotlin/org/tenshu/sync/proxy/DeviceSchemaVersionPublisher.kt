package org.tenshu.sync.proxy

import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.DeviceSchemaVersion

/**
 * Publishes **this device's** `VersionVector` so the roster can compute the
 * readable floor (E4, `core-schema-evolution.md` §8.3).
 *
 * ⚖️ **A one-shot at init, not a per-write hook** — unlike
 * [RealmSchemaEpochWriter], which must watch every write because the *realm's*
 * high-water mark moves whenever any device writes a newer type. A device's own
 * vector is assembled from its registered module graph: it is a **compile-time
 * constant for the life of the process**, so the only moment it can change is a
 * restart on a new build. Watching writes for it would be pure overhead.
 *
 * ⚠ Write-if-changed, not write-always. The row is `Syncable(Eager)`, so an
 * unconditional save on every start would emit a block per launch per device
 * forever — pure noise on a fact that changes only at upgrade.
 *
 * ⚠ Requires realm membership, for the same reason `reconcileOwnDeviceRow` does:
 * a device that has not joined must not self-assert a roster row. Locked or
 * unpaired ⇒ no-op, retried on the next start (and, like the own-`Device`
 * repair, on the unlock edge).
 */
internal class DeviceSchemaVersionPublisher(
    private val executionContext: ExecutionContext,
) {

    private val schemas: SchemaRegistry? =
        executionContext.schemaProvider as? SchemaRegistry

    private val parser: BinaryParser by lazy {
        val registry = schemas ?: error("DeviceSchemaVersionPublisher requires a SchemaRegistry")
        BinaryParser(registry, registry)
    }

    /**
     * @return true when a row was written (new or changed), false when the
     *   recorded row already matches or the device cannot publish yet.
     */
    fun publishIfChanged(deviceId: String): Boolean {
        if (deviceId.isEmpty()) return false
        val registry = schemas ?: return false

        val mine = registry.currentVersionVector(executionContext.context)
        val rowId = DataId.StringDataId.create(
            context = executionContext.context,
            type = DeviceSchemaVersion.ID,
            keyValue = deviceId,
        )

        val existing = executionContext.database.getData(rowId) as? DeviceSchemaVersion
        // A row whose bytes will not decode is treated as absent and overwritten:
        // the schema is frozen, so a decode failure means corruption, and a
        // corrupt row must not permanently pin this device as "silent" — which is
        // precisely what would freeze the floor for the whole realm.
        val recorded = existing?.let {
            runCatching { parser.parse(it.versionsBytes.get()) as VersionVector }.getOrNull()
        }

        if (recorded != null && recorded.sameAs(mine)) return false

        executionContext.saveData(
            DeviceSchemaVersion.create(
                context = executionContext.context,
                deviceId = deviceId,
                versions = mine,
            )
        )
        return true
    }

    /**
     * Structural equality over what the roster actually reads. ⚠ Not `==`: these
     * are materialised composites whose identity is per-instance, so a value
     * comparison is the only one that means anything here.
     */
    private fun VersionVector.sameAs(other: VersionVector): Boolean =
        coreVersion.get().toInt() == other.coreVersion.get().toInt() && toMap() == other.toMap()
}
