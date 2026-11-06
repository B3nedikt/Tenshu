package org.tenshu.core.file.sync

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId

/**
 * The chain-DSL-native, typed **projection** of one device's file-synchronisation
 * settings — the first-class `serviceDataRelation` follow-up to the
 * `FileSynchronisationSettings` facade (`docs/proposals/drive-offline-online.md`
 * §"Follow-up — a first-class `serviceDataRelation`").
 *
 * One row per device, folding the two underlying preference rows
 * (`file.sync.defaultPolicy` + `file.sync.overrides`, both
 * `DeviceSetting`s — see [FileSyncPreferences]) into a single typed entity so a
 * caller can read `preferenceService.fileSyncSettings.search { deviceId equalTo … }`
 * and pull `defaultPolicy` straight off the schema, with no generic-`Data` decode.
 *
 * **This is a derived projection, not a synced source of truth.** The
 * `DeviceSetting` rows are the `Syncable(Eager)` originals; on every device a
 * proxy-side `FileSyncSettingsRelationMaintainer` re-derives this projection on
 * each (local or sync-arriving) file-sync setting save — exactly as `Share` /
 * `FileModification` collections are re-derived. So `FileSyncSetting` carries no
 * `Syncable` trait: it never goes on the wire.
 *
 * - [defaultPolicy] is a [SyncPolicy] constant, held directly in a
 *   `composite(DEFAULT_POLICY, type = SyncPolicy.Schema)` field — self-describing,
 *   readable field-by-field through the schema.
 * - [overrideSet] is a [PathSyncOverrideSet] composite (the §4.10 promotion of the
 *   former opaque blob), so Control reads each override field-by-field with no
 *   shared codec; decode it back to the value form with [ruleSet].
 *
 * ⚠ The overrides ride a **composite scalar field**, not a native `list`, on
 * purpose: a list field on a *keyed* row (the projection is addressed by deviceId)
 * reads back as a lazy `ArrayDatabaseReference` that a plain `search().get()`
 * doesn't resolve — so the list would arrive empty over the SDK chain (the
 * lazy-vs-materialised seam). A composite scalar field is deSerialised eagerly with
 * a null dataId, which materialises its own nested list, so the whole override set
 * survives the chain read. The wrapper carries the list; see [PathSyncOverrideSet].
 */
interface FileSyncSetting : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The device this projection describes (matches `DeviceSetting.deviceId`). */
    val deviceId: TString
        get() = getField(Field.DEVICE_ID)

    /** This device's default [SyncPolicy] (a self-describing constant). */
    val defaultPolicy: SyncPolicy
        get() = getField(Field.DEFAULT_POLICY)

    /** The sparse override set, wrapped in a [PathSyncOverrideSet] composite. */
    val overrideSet: PathSyncOverrideSet
        get() = getField(Field.OVERRIDES)

    companion object {
        const val ID = "tenshu/file/sync/setting"

        /**
         * The cross-config `serviceDataRelation` id hung off `PreferenceService`
         * (registered from `:file-service-common`, not the preference schema). The
         * client reads it as `preferenceService.fileSyncSettings`.
         */
        const val RELATION = "tenshu/file/sync/settings"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(FileSyncSettingDataIdProvider.Schema),
            fields = {
                string(Field.DEVICE_ID)
                composite(Field.DEFAULT_POLICY, type = SyncPolicy.Schema)
                // A composite scalar field (NOT a native list) — see the class KDoc:
                // a list on a keyed row reads back as a lazy reference a plain
                // `search().get()` won't resolve, so it would arrive empty over the
                // chain. The wrapper's own nested list materialises eagerly here.
                composite(Field.OVERRIDES, type = PathSyncOverrideSet.Schema)
            }
        )

        /**
         * Builds the typed projection for [deviceId] from the resolved
         * [defaultPolicy] + [overrides]. The policy constant is stored directly (no
         * codec); overrides are wrapped in a [PathSyncOverrideSet] composite.
         */
        fun create(
            context: TContext,
            deviceId: String,
            defaultPolicy: SyncPolicy,
            overrides: List<PathSyncOverride>,
        ): FileSyncSetting = context.create(
            type = ID,
            fields = listOf(
                deviceId,
                defaultPolicy,
                PathSyncOverrideSet.create(context, overrides),
            ),
        )
    }

    object Field {
        const val DEVICE_ID = "$ID/deviceId"
        const val DEFAULT_POLICY = "$ID/defaultPolicy"
        const val OVERRIDES = "$ID/overrides"
    }
}

// Entity-level accessors mirroring the Share / FileModification convention so the
// chain DSL can resolve fields (and `search { deviceId equalTo … }`) without the
// composite being materialised.
val Entity<out FileSyncSetting>.deviceId: TString
    get() = getFieldValue(FileSyncSetting.Field.DEVICE_ID)

val Entity<out FileSyncSetting>.defaultPolicy: SyncPolicy
    get() = getFieldValue(FileSyncSetting.Field.DEFAULT_POLICY)

val Entity<out FileSyncSetting>.overrideSet: PathSyncOverrideSet
    get() = getFieldValue(FileSyncSetting.Field.OVERRIDES)

/**
 * Decodes the projection back into a [SyncRuleSet]. The policy is read straight off
 * the schema (a constant); the overrides are converted from their persisted
 * [PathSyncOverrideSet] composite to the context-free value list. Pure — no IO.
 */
fun FileSyncSetting.ruleSet(): SyncRuleSet = SyncRuleSet(
    deviceDefault = defaultPolicy,
    overrides = overrideSet.toValues(),
)

/**
 * Keys [FileSyncSetting] projections by their `deviceId` — one row per device.
 * Mirrors `ShareDataIdProvider` / `DeviceSettingDataIdProvider`.
 */
interface FileSyncSettingDataIdProvider : CompositeDataIdProvider<FileSyncSetting> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/file/sync/setting/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = FileSyncSetting.Field.DEVICE_ID
        )
    }
}
