package org.tenshu.core.common.proxy

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
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.TerminalOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Regression test for the in-process client surviving a proxy teardown
 * (the "Registering your realm" incident, 2026-07-25).
 *
 * On Android, `ProxyService.onDestroy` quiesces every session whenever the
 * last Binder client unbinds — while Control's UI process lives on. The old
 * `by lazy` executor in `ProxyModule` pinned the first session's
 * `ExecutionContext` forever, so every op after the teardown died against the
 * cancelled sessionScope. This test drives exactly that sequence against
 * [SessionAwareLocalTaskExecutor]: op → service teardown → op, and requires
 * the second op to succeed on a freshly minted session. A *final* shutdown
 * (`shutdownAndJoin`, process/test exit) must instead fail contained — typed
 * failure, no resurrected session.
 */
class SessionAwareLocalTaskExecutorTest {

    private val opSchema = operation(
        id = "session-aware-op",
        traits = listOf(TerminalOperation.Schema)
    )

    private fun testProxy(onRun: () -> Unit = {}): TestProxy =
        TestProxy(
            implementationFactoryOverride = { id, _ ->
                if (id == "session-aware-op") {
                    object : AsyncOperationImplementation {
                        @Suppress("UNCHECKED_CAST")
                        override suspend fun <T : Data?> execute(
                            executionContext: ExecutionContext,
                            operationContext: OperationContext?,
                            implementationFactory: OperationImplementationFactory,
                            caller: Any?,
                            params: Map<String, Data>?
                        ): T {
                            onRun()
                            return null as T
                        }
                    }
                } else null
            }
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
    fun opsSucceedAcrossAFullProxyTeardown() = runTest {
        var runs = 0
        val proxy = testProxy(onRun = { runs++ })
        val adopted = mutableListOf<ExecutionContext>()
        val executor = SessionAwareLocalTaskExecutor(
            sessionProvider = { proxy.clientRegistry.getExecutionContext("local-client") },
            onSessionAdopted = { adopted.add(it) },
        )

        assertIs<TResult.Success<*>>(executor.executeAsync(proxy.task()))
        assertEquals(1, runs)
        assertEquals(1, adopted.size, "first use adopts the initial session")

        // The incident sequence: ProxyService.onDestroy quiesces every session
        // while the process (this executor) lives on.
        proxy.clientRegistry.quiesceSessionsAndJoin()

        // Before the fix this died with a silently-swallowed
        // CancellationException — the caller hung forever.
        assertIs<TResult.Success<*>>(executor.executeAsync(proxy.task()))
        assertEquals(2, runs)
        assertEquals(2, adopted.size, "the rebuild adopts a freshly minted session")
        assertNotSame(adopted[0], adopted[1], "the dead session must not be re-adopted")
    }

    @Test
    fun finalShutdown_failsContained_withoutResurrectingASession() = runTest {
        var runs = 0
        val proxy = testProxy(onRun = { runs++ })
        val adopted = mutableListOf<ExecutionContext>()
        val executor = SessionAwareLocalTaskExecutor(
            sessionProvider = { proxy.clientRegistry.getExecutionContext("local-client") },
            onSessionAdopted = { adopted.add(it) },
        )

        assertIs<TResult.Success<*>>(executor.executeAsync(proxy.task()))

        // Final teardown (process/test exit): the registry closes against new
        // sessions — a straggler op must not resurrect background loops
        // against a dropped DI world.
        proxy.clientRegistry.shutdownAndJoin()

        val result = executor.executeAsync(proxy.task())
        val failure = assertIs<TResult.Failure<*>>(result)
        assertIs<InternalError>(failure.error, "dead-scope guard yields a contained typed failure")
        assertEquals(1, runs, "the impl must not run after a final shutdown")
        assertEquals(1, adopted.size, "no session may be resurrected after a final shutdown")
    }

    @Test
    fun liveSessionIsReused_noSpuriousRebuilds() = runTest {
        val proxy = testProxy()
        val adopted = mutableListOf<ExecutionContext>()
        val executor = SessionAwareLocalTaskExecutor(
            sessionProvider = { proxy.clientRegistry.getExecutionContext("local-client") },
            onSessionAdopted = { adopted.add(it) },
        )

        executor.executeAsync(proxy.task())
        executor.executeAsync(proxy.task())
        executor.executeAsyncCancellable(proxy.task())

        assertEquals(1, adopted.size, "a live session must be reused, not re-adopted")
        assertSame(
            adopted.single(),
            proxy.clientRegistry.getExecutionContext("local-client"),
            "the adopted session is the registry's session"
        )
    }
}
