package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.InternalError
import org.tenshu.core.NotFoundError
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.get
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.TerminalOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 3 of `docs/proposals/core-error-channel.md` (v0.2 review Option A):
 * `LocalTaskExecutor` is the single catch-all and produces typed `TResult.Failure`s:
 *  - a [TenshuException] thrown by an impl unwraps to the carried `TError` verbatim;
 *  - any other [Exception] wraps as [InternalError] with `internalDetail = e.message`
 *    (proxy-local — Phase 6's upload pipeline redacts it; proposal §5).
 */
class LocalTaskExecutorTest {

    @Test
    fun execute_unwrapsTenshuExceptionToTypedFailure() = runTest {
        val typedOpSchema = operation(
            id = "typed-error-op",
            traits = listOf(TerminalOperation.Schema)
        )

        val testProxy = TestProxy(
            implementationFactoryOverride = { id, _ ->
                if (id == "typed-error-op") {
                    object : SyncOperationImplementation {
                        override fun <T : Data?> execute(
                            executionContext: ExecutionContext,
                            operationContext: OperationContext?,
                            implementationFactory: OperationImplementationFactory,
                            caller: Any?,
                            params: Map<String, Data>?
                        ): T {
                            throw TenshuException(
                                NotFoundError.create(
                                    context = executionContext.context,
                                    message = "thing missing"
                                )
                            )
                        }
                    }
                } else null
            }
        ).apply {
            schemaRegistry.registerConfig(
                tModule {
                    operation(
                        interfaceClass = Operation::class,
                        protocolSchema = typedOpSchema
                    )
                }
            )
        }

        val executor = LocalTaskExecutor(testProxy.executionContext)
        val task = listOf<Data>(
            OperationDto(params = emptyMap(), schema = typedOpSchema)
        ).toTList(testProxy.tContext)

        val result = executor.execute(task)

        assertTrue(result is TResult.Failure<*>)
        val error = assertIs<NotFoundError>(result.error)
        assertEquals("thing missing", error.message.get())
    }

    @Test
    fun execute_wrapsUnexpectedExceptionAsInternalError() = runTest {
        val errorOpSchema = operation(
            id = "error-op",
            traits = listOf(TerminalOperation.Schema)
        )

        val testProxy = TestProxy(
            implementationFactoryOverride = { id, _ ->
                if (id == "error-op") {
                    object : SyncOperationImplementation {
                        override fun <T : Data?> execute(
                            executionContext: ExecutionContext,
                            operationContext: OperationContext?,
                            implementationFactory: OperationImplementationFactory,
                            caller: Any?,
                            params: Map<String, Data>?
                        ): T {
                            throw RuntimeException("Test exception")
                        }
                    }
                } else null
            }
        ).apply {
            schemaRegistry.registerConfig(
                tModule {
                    operation(
                        interfaceClass = Operation::class,
                        protocolSchema = errorOpSchema
                    )
                }
            )
        }

        val executor = LocalTaskExecutor(testProxy.executionContext)
        val task = listOf<Data>(
            OperationDto(params = emptyMap(), schema = errorOpSchema)
        ).toTList(testProxy.tContext)

        val result = executor.execute(task)

        assertTrue(result is TResult.Failure<*>)
        val error = assertIs<InternalError>(result.error)
        // Proxy-local — redacted on Phase 6 upload (proposal §5 PII-by-construction).
        assertEquals("Test exception", error.internalDetail?.get())
    }

    /**
     * Regression guard for the Drive-on-iOS `Back up now` crash (iOS crash report
     * 2026-08-13): an `AsyncOperationImplementation` that reports failure the way
     * every impl does — a thrown [TenshuException] (Shape A,
     * `core-error-channel.md` §3.1) — must come back as a `TResult.Failure`
     * **and must not escape the request scope**.
     *
     * ⚠ The two halves are separate failure modes, and only the second one was
     * broken. [executeAsync]'s catch-all always produced the right `TResult`;
     * what killed the process was the request scope failing *anyway* once the
     * block returned, because `ExecutionContext` runs the impl as
     * `evaluationScope.async { … }` and an ordinary `async` also reports failure
     * upward to its parent job. So a test that only asserted on the returned
     * value passed throughout — this one has to assert the call *returns at all*,
     * which is why the assertion that matters is simply that no exception left
     * `executeAsyncCancellable`. On Kotlin/Native that escape reached the
     * uncaught-exception handler, i.e. `abort()`.
     *
     * The two-arg (`taskId`) overload is the one exercised because a `withTask`
     * block — Drive's `withBulkUploadTask`, Fit's `withImportTask` — is what
     * routes an op down this path at all (`DefaultInteractorProvider`, TS-4).
     */
    @Test
    fun executeAsyncCancellable_asyncImplThrowingTypedError_doesNotEscapeTheRequestScope() = runTest {
        val asyncOpSchema = operation(
            id = "async-typed-error-op",
            traits = listOf(TerminalOperation.Schema)
        )

        val testProxy = TestProxy(
            coroutineScope = this,
            implementationFactoryOverride = { id, _ ->
                if (id == "async-typed-error-op") {
                    object : AsyncOperationImplementation {
                        override suspend fun <T : Data?> execute(
                            executionContext: ExecutionContext,
                            operationContext: OperationContext?,
                            implementationFactory: OperationImplementationFactory,
                            caller: Any?,
                            params: Map<String, Data>?
                        ): T {
                            throw TenshuException(
                                NotFoundError.create(
                                    context = executionContext.context,
                                    message = "thing missing"
                                )
                            )
                        }
                    }
                } else null
            }
        ).apply {
            schemaRegistry.registerConfig(
                tModule {
                    operation(
                        interfaceClass = Operation::class,
                        protocolSchema = asyncOpSchema
                    )
                }
            )
        }

        val executor = LocalTaskExecutor(testProxy.executionContext)
        val task = listOf<Data>(
            OperationDto(params = emptyMap(), schema = asyncOpSchema)
        ).toTList(testProxy.tContext)

        // Before the supervisorScope fix this line THREW instead of returning.
        val result = executor.executeAsyncCancellable(task, taskId = "test-task")

        assertTrue(result is TResult.Failure<*>)
        val error = assertIs<NotFoundError>(result.error)
        assertEquals("thing missing", error.message.get())
    }
}
