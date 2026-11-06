package org.tenshu.core.common.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.CancellableTaskInteractor
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.BackgroundOperation
import org.tenshu.core.trait.TerminalOperation
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase L2 in-process carve-out closed (`docs/proposals/core-background-processing.md`
 * §8 "Phase L2"). With no `ProxyGateway` (the JS SDK shape — provider over a
 * `LocalTaskExecutor`), a `BackgroundOperation` driven by a
 * `CancellableTaskInteractor` must run *awaited and cancellable*, not the
 * legacy detached `sessionScope.launch` + immediate `Success(null)`.
 */
class InProcessBackgroundCancelTest {

    private val stubSchema = operation(
        id = "inproc-l2-stub",
        traits = listOf(TerminalOperation.Schema, BackgroundOperation.Schema)
    )

    private class StubBgOp(
        val started: CompletableDeferred<Unit>,
        val cancelled: CompletableDeferred<Unit>,
        val release: CompletableDeferred<Unit> = CompletableDeferred()
    ) : AsyncOperationImplementation {
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            started.complete(Unit)
            try {
                release.await()
                @Suppress("UNCHECKED_CAST")
                return null as T
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
        }
    }

    private fun proxyWithStub(impl: AsyncOperationImplementation): TestProxy =
        TestProxy(
            implementationFactoryOverride = { id, _ ->
                if (id == stubSchema.id.get()) impl else null
            }
        ).apply {
            schemaRegistry.registerConfig(
                tModule {
                    operation(interfaceClass = Operation::class, protocolSchema = stubSchema)
                }
            )
        }

    private fun TestProxy.handleForStub(): org.tenshu.core.TaskHandle<Data?> = runBlocking {
        val ro = object : OperationRo(
            requestChain = LocalRequestChain(localTaskExecutor, tContext)
                .addArray(OperationDto(schema = stubSchema, params = emptyMap())),
            context = tContext
        ) {
            override val schema = stubSchema
        }
        val interactor = interactorProvider.getInteractor<Data?>(ro)
        assertTrue(
            interactor is CancellableTaskInteractor<*>,
            "in-process BackgroundOperation must resolve to a CancellableTaskInteractor"
        )
        @Suppress("UNCHECKED_CAST")
        (interactor as CancellableTaskInteractor<Data?>).execute()
    }

    @Test
    fun inprocess_background_op_is_awaited_not_detached() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stub = StubBgOp(started, CompletableDeferred(), release)
        val proxy = proxyWithStub(stub)

        val handle = proxy.handleForStub()
        var result: TResult<Data?>? = null
        val awaitJob = launch(Dispatchers.Default) { result = handle.await() }

        withTimeout(3_000) { started.await() }
        delay(250)
        // The legacy detach would have returned Success(null) instantly; the
        // carve-out being closed means await() is genuinely suspended on the
        // running op.
        assertTrue(awaitJob.isActive, "await() must not complete while the op is still running")
        assertTrue(result == null, "no result until the op completes")

        release.complete(Unit)
        withTimeout(3_000) { awaitJob.join() }
        assertTrue(result is TResult.Success, "await() resolves with the op's real result")
    }

    @Test
    fun inprocess_taskhandle_cancel_unwinds_the_background_op() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val proxy = proxyWithStub(StubBgOp(started, cancelled))

        val handle = proxy.handleForStub()
        val awaitJob = launch(Dispatchers.Default) {
            runCatching { handle.await() }
        }

        withTimeout(3_000) { started.await() }
        handle.cancel()
        // The in-process op cooperatively observed CancellationException —
        // the carve-out is closed (previously this would hang: the op ran
        // detached on sessionScope and the handle controlled nothing).
        withTimeout(3_000) { cancelled.await() }
        awaitJob.join()
    }
}
