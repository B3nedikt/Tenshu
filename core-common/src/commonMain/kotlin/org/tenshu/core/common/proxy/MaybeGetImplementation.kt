package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.Entity

/**
 * Terminal that unwraps a [Maybe] and — when the wrapped value carries
 * any [PendingOnDemandProperty] placeholders left behind by
 * [SelectImplementation] — materialises them in place by dispatching
 * [OnDemandResolution.resolve] for each, via [materialisePendingOnDemandProperties].
 *
 * `Get` stays a [SyncOperationImplementation] so the sync-interactor path
 * (`SyncInteractor.execute()` via [LocalTaskExecutor.execute]) keeps
 * working for the common no-OnDemand case. When pending markers ARE
 * present the impl returns an [AsyncReference] whose `asyncCall` does
 * the fetch + re-resolve and whose `remainingCallstack` is empty —
 * `LocalTaskExecutor.executeAsync` awaits the call and returns the
 * materialised value as the final result. The sync executor would
 * throw `SynchronousViolationException` for an `AsyncReference`, which
 * is the correct behaviour: callers that dispatch via the sync
 * interactor cannot transparently fetch OnDemand bytes (network I/O
 * can't run on a non-suspend path) and need to use the suspend
 * interactor instead.
 */
internal class MaybeGetImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        if (caller !is Maybe<*>) {
            error("Get must be called on a Maybe. Caller was: $caller")
        }

        val value = (caller as? Maybe.Some<*>)?.value as? Data
        if (!hasPendingPlaceholders(value)) {
            return value as T
        }

        // OnDemand resolution is required — defer to async via AsyncReference.
        return AsyncReference.create(
            asyncCall = {
                materialisePendingOnDemandProperties(
                    executionContext = executionContext,
                    value = value
                )
            },
            remainingCallstack = emptyList()
        ) as T
    }

    private fun hasPendingPlaceholders(value: Data?): Boolean {
        if (value !is Entity<*>) return false
        return value.properties.values.any { it is PendingOnDemandProperty }
    }
}
