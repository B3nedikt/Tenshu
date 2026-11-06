package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId

/**
 * The typed signal for the **realm schema pull fence**
 * (`core-schema-evolution.md` §7.3, E2d-2): this device's schema version vector is
 * below the realm's epoch for at least one service it knows, so it has stopped
 * pulling rather than garble blocks it cannot parse. Local reads keep working —
 * the device is *read-only-stale*, not bricked — and it **keeps pushing** (§7.3's
 * asymmetric contract: up-to-date peers upcast old blocks, so a pull-fence with
 * push-allow strands nothing).
 *
 *  - [fenced] — the verdict: `true` while at least one service fences.
 *  - [fencedServiceIds] — *which* services, so a consumer can name the app(s) to
 *    update ("update Fit") rather than showing an unactionable generic warning.
 *
 * ## Locally-derived, **NOT `Syncable`** — nothing here reaches the wire
 *
 * Exactly like [SyncHealthStatus]: each device's proxy derives its own single row,
 * keyed on the constant [SINGLETON_KEY] so the relation upsert replaces rather
 * than appends. The row is a *verdict about this device*, so it is meaningless on
 * another one; it never enters a `SyncBlock`, never reaches the relay (House Rule
 * #1 untouched), and crosses only the already-authenticated app↔Control IPC
 * channel. Consequently this type owes **no `TENSHU_STORAGE_FORMAT_VERSION` bump**
 * (HR #9): it reshapes no stored row and adds no wire shape. Contrast
 * `RealmSchemaEpoch`, which *is* `Syncable` and did bump (v14→v15, §7.6).
 *
 * ## v1 is the signal only — no UI
 *
 * There is deliberately no banner, string catalogue entry or ViewModel in this
 * slice; the fence's user-facing "update this device" surface is a follow-up,
 * exactly as [SyncHealthStatus] shipped one slice ahead of Drive's banners.
 * Publishing the typed row first keeps that follow-up a pure UI change.
 *
 * ## Adding to it later
 *
 * This is a **positional composite**: appending a field shifts the layout and
 * breaks a version-skewed app↔Control pair (two separately-installed apps). Richer
 * detail the banner may eventually want — per-service realm-vs-device versions, a
 * "which side is behind" discriminator — therefore goes in a NEW composite id, not
 * as fields here (the §7.1/§6.2 discipline, HR #9's positional-composite rule).
 */
interface SchemaFenceStatus : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable single-row key; always [SINGLETON_KEY]. */
    val key: TString
        get() = getField(Field.KEY)

    /**
     * `true` while the realm's epoch exceeds this device's declared version for at
     * least one service this device knows — i.e. while the pull is fenced.
     */
    val fenced: TBoolean
        get() = getField(Field.FENCED)

    /**
     * The offending service ids, [SERVICE_ID_SEPARATOR]-delimited and sorted;
     * empty while [fenced] is `false`. Decode with [fencedServices].
     *
     * ⚠ **A delimited string, not a `TList<TString>` field, and deliberately so.**
     * The local store persists a list field's elements as their own rows, so every
     * member needs a `DataIdProvider` — the same store-layer constraint that made
     * `RealmSchemaEpoch` carry its vector as bytes (§7.6), and the reason
     * `StringListAdapter` encodes preference string-lists into a blob rather than a
     * list field. Service ids are path-like (`tenshu/sync/service`) and can never
     * contain the separator, so the encoding is lossless without escaping.
     */
    val fencedServiceIds: TString
        get() = getField(Field.FENCED_SERVICE_IDS)

    companion object {
        const val ID = "tenshu/sync/schema/fenceStatus"

        /** The one and only row's key. */
        const val SINGLETON_KEY = "current"

        /** Separator of [fencedServiceIds]; never occurs inside a service id. */
        const val SERVICE_ID_SEPARATOR = ","

        /**
         * The reserved id under which a **core-version** fence is reported
         * (`core-schema-evolution.md` §7.11). ⚠ Not a real service id — no
         * `ServiceSchema` declares it, and it will never collide with one, because
         * every real id is `tenshu/<service>/service`.
         *
         * Core skew is the one hazard the per-service vector cannot name: it is the
         * global store/wire floor, so a rise means the shared primitives moved and the
         * damage is not attributable to any single service. Reporting it in this list
         * rather than as a new field is deliberate — this composite's positional shape
         * is consumed by version-skewed peers over IPC and can never gain a field — and
         * it keeps `fenced == fencedServiceIds.isNotEmpty()` true, which is how the
         * verdict is derived.
         */
        const val CORE_SERVICE_ID = "tenshu/core"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                SchemaFenceStatusDataIdProvider.Schema,
            ),
            fields = {
                string(Field.KEY)
                boolean(Field.FENCED)
                string(Field.FENCED_SERVICE_IDS)
            },
        )

        fun create(
            context: TContext,
            fenced: Boolean,
            fencedServiceIds: List<String>,
        ): SchemaFenceStatus = context.create(
            type = ID,
            fields = listOf(
                SINGLETON_KEY,
                fenced,
                fencedServiceIds.sorted().joinToString(SERVICE_ID_SEPARATOR),
            ),
        )
    }

    object Field {
        const val KEY = "$ID/key"
        const val FENCED = "$ID/fenced"
        const val FENCED_SERVICE_IDS = "$ID/fencedServiceIds"
    }
}

/** The offending service ids as a list — the read side of [SchemaFenceStatus.fencedServiceIds]. */
fun SchemaFenceStatus.fencedServices(): List<String> =
    fencedServiceIds.get()
        .split(SchemaFenceStatus.SERVICE_ID_SEPARATOR)
        .filter { it.isNotBlank() }

/**
 * Stable identity for the single [SchemaFenceStatus] row — keyed on
 * [SchemaFenceStatus.Field.KEY] (always [SchemaFenceStatus.SINGLETON_KEY]) so the
 * proxy's relation upsert dedupes to one row. Mirrors [SyncHealthStatusDataIdProvider].
 */
interface SchemaFenceStatusDataIdProvider : CompositeDataIdProvider<SchemaFenceStatus> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/sync/schema/fenceStatus/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = SchemaFenceStatus.Field.KEY,
        )
    }
}
