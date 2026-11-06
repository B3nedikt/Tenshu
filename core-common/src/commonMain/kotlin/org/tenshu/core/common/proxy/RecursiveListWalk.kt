package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.baseSchema

/**
 * Depth-first traversal shared by `searchAll` ([SearchAllImplementation]) and
 * `traverse` ([TraverseImplementation]): for each item, emit it, then recurse
 * into every `TList`-typed field it carries. Returns the receiver's elements
 * plus all their descendants, flattened.
 *
 * `searchAll` filters the result by a predicate; `traverse` keeps everything —
 * the walk itself is identical, so it lives here once.
 */
internal fun recursivelyWalk(
    items: List<Data>,
    executionContext: ExecutionContext,
): List<Data> {
    val results = mutableListOf<Data>()
    items.forEach { item ->
        results.add(item)

        nestedListFieldValues(item, executionContext).forEach { fieldData ->
            val nestedArray = executionContext.resolveList(fieldData)
            if (nestedArray != null) {
                results.addAll(recursivelyWalk(nestedArray.toList(), executionContext))
            }
        }
    }
    return results
}

/**
 * The values of every `TList`-typed field of [item], in schema order — what [recursivelyWalk]
 * descends into. The element is **re-read** with [SKELETON_PROJECTION] by its `DataId` so a list
 * field arrives as the deferred [ArrayDatabaseReference] the store holds for it (falling back to
 * [item] itself when it has no `DataId` or no stored row). A `null` entry is an absent optional
 * list.
 *
 * ⚠ Shared with the filter-aware ordered-window fusion's walk, which must descend exactly where
 * this walk does — same fields, same order, same accessor per field kind — or the fused feed
 * would emit a different set of rows than `traverse()` (`core-ordered-window-fusion.md` §9).
 */
internal fun nestedListFieldValues(
    item: Data,
    executionContext: ExecutionContext,
): List<Data?> {
    val dataId = executionContext.getDataId(item)
    val elementToTraverse = if (dataId != null) {
        executionContext.database.getData(
            dataId,
            asEntity = true,
            projection = SKELETON_PROJECTION,
        ) as? Composite
            ?: item as? Composite
    } else item as? Composite

    elementToTraverse ?: return emptyList()
    val typeId =
        if (elementToTraverse is Entity<*>) elementToTraverse.type.get() else elementToTraverse.id
    val schema = executionContext.schemaProvider.getSchema(typeId) as? CompositeSchema
        ?: return emptyList()
    return collectFields(schema)
        .filter { it.type.baseSchema?.id?.get() == TList.ID }
        .map { fieldSchema ->
            val fieldId = fieldSchema.id.get()
            if (fieldSchema.isOptional.get()) {
                elementToTraverse.getOptionalField<Data>(fieldId)
            } else {
                if (elementToTraverse is Entity<*>) {
                    elementToTraverse.getFieldValue<Data>(fieldId)
                } else {
                    elementToTraverse.getField<Data>(fieldId)
                }
            }
        }
}

/**
 * Whether a row of type [typeId] has any `TList`-typed field at all — i.e. whether
 * [nestedListFieldValues] could descend into it. Lets the fused walk skip the skeleton re-read for
 * leaf rows (every non-folder file), which [recursivelyWalk] pays for and then discards.
 */
internal fun hasNestedListFields(typeId: String, executionContext: ExecutionContext): Boolean {
    val schema = executionContext.schemaProvider.getSchema(typeId) as? CompositeSchema ?: return false
    return collectFields(schema).any { it.type.baseSchema?.id?.get() == TList.ID }
}

/**
 * The projection for the walk's **skeleton read** at [recursivelyWalk] — the re-load whose only
 * job is to reach an element's `TList`-typed fields so the walk can descend.
 *
 * `keepFields = emptySet()` + `skipUnprojectedComposites = false` reads as "keep every stored
 * field, drop only the eager relation map" — `keepFields` is consulted *only* when
 * `skipUnprojectedComposites` is true (see [Projection]'s KDoc), so nothing is stripped here
 * except `DbEntry.properties`. That map is where `File.Thumbnail` / `File.ThumbnailLarge` live
 * (`Property.Value<TByteArray>`), so without this the walk materialised **every thumbnail blob in
 * the drive** on every traversal — the predicate in a Drive search was ~1% of the query's cost.
 *
 * ### Why this is safe, and why it applies HERE and nowhere else in the walk
 *
 * The skeleton element is **never emitted**: `recursivelyWalk` emits `item` (from the caller's own
 * materialisation) and uses the re-loaded element solely for `getField`/`getOptionalField` on
 * `TList` fields. List fields are never guarded by a projection in `DbEntryMapper`, so recursion is
 * unaffected — and relations are re-read from the database on demand anyway
 * (`ReferenceResolverImpl.resolveStaticProperty` issues its own un-projected `getData`, and
 * `DatabaseImpl.getData` has no entity cache, so a projected read can never be handed back).
 *
 * ⚠ **Do NOT extend this to the child read below** (`resolveList` → `getArrayData`, which defaults
 * to `projection = null`). Those rows *are* emitted. Today they happen to be non-entity composites
 * (`FileDto`), which carry no relation map either way — so an experiment showed no observable
 * difference — but that is an accident of the current element shape, not a contract: the moment a
 * caller materialises descendants as entities, a no-`select` consumer reading relations straight
 * off them (`tenshu-sdk-js`'s `FileSystem.traverse().sortByDescending(…)` → `CompositeSnapshot`,
 * which walks `properties`) would silently see them emptied. The skeleton read carries no such
 * risk because it is never emitted at all. Same reason `item` itself is passed through untouched.
 *
 * ⚠ Deliberately not `skipUnprojectedComposites = true`: the walk reads no composite scalar field,
 * so stripping them would also be sound, but the win over dropping the blob-bearing relation map is
 * marginal and the blast radius of a wrong projection in shared core code is not.
 */
private val SKELETON_PROJECTION = Projection(
    keepFields = emptySet(),
    skipUnprojectedComposites = false,
)

// See `docs/schema-dsl.md` §2 "Field Inheritance Invariant".
private fun collectFields(schema: CompositeSchema): List<Field> = schema.values.toList()
