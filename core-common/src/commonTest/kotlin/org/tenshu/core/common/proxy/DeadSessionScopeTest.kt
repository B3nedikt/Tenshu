package org.tenshu.core.common.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.InternalError
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.get
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.TerminalOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the dead-scope guard in `ExecutionContext.execute`'s
 * async branch (the "Registering your realm" incident, 2026-07-25).
 *
 * An async op dispatched after its session's scope died used to ride
 * `sessionScope.async {}` on a cancelled scope: `await()` threw a
 * CancellationException that `LocalTaskExecutor` rethrew (it cannot be told
 * apart from caller cancellation without inspecting the caller's context) and
 * upstream coroutine machinery (`viewModelScope.launch`) swallowed as a normal
 * cancellation — the caller observed an eternal hang, the impl never ran, and
 * no error was recorded anywhere. The guard converts a dead *op* scope into a
 * typed `TResult.Failure` while preserving genuine caller cancellation.
 */
class DeadSessionScopeTest {

    private val opSchema = operation(
        id = "dead-scope-op",
        traits = listOf(TerminalOperation.Schema)
    )

    private fun asyncImpl(body: suspend () -> Unit = {}) = object : AsyncOperationImplementation {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            body()
            return null as T
        }
    }

    private fun testProxy(impl: AsyncOperationImplementation): TestProxy =
        TestProxy(
            implementationFactoryOverride = { id, _ -> if (id == "dead-scope-op") impl else null }
        ).apply {
            schemaRegistry.registerConfig(
                tModule {
                    operation(interfaceClass = Operation::class, protocolSchema = opSchema)
                }
            )
        }

    private fun TestProxy.task() =
        listOf<Data>(OperationDto(params = emptyMap(), schema = opSchema)).toTList(tContext)

    @Test
    fun asyncOpOnLiveSessionScope_succeeds() = runTest {
        var ran = false
        val proxy = testProxy(asyncImpl { ran = true })

        val result = LocalTaskExecutor(proxy.executionContext).executeAsync(proxy.task())

        assertTrue(
            result is TResult.Success<*>,
            "expected success, got: ${(result as? TResult.Failure<*>)?.error?.let { e ->
                (e as? InternalError)?.internalDetail?.get() ?: e.toString()
            } ?: result}"
        )
        assertTrue(ran, "the impl must have run")
    }

    @Test
    fun asyncOpOnDeadSessionScope_returnsTypedFailure_notSilentCancellation() = runTest {
        var ran = false
        val proxy = testProxy(asyncImpl { ran = true })
        val context = proxy.executionContext

        // Simulate the proxy teardown that outlives the process: the session's
        // scope is cancelled while the executor (and its callers) live on.
        context.sessionScope.cancel("proxy torn down")

        val result = LocalTaskExecutor(context).executeAsync(proxy.task())

        // The one behaviour that must never come back: a CancellationException
        // escaping here. It must be a *visible* typed failure.
        val failure = assertIs<TResult.Failure<*>>(result)
        val error = assertIs<InternalError>(failure.error)
        assertTrue(
            error.internalDetail?.get().orEmpty().contains("torn down"),
            "expected the session-torn-down detail, got: ${error.internalDetail?.get()}"
        )
        assertFalse(ran, "the impl must not run on a dead scope")
    }

    @Test
    fun callerCancellation_stillPropagatesAsCancellation() = runTest {
        val proxy = testProxy(asyncImpl { awaitCancellation() })
        val context = proxy.executionContext

        var result: TResult<Data>? = null
        val caller = launch {
            result = LocalTaskExecutor(context).executeAsync(proxy.task())
        }
        runCurrent() // let the op start and suspend
        caller.cancel()
        caller.join()

        assertTrue(caller.isCancelled, "caller cancellation must stay a cancellation")
        assertNull(result, "a cancelled caller must not receive a synthesized result")
    }

    @Test
    fun deadScopeFailure_reportsErrorEvent() = runTest {
        val proxy = testProxy(asyncImpl())
        val context = proxy.executionContext
        val recorded = mutableListOf<String>()
        context.errorEventRecorder = ErrorEventRecorder { _, operationId, _ ->
            recorded.add(operationId)
        }

        context.sessionScope.cancel("proxy torn down")
        LocalTaskExecutor(context).executeAsync(proxy.task())

        // The catch-all records the typed failure — the incident's other half
        // was that nothing was logged anywhere.
        assertEquals(listOf("dead-scope-op"), recorded)
    }

    /**
     * The guard's scope is `evaluationScope ?: sessionScope` — two scopes with
     * *opposite* lifetimes. `sessionScope` dies only on teardown (the incident
     * above); `evaluationScope` is the per-request scope
     * `LocalTaskExecutor.executeAsyncCancellable` opens with `coroutineScope { }`
     * and dies at the end of **every** request, entirely normally.
     *
     * Converting a dead scope to `sessionTornDown()` without telling the two
     * apart reports ordinary request completion as a fatal session teardown.
     * The window is narrow — the scope must die *between* the pre-check and
     * `await()` — so this is a race, and Kotlin/Native's scheduling loses it
     * consistently where the JVM's wins it (12 `iosSimulatorArm64Test`
     * failures in `:fit-shared-data` against a byte-identical store).
     *
     * The invariant, independent of what else is right: **a live session must
     * never be reported as torn down.**
     */
    @Test
    fun requestScopeDeath_onALiveSession_isNotReportedAsSessionTornDown() = runTest {
        val started = CompletableDeferred<Unit>()
        val proxy = testProxy(asyncImpl { started.complete(Unit); awaitCancellation() })

        // The per-request scope, live at dispatch — so the guard's pre-check
        // passes and the op rides `requestScope.async { }`.
        val requestScope = CoroutineScope(coroutineContext + Job())
        val context = proxy.executionContext.withEvaluationScope(requestScope)

        var result: TResult<Data>? = null
        val caller = launch { result = LocalTaskExecutor(context).executeAsync(proxy.task()) }
        started.await()
        // The request ends while the session lives on — the ordinary case.
        requestScope.cancel("request completed")
        caller.join()

        assertTrue(
            proxy.executionContext.sessionScope.isActive,
            "precondition: the session must still be alive"
        )
        val failure = result as? TResult.Failure<*>
        val detail = (failure?.error as? InternalError)?.internalDetail?.get().orEmpty()
        assertFalse(
            detail.contains("torn down"),
            "a per-request scope ending must not be reported as a session teardown, got: $detail"
        )
    }
}
