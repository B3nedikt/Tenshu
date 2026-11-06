package org.tenshu.file.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.db.ObjectStore

/**
 * Rows of [typeId] as a lazy sequence of pages, or `null` when this store cannot enumerate —
 * the file-service copy of `SchemaDrivenContentWalk.pagesOf` (CR-4e), which is private to
 * `:sync-service-proxy` and sits on the wrong side of the module graph for a file-service pass.
 *
 * ⚠⚠ The contract is the CR-4e one, word for word: prefer the **bounded** [ObjectStore.scanRowsOfType]
 * and accept the unbounded [ObjectStore.rowsOfTypes] only where the store implements nothing else
 * (`InMemoryObjectStore`, whose whole map is resident anyway); key completion on
 * [ObjectStore.TypeScanPage.exhausted], never on a short page; and read `null` as *"could not ask"*,
 * which is not empty — a caller that latches a marker over `null` has latched over a store it never
 * looked at.
 */
internal fun ObjectStore.pagesOfType(typeId: String): Sequence<List<ObjectStore.DbEntry>>? {
    scanRowsOfType(typeId, Long.MIN_VALUE, TYPE_SCAN_MATCH_LIMIT, TYPE_SCAN_EXAMINE_LIMIT)
        ?: return rowsOfTypes(setOf(typeId))?.let { sequenceOf(it) }

    return sequence {
        var cursor = Long.MIN_VALUE
        while (true) {
            val page = scanRowsOfType(typeId, cursor, TYPE_SCAN_MATCH_LIMIT, TYPE_SCAN_EXAMINE_LIMIT)
                ?: return@sequence
            yield(page.rows)
            cursor = page.nextCursor
            if (page.exhausted) return@sequence
        }
    }
}

/**
 * Materialises one stored row, following nested-composite references back into the store (E2b),
 * or `null` when the row cannot be decoded — a row of a type this graph no longer knows is skipped,
 * never fatal. ⚠ `getDataIdHash()` is one-way, so this is the only route from a scanned row back
 * to a `DataId` — the same reason `SchemaCompactor` and the CR-4e walk decode rather than look up.
 */
internal fun ExecutionContext.decodeRow(objectStore: ObjectStore, row: ObjectStore.DbEntry): Data? {
    val mapper = rowMapper() ?: return null
    return runCatching {
        mapper.deSerialize(row) { hash -> objectStore.getObject(hash) ?: error("missing nested row $hash") }
    }.getOrNull()
}

/**
 * A [DbEntryMapper] over this context's registry, or `null` where the schema provider is not a
 * [SchemaRegistry] (a narrowed harness) — the `SchemaDrivenContentWalk` idiom. In production the
 * provider IS the registry; `null` says so rather than pretending.
 */
private fun ExecutionContext.rowMapper(): DbEntryMapper? {
    val registry = schemaProvider as? SchemaRegistry ?: return null
    return DbEntryMapper(context, registry, registry, traitResolver)
}

/** One page's matches — small, because each match is decoded. Mirrors `SchemaDrivenContentWalk.MATCH_LIMIT`. */
internal const val TYPE_SCAN_MATCH_LIMIT = 256

/**
 * Rows examined per page. ⚠ The second budget exists because a store whose only index is the hash
 * (the retired iOS file store was one) pays an unbounded scan to find [TYPE_SCAN_MATCH_LIMIT] matches
 * of a rare type.
 */
internal const val TYPE_SCAN_EXAMINE_LIMIT = 4_096
