package org.tenshu.core.common.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.app.AnnounceSession
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.schema.operation
import org.tenshu.core.toTString
import org.tenshu.core.trait.TerminalOperation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ **One reply the proxy never writes must cost one caller, not the whole connection**
 * (`docs/protocol/desktop-ipc.md` §2.2; `tech-debt.md` *"Desktop IPC: one lost reply wedges the whole
 * connection"*).
 *
 * The lost reply is faked **the way production loses one**: the real [UnixSocketServer] over a real
 * [org.tenshu.core.common.proxy.ProxyGateway], serving an operation whose body never returns. The
 * proxy stays alive, the socket stays open, and no frame for that call is ever written — nothing in
 * the harness is a cancellable coroutine standing in for the proxy, which is the fake that once
 * passed against a fix that could never fire on a device.
 *
 * Before the fix `call()` held `callLock` inside `NonCancellable` until its reply arrived, so the
 * first test's unrelated call queued behind the lost one for the life of the process, and the
 * second test's caller could not stop waiting even when its own timeout fired.
 */
class UnixSocketLostReplyTest {

    private val hangSchema = operation(id = "lost-reply-hang", traits = listOf(TerminalOperation.Schema))
    private val quickSchema = operation(id = "lost-reply-quick", traits = listOf(TerminalOperation.Schema))

    /** Never answers: the proxy is alive and well, it simply never produces this reply. */
    private class HangOp(val started: CompletableDeferred<Unit>, val release: CompletableDeferred<Unit>) :
        AsyncOperationImplementation {
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            started.complete(Unit)
            release.await()
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    private object QuickOp : AsyncOperationImplementation {
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    private class Rig(
        val proxy: TestProxy,
        val executor: RemoteBinaryTaskExecutor,
        val socketPath: String,
        val started: CompletableDeferred<Unit>,
        val release: CompletableDeferred<Unit>,
    )

    private fun task(proxy: TestProxy, schema: org.tenshu.core.schema.OperationSchema): TList<Data> =
        listOf<Data>(OperationDto(params = emptyMap(), schema = schema)).toTList(proxy.tContext)

    private fun rig(scope: CoroutineScope): Rig {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hang = HangOp(started, release)
        val proxy = TestProxy(
            implementationFactoryOverride = { id, _ ->
                when (id) {
                    hangSchema.id.get() -> hang
                    quickSchema.id.get() -> QuickOp
                    else -> null
                }
            },
        ).apply {
            schemaRegistry.registerConfig(
                tModule {
                    operation(interfaceClass = Operation::class, protocolSchema = hangSchema)
                    operation(interfaceClass = Operation::class, protocolSchema = quickSchema)
                }
            )
        }
        val socketPath = Files.createTempDirectory("uds-lost").resolve("t.sock").toAbsolutePath().toString()
        UnixSocketServer(proxy.gateway, proxy.serializer, socketPath).start()

        val parser = BinaryParser(proxy.schemaRegistry, proxy.schemaRegistry)
        val transport = UnixSocketTaskTransport(parser, socketPath)
        val executor = RemoteBinaryTaskExecutor(BinarySerializer(), parser, transport, proxy.tContext, scope)
        // §6.5 reject-if-silent: announce like production does, over the real socket.
        executor.sessionPreflight = {
            val announce: TList<Data> = listOf<Data>(
                OperationDto(
                    params = mapOf(
                        AnnounceSession.Params.APP_ID to "app.test".toTString(proxy.tContext),
                        AnnounceSession.Params.CURRENT to
                            proxy.schemaRegistry.currentVersionVector(proxy.tContext),
                        AnnounceSession.Params.REQUIRES to
                            proxy.schemaRegistry.currentVersionVector(proxy.tContext),
                    ),
                    schema = AnnounceSession.Schema,
                )
            ).toTList(proxy.tContext)
            executor.executeAsync(announce)
        }
        executor.bind()
        return Rig(proxy, executor, socketPath, started, release)
    }

    @Test
    fun aReplyThatNeverComesDoesNotBlockAnUnrelatedCall() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = rig(scope)
        try {
            // Warm the connection so the only thing in flight below is the lost call.
            withTimeout(5_000) { rig.executor.executeAsync(task(rig.proxy, quickSchema)) }

            scope.launch { rig.executor.executeAsync(task(rig.proxy, hangSchema)) }
            withTimeout(5_000) { rig.started.await() } // the lost call is on the proxy, unanswered
            // ⚠ The call id is a transport tag, not a task id: a plain call must not register in
            // the gateway's in-flight registry (that is the detached, sync-laned Layer-2 path).
            assertEquals(0, rig.proxy.gateway.inFlightCount.value, "a plain call was served as a task")

            val unrelated = withTimeoutOrNull(3_000) {
                rig.executor.executeAsync(task(rig.proxy, quickSchema))
            }
            assertNotNull(
                unrelated,
                "an unrelated call queued behind the one whose reply never came — the whole " +
                    "connection is wedged, not just that read",
            )
            assertTrue(
                unrelated is TResult.Success<*>,
                "and it must be answered, not failed. Got: $unrelated",
            )
        } finally {
            rig.release.complete(Unit) // free any waiter the old transport would still hold
            scope.cancel()
            rig.executor.unbind()
            Files.deleteIfExists(Path.of(rig.socketPath))
        }
    }

    @Test
    fun aCallerCanStopWaitingForAReplyThatNeverComes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = rig(scope)
        try {
            withTimeout(5_000) { rig.executor.executeAsync(task(rig.proxy, quickSchema)) }

            // The caller's own budget — what `CollectionsViewModel.refresh()` puts round its reads.
            val caller = scope.launch {
                withTimeoutOrNull(300) { rig.executor.executeAsync(task(rig.proxy, hangSchema)) }
            }
            withTimeout(5_000) { rig.started.await() }

            // `join` is cancellable, so this outer bound works even if the caller is stuck.
            val settled = withTimeoutOrNull(3_000) { caller.join() } != null
            assertTrue(
                settled,
                "the caller's own timeout could not free it: the wait is NonCancellable, so a " +
                    "screen's retry has nothing to retry with",
            )
        } finally {
            rig.release.complete(Unit)
            scope.cancel()
            rig.executor.unbind()
            Files.deleteIfExists(Path.of(rig.socketPath))
        }
    }
}
