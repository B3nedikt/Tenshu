package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation

/**
 * Proxy-side half of [org.tenshu.core.Resolve]: hand back what the chain already produced.
 *
 * ⚠⚠ It looks like a no-op because proxy-side it **is** one, and that is the whole point. The ops
 * before it did the work — `count()` produced the integer — and this exists so the chain has a
 * terminal carrying `IoOperation`, which is what makes the CLIENT dispatch it asynchronously
 * instead of down the sync path that cannot cross IPC. The value of the operation is in its traits,
 * not in its body.
 *
 * ⚠ [SyncOperationImplementation], like `MaybeGetImplementation`: a value chain evaluated entirely
 * proxy-side has no IO gap of its own, so the local executor can still run it without going async.
 */
internal class ResolveImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T = caller as T
}
