package org.tenshu.sync.common

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * What **one device** can read — the per-device half of the schema picture, and
 * the fact the readable floor advances on (E4, `core-schema-evolution.md` §8.3).
 *
 * ## Why this cannot be [RealmSchemaEpoch]
 *
 * ⚠⚠ The two rows aggregate in **opposite directions**, because they answer
 * opposite questions:
 *
 *  - [RealmSchemaEpoch] is one row per realm, merged by **MAX** — "the newest
 *    schema anyone has written here". Fencing needs that: a reader below the max
 *    might meet bytes it cannot parse (§7.3).
 *  - This row is one row **per device**, and the roster reads it by **MIN** —
 *    "the oldest schema still in service". Floor advance needs that: history may
 *    only be cut off once *every* device has moved past it.
 *
 * A MAX-merged aggregate can never yield a MIN, and a single row cannot hold
 * both without losing which device contributed what. Hence a keyed row per
 * device rather than a field on the epoch — and rather than a field on the
 * `Device` roster row, which would churn identity rows on every version bump and
 * reshape a load-bearing type for metadata that is not identity (the E2h
 * precedent: per-X detail is a NEW composite id, never appended fields).
 *
 * ## Absence is not zero
 *
 * ⚠ A registered, non-revoked device with **no row here** must *hold the floor
 * back*, never be skipped. It means "this device has not told us what it can
 * read" — most likely it has not run since the row was introduced — which is
 * exactly the device that would be harmed by cutting history off. The roster
 * computation returns *unknown* rather than a floor in that case; see
 * `RosterSchemaFloor`.
 *
 * ## Shape notes
 *
 * The vector rides as **serialized bytes** for the same reason
 * [RealmSchemaEpoch]'s does: `TList` members need a `DataIdProvider` and
 * `ServiceVersion` is wire-only. Conflict strategy is plain LWW — unlike the
 * epoch there is nothing to merge, since a device is the sole author of its own
 * row and its later statement always supersedes its earlier one.
 */
interface DeviceSchemaVersion : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** The device this statement is about — the row's key. */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /**
     * That device's [VersionVector] as serialized under its frozen schema: the
     * versions it *runs*, not the versions it has written. Decode with
     * [decodeVersions].
     */
    val versionsBytes: TByteArray
        get() = getField(Fields.VERSIONS_BYTES)

    companion object {
        const val ID = "tenshu/sync/deviceSchemaVersion"

        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.DEVICE_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(
                DataIdProviderSchema,
                // LWW, not Merge: one author per row (see the KDoc).
                Syncable(SyncStrategy.Eager, ConflictStrategy.LastWriterWins),
            ),
            fields = {
                string(Fields.DEVICE_ID)
                byteArray(Fields.VERSIONS_BYTES)
            },
        )

        fun create(
            context: TContext,
            deviceId: String,
            versions: VersionVector,
        ): DeviceSchemaVersion =
            create(context, deviceId, BinarySerializer().serialize(versions))

        fun create(
            context: TContext,
            deviceId: String,
            versionsBytes: ByteArray,
        ): DeviceSchemaVersion = context.create(
            type = ID,
            fields = listOf(
                deviceId.toTString(context),
                versionsBytes.toTByteArray(context),
            ),
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val VERSIONS_BYTES = "$ID/versions"
    }
}
