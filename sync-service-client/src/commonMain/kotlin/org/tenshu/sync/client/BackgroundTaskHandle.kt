package org.tenshu.sync.client

import kotlinx.coroutines.withContext
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TaskSpec
import org.tenshu.core.WithTaskElement
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.util.generateId
import org.tenshu.core.getOrNull
import org.tenshu.sync.BackgroundTask
import org.tenshu.sync.TaskService
import org.tenshu.sync.TransitionTask
import org.tenshu.sync.renewTaskLease
import org.tenshu.sync.registerTask
import org.tenshu.sync.transitionTask

val TenshuClient.taskService: TaskService
    get() = getService(TaskService.ID)

/**
 * The client-side typed handle over one registered bulk task
 * (`core-task-composite.md` §5). [R] is the task's phantom result type,
 * inferred from the [TaskSpec] passed to [register] — never on the wire.
 * Lifecycle delegates to the generic `TaskService` ops; constituent
 * one-shot `IoOperation` calls join via [withTask].
 */
class BackgroundTaskHandle<R : Data?> internal constructor(
    val taskId: String,
    private val service: TaskService,
) {
    /** Lease heartbeat — call at least every lease/2 during a long loop. Progress is a domain concern (see RenewTaskLease). */
    suspend fun renewLease(): TResult<BackgroundTask?> =
        service.renewTaskLease(taskId)

    suspend fun complete(): TResult<BackgroundTask?> =
        service.transitionTask(taskId, TransitionTask.Transitions.COMPLETE)

    suspend fun fail(): TResult<BackgroundTask?> =
        service.transitionTask(taskId, TransitionTask.Transitions.FAIL)

    suspend fun cancel(): TResult<BackgroundTask?> =
        service.transitionTask(taskId, TransitionTask.Transitions.CANCEL)
}

/**
 * Register a bulk task: mints a client-side taskId (the Phase-L2
 * precedent), creates the proxy task row, opens its sync lane, and starts
 * its lease. Returns the typed handle; the work itself is the client loop
 * that follows — wrap it in [withTask] so constituent ops join the task.
 * Returns null if registration failed (e.g. proxy unreachable).
 */
suspend fun <R : Data?> TaskService.register(spec: TaskSpec<R>): BackgroundTaskHandle<R>? {
    val taskId = "task-" + generateId()
    val result = registerTask(taskId, spec)
    if (result !is TResult.Success || result.getOrNull() == null) return null
    return BackgroundTaskHandle(taskId, this)
}

/**
 * Runs [block] with [task]'s id installed as a [WithTaskElement]: every
 * constituent operation call inside joins the task — carried to the proxy
 * on the L2 cancellable envelope, threaded to `saveData` so the saves stage
 * into the task's sync lane. Deliberately not exactly-once: joining one
 * task from several `withTask` blocks is legal (e.g. a retry pass).
 */
suspend fun <T> withTask(task: BackgroundTaskHandle<*>, block: suspend () -> T): T =
    withContext(WithTaskElement(task.taskId)) { block() }
