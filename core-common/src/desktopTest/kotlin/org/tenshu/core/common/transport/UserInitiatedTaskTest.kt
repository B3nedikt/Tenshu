package org.tenshu.core.common.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.CancellableTaskInteractor
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.WithTaskElement
import org.tenshu.core.common.DefaultInteractorProvider
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.BackgroundOperation
import org.tenshu.core.trait.TerminalOperation
import org.tenshu.core.userInitiatedWork
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The user-started mark, end to end (`control-background-work-off-fgs.md` §5.1): from
 * `userInitiatedWork { }` in the app, through the interactor's detached scope and the transport, to
 * the proxy's [org.tenshu.core.common.proxy.ProxyGateway.userInitiatedInFlightCount] — the figure
 * Android's foreground-service promotion counts.
 *
 * ⚠⚠ The property that matters is the negative one too: an unmarked task must count as automatic,
 * because the foreground service is held for user-started work only.
 */
class UserInitiatedTaskTest {

    private val stubSchema = operation(
        id = "user-initiated-stub",
        traits = listOf(TerminalOperation.Schema, BackgroundOperation.Schema),
    )

    /** A Background op that holds its request open until [release]. */
    private class HoldOp(
        val started: CompletableDeferred<Unit> = CompletableDeferred(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : AsyncOperationImplementation {
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

    /** Binder-shaped: records which method the client used and serves it through the gateway. */
    private class RecordingTransport(private val proxy: TestProxy) : TaskTransport {
        val methods: MutableList<String> = CopyOnWriteArrayList()
        override val isConnected: Boolean = true
        override fun bind() {}
        override fun unbind() {}
        override suspend fun call(request: ByteArray): ByteArray =
            proxy.gateway.call(CLIENT, request).also { methods += "call" }

        override suspend fun callCancellable(request: ByteArray, taskId: String): ByteArray {
            methods += "callCancellable"
            return proxy.gateway.call(CLIENT, request, taskId = taskId)
        }

        override suspend fun callCancellableUserInitiated(request: ByteArray, taskId: String): ByteArray {
            methods += "callCancellableUserInitiated"
            return proxy.gateway.call(CLIENT, request, taskId = taskId, userInitiated = true)
        }

        override fun sendFile(uri: String) {}
        override var onCallback: ((ByteArray) -> Unit)? = null
    }

    private class Rig(op: HoldOp, stubSchema: org.tenshu.core.schema.OperationSchema) {
        val proxy = TestProxy(
            coroutineScope = CoroutineScope(Dispatchers.Default),
            implementationFactoryOverride = { id, _ -> if (id == stubSchema.id.get()) op else null },
        ).apply {
            schemaRegistry.registerConfig(
                tModule { operation(interfaceClass = Operation::class, protocolSchema = stubSchema) },
            )
            admitSchemaSession(CLIENT)
        }
        val transport = RecordingTransport(proxy)
        val executor = RemoteBinaryTaskExecutor(
            BinarySerializer(),
            BinaryParser(proxy.schemaRegistry, proxy.schemaRegistry),
            transport,
            proxy.tContext,
        )
        val provider = DefaultInteractorProvider(taskExecutorProvider = { executor })

        fun ro(schema: org.tenshu.core.schema.OperationSchema) = object : OperationRo(
            requestChain = LocalRequestChain(executor, proxy.tContext)
                .addArray(OperationDto(schema = schema, params = emptyMap())),
            context = proxy.tContext,
        ) {
            override val schema = schema
        }
    }

    private suspend fun startBackgroundOp(rig: Rig): kotlinx.coroutines.Job {
        @Suppress("UNCHECKED_CAST")
        val interactor = rig.provider.getInteractor<Data?>(rig.ro(stubSchema)) as CancellableTaskInteractor<Data?>
        val handle = interactor.execute()
        return CoroutineScope(Dispatchers.Default).launch {
            try {
                handle.await()
            } catch (_: CancellationException) {
            }
        }
    }

    @Test
    fun aBackgroundOpInsideUserInitiatedWorkCountsAsUserStarted() = runBlocking {
        val op = HoldOp()
        val rig = Rig(op, stubSchema)

        val waiter = userInitiatedWork { startBackgroundOp(rig) }
        withTimeout(5_000) { op.started.await() }

        assertEquals(listOf("callCancellableUserInitiated"), rig.transport.methods)
        assertEquals(1, rig.proxy.gateway.inFlightCount.value)
        assertEquals(1, rig.proxy.gateway.userInitiatedInFlightCount.value)

        op.release.complete(Unit)
        waiter.join()
        assertEquals(0, rig.proxy.gateway.userInitiatedInFlightCount.value, "drained → no longer counted")
    }

    @Test
    fun anUnmarkedBackgroundOpIsAutomatic() = runBlocking {
        val op = HoldOp()
        val rig = Rig(op, stubSchema)

        val waiter = startBackgroundOp(rig)
        withTimeout(5_000) { op.started.await() }

        assertEquals(listOf("callCancellable"), rig.transport.methods)
        assertEquals(1, rig.proxy.gateway.inFlightCount.value, "still a Layer-2 task")
        assertEquals(
            0,
            rig.proxy.gateway.userInitiatedInFlightCount.value,
            "an automatic task must never count towards the foreground service",
        )

        op.release.complete(Unit)
        waiter.join()
    }

    @Test
    fun theMarkSurvivesWithTask() = runBlocking {
        val op = HoldOp()
        val rig = Rig(op, stubSchema)

        // A user-started folder upload: a bulk task's constituent calls carry the task's id AND the mark.
        val waiter = userInitiatedWork {
            withContext(WithTaskElement("task-folder")) { startBackgroundOp(rig) }
        }
        withTimeout(5_000) { op.started.await() }

        assertEquals(listOf("callCancellableUserInitiated"), rig.transport.methods)
        assertEquals(1, rig.proxy.gateway.userInitiatedInFlightCount.value)

        op.release.complete(Unit)
        waiter.join()
    }

    @Test
    fun theGatewayNamesTheTaskOfAMarkedCallAndNoOther() = runBlocking {
        // How a registered bulk task learns it is user-started, and so holds the foreground service
        // between its calls (control-background-work-off-fgs.md §5.2).
        val op = HoldOp()
        val rig = Rig(op, stubSchema)
        val named = java.util.concurrent.CopyOnWriteArrayList<String>()
        rig.proxy.gateway.onUserInitiatedTask = { named += it }

        val automatic = withContext(WithTaskElement("task-auto")) { startBackgroundOp(rig) }
        val user = userInitiatedWork { withContext(WithTaskElement("task-user")) { startBackgroundOp(rig) } }
        withTimeout(5_000) { op.started.await() }
        kotlinx.coroutines.delay(100)
        op.release.complete(Unit)
        automatic.join(); user.join()

        assertEquals(listOf("task-user"), named)
    }

    @Test
    fun aControlBelowRevisionTwoIsSentThePlainTaskCall() {
        // Below revision 2 the method does not exist: its empty reply would parse as an unreadable
        // answer. Only a failed revision query reads that low now (BinderIpc.REVISION_FLOOR).
        assertEquals(false, BinderIpc.sendsUserInitiated(BinderIpc.REVISION_CANCELLABLE_READS))
        assertEquals(true, BinderIpc.sendsUserInitiated(BinderIpc.REVISION_USER_INITIATED))
        assertEquals(true, BinderIpc.sendsUserInitiated(BinderIpc.REVISION))
    }

    private companion object {
        const val CLIENT = "c1"
    }
}
