package org.tenshu.core.common.request

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList

/**
 * Thrown when a synchronous execution path hits an asynchronous gap.
 */
class SynchronousViolationException(message: String) : RuntimeException(message)

interface TaskExecutor {
    /**
     * Executes the task synchronously.
     *
     * @return The result if evaluation completes immediately.
     * @throws SynchronousViolationException if evaluation hits an interruption (IO gap).
     */
    fun execute(task: TList<Data>): TResult<Data>
}

interface AsyncTaskExecutor : TaskExecutor {
    /**
     * Executes the task asynchronously, driving the interruption loop.
     * Cooperates with the caller's CoroutineContext for cancellation.
     */
    suspend fun executeAsync(task: TList<Data>): TResult<Data>
}
