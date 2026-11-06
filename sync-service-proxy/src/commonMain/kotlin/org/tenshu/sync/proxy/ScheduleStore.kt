package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.ScheduleItem
import org.tenshu.sync.ScheduleService

/**
 * Phase 5 (§3.2(b)): the persisted [ScheduleItem] queue lives in one
 * Database array, keyed by `dedupKey` via [ScheduleItem]'s `DataIdProvider`.
 * Shared by the three `ScheduleService` op implementations and the
 * [ScheduleDrainLoop] (mirror of `SyncScheduler`'s `pendingBlocksRef`).
 */
internal fun scheduledItemsRef(executionContext: ExecutionContext): ArrayDatabaseReference =
    ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = ScheduleItem.ID,
            relationId = ScheduleService.Collections.SCHEDULED_ITEMS
        ),
        isEntity = false,
        elementSchemaId = ScheduleItem.ID
    )

/**
 * Writes [item] as its key's row. ⚠ Call inside a `Database.transaction`.
 *
 * ⚠⚠ **In place when the key already has a row — never remove-then-add.** `Database.transaction` is
 * a lock, not a transaction: it has no rollback, so a remove followed by an add that throws leaves
 * the key with no row at all. Every row for a key shares one `DataId`, so `saveData` overwrites the
 * row the array already references and the key keeps a row whether or not the write succeeds. Only
 * a key with no row yet is appended, where a failure loses nothing that existed.
 *
 * ⚠ **Not all-or-nothing.** A row is several store writes — its three nested specs first, each an
 * entry of its own, then the row's entry. A failure before the first leaves the previous row whole;
 * one part-way can leave newer specs under the previous entry (state, attempts, op id). Still a row
 * that runs, which is the property that matters here.
 */
internal fun writeScheduleItem(
    executionContext: ExecutionContext,
    ref: ArrayDatabaseReference,
    item: ScheduleItem,
) {
    val database = executionContext.database
    if (scheduleItemFor(executionContext, ref, item.dedupKey.get()) == null) {
        database.addToArray(ref, item)
    } else {
        database.saveData(item)
        // Membership did not change, so no array event fired; tell listeners the member did.
        database.notifyArrayMemberUpdated(ref, item)
    }
}

/** The row currently stored for [dedupKey], or `null`. */
internal fun scheduleItemFor(
    executionContext: ExecutionContext,
    ref: ArrayDatabaseReference,
    dedupKey: String,
): ScheduleItem? =
    executionContext.database.getArrayData(ref)
        ?.firstOrNull { (it as ScheduleItem).dedupKey.get() == dedupKey } as ScheduleItem?
