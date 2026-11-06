package org.tenshu.core.common.schema.migration

import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.GenericType
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.baseSchema

/**
 * Holds every registered [SchemaMigration] and derives the per-service
 * readable floor from them (`core-schema-evolution.md` §4/§8.4: the floor is
 * DERIVED — cutting off old formats = deleting migration steps; nothing is
 * declared separately that could drift).
 *
 * [verify] is the init-time fail-fast: hand-authored snapshots are the one
 * place this design can silently drift, so drift becomes a refused proxy
 * start, in the same spirit as the floor/current completeness check.
 */
class SchemaMigrationRegistry {

    private val migrations = mutableListOf<SchemaMigration>()

    fun register(migration: SchemaMigration) {
        migrations += migration
    }

    /**
     * Init-time chain-integrity check (§8.4):
     *
     * 1. **Per-service version contiguity** — a service's registered
     *    `fromVersion`s must form a gapless run ending at `current − 1` (when
     *    [live] knows the service's [org.tenshu.core.schema.ServiceSchema]).
     *    A bump that reshaped no types registers an empty [SchemaMigration]
     *    to keep the chain honest.
     * 2. **Per-type snapshot chain** — for each `typeId`, ordered by version,
     *    `step[i].to` must structurally equal `step[i+1].from`, and the last
     *    `to` must structurally equal the live schema (when the live registry
     *    knows the type; a retired type has no live schema to match).
     * 3. **Nested-reshape rejection** — a migrated type embedded as a
     *    composite field of another live type would require rewriting every
     *    embedding row under a version-N overlay (§8.4 "Nested types
     *    ripple"); that orchestration is not built yet, so the case fails
     *    loudly instead of silently mis-migrating. (References are exempt:
     *    they serialize as 16-byte ids, never inline bytes.) The scan needs
     *    schema enumeration, so it runs only when [live] is a [SchemaRegistry].
     *
     *    ⚠⚠ **The consequence for authors: a type that rides as an operation
     *    parameter cannot gain a field** until the overlay orchestration lands.
     *    An op's params are a composite, so the parameter type is embedded in it.
     *    That covers nearly every domain type in the tree. The way around it is a
     *    **sibling keyed row**: a new composite keyed by the parent's id. It is
     *    additive, reshapes nothing, needs no migration, and usually fits better
     *    anyway, since most parents would carry an empty field forever.
     *
     *    Worked examples: `PlaceMerge` and `PlaceRole`, which `Place` couldn't carry
     *    (`Place` is a `recordPlace` param), and `SnappedRoute`, which
     *    `MovementTrace` couldn't carry (`recordMovementTrace`). Each type's KDoc
     *    explains its case.
     */
    fun verify(live: SchemaProvider) {
        val currentByService = live.serviceSchemas().associate { it.id.get() to it.version }

        migrations.groupBy { it.serviceId }.forEach { (serviceId, steps) ->
            val froms = steps.map { it.fromVersion }.sorted()
            check(froms.distinct().size == froms.size) {
                "Migration chain for '$serviceId' registers duplicate fromVersions: $froms."
            }
            froms.zipWithNext().forEach { (a, b) ->
                check(b == a + 1) {
                    "Migration chain for '$serviceId' has a gap: v$a→v${a + 1} is followed by " +
                        "v$b→v${b + 1} — register the missing step(s); a bump that reshaped no " +
                        "types registers an empty SchemaMigration."
                }
            }
            currentByService[serviceId]?.let { current ->
                check(froms.last() == current - 1) {
                    "Migration chain for '$serviceId' ends at v${froms.last()}→v${froms.last() + 1} " +
                        "but the live service version is $current — the chain must reach the " +
                        "current version (register the missing step(s), empty if the bump " +
                        "reshaped no types)."
                }
            }
        }

        val liveComposites = (live as? SchemaRegistry)
            ?.allSchemas()
            ?.filterIsInstance<CompositeSchema>()

        migrations
            .flatMap { step -> step.migrations.map { step.fromVersion to it } }
            .groupBy { (_, migration) -> migration.typeId }
            .forEach { (typeId, steps) ->
                val ordered = steps.sortedBy { (fromVersion, _) -> fromVersion }
                ordered.zipWithNext().forEach { (a, b) ->
                    check(a.second.to.structurallyEquals(b.second.from)) {
                        "Snapshot drift for '$typeId': the v${a.first}→v${a.first + 1} step's 'to' " +
                            "does not structurally equal the v${b.first}→v${b.first + 1} step's " +
                            "'from' — freeze the intermediate snapshot correctly."
                    }
                }
                (live.getSchema(typeId) as? CompositeSchema)?.let { liveSchema ->
                    val last = ordered.last()
                    check(last.second.to.structurallyEquals(liveSchema)) {
                        "Snapshot drift for '$typeId': the last step's (v${last.first}→" +
                            "v${last.first + 1}) 'to' does not structurally equal the live " +
                            "schema — either the live schema moved without a new migration " +
                            "step, or the snapshot is stale."
                    }
                }
                liveComposites?.forEach { other ->
                    if (other.id.get() != typeId && other.embedsComposite(typeId)) {
                        error(
                            "Nested reshapes are not yet supported: migrated type '$typeId' is " +
                                "embedded as a composite field of '${other.id.get()}' — its bytes " +
                                "inline into every embedding row (§8.4 \"Nested types ripple\"), " +
                                "so migrating it requires the version-N overlay orchestration."
                        )
                    }
                }
            }
    }

    /**
     * The lowest contiguous `fromVersion` reachable back from
     * [currentVersion] — the service's readable floor (§4). No migrations for
     * the service ⇒ the floor equals the current version.
     */
    fun floorFor(serviceId: String, currentVersion: Int): Int {
        val froms = migrations
            .filter { it.serviceId == serviceId }
            .map { it.fromVersion }
            .toSet()
        var floor = currentVersion
        while ((floor - 1) in froms) floor--
        return floor
    }

    /** The steps migrating [serviceId] from [fromVersion] up to [toVersion], in order. */
    fun stepsFor(serviceId: String, fromVersion: Int, toVersion: Int): List<SchemaMigration> =
        migrations
            .filter { it.serviceId == serviceId && it.fromVersion in fromVersion until toVersion }
            .sortedBy { it.fromVersion }
}

/**
 * Structural schema equality for the chain check (§8.4): same ordered list of
 * `(field id, declared field type id, isOptional)`. The declared type id is
 * sufficient — parameterisation differences without a base-type change do not
 * alter the positional decode plan at this granularity.
 */
internal fun CompositeSchema.structurallyEquals(other: CompositeSchema): Boolean =
    structuralShape() == other.structuralShape()

/**
 * ⚠ `internal` rather than private because [SchemaShapeBaseline] pins exactly
 * this shape (§8.9): the baseline's fingerprint and this chain check must
 * judge a schema by the same triple, or a reshape could pass one and fail the
 * other.
 */
internal fun CompositeSchema.structuralShape(): List<Triple<String, String?, Boolean>> {
    val fields = values.toList().map { field ->
        Triple(field.id.get(), field.type.baseSchema?.id?.get(), field.isOptional.get())
    }
    // ⚠⚠ An operation's RETURN TYPE is part of its shape. `OperationSchema.values` is its
    // `params`, so `returnType` — a sibling field — is pinned by nothing else: changing an op's
    // return from `Data?` to `TInteger` would leave the baseline fingerprint byte-identical.
    //
    // It is genuinely not a *positional* concern — a return value is written tagged, so it can
    // never shift a decode plan. But it IS wire-visible behaviour
    // both peers depend on, nothing else in the repo checks it (the announce gate compares
    // version numbers, not shapes, and `OperationSchema.returnType` is read nowhere at runtime),
    // and the failure is silent: a stale proxy answers `null`, the new client reads 0, and only
    // a human notices. Appended rather than interleaved so every composite's existing
    // fingerprint is untouched.
    val returnType = (this as? OperationSchema)?.returnType?.baseSchema?.id?.get()
    return if (returnType == null) fields else fields + Triple(RETURN_SHAPE_KEY, returnType, false)
}

/**
 * The synthetic trailing entry [structuralShape] uses for an operation's return type.
 *
 * ⚠ Not a real field id, and deliberately unmistakable for one — it shares the operation-schema
 * namespace so it can never collide with a declared field.
 */
internal const val RETURN_SHAPE_KEY = "tenshu/core/schema/operation/<return>"

/**
 * Whether any of this schema's fields inlines [typeId]'s bytes — a direct
 * composite field or a value nested inside a generic parameterisation (a
 * `TList<X>` element's bytes inline too). Reference-typed fields are skipped:
 * a reference serializes as a 16-byte id, so reshaping its target never
 * changes the embedding row's bytes.
 */
private fun CompositeSchema.embedsComposite(typeId: String): Boolean =
    values.toList().any { field -> field.type.mentionsComposite(typeId) }

private fun Type.mentionsComposite(typeId: String): Boolean {
    val base = baseSchema ?: return false
    if (base is ReferenceSchema || base.id.get() == Reference.ID) return false
    if (base.id.get() == typeId) return true
    return (this as? GenericType)
        ?.params
        ?.toList()
        ?.any { it.type.mentionsComposite(typeId) }
        ?: false
}
