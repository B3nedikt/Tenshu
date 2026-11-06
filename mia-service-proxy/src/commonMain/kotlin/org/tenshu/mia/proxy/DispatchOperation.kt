package org.tenshu.mia.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation

/**
 * Calls another service's operation implementation directly, whichever kind it is.
 *
 * ⚠⚠ **This exists because a direct call has to know something the dispatcher normally hides.**
 * `OperationImplementation` is a family — sync, async, pure — and `evaluateCallstack` picks the
 * right arm. Reaching for an implementation by id and casting to one arm compiles fine and
 * fails at *runtime*, a long way from the assumption: MQ-3a assumed `IssueDeviceCommand` was
 * async (it is sync) and MQ-3b assumed `SaveEvent` was async (it is sync too). Two for two, so
 * the guess is not worth making twice more.
 *
 * ⚠ Returns `false` when the op is genuinely absent — a caller must say "I can't reach that"
 * rather than reporting a success nothing performed.
 */
/**
 * As [dispatchOperation], but **keeps the answer** — for a call made *for* its result rather than
 * for its effect (MQ-5's intent classification).
 *
 * ⚠ Returns `null` both when the op is absent and when it returned nothing, because a caller that
 * needs the value cannot use "it ran" for anything. The two are distinguishable at this seam and
 * nowhere downstream cares: MQ-5's caller falls back to the deterministic planner either way.
 */
internal suspend fun dispatchOperationForResult(
    operationId: String,
    executionContext: ExecutionContext,
    implementationFactory: OperationImplementationFactory,
    params: Map<String, Data>,
): Data? {
    return when (val implementation = implementationFactory(operationId)) {
        is SyncOperationImplementation -> implementation.execute<Data?>(
            executionContext = executionContext,
            operationContext = null,
            implementationFactory = implementationFactory,
            caller = null,
            params = params,
        )

        is AsyncOperationImplementation -> implementation.execute<Data?>(
            executionContext = executionContext,
            operationContext = null,
            implementationFactory = implementationFactory,
            caller = null,
            params = params,
        )

        else -> null
    }
}

internal suspend fun dispatchOperation(
    operationId: String,
    executionContext: ExecutionContext,
    implementationFactory: OperationImplementationFactory,
    params: Map<String, Data>,
): Boolean {
    return when (val implementation = implementationFactory(operationId)) {
        is SyncOperationImplementation -> {
            implementation.execute<Data?>(
                executionContext = executionContext,
                operationContext = null,
                implementationFactory = implementationFactory,
                caller = null,
                params = params,
            )
            true
        }

        is AsyncOperationImplementation -> {
            implementation.execute<Data?>(
                executionContext = executionContext,
                operationContext = null,
                implementationFactory = implementationFactory,
                caller = null,
                params = params,
            )
            true
        }

        else -> false
    }
}
