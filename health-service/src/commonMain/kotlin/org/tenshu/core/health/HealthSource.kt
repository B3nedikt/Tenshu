package org.tenshu.core.health

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A **standing platform health connection**, on one device
 * (`docs/proposals/fit-data-sources-restructure.md` §2.1). One row per
 * ([kind], [deviceId]) — a health store is a device-local identity — but the row is
 * **`Syncable(Eager)`**, which is the whole point: it is what lets an Android phone show
 * that an iPhone imported from Apple Health last Tuesday. Before this type, sources were
 * *derived* by scanning `Workout.source` on the local device (`ProfileViewModel.kt`), so a
 * source that had produced nothing here was structurally invisible (§0).
 *
 * ⚠ **The invariant is narrow, and it is load-bearing (D11): a row means "a platform health
 * store this device can import from, and can be told to stop importing from."** That is why
 * there is no `TAKEOUT` row (an import from a file is an *act*, not a connection — the batch
 * surface derives from its [HealthImportRun]s) and no `TENSHU` row (Fit's own recorded /
 * manual / detected workouts are a projection over `Workout`). Widening [kind] past the two
 * platform stores puts fields here that two thirds of the rows cannot honour.
 *
 * Keyed by [key] (`"$kind@$deviceId"`) via [HealthSourceDataIdProvider] — the `IngestSource`
 * shape — so register / status / enable are all idempotent upserts against the stable key.
 * `Syncable(Eager)` + default LastWriterWins, mirroring `IngestSource` / `Device`: an
 * individual descriptor under a unique key has nothing for a semantic merge to do.
 *
 * [lastRunAt] / [lastStatus] / [lastItemCount] are **denormalised** from the newest
 * [HealthImportRun] (D5) so the Data sources list is one read rather than N reads plus a sort
 * for three fields of text. `RecordImportRun` writes both in the same operation; the run rows
 * stay authoritative.
 */
interface HealthSource : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable identity + relation key: `"$kind@$deviceId"`. */
    val key: TString
        get() = getField(Fields.KEY)

    /** [KIND_HEALTH_CONNECT] or [KIND_APPLE_HEALTH]. Deliberately no other value — see D11. */
    val kind: TString
        get() = getField(Fields.KIND)

    /** The device this connection lives on. ⚠ Never defaulted — see [keyOf]. */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** Human device name for the UI ("iPhone 15"), stamped at register time (D3). */
    val deviceLabel: TString
        get() = getField(Fields.DEVICE_LABEL)

    /**
     * Whether this device may import from this store automatically (D4).
     *
     * ⚠ This crosses devices, and that is the point: we cannot revoke HealthKit's permission
     * from an Android phone, but this field is ours, so flipping it here stops the iPhone's
     * `HealthImportBootstrap` on its next foreground. What stays device-local is *triggering*
     * an import and *fixing* permissions.
     */
    val autoImport: TBoolean
        get() = getField(Fields.AUTO_IMPORT)

    /** Epoch millis of the newest recorded run, denormalised (D5). */
    val lastRunAt: TInteger?
        get() = getOptionalField(Fields.LAST_RUN_AT)

    /** The newest run's status — one of [HealthImportRun]'s `STATUS_*`. */
    val lastStatus: TString?
        get() = getOptionalField(Fields.LAST_STATUS)

    /** The newest run's item count. */
    val lastItemCount: TInteger?
        get() = getOptionalField(Fields.LAST_ITEM_COUNT)

    companion object {
        const val ID = "tenshu/health/source"

        /** Android's Health Connect. */
        const val KIND_HEALTH_CONNECT = "HEALTH_CONNECT"

        /** iOS/watchOS HealthKit. */
        const val KIND_APPLE_HEALTH = "APPLE_HEALTH"

        /**
         * The stable relation/identity key for a connection on a device.
         *
         * ⚠ [deviceId] must be a real resolved id. Fit already has one
         * (`TenshuAutoDetectPreferences.resolveDeviceId`); substituting a blank or a default
         * writes this device's state into **another device's** row under a borrowed key.
         */
        fun keyOf(kind: String, deviceId: String): String = "$kind@$deviceId"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(HealthSourceDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.KEY)
                string(Fields.KIND)
                string(Fields.DEVICE_ID)
                string(Fields.DEVICE_LABEL)
                boolean(Fields.AUTO_IMPORT)
                int(Fields.LAST_RUN_AT, isOptional = true)
                string(Fields.LAST_STATUS, isOptional = true)
                int(Fields.LAST_ITEM_COUNT, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            kind: String,
            deviceId: String,
            deviceLabel: String,
            autoImport: Boolean = true,
            lastRunAt: Long? = null,
            lastStatus: String? = null,
            lastItemCount: Long? = null,
        ): HealthSource = context.create(
            type = ID,
            fields = listOf(
                keyOf(kind, deviceId).toTString(context),
                kind.toTString(context),
                deviceId.toTString(context),
                deviceLabel.toTString(context),
                autoImport.toTBoolean(context),
                lastRunAt?.toTInteger(context),
                lastStatus?.toTString(context),
                lastItemCount?.toTInteger(context),
            )
        )
    }

    object Fields {
        const val KEY = "$ID/key"
        const val KIND = "$ID/kind"
        const val DEVICE_ID = "$ID/deviceId"
        const val DEVICE_LABEL = "$ID/deviceLabel"
        const val AUTO_IMPORT = "$ID/autoImport"
        const val LAST_RUN_AT = "$ID/lastRunAt"
        const val LAST_STATUS = "$ID/lastStatus"
        const val LAST_ITEM_COUNT = "$ID/lastItemCount"
    }
}

/**
 * Content-addresses a [HealthSource] by its [HealthSource.key], so re-registering the same
 * connection on the same device folds to a single row rather than duplicating.
 */
interface HealthSourceDataIdProvider : CompositeDataIdProvider<HealthSource> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/source/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = HealthSource.Fields.KEY
        )
    }
}
