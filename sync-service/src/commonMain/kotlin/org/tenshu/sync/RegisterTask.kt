package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TaskSpec
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Register a bulk task (`core-task-composite.md` §5): creates the proxy-side
 * task row, **opens its sync lane** (keyed by [taskId]) and starts its lease.
 * The verb is `register`, not `begin`/`start` — the work itself is the client
 * loop that follows under `withTask`; the proxy only tracks it.
 *
 * [taskId] is client-minted (the Phase-L2 precedent) so it can double as the
 * L2 cancellable-call correlation key for constituent ops.
 */
suspend fun TaskService.registerTask(taskId: String, spec: TaskSpec<*>): TResult<BackgroundTask?> =
    findOperation<RegisterTask>(RegisterTask.ID)
        .registerTask(taskId, spec)
        .asTaskInteractor<BackgroundTask?>()
        .execute()

interface RegisterTask : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun registerTask(taskId: String, spec: TaskSpec<*>): RequestObject

    companion object {
        const val ID = "${TaskService.ID}/register"

        val Schema = operation(
            id = ID,
            receiver = TaskService.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
            params = {
                string(Params.TASK_ID)
                // Polymorphic: any TaskSpec subtype (Schedule's spec params
                // are the precedent — composites carry their concrete type).
                composite(Params.SPEC, type = TaskSpec.Schema)
            },
            returnType = BackgroundTask.Schema.toType()
        )
    }

    object Params {
        const val TASK_ID = "$ID/taskId"
        const val SPEC = "$ID/spec"
    }
}
