package org.tenshu.core.common.schema.migration

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TConstant
import org.tenshu.core.TValue
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.EntityDto
import org.tenshu.core.common.impl.reference.CompositeTypeReferenceDto
import org.tenshu.core.common.impl.reference.FieldReferenceDto
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.core.createEntity
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.reference.Reference
import org.tenshu.core.schema.CompositeSchema
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Seam 2 of the §8.4 orchestrator — the lazy relay-history upcast
 * (`core-schema-evolution.md` §8.2, E2c): when a block's E0/E1 stamp names a
 * per-service version BELOW the receiver's declared version for a service
 * with registered migration chains, the payload is decoded under the
 * *producing* schemas and upcast value-by-value to current before
 * materialization. Blocks are immutable ciphertext and never rewritten; the
 * upcast runs per-block in the one shared decode funnel
 * (`SyncProcessor.decryptAndParseCipher`).
 *
 * Fast path (zero migration cost, today's production reality): when no
 * stamped service with a registered chain is behind, the payload parses with
 * the ordinary live parser and passes through untouched.
 *
 * Slow path: the payload tree parses under a from-snapshot overlay — the
 * payload envelopes (`DataBlockPayload`, `DeltaBatchPayload`,
 * `FileContentManifestPayload`) embed entity/property values through
 * **generic `Data` fields**, which serialize with an inline 16-byte type tag,
 * so overlaying the migrated type ids' frozen `from` snapshots makes the
 * WHOLE tree decode correctly: envelope types resolve live, migrated
 * embedded values materialize as [SnapshotComposite]s with the OLD positional
 * order. The materialized tree is then walked **uniformly by type id** (no
 * per-payload-branch knowledge): each embedded value of a migrated type runs
 * through its chain's [CompositeMigration.migrate] steps value-to-value —
 * intermediates bound to each step's `to` snapshot ([OverlayTContext], the
 * §8.5 multi-step invariant), the final step building via the live context
 * (last `to` == live) so the output is a registered dto the ingest pipeline
 * can merge and store.
 *
 * Drop semantics — deliberate, never a parse accident (§8.4):
 * - a below-floor stamp drops the whole block BY DECISION;
 * - a `migrate` returning `null` (e.g. `dropType`) drops that value: a list
 *   element (a `SyncDelta` in a batch) is removed and the rest applies; a
 *   required field's value (a `DataBlockPayload.value`) drops its parent —
 *   ultimately the block. Every decision surfaces in the result so the
 *   caller can log it; malformed bytes still throw (the trial-decrypt loop
 *   in `SyncProcessor` relies on parse failures being non-fatal).
 *
 * Known bounds, inherited from [SchemaMigrationRegistry.verify]:
 * - **Statically-typed nesting of a migrated type is impossible by
 *   construction** — `verify` rejects a migrated type embedded as a
 *   composite/list field of another live type ("nested reshapes not yet
 *   supported"), so only generic-`Data`-carried values (payload `value`
 *   slots, entity field maps, `genericData` fields reached through them)
 *   ever need migrating here. The walk covers those uniformly.
 * - Migrating a payload *envelope* type itself falls out of the same
 *   uniform dispatch (the top-level value is upcast like any other), but a
 *   reshaped envelope would break every pre-E1 peer at once — envelope
 *   changes remain lockstep territory.
 *
 * Core-only stamps (`stampBytes` empty — the E0-era test convenience; every
 * production emitter since E1 writes the full vector): with chains
 * registered, such a stamp cannot prove which per-service versions produced
 * the payload, so it is dropped BY DECISION rather than guessed at. (With no
 * chains registered this class is never consulted — `SyncProcessor` keeps
 * the plain live parse.)
 *
 * A stamp naming a service version *ahead* of ours is deliberately NOT
 * judged here — old-never-reads-new is the epoch pull-fence's duty (§7.3,
 * E2d); until it lands such payloads take the live parse and fail (or
 * garble-drop) exactly as today.
 */
@OptIn(ExperimentalAtomicApi::class)
class PayloadUpcaster(
    private val live: SchemaRegistry,
    private val context: TContext,
    /**
     * Read per migration invocation (never cached): [MigrationContext.deviceId]
     * for derive/convert closures. Empty until the realm has an identity —
     * honest, since a receiver without a realm context ingests nothing.
     */
    private val deviceIdProvider: () -> String,
) {

    /** The upcast verdict for one block payload. */
    sealed interface UpcastResult {
        /**
         * The payload to apply — parsed live on the fast path, rebuilt at the
         * current shape on the slow path. [droppedValues] names any *nested*
         * values dropped BY DECISION on the way (e.g. a retired-type delta
         * removed from a surviving batch); the caller logs them.
         */
        class Current(val payload: Data, val droppedValues: List<String> = emptyList()) : UpcastResult

        /** The whole block payload is dropped BY DECISION; [reason] names type/version. */
        class Dropped(val reason: String) : UpcastResult
    }

    private val migrations = SchemaMigrationRegistry().apply {
        live.schemaMigrations().forEach(::register)
        // The init-time fail-fast (§8.4): drifted snapshots refuse ingest
        // loudly instead of silently mis-migrating history. Idempotent with
        // the store-open seam's verify (E2b) — wirings without a configured
        // store still get the check here.
        verify(live)
    }

    private val liveParser = BinaryParser(live, live)

    /**
     * serviceId → declared current version, restricted to services with
     * registered chains — the only services this seam ever judges. Computed
     * lazily so construction may precede late module registration; a chain
     * whose service registered no [org.tenshu.core.schema.ServiceSchema] has
     * no declared current to compare against and is inert here (the same
     * carve-out `verify` applies).
     */
    private val currentWithChains: Map<String, Int> by lazy {
        val chainServices = live.schemaMigrations().map { it.serviceId }.toSet()
        live.serviceSchemas()
            .filter { it.id.get() in chainServices }
            .associate { it.id.get() to it.version }
    }

    // Copy-on-write window cache: the same behind-vector recurs for every
    // block of one emitter's backlog. A CAS race costs one duplicate window
    // build, never corruption (the SchemaRegistry materialization pattern).
    private val windows = AtomicReference<Map<Map<String, Int>, UpcastWindow>>(emptyMap())

    /**
     * Copy-on-write cache of parsed stamps — the same reason [windows] has one, and it matters
     * far more.
     *
     * ⚠⚠ **Parsing the stamp cost 80 ms per block, ten times the payload it accompanies.**
     * Measured on a real iPhone 2026-08-05 while chasing a ~100 ms/block ingest rate:
     * `meanStampUs=80462 meanParseUs=8438`, and on the **fast path** — where there is no
     * migration work at all — so every block of a backlog paid it to re-learn something it
     * already knew. It made catch-up ~5x slower than necessary: 500 blocks/tick took ~55 s, of
     * which ~40 s was re-parsing the same handful of version vectors.
     *
     * A stamp is the emitting build's whole `VersionVector`, so a realm holds as many distinct
     * stamps as it has emitting builds — a handful — and consecutive blocks of one emitter's
     * backlog share one exactly. A short list scanned by content is therefore a near-100% hit
     * rate, and cheaper than hashing: `contentEquals` over ~100 bytes is nanoseconds against the
     * 80 ms it replaces.
     *
     * ⚠ Keyed by CONTENT, never by the array. `ByteArray` equality is reference-based, so a
     * `Map<ByteArray, _>` would miss on every call and silently cache nothing — the failure mode
     * would be invisible, since it is a pure optimisation.
     *
     * Bounded ([MAX_CACHED_STAMPS]) so "a realm cannot accumulate unbounded stamps" is structural
     * rather than assumed; oldest-out, because the current fleet's stamps are the recurring ones.
     * A CAS race costs one duplicate parse, never corruption — [windows]'s rule.
     */
    private val stamps = AtomicReference<List<Pair<ByteArray, Map<String, Int>>>>(emptyList())

    /**
     * Parse [payloadBytes] under the schemas [stampBytes] names, upcast to
     * current. [stampBytes] is the envelope's serialized frozen
     * [VersionVector] (empty = core-only stamp). Throws on malformed bytes —
     * the caller treats that exactly like today's parse failure.
     */
    fun upcast(payloadBytes: ByteArray, stampBytes: ByteArray): UpcastResult {
        val stamp = if (stampBytes.isEmpty()) null else stampOf(stampBytes)
        if (stamp == null) {
            // Core-only stamp under registered chains: the per-service
            // versions that produced this payload are unknowable — drop BY
            // DECISION rather than guess (see class KDoc).
            return if (currentWithChains.isEmpty()) {
                UpcastResult.Current(liveParser.parse(payloadBytes))
            } else {
                UpcastResult.Dropped(
                    "core-only stamp names no per-service versions while migration chains are " +
                        "registered for ${currentWithChains.keys.sorted()} — the payload's " +
                        "producing shape cannot be proven"
                )
            }
        }

        val behind = mutableMapOf<String, Int>()
        for ((serviceId, current) in currentWithChains) {
            // A service absent from the stamp did not exist on the emitter, so
            // the payload cannot carry its types — nothing to migrate.
            val stamped = stamp[serviceId] ?: continue
            val floor = migrations.floorFor(serviceId, current)
            when {
                stamped < floor -> return UpcastResult.Dropped(
                    "stamped '$serviceId' v$stamped is below the readable floor v$floor " +
                        "(current v$current) — the migration chain no longer reaches it"
                )
                stamped < current -> behind[serviceId] = stamped
                // stamped >= current: current — or ahead, which is E2d's fence.
            }
        }
        if (behind.isEmpty()) return UpcastResult.Current(liveParser.parse(payloadBytes))

        val window = windowFor(behind)
        val parsed: Data = window.parser.parse(payloadBytes)
        val droppedValues = mutableListOf<String>()
        return when (val outcome = window.upcastValue(parsed, droppedValues)) {
            is ValueOutcome.Dropped -> UpcastResult.Dropped(outcome.reason)
            is ValueOutcome.Changed -> UpcastResult.Current(outcome.value, droppedValues)
            ValueOutcome.Unchanged -> UpcastResult.Current(parsed, droppedValues)
        }
    }

    /**
     * How many stamps this instance actually parsed — a **test seam**, so the cache's whole point
     * is assertable. Without it a regression here is invisible: re-parsing every stamp is still
     * *correct*, just 80 ms/block slower, so no behavioural test can catch it.
     */
    var stampParses: Int = 0
        private set

    /** The stamp's `serviceId -> version` map, parsed at most once per distinct stamp. */
    private fun stampOf(stampBytes: ByteArray): Map<String, Int> {
        stamps.load().firstOrNull { it.first.contentEquals(stampBytes) }?.let { return it.second }
        val parsed = liveParser.parse<VersionVector>(stampBytes).toMap()
        stampParses++
        while (true) {
            val current = stamps.load()
            current.firstOrNull { it.first.contentEquals(stampBytes) }?.let { return it.second }
            val grown = current + (stampBytes to parsed)
            val next =
                if (grown.size > MAX_CACHED_STAMPS) grown.drop(grown.size - MAX_CACHED_STAMPS)
                else grown
            if (stamps.compareAndSet(current, next)) return parsed
        }
    }

    private fun windowFor(behind: Map<String, Int>): UpcastWindow {
        windows.load()[behind]?.let { return it }
        val built = UpcastWindow(behind)
        while (true) {
            val current = windows.load()
            current[behind]?.let { return it }
            if (windows.compareAndSet(current, current + (behind to built))) return built
        }
    }

    private sealed interface ValueOutcome {
        /** Value already at the current shape — reuse the instance. */
        data object Unchanged : ValueOutcome

        /** Value (or something inside it) was upcast; use [value]. */
        class Changed(val value: Data) : ValueOutcome

        /** Value dropped BY DECISION ([CompositeMigration.migrate] returned null). */
        class Dropped(val reason: String) : ValueOutcome
    }

    /** One [CompositeMigration] of the window, with its step's encode overlay. */
    private class ChainEntry(
        val serviceId: String,
        val fromVersion: Int,
        val migration: CompositeMigration,
        val encodeOverlay: SchemaOverlayProvider,
    )

    /**
     * The prepared machinery for one behind-vector: per migrated type the
     * ordered chain entries covering `stamp → current`, and the decode
     * overlay parser keyed on each type's *earliest* `from` in the window
     * (per-type chain integrity guarantees that IS the type's shape at the
     * stamped version).
     */
    private inner class UpcastWindow(behind: Map<String, Int>) {

        private val chains: Map<String, List<ChainEntry>>

        val parser: BinaryParser

        init {
            val entries = mutableMapOf<String, MutableList<ChainEntry>>()
            behind.forEach { (serviceId, stamped) ->
                val current = currentWithChains.getValue(serviceId)
                migrations.stepsFor(serviceId, stamped, current).forEach { step ->
                    val encodeOverlay = step.toBoundOverlay(live)
                    step.migrations.forEach { migration ->
                        entries.getOrPut(migration.typeId) { mutableListOf() } +=
                            ChainEntry(serviceId, step.fromVersion, migration, encodeOverlay)
                    }
                }
            }
            chains = entries
            val decode = HistoryDecodeOverlay(
                live = live,
                snapshots = chains.mapValues { (_, chain) -> chain.first().migration.from },
            )
            parser = BinaryParser(decode, decode)
        }

        /**
         * Uniform per-value dispatch by type id (§8.4 "Dispatch"). Recurses
         * through the shapes a payload can generically carry — entities,
         * lists, composites (their fields include the `genericData` slots the
         * verify scan cannot see) — and leaves scalars/references alone.
         */
        fun upcastValue(value: Data, dropped: MutableList<String>): ValueOutcome = when (value) {
            is TValue, is TConstant, is Reference -> ValueOutcome.Unchanged
            is Entity<*> -> upcastEntity(value, dropped)
            is TList<*> -> upcastList(value, dropped)
            is Composite -> chains[value.id]
                ?.let { migrateChain(value, it) }
                ?: walkFields(value, dropped)
            else -> ValueOutcome.Unchanged
        }

        /**
         * Run one from-shaped value through its ordered chain. Intermediates
         * build under the step's to-bound overlay context; the FINAL entry
         * builds via the live context (last `to` == live, §8.5), so the
         * output is a registered dto — required because the ingest pipeline
         * hands it to typed mergers and the resident store cache.
         */
        private fun migrateChain(old: Data, chain: List<ChainEntry>): ValueOutcome {
            var current = old
            chain.forEachIndexed { index, entry ->
                val stepContext =
                    if (index == chain.lastIndex) context
                    else OverlayTContext(context, entry.encodeOverlay)
                current = entry.migration.migrate(
                    current,
                    MigrationContext(stepContext, deviceIdProvider()),
                ) ?: return ValueOutcome.Dropped(
                    "value of type '${entry.migration.typeId}' dropped by the '${entry.serviceId}' " +
                        "v${entry.fromVersion}→v${entry.fromVersion + 1} migration"
                )
            }
            return ValueOutcome.Changed(current)
        }

        /**
         * Entities decompose their composite into an id-keyed field map on
         * the wire (no positional inlining), so an old-shaped entity is
         * re-assembled as a [SnapshotComposite] by reading the map in the
         * `from` snapshot's field order, migrated, and re-wrapped via the
         * live `createEntity` (which reads the final live shape by id).
         * Relation properties are outside the composite field list the
         * migration artifact covers — preserved verbatim.
         */
        private fun upcastEntity(entity: Entity<*>, dropped: MutableList<String>): ValueOutcome {
            val typeId = entity.type.get()
            val relations = entity.properties.entries.associate { (key, value) -> key.get() to value }
            val chain = chains[typeId]
            if (chain != null) {
                val fieldsById = entity.fields.entries.associate { (key, value) -> key.get() to value }
                val from = chain.first().migration.from
                val old = SnapshotComposite(
                    from,
                    from.values.toList().map { field -> fieldsById[field.id.get()] },
                )
                return when (val outcome = migrateChain(old, chain)) {
                    is ValueOutcome.Dropped -> outcome
                    is ValueOutcome.Changed ->
                        ValueOutcome.Changed(context.createEntity<Composite>(outcome.value as Composite, relations))
                    ValueOutcome.Unchanged -> error("unreachable: migrateChain changes or drops")
                }
            }
            // Unmigrated entity type: its field map values are generic Data,
            // so a migrated value can still hide there (the genericData
            // corner). Walk them; a dropped value invalidates the entity.
            var changed = false
            val newFields = mutableMapOf<String, Data>()
            for ((key, value) in entity.fields.entries) {
                val fieldId = key.get()
                when (val outcome = upcastValue(value, dropped)) {
                    ValueOutcome.Unchanged -> newFields[fieldId] = value
                    is ValueOutcome.Changed -> {
                        newFields[fieldId] = outcome.value
                        changed = true
                    }
                    is ValueOutcome.Dropped -> return ValueOutcome.Dropped(
                        "entity of type '$typeId' dropped: its field '$fieldId' value was dropped " +
                            "(${outcome.reason})"
                    )
                }
            }
            if (!changed) return ValueOutcome.Unchanged
            return ValueOutcome.Changed(live.createEntity<Composite>(typeId, newFields, relations))
        }

        /** Per-element upcast; a dropped element is removed and recorded (batch upcast is per-value). */
        private fun upcastList(list: TList<*>, dropped: MutableList<String>): ValueOutcome {
            var changed = false
            val newElements = mutableListOf<Data?>()
            for (element in list.toList()) {
                if (element == null) {
                    newElements += null
                    continue
                }
                when (val outcome = upcastValue(element, dropped)) {
                    ValueOutcome.Unchanged -> newElements += element
                    is ValueOutcome.Changed -> {
                        newElements += outcome.value
                        changed = true
                    }
                    is ValueOutcome.Dropped -> {
                        dropped += outcome.reason
                        changed = true
                    }
                }
            }
            return if (changed) ValueOutcome.Changed(live.createList<Data>(newElements)) else ValueOutcome.Unchanged
        }

        /**
         * Recurse into an unmigrated composite's fields (schema-order,
         * optional-aware) — this is how the envelope types' generic `Data`
         * slots (`DataBlockPayload.value`, each `SyncDelta.value`) are
         * reached without naming them. Rebuild via the live factory only on
         * change; a dropped required-field value drops the parent (an
         * optional one goes absent, recorded).
         */
        private fun walkFields(composite: Composite, dropped: MutableList<String>): ValueOutcome {
            val schema = composite.schema
            var changed = false
            val newFields = mutableListOf<Data?>()
            for (field in schema.values.toList()) {
                val fieldId = field.id.get()
                val value: Data? = if (field.isOptional.get()) {
                    composite.getOptionalField(fieldId)
                } else {
                    composite.getField(fieldId)
                }
                if (value == null) {
                    newFields += null
                    continue
                }
                when (val outcome = upcastValue(value, dropped)) {
                    ValueOutcome.Unchanged -> newFields += value
                    is ValueOutcome.Changed -> {
                        newFields += outcome.value
                        changed = true
                    }
                    is ValueOutcome.Dropped -> {
                        if (field.isOptional.get()) {
                            newFields += null
                            dropped += outcome.reason
                            changed = true
                        } else {
                            return ValueOutcome.Dropped(
                                "'${schema.id.get()}' dropped: required field '$fieldId' value was " +
                                    "dropped (${outcome.reason})"
                            )
                        }
                    }
                }
            }
            return if (changed) {
                ValueOutcome.Changed(live.createComposite<Composite>(schema.id.get(), newFields))
            } else {
                ValueOutcome.Unchanged
            }
        }
    }
    private companion object {
        /** Distinct stamps kept parsed. A realm has one per emitting build; 16 is generous. */
        const val MAX_CACHED_STAMPS = 16
    }
}

/**
 * The decode overlay for a history window: [SchemaOverlayProvider]'s
 * from-snapshot type resolution plus the three extras entity wire shapes
 * need — ids that only the *producing* schemas knew:
 *
 * - 16-byte tags of retired **field** ids (an old entity's field map keys —
 *   e.g. a since-`drop`ped field whose `reference(...)` registration left
 *   the live module) resolve through the snapshots' field lists;
 * - [getReference] falls back to snapshot-derived reference dtos for those
 *   ids (and for retired type ids);
 * - [createEntity] of a retired type (a `dropType`d entity — its migration
 *   returns null, but parse runs first) builds the entity dto directly
 *   instead of failing the live reference lookup.
 */
internal class HistoryDecodeOverlay private constructor(
    private val live: SchemaRegistry,
    private val base: SchemaOverlayProvider,
    snapshots: Map<String, CompositeSchema>,
) : SchemaProvider by base, DataFactory by base {

    constructor(live: SchemaRegistry, snapshots: Map<String, CompositeSchema>) : this(
        live = live,
        base = SchemaOverlayProvider(liveSchemas = live, liveFactory = live, snapshots = snapshots),
        snapshots = snapshots,
    )

    private val fallbackReferences: Map<String, Reference> = buildMap {
        snapshots.forEach { (typeId, from) ->
            put(typeId, CompositeTypeReferenceDto(typeId))
            from.values.toList().forEach { field ->
                put(field.id.get(), FieldReferenceDto(field.id.get()))
            }
        }
    }

    private val fallbackTags: List<Pair<ByteArray, String>> =
        fallbackReferences.keys.map { id -> idToUuid(id) to id }

    override fun getTypeId(type: ByteArray): String {
        try {
            return base.getTypeId(type)
        } catch (original: Exception) {
            fallbackTags.firstOrNull { (tag, _) -> tag.contentEquals(type) }
                ?.let { (_, id) -> return id }
            throw original
        }
    }

    override fun getReference(type: String): Reference =
        try {
            base.getReference(type)
        } catch (original: Exception) {
            fallbackReferences[type] ?: throw original
        }

    override fun <T : Composite> createEntity(
        type: String,
        fields: Map<String, Data>,
        relations: Map<String, Data>,
    ): Entity<T> =
        try {
            base.createEntity(type, fields, relations)
        } catch (original: Exception) {
            val typeReference = fallbackReferences[type] ?: throw original
            EntityDto(type = typeReference, fields = fields, properties = relations)
        }

}
