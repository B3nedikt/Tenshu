package org.tenshu.sync.common

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity

/**
 * Interface for implementing custom semantic merging logic for specific data types.
 */
interface SemanticMerger {
    /**
     * The ID of the data type this merger handles (e.g., Folder.ID).
     */
    val typeId: String

    /**
     * Merges two instances of the data type.
     * 
     * @param executionContext The ExecutionContext for resolving lazy references and creating objects.
     * @param existing The current local state of the data (often contains lazy database references).
     * @param incoming The incoming remote state of the data (usually fully materialized from wire).
     * @return The merged result.
     */
    fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data

    /**
     * Drop references to entities that are **dead** — tombstoned and absent from the
     * local store — from [data]'s collection fields. Default: identity.
     *
     * ⚠⚠ **A union merge has no removal semantics, and this is the only thing that
     * supplies them.** A merger that combines two states of a collection (`FolderMerger`
     * is the canonical one) can express "these two lists both contribute members"; it
     * cannot express "this member was deleted", because a member missing from one side
     * is indistinguishable from a member the other side simply hasn't learned about yet.
     * So a device that writes to a container *before* it learns of a child's deletion
     * emits a legitimately-newer block that still lists the child, and the union puts
     * the child back — as a reference to an entity every device has already deleted.
     * The result is a row that lists but cannot be opened: `drive-synced-folders.md`
     * §3.9 (a backup destination folder that came back empty on a peer).
     *
     * The tombstone is the only durable statement that a removal happened, which is why
     * this hook exists at all rather than the merger inferring removal from absence.
     *
     * ⚠ [isDead] is deliberately *tombstoned **and** absent locally*, not merely
     * tombstoned: a tombstone does not retire an id. Standard CRDT semantics let a
     * higher-HLC create resurrect the entity for real (Drive's own auto-backup does
     * exactly this — `ensureBackupDestination` re-creates a destination chain a peer
     * deleted), and purging a member whose entity is present again would hide a live
     * row instead of a dead one.
     *
     * Called by the sync ingest path, which owns the tombstone registry; implementors
     * never look tombstones up themselves.
     */
    fun purgeDeadMembers(
        executionContext: ExecutionContext,
        data: Data,
        isDead: (DataId) -> Boolean,
    ): Data = data
}

/**
 * Registry for [SemanticMerger] implementations.
 */
class SemanticMergerRegistry {
    private val mergers = mutableMapOf<String, SemanticMerger>()

    fun register(merger: SemanticMerger) {
        mergers[merger.typeId] = merger
    }

    fun getMerger(typeId: String): SemanticMerger? = mergers[typeId]
}

/**
 * Utility to resolve a list field that might be either a [TList] or a database reference.
 */
fun ExecutionContext.resolveListField(data: Data, fieldId: String): List<Data> {
    val raw = when (data) {
        is Entity<*> -> data.getFieldValue<Data>(fieldId)
        is Composite -> data.getField<Data>(fieldId)
        else -> null
    }
    return resolveList(raw)?.toList() ?: emptyList()
}
