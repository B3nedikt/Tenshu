package org.tenshu.core.file.sync

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId

/**
 * The **persisted** form of a [PathSyncOverride] (`docs/proposals/drive-offline-online.md`
 * §4.10) — a registered `Composite` so the override set is a self-describing
 * `TList<PathSyncOverrideEntry>` that Control reads field-by-field with no shared
 * codec, superseding the old length-prefixed byte blob.
 *
 * Distinct from [PathSyncOverride], which stays a context-free value form for the
 * pure resolvers and UI (see [PathSyncOverride]'s KDoc — the seam). This composite
 * exists only at the storage boundary; convert with [toEntry] / [toValue].
 *
 * [policy] is a `composite(POLICY, type = SyncPolicy.Schema)` field — a
 * [SyncPolicy] constant read straight through the schema. Keyed by [pathPrefix]
 * via [PathSyncOverrideEntryDataIdProvider] (an exact prefix identifies an
 * override, so a re-set folds to one row — the sparse invariant). Value-lists
 * aren't serialisable, so a list member must be a composite carrying a
 * DataIdProvider (the `TripMember` shape).
 */
interface PathSyncOverrideEntry : Composite {

    override val schema: CompositeSchema get() = Schema

    val pathPrefix: TString
        get() = getField(Field.PATH_PREFIX)

    val policy: SyncPolicy
        get() = getField(Field.POLICY)

    companion object {
        const val ID = "tenshu/file/sync/pathOverride"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(PathSyncOverrideEntryDataIdProvider.Schema),
            fields = {
                string(Field.PATH_PREFIX)
                composite(Field.POLICY, type = SyncPolicy.Schema)
            }
        )

        fun create(context: TContext, pathPrefix: String, policy: SyncPolicy): PathSyncOverrideEntry =
            context.create(type = ID, fields = listOf(pathPrefix, policy))

        fun create(context: TContext, value: PathSyncOverride): PathSyncOverrideEntry =
            create(context, value.pathPrefix, value.policy)
    }

    object Field {
        const val PATH_PREFIX = "$ID/pathPrefix"
        const val POLICY = "$ID/policy"
    }
}

val Entity<out PathSyncOverrideEntry>.pathPrefix: TString
    get() = getFieldValue(PathSyncOverrideEntry.Field.PATH_PREFIX)

/** Reads this persisted entry back into the context-free [PathSyncOverride] value form. */
fun PathSyncOverrideEntry.toValue(): PathSyncOverride =
    PathSyncOverride(pathPrefix = pathPrefix.get(), policy = policy)

/** Content-addresses a [PathSyncOverrideEntry] by its [PathSyncOverrideEntry.pathPrefix]. */
interface PathSyncOverrideEntryDataIdProvider : CompositeDataIdProvider<PathSyncOverrideEntry> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/file/sync/pathOverride/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = PathSyncOverrideEntry.Field.PATH_PREFIX
        )
    }
}

/**
 * A wrapper composite holding the override [overrides] list — the form the sparse
 * override set takes **as a preference value** (`FileSyncPreferences.Overrides`).
 * The polymorphic `genericData` `Setting.value` slot stores an inline primitive or
 * a *composite reference*, but **not** a bare array (`PreferenceAdapter` docstring),
 * so the list must be wrapped in one composite. Used only by
 * `PathSyncOverrideSetAdapter`; the `FileSyncSetting` projection carries the list
 * as a native field instead.
 *
 * Carries a **content-derived [key]** + a [PathSyncOverrideSetDataIdProvider]: a
 * composite with a list field needs its own DataId so the DB can address the list
 * (`EntityFieldDataId(parent = this, fieldRef = overrides)`), and a content key
 * makes the entry safely shareable/immutable — two devices with identical override
 * sets fold to one entry, a change mints a fresh one (no cross-device clobber).
 */
interface PathSyncOverrideSet : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Deterministic digest of the override set — the content-addressed identity. */
    val key: TString
        get() = getField(Field.KEY)

    val overrides: TList<PathSyncOverrideEntry>
        get() = getOptionalField(Field.OVERRIDES) ?: SimpleTList(emptyList())

    companion object {
        const val ID = "tenshu/file/sync/pathOverrideSet"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(PathSyncOverrideSetDataIdProvider.Schema),
            fields = {
                string(Field.KEY)
                // Optional so an empty override set (which serialises to an absent
                // list field) round-trips as an empty list rather than tripping the
                // mandatory-field read. New schema → no peer to break.
                list(Field.OVERRIDES, of = PathSyncOverrideEntry.Schema, isOptional = true)
            }
        )

        fun create(context: TContext, overrides: List<PathSyncOverride>): PathSyncOverrideSet =
            context.create(
                type = ID,
                fields = listOf(
                    contentKey(overrides),
                    overrides.map { PathSyncOverrideEntry.create(context, it) },
                ),
            )

        /**
         * A deterministic, collision-safe digest of the override set (order-independent):
         * sorted `prefix + policyId` pairs joined by control chars a path can't contain. Empty set → `""`.
         */
        fun contentKey(overrides: List<PathSyncOverride>): String =
            overrides
                .sortedBy { it.pathPrefix }
                .joinToString("\u0001") { "${it.pathPrefix}\u0002${it.policy.schema.id.get()}" }
    }

    object Field {
        const val KEY = "$ID/key"
        const val OVERRIDES = "$ID/overrides"
    }
}

/** Reads the wrapped entries back into the context-free [PathSyncOverride] value list. */
fun PathSyncOverrideSet.toValues(): List<PathSyncOverride> =
    overrides.toList().map { it.toValue() }

/** Content-addresses a [PathSyncOverrideSet] by its derived [PathSyncOverrideSet.key]. */
interface PathSyncOverrideSetDataIdProvider : CompositeDataIdProvider<PathSyncOverrideSet> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/file/sync/pathOverrideSet/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = PathSyncOverrideSet.Field.KEY
        )
    }
}
