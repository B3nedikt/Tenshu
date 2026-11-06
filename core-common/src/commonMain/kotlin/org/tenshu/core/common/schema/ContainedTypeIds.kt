package org.tenshu.core.common.schema

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TConstant
import org.tenshu.core.TValue
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.Entity
import org.tenshu.core.reference.Reference

/**
 * Every composite/entity type id **contained** in [value] — its own type plus
 * every type reachable through its fields, list elements and (for entities)
 * relation property values, recursively.
 *
 * This is the schema walk `core-schema-evolution.md` §4.1.3 calls for: "the
 * epoch bump follows contained types, not the owning entity", because a
 * positional payload can embed foreign-service composites (the `genericData`
 * precedent — a preference row holding a sync-service `SyncPolicy`), so a
 * lagging reader must be fenced for the service whose *bytes* it would garble,
 * not for the written entity's nominal owner.
 *
 * Deliberately a **read-only id collector**, not a second copy of
 * [org.tenshu.core.common.schema.migration.PayloadUpcaster]'s walk: that one is
 * a value *transform* (per-type migration chains in, rebuilt values or a drop
 * verdict out, keyed on one prepared window). Only the recursion *shape* is
 * shared — entity fields + relation values, list elements, composite fields
 * (which is how generic `Data` slots are reached without naming them),
 * scalars/references left alone. Folding them into one traversal would mean
 * parameterising the transform's `ValueOutcome` machinery over a collector,
 * which buys nothing and couples the epoch to the migration overlay.
 *
 * Values handed here are already materialised (they came off a completed
 * `saveData`), so nothing lazy is forced: a `DatabaseReference` is a plain
 * [Data] that is neither [Composite] nor [TList] and contributes nothing.
 *
 * @param maxDepth a defensive bound; real value graphs are a handful of levels
 *   deep, and a self-referential value would otherwise recurse forever.
 */
fun containedTypeIds(value: Data, maxDepth: Int = 32): Set<String> {
    val ids = LinkedHashSet<String>()
    collectContainedTypeIds(value, ids, maxDepth)
    return ids
}

private fun collectContainedTypeIds(value: Data, into: MutableSet<String>, depth: Int) {
    if (depth <= 0) return
    when (value) {
        // Scalars carry no composite shape of their own.
        is TValue, is TConstant, is Reference -> Unit

        is Entity<*> -> {
            into += value.type.get()
            value.fields.values.forEach { collectContainedTypeIds(it, into, depth - 1) }
            // Relation property values are separately emitted as their own
            // blocks by `SyncObserver.processSavedData`, so their types are
            // part of what this write puts on the wire. Already-materialised
            // here (the observer reads the same map), never re-resolved.
            value.properties.values.forEach { collectContainedTypeIds(it, into, depth - 1) }
        }

        is TList<*> -> value.toList().forEach { element ->
            element?.let { collectContainedTypeIds(it, into, depth - 1) }
        }

        is Composite -> {
            val schema = value.schema
            into += schema.id.get()
            schema.values.toList().forEach { field ->
                val fieldId = field.id.get()
                val fieldValue: Data? = if (field.isOptional.get()) {
                    value.getOptionalField(fieldId)
                } else {
                    value.getField(fieldId)
                }
                fieldValue?.let { collectContainedTypeIds(it, into, depth - 1) }
            }
        }

        else -> Unit
    }
}
