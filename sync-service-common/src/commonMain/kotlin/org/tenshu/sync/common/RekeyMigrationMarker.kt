package org.tenshu.sync.common

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * Durable, local-only **intent-journal marker** for one in-progress DataId migration
 * (`file-content-addressing.md` §3.3.4 slice 3c). [DataIdMigrator] writes one before it starts
 * retiring the old key (sweep `SyncMetadata`, delete the old entity, emit its tombstone) and
 * clears it once done; a marker still present on the next startup means a migration was
 * interrupted mid-flight, and [DataIdMigrator.resumeIncompleteMigrations] re-runs the (idempotent)
 * retirement to completion.
 *
 * Written **below** `ExecutionContext.saveData` (via `database.addToArray`/`removeFromArray` on the
 * [org.tenshu.sync.SyncService.Collections.PENDING_REKEY_MIGRATIONS] array) so it never re-fires
 * observers — mirroring [ResolvedDataId] / [TombstoneRegistry]'s durable-sidecar pattern.
 *
 * Because the marker is committed in its own `transaction {}` **before** the first retirement
 * write, and the object store's write mailbox is ordered, any retirement write that reaches disk
 * implies this marker reached disk first — so a crash can never leave a partially-retired entity
 * with no marker to resume it (§3.3.3.F). Local-only: never serialised into a block or pushed.
 *
 * @property markerKey the stringified hash of [oldDataId] — the key this record is stored under.
 * @property oldDataId the entity's old key being retired (kept whole so resume can `deleteData` +
 *   derive the tombstone hash without reconstruction).
 * @property newHash the stringified hash of the entity's new key (for the target-side
 *   `SyncMetadata` sweep).
 * @property entityType the schema id of the migrated entity (the tombstone's `targetEntityType`).
 */
interface RekeyMigrationMarker : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val markerKey: TString
        get() = getField(Fields.MARKER_KEY)

    val oldDataId: DataId
        get() = getField(Fields.OLD_DATA_ID)

    val newHash: TString
        get() = getField(Fields.NEW_HASH)

    val entityType: TString
        get() = getField(Fields.ENTITY_TYPE)

    companion object {
        const val ID = "tenshu/sync/rekeyMigrationMarker"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.MARKER_KEY)
                composite(Fields.OLD_DATA_ID, type = DataId.Schema)
                string(Fields.NEW_HASH)
                string(Fields.ENTITY_TYPE)
            }
        )

        fun create(
            context: TContext,
            markerKey: String,
            oldDataId: DataId,
            newHash: String,
            entityType: String,
        ): RekeyMigrationMarker = context.create(
            type = ID,
            fields = listOf(
                markerKey.toTString(context),
                oldDataId,
                newHash.toTString(context),
                entityType.toTString(context),
            )
        )
    }

    object Fields {
        const val MARKER_KEY = "$ID/markerKey"
        const val OLD_DATA_ID = "$ID/oldDataId"
        const val NEW_HASH = "$ID/newHash"
        const val ENTITY_TYPE = "$ID/entityType"
    }
}
