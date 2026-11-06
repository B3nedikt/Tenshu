package org.tenshu.logging.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.NotFoundError
import org.tenshu.core.Operation
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.ErrorEventRecorder
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.id
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.TerminalOperation
import org.tenshu.logging.test.createLoggingServiceCommonTestRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 5 of `docs/proposals/core-error-channel.md`: when an impl throws a
 * typed [TenshuException], `LocalTaskExecutor`'s catch-all fires the wired
 * [ErrorEventRecorder] with the typed error + top-level op id + callstack
 * depth before returning the `TResult.Failure` on the wire reply path.
 *
 * The integration test exercises the recorder *port* (not the
 * [ErrorEventRecorderImpl] persistence layer — that's a separate suite that
 * needs realm DB plumbing). It verifies:
 *  - typed `TenshuException(NotFoundError)` reaches the recorder verbatim;
 *  - the catch-all still returns `TResult.Failure(error)` on the wire;
 *  - the operation id captured is the top-of-stack op, not anything deeper;
 *  - a misbehaving recorder cannot leak its own exception onto the wire.
 */
class ErrorEventRecorderIntegrationTest {

    private class RecordingRecorder : ErrorEventRecorder {
        data class Recorded(val error: TError, val operationId: String, val depth: Int)
        val calls = mutableListOf<Recorded>()
        override fun record(error: TError, operationId: String, callstackDepth: Int) {
            calls += Recorded(error, operationId, callstackDepth)
        }
    }

    private val throwingOpSchema = operation(
        id = "test/throwing",
        traits = listOf(TerminalOperation.Schema)
    )

    private fun setupProxy(): Pair<TestProxy, RecordingRecorder> {
        val recorder = RecordingRecorder()
        val proxy = TestProxy(
            schemaRegistry = createLoggingServiceCommonTestRegistry(),
            implementationFactoryOverride = { id, _ ->
                if (id == "test/throwing") throwingImpl() else null
            }
        ).apply {
            schemaRegistry.registerConfig(
                tModule {
                    operation(
                        interfaceClass = Operation::class,
                        protocolSchema = throwingOpSchema,
                    )
                }
            )
        }
        return proxy to recorder
    }

    private fun throwingImpl() = object : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: org.tenshu.core.common.proxy.OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            throw TenshuException(
                NotFoundError.create(
                    context = executionContext.context,
                    message = "synthetic-not-found"
                )
            )
        }
    }

    @Test
    fun typedThrow_firesRecorder_andStillReturnsWireFailure() = runTest {
        val (proxy, recorder) = setupProxy()

        // Build an ExecutionContext wrapper that exposes our recorder. The
        // catch-all reads it off `executionContext.errorEventRecorder` and
        // calls back synchronously inside `wrapException`.
        val executor = LocalTaskExecutor(
            proxy.executionContext.withEvaluationScopeAndRecorder(
                scope = proxy.coroutineScope,
                recorder = recorder,
            )
        )

        val task = listOf<Data>(
            OperationDto(params = emptyMap(), schema = throwingOpSchema)
        ).toTList(proxy.tContext)

        val result = executor.executeAsync(task)

        // Wire reply is still a typed Failure — recorder is a side-channel.
        assertIs<TResult.Failure<*>>(result)
        val error = assertIs<NotFoundError>(result.error)
        assertEquals("synthetic-not-found", error.message.get())

        // Recorder saw the typed error verbatim + the op id at the top of
        // the callstack. Depth is the size of the materialised task.
        assertEquals(1, recorder.calls.size, "Recorder should fire once per catch")
        val captured = recorder.calls.single()
        assertIs<NotFoundError>(captured.error)
        assertEquals("test/throwing", captured.operationId)
        assertEquals(1, captured.depth)
    }

    @Test
    fun recorderException_doesNotEscapeCatchAll() = runTest {
        val (proxy, _) = setupProxy()
        val misbehavingRecorder = ErrorEventRecorder { _, _, _ ->
            throw IllegalStateException("recorder is broken")
        }

        val executor = LocalTaskExecutor(
            proxy.executionContext.withEvaluationScopeAndRecorder(
                scope = proxy.coroutineScope,
                recorder = misbehavingRecorder,
            )
        )

        val task = listOf<Data>(
            OperationDto(params = emptyMap(), schema = throwingOpSchema)
        ).toTList(proxy.tContext)

        val result = executor.executeAsync(task)

        // Wire reply still carries the original typed error, not an
        // InternalError from the recorder. The recorder's exception must
        // be swallowed inside `wrapException`.
        assertIs<TResult.Failure<*>>(result)
        val error = result.error
        assertTrue(
            error is NotFoundError,
            "Misbehaving recorder must not leak as InternalError; got ${error.id}"
        )
    }
}

/** Test-only convenience: clone with both a scope and a recorder forwarded. */
private fun ExecutionContext.withEvaluationScopeAndRecorder(
    scope: kotlinx.coroutines.CoroutineScope,
    recorder: ErrorEventRecorder,
): ExecutionContext {
    return ExecutionContext(
        context = context,
        database = database,
        traitResolver = traitResolver,
        implementationFactory = implementationFactory,
        clientConnection = clientConnection,
        pureOperationRunner = pureOperationRunner,
        materializer = materializer,
        sessionScope = sessionScope,
        referenceResolver = referenceResolver,
        streamRegistry = streamRegistry,
        observerRegistry = observerRegistry,
        evaluationScope = scope,
        errorEventRecorder = recorder,
        initialization = {},
    )
}

