package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Pure lease heartbeat (§10.5 D9): a live client loop calls this (at least
 * every lease/2) so the task doesn't lease-expire between constituent calls.
 *
 * Deliberately carries **no progress payload** (maintainer decision,
 * 2026-07-24, superseding the TS-3 `AdvanceTask(completed)` shape): progress
 * is domain-shaped — bytes-of-bytes for a blob upload, n-of-m items for a
 * multi-file task, a percentage where raw counts stop meaning anything —
 * so the *generic* task API doesn't model it at all. Typed
 * [org.tenshu.core.TaskSpec] subtypes may carry whatever domain fields they
 * like, and each consumer's own progress surface (e.g.
 * `FolderUploadController.state`, `FitRepository.importStatus()`) renders
 * it. If a proxy-authoritative generic progress display is ever wanted, the
 * escape hatch is an opaque domain-defined progress composite on this op —
 * deferred until something needs it.
 */
suspend fun TaskService.renewTaskLease(taskId: String): TResult<BackgroundTask?> =
    findOperation<RenewTaskLease>(RenewTaskLease.ID)
        .renewTaskLease(taskId)
        .asTaskInteractor<BackgroundTask?>()
        .execute()

interface RenewTaskLease : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun renewTaskLease(taskId: String): RequestObject

    companion object {
        const val ID = "${TaskService.ID}/renewLease"

        val Schema = operation(
            id = ID,
            receiver = TaskService.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
            params = {
                string(Params.TASK_ID)
            },
            returnType = BackgroundTask.Schema.toType()
        )
    }

    object Params {
        const val TASK_ID = "$ID/taskId"
    }
}
