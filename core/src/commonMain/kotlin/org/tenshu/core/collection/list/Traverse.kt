package org.tenshu.core.collection.list

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.core.OperationReceiverHolder
import org.tenshu.core.core.TContext
import org.tenshu.core.core.findOperation
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type
import kotlin.reflect.KClass

/**
 * Recursively walks a list and every nested list it reaches, returning **all**
 * nodes (the receiver's elements + all their descendants) flattened into one
 * list. Same depth-first traversal as [searchAll] but without a predicate — it
 * keeps everything.
 *
 * Distinct from Kotlin's `flatten` (single level) and from the `…All`
 * naming axis (single-value vs list return). Use it to address a tree of
 * self-referential composites as a flat collection, e.g. every file/folder in
 * the drive: `root.files.traverse().sortByDescending(File.LastModifiedAt)`.
 *
 * **Cost:** a full-tree walk — `O(tree)` per call. ⚠ How much of each node it
 * loads depends on what follows:
 * - `traverse()` · [filter]s · [sortBy]/[sortByDescending] · a finite `take` · `select`
 *   runs as ONE thin walk with a bounded top-N — only the selected, filtered and
 *   sort-key fields of each node are read, and only the window is kept
 *   (filter-aware fusion, `docs/proposals/core-ordered-window-fusion.md` §9).
 *   Every predicate must be fusable — see [filter]; one that is not makes the
 *   whole chain fall back, silently, to loading every node, filtering and fully
 *   sorting.
 * - Anything else (no window, a stream op after it, no `select`) loads every
 *   node in full.
 * For top-N-recent over a large drive without a usable window, prefer a
 * maintained flat collection or the modification log
 * (`docs/proposals/file-service-history.md` §5.1).
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Data> TList<T>.traverse(): TList<T> {
    val context = this as TContext
    val narrowed = context.schemaProvider().getSchema(
        context.schemaProvider().getTypeId(T::class as KClass<Data>)
    )
    val operation = findOperation<Traverse<T>>(Traverse.ID)
    (operation as? OperationReceiverHolder)?.narrowedElementType = narrowed
    return operation.traverse()
}

interface Traverse<T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun traverse(): TList<T>

    companion object {
        const val ID = "tenshu/core/collection/list/traverse"

        val Schema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E") },
            returnType = listType("E")
        )
    }
}
