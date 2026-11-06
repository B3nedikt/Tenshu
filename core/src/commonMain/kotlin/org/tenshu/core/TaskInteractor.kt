package org.tenshu.core

/**
 * Interactor for IO-bound operations.
 */
interface TaskInteractor<out T : Data?> : Interactor<T> {
    /**
     * Executes the operation asynchronously and suspends until completion.
     */
    suspend fun execute(): TResult<T>
}
