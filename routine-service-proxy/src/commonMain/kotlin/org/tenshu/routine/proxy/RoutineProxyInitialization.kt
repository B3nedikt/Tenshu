package org.tenshu.routine.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.routine.PresenceState
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.RoutineService
import org.tenshu.core.id

/**
 * Initialises the routines service's proxy-side observers.
 *
 * [RoutineService.Relations.ROUTINES] and
 * [RoutineService.Relations.ROUTINE_RUNS] are derived views of every
 * locally-known [Routine] / [RoutineRun] — whether saved by a local operation
 * **or** arriving via the sync-merge path. Relation membership is not a synced
 * primitive; the receiver derives it.
 *
 * Both are **declared on [RoutineService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, R2) —
 * they were the local `RoutineRelationMaintainer`.
 */
fun ExecutionContext.initRoutineService() {
    installRelationMaintainer()
}
