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
 * Terminal transition — ONE op for `complete`/`fail`/`cancel` (they differ
 * only in the resulting [BackgroundTask.States] value; three separate ops
 * would be three Ros + three registrations for one switch). Seals the lane's
 * **final checkpoint**, closes the lane, and releases the task's liveness
 * contribution. Idempotent: transitioning an already-terminal task returns
 * its row unchanged.
 */
suspend fun TaskService.transitionTask(taskId: String, transition: String): TResult<BackgroundTask?> =
    findOperation<TransitionTask>(TransitionTask.ID)
        .transitionTask(taskId, transition)
        .asTaskInteractor<BackgroundTask?>()
        .execute()

suspend fun TaskService.completeTask(taskId: String) = transitionTask(taskId, TransitionTask.Transitions.COMPLETE)
suspend fun TaskService.failTask(taskId: String) = transitionTask(taskId, TransitionTask.Transitions.FAIL)
suspend fun TaskService.cancelTask(taskId: String) = transitionTask(taskId, TransitionTask.Transitions.CANCEL)

interface TransitionTask : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun transitionTask(taskId: String, transition: String): RequestObject

    object Transitions {
        const val COMPLETE = "complete"
        const val FAIL = "fail"
        const val CANCEL = "cancel"
    }

    companion object {
        const val ID = "${TaskService.ID}/transition"

        val Schema = operation(
            id = ID,
            receiver = TaskService.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
            params = {
                string(Params.TASK_ID)
                string(Params.TRANSITION)
            },
            returnType = BackgroundTask.Schema.toType()
        )
    }

    object Params {
        const val TASK_ID = "$ID/taskId"
        const val TRANSITION = "$ID/transition"
    }
}
