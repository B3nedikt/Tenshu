package org.tenshu.core.common.schema.migration

import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext

/*
 * Shared to-bound overlay materialization for multi-step migration chains
 * (`core-schema-evolution.md` §8.5 "⚠ For E2b" / §8.6): a step's `migrate`
 * must NOT hand a live-schema dto to the next step's decode — intermediates
 * are materialised as [SnapshotComposite]s bound to the step's `to` snapshot,
 * so field access on the output maps positions under TO order even when `to`
 * differs from the live schema. Extracted from `StoreRowMigrationCodec` (E2b)
 * so the history-upcast seam (E2c, `PayloadUpcaster`) runs the identical
 * shape instead of duplicating it.
 */

/**
 * Encode-side overlay for one step: `create(typeId, …)` for a type this step
 * migrates yields a [SnapshotComposite] bound to the step's `to` snapshot;
 * everything else resolves live.
 */
internal fun SchemaMigration.toBoundOverlay(live: SchemaRegistry): SchemaOverlayProvider =
    SchemaOverlayProvider(
        liveSchemas = live,
        liveFactory = live,
        snapshots = migrations.associate { it.typeId to it.to },
    )

/**
 * A [MigrationContext] whose factory runs under [toBoundOverlay], so a
 * generated or hand-written [CompositeMigration.migrate] building via
 * `context.context.create` produces a `to`-bound intermediate. Correct for
 * every step of a chain (the last step's `to` structurally equals the live
 * schema); seams whose output must be a *registered live dto* (the wire
 * seam's final step) use a plain live [MigrationContext] instead.
 */
internal fun SchemaMigration.toBoundMigrationContext(
    live: SchemaRegistry,
    baseContext: TContext,
    deviceId: String,
): MigrationContext = MigrationContext(
    context = OverlayTContext(baseContext, toBoundOverlay(live)),
    deviceId = deviceId,
)

/**
 * The base context with a schema overlay swapped in for the [DataFactory] and
 * [SchemaProvider] components, so `context.create` inside a migration
 * materialises migrated types bound to the overlay's snapshots (see the
 * multi-step note above). Everything else (traits, …) resolves through the
 * base context.
 */
internal class OverlayTContext(
    base: TContext,
    overlay: SchemaOverlayProvider,
) : TContext {

    /**
     * ⚠⚠ **A lookup view, never `base.components + overrides`.** The production
     * [TContext.components] is a *lazy view over the DI graph* — every `get(key)`
     * is a container lookup that may CREATE the singleton. `+` copies the map,
     * which forces `entries`, which resolves **every** component: the interactor
     * provider, the uploader, the on-demand resolver, the crypto manager. A
     * migration needs none of them; it needs the two ids overridden here.
     *
     * That mattered enough to deadlock the app (E2j-4,
     * `core-schema-evolution.md` §8.9). The store opens *inside* a DI factory,
     * so during a store-open migration the graph is **mid-construction** on the
     * calling thread, while `SqliteObjectStore.open`'s `withContext(IO)` runs the
     * migration on a worker. The `+` made that worker demand exactly the
     * singletons the calling thread was already building and holding locks for:
     * neither could finish. Android hung at startup (ANR "failed to complete
     * startup"), iOS died in the same resolution with `SIGSEGV`.
     *
     * A per-key view also stops re-resolving the whole component set for every
     * value a migration creates, which is the same fix twice over.
     */
    private val view: Map<String, TComponent> = OverlayComponents(
        base = base,
        overrides = mapOf(
            DataFactory.COMPONENT_ID to overlay,
            SchemaProvider.COMPONENT_ID to overlay,
        ),
    )

    override val components: Map<String, TComponent> get() = view
}

/**
 * Overrides first, then the base — resolving **one** key, never the whole map.
 * The bulk accessors ([entries]/[values]) are still correct if something asks,
 * but nothing on the migration path does, and asking re-enters the DI graph.
 */
private class OverlayComponents(
    private val base: TContext,
    private val overrides: Map<String, TComponent>,
) : Map<String, TComponent> {

    override val keys: Set<String> get() = base.components.keys + overrides.keys
    override val size: Int get() = keys.size
    override fun isEmpty(): Boolean = keys.isEmpty()
    override fun containsKey(key: String): Boolean = keys.contains(key)
    override fun containsValue(value: TComponent): Boolean = values.contains(value)

    override fun get(key: String): TComponent? = overrides[key] ?: base.components[key]

    override val values: Collection<TComponent> get() = keys.mapNotNull { get(it) }

    override val entries: Set<Map.Entry<String, TComponent>>
        get() = keys.mapNotNull { key ->
            val resolved = get(key) ?: return@mapNotNull null
            object : Map.Entry<String, TComponent> {
                override val key: String = key
                override val value: TComponent = resolved
            }
        }.toSet()
}
