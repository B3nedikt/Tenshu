package org.tenshu.storage

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId

/**
 * The current storage-provision snapshot for the user, per
 * `docs/proposals/storage-capacity-gating.md` §3. Carries the two halves of the
 * gate: whether storage is available at all ([hasStorage]) and the capacity
 * figures ([usedBytes] / [limitBytes]).
 *
 * **Locally-derived, NOT `Syncable`.** Unlike `RealmSetting`, this is not a
 * synced primitive — each device's proxy derives it from the entitlement (limit)
 * + relay-reported usage and maintains its own single row. There is exactly one
 * row, keyed on the constant [SINGLETON_KEY] so the proxy upsert replaces rather
 * than appends (one provider per user — see [StorageService]).
 *
 * `usedBytes` / `limitBytes` are `TInteger` (Long-backed) so they hold byte
 * counts beyond the 32-bit range (a 50 GiB limit overflows `Int`).
 */
interface StorageStatus : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable single-row key; always [SINGLETON_KEY]. */
    val key: TString
        get() = getField(Field.KEY)

    /** True iff a storage provider / entitlement is bound. */
    val hasStorage: TBoolean
        get() = getField(Field.HAS_STORAGE)

    val usedBytes: TInteger
        get() = getField(Field.USED_BYTES)

    val limitBytes: TInteger
        get() = getField(Field.LIMIT_BYTES)

    companion object {
        const val ID = "tenshu/storage/status"

        /** The one and only row's key (one provider per user, v1). */
        const val SINGLETON_KEY = "current"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                StorageStatusDataIdProvider.Schema,
            ),
            fields = {
                string(Field.KEY)
                boolean(Field.HAS_STORAGE)
                int(Field.USED_BYTES)
                int(Field.LIMIT_BYTES)
            },
        )

        fun create(
            context: TContext,
            hasStorage: Boolean,
            usedBytes: Long,
            limitBytes: Long,
        ): StorageStatus = context.create(
            type = ID,
            fields = listOf(SINGLETON_KEY, hasStorage, usedBytes, limitBytes),
        )
    }

    object Field {
        const val KEY = "$ID/key"
        const val HAS_STORAGE = "$ID/hasStorage"
        const val USED_BYTES = "$ID/usedBytes"
        const val LIMIT_BYTES = "$ID/limitBytes"
    }
}

/**
 * Stable identity for the single [StorageStatus] row — keyed on [StorageStatus.Field.KEY]
 * (always [StorageStatus.SINGLETON_KEY]), so the proxy's relation upsert
 * dedupes to one row. Mirrors `RealmSettingDataIdProvider`.
 */
interface StorageStatusDataIdProvider : CompositeDataIdProvider<StorageStatus> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/storage/status/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = StorageStatus.Field.KEY,
        )
    }
}
