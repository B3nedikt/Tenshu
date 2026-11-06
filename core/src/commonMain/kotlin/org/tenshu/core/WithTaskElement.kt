package org.tenshu.core

import kotlin.coroutines.CoroutineContext

/**
 * The coroutine-context element `withTask(task) { }` installs
 * (`core-task-composite.md` §5, Q-DSL-1 resolution): every constituent
 * operation call issued inside the block reads it at interactor level
 * (`TaskInteractor.execute()` is suspend, so the caller's context is in
 * scope) and carries [taskId] to the proxy on the Phase-L2 cancellable
 * envelope (`TaskTransport.callCancellable`) — zero new transport surface,
 * and `cancel(taskId)` composes: cancelling the task can cancel an
 * in-flight constituent call.
 *
 * Constituent ops stay ordinary one-shot `IoOperation`s (the color-blind
 * rule); only the *carrier* changes when a task element is present.
 */
class WithTaskElement(val taskId: String) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<WithTaskElement>
}
