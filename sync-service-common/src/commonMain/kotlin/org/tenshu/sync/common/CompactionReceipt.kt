package org.tenshu.sync.common

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.string
import org.tenshu.core.toTString
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * "Device D compacted service S to version N" — the dedup signal for compaction
 * (E4, `core-schema-evolution.md` §8.3).
 *
 * ⚖️ **This is the whole coordination mechanism. There is no election and no
 * quorum**, because compaction — re-emitting current state at the current
 * version — is *idempotent and convergent*: two devices racing produce redundant
 * blocks that merge by the ordinary HLC and semantic-merge rules. Consensus
 * would buy agreement the system does not need, at an availability cost it
 * cannot afford (the user's devices are mostly offline; a quorum may simply
 * never be reachable). A lost race costs one redundant re-emission — bandwidth,
 * never correctness.
 *
 * It is the "signalling block" idea with the blockchain removed: inside the
 * trusted device set there is no adversary, so the signal is a plain E2E row —
 * no proof-of-work, no chain, no ordering guarantee needed beyond HLC.
 *
 * ## Keyed per (realm, service), not per device
 *
 * ⚠ The key is the **service**, so a second device's receipt *replaces* the
 * first rather than accumulating one row per device forever. What matters to a
 * peer deciding whether to compact is "has anyone done S at version N recently",
 * never "who". `LastWriterWins` is therefore correct and the row count stays
 * bounded by the service count.
 *
 * ⚠ [compactedBy] is diagnostic only. Nothing may branch on it: a device that
 * treats "my own receipt" differently from a peer's re-introduces exactly the
 * per-device coordination this design removed.
 */
interface CompactionReceipt : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** The row's key — `"$realmKey::$serviceId"`, or the bare service id personally. */
    val receiptId: TString
        get() = getField(Fields.RECEIPT_ID)

    /** The service whose current state was re-emitted. */
    val serviceId: TString
        get() = getField(Fields.SERVICE_ID)

    /** The version its state was re-emitted **at** — what makes the receipt actionable. */
    val compactedToVersion: TInteger
        get() = getField(Fields.COMPACTED_TO_VERSION)

    /** Diagnostic only — see the class KDoc. */
    val compactedBy: TString
        get() = getField(Fields.COMPACTED_BY)

    companion object {
        const val ID = "tenshu/sync/compactionReceipt"

        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.RECEIPT_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(
                DataIdProviderSchema,
                Syncable(SyncStrategy.Eager, ConflictStrategy.LastWriterWins),
            ),
            fields = {
                string(Fields.RECEIPT_ID)
                string(Fields.SERVICE_ID)
                int(Fields.COMPACTED_TO_VERSION)
                string(Fields.COMPACTED_BY)
            },
        )

        /** Personal realm key; a shared realm prefixes its own `"$realmId::"`. */
        fun receiptIdFor(serviceId: String, realmPrefix: String = ""): String =
            realmPrefix + serviceId

        fun create(
            context: TContext,
            serviceId: String,
            compactedToVersion: Int,
            compactedBy: String,
            realmPrefix: String = "",
        ): CompactionReceipt = context.create(
            type = ID,
            fields = listOf(
                receiptIdFor(serviceId, realmPrefix).toTString(context),
                serviceId.toTString(context),
                compactedToVersion.toTInteger(context),
                compactedBy.toTString(context),
            ),
        )
    }

    object Fields {
        const val RECEIPT_ID = "$ID/receiptId"
        const val SERVICE_ID = "$ID/serviceId"
        const val COMPACTED_TO_VERSION = "$ID/compactedToVersion"
        const val COMPACTED_BY = "$ID/compactedBy"
    }
}
