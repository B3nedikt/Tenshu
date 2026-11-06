package org.tenshu.sync.proxy.impl

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.sync.RenewTaskLease
import org.tenshu.sync.BackgroundTask
import org.tenshu.sync.RegisterTask
import org.tenshu.sync.TransitionTask
import org.tenshu.sync.common.SyncBlockEmitter
import org.tenshu.sync.proxy.TaskRegistry

/**
 * TaskService op implementations (`core-task-composite.md` §5–§6, TS-3).
 * Register creates the row + **opens the sync lane** (the explicit-open gate
 * `SyncObserver.stageIntoLane` checks); a terminal transition seals the final
 * checkpoint by **closing the lane** and drops the liveness contribution.
 */

private fun TaskRegistry.TaskRow.toComposite(executionContext: ExecutionContext): BackgroundTask =
    BackgroundTask.create(
        context = executionContext.context,
        taskId = taskId,
        specType = specType,
        state = state,
    )

internal class RegisterTaskImplementation(
    private val taskRegistry: TaskRegistry,
    private val laneEmitter: () -> SyncBlockEmitter?,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val taskId = (params?.get(RegisterTask.Params.TASK_ID) as TString).get()
        val spec = params[RegisterTask.Params.SPEC] as Composite
        val specType = spec.schema.id.get()

        val row = taskRegistry.register(taskId, specType)
        // Open the task's sync lane so withTask-tagged constituent saves stage
        // (an unopened lane id falls through to the per-save path — the gate).
        laneEmitter()?.openSyncLane(taskId)
        return row.toComposite(executionContext) as T
    }
}

internal class RenewTaskLeaseImplementation(
    private val taskRegistry: TaskRegistry,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val taskId = (params?.get(RenewTaskLease.Params.TASK_ID) as TString).get()
        val row = taskRegistry.renewLease(taskId)
            ?: error("RenewTaskLease: unknown task '$taskId' (expired and swept, or never registered)")
        return row.toComposite(executionContext) as T
    }
}

internal class TransitionTaskImplementation(
    private val taskRegistry: TaskRegistry,
    private val laneEmitter: () -> SyncBlockEmitter?,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val taskId = (params?.get(TransitionTask.Params.TASK_ID) as TString).get()
        val transition = (params[TransitionTask.Params.TRANSITION] as TString).get()
        val terminalState = when (transition) {
            TransitionTask.Transitions.COMPLETE -> BackgroundTask.States.DONE
            TransitionTask.Transitions.FAIL -> BackgroundTask.States.FAILED
            TransitionTask.Transitions.CANCEL -> BackgroundTask.States.CANCELLED
            else -> error("TransitionTask: unknown transition '$transition'")
        }
        val row = taskRegistry.transition(taskId, terminalState)
            ?: error("TransitionTask: unknown task '$taskId'")
        // Final checkpoint + lane teardown (D9: task lease = lane lease).
        laneEmitter()?.closeSyncLane(taskId)
        return row.toComposite(executionContext) as T
    }
}
