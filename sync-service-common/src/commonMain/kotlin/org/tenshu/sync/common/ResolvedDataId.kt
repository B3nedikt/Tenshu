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
 * Durable, local-only reverse index `entityIdHash → DataId`.
 *
 * A SyncBlock addresses its target entity by a one-way hash of the DataId
 * ([SyncBlock.targetEntityIdHash]) — cleartext on the wire so the relay never learns the
 * real [DataId] (which encodes the file path; House Rule #1). To *apply* an incoming
 * update, tombstone, or on-demand file-content manifest, the proxy must recover the real
 * local [DataId] from that hash. That mapping is only learnable when the entity's own
 * *create* is applied locally, so [TombstoneRegistry] records it as a side effect of every
 * save.
 *
 * Previously the mapping lived only in an in-memory map that was lost across restart and
 * never rebuilt (the side-cache walks no persisted source). That broke binary content
 * sync: a file whose metadata + thumbnail synced in an earlier session could never have
 * its bytes resolved on the next launch — `resolveDataId` returned null and
 * `FileContentManifestApplier` no-op'd ("entity not synced yet"), so the offline toggle
 * never cleared. This record persists the mapping so it survives a restart, keyed by
 * [targetEntityIdHash] via [DataIdProvider] (mirrors [SyncMetadata]).
 *
 * Local-only: never serialised into a block or pushed to the relay.
 */
interface ResolvedDataId : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** Stringified hash of [targetDataId] — the key this record is stored under. */
    val targetEntityIdHash: TString
        get() = getField(Fields.TARGET_ENTITY_ID_HASH)

    /** The real local [DataId] the hash resolves to. */
    val targetDataId: DataId
        get() = getField(Fields.TARGET_DATA_ID)

    companion object {
        const val ID = "tenshu/sync/resolvedDataId"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.TARGET_ENTITY_ID_HASH)
                composite(Fields.TARGET_DATA_ID, type = DataId.Schema)
            }
        )

        fun create(
            context: TContext,
            targetEntityIdHash: String,
            targetDataId: DataId
        ): ResolvedDataId = context.create(
            type = ID,
            fields = listOf(
                targetEntityIdHash.toTString(context),
                targetDataId
            )
        )
    }

    object Fields {
        const val TARGET_ENTITY_ID_HASH = "$ID/targetEntityIdHash"
        const val TARGET_DATA_ID = "$ID/targetDataId"
    }
}
