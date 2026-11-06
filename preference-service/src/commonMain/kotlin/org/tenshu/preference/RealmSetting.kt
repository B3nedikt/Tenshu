package org.tenshu.preference

import org.tenshu.core.Data
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.genericData
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * Realm-scoped setting row — one per [preferenceId], visible to every paired
 * device. Writes from any device propagate via the regular sync layer; the
 * `Syncable(Eager)` trait emits one outbound `SyncBlock` on save and
 * `LastWriterWins` resolves concurrent edits at the HLC layer.
 *
 * The stored [value] is a polymorphic `Data` field; see [Setting] KDoc on why
 * the consumer-facing type lives in the [Preference] declaration's
 * [PreferenceAdapter] rather than on the schema.
 *
 * Use [Companion.create] to construct from a [TContext]; consumers normally
 * go through the typed `PreferenceService.set(pref, value)` ergonomics in
 * `:preference-service-client` and never touch this type directly.
 */
interface RealmSetting : Setting {

    override val schema: CompositeSchema get() = Schema

    companion object {
        const val ID = "tenshu/preference/realmSetting"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Setting.Schema,
            traits = listOf(
                RealmSettingDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager)
            ),
            fields = {
                string(Setting.Field.PREFERENCE_ID)
                genericData(Setting.Field.VALUE)
            }
        )

        fun create(
            context: TContext,
            preferenceId: String,
            value: Data,
        ): RealmSetting = context.create(
            type = ID,
            fields = listOf(preferenceId, value),
        )
    }
}

interface RealmSettingDataIdProvider : CompositeDataIdProvider<RealmSetting> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/preference/realmSetting/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Setting.Field.PREFERENCE_ID
        )
    }
}
