package org.tenshu.routine.proxy

import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.routine.RoutineFiringMarker

/**
 * The restart half of RE-D4's two dedupe mechanisms.
 *
 * Written **below** `saveData` (straight onto the database array, the
 * `ScheduleStore` / `RekeyMigrationMarker` pattern) so it never re-fires
 * observers and never syncs.
 *
 * ⚠⚠ Ordering is the whole contract (§4.9): [begin] must reach the store
 * **before** the first action runs and before the `CLAIMED` receipt is written.
 * A crash between the journal write and the receipt leaves a marker with no row,
 * and [incomplete] correctly declines to repeat; the reverse order would leave a
 * row with no marker and the restart would repeat the actions. At-most-once
 * rests on this one ordering.
 */
internal class RoutineFiringJournal(
    private val executionContext: ExecutionContext,
) {

    private val ref: ArrayDatabaseReference
        get() = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = RoutineFiringMarker.ID,
                relationId = RoutineFiringMarker.COLLECTION,
            ),
            isEntity = false,
            elementSchemaId = RoutineFiringMarker.ID,
        )

    fun begin(firingId: String, routineId: String, nowEpochMs: Long) {
        val marker = RoutineFiringMarker.create(
            context = executionContext.context,
            firingId = firingId,
            routineId = routineId,
            startedAtEpochMs = nowEpochMs,
        )
        executionContext.database.transaction {
            executionContext.database.addToArray(ref, marker)
        }
    }

    fun complete(firingId: String) {
        val current = ref
        executionContext.database.transaction {
            executionContext.database.getArrayData(current)
                ?.toList()
                .orEmpty()
                .filterIsInstance<RoutineFiringMarker>()
                .filter { it.firingId.get() == firingId }
                .forEach { marker ->
                    executionContext.getDataId(marker)?.let {
                        executionContext.database.removeFromArray(current, it)
                    }
                }
        }
    }

    /** Markers left by a run that never finished — reconciled, never re-run. */
    fun incomplete(): List<RoutineFiringMarker> =
        executionContext.database.getArrayData(ref)
            ?.toList()
            .orEmpty()
            .filterIsInstance<RoutineFiringMarker>()

    fun contains(firingId: String): Boolean =
        incomplete().any { it.firingId.get() == firingId }
}
