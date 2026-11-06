package org.tenshu.core.common.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.app.AnnounceSession
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.operation
import org.tenshu.core.toTString
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Shared fixture for the cancellable-read tests (`core-background-processing.md` §6.2.1).
 *
 * ⚠⚠ The slow read is **synchronous**, the way a real read is: `slow-walk` is a
 * [SyncOperationImplementation] that walks its caller through [ExecutionContext.resolveList] — the
 * same row seam `GetAll`, a filter and the ordered-window fusion read through — and `slow-step` is a
 * sync op chained step after step. Neither suspends. A suspending stand-in (an `await()` on a
 * deferred) would be stopped by the request job's cancellation alone and pass against a fix whose
 * synchronous half was missing — which is the half a device needs, since a read's time is spent in
 * plain loops (`photos-app.md` §37: a cancelled page answered 1.9–8.2 s later).
 */
internal class CancellableReadRig(
    val rowMs: Long = 5,
    val stepMs: Long = 20,
    scope: CoroutineScope? = null,
    schemaRegistry: SchemaRegistry = createCoreTestRegistry(),
    objectStore: ObjectStore = InMemoryObjectStore(),
    // ⚠ A real lock: the rig runs on `Dispatchers.Default` with threads blocked in `runBlocking`.
    lock: DatabaseLock = ReentrantLockDatabaseLock(),
) {
    /** The stored array `stored-read` reads — set by a store-backed test. */
    @Volatile
    var storedArray: ArrayDatabaseReference? = null
    /** Rows `slow-walk` has visited, across every call. */
    val walked = AtomicInteger()

    /** Steps `slow-step` has run, across every call. */
    val stepped = AtomicInteger()

    /** Completed on the first row a walk visits. */
    val walking = CompletableDeferred<Unit>()

    private inner class SlowWalk : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            for (row in executionContext.resolveList(caller)!!) {
                walked.incrementAndGet()
                walking.complete(Unit)
                Thread.sleep(rowMs)
            }
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    /** Reads [storedArray] through the real row seam — the store's eager, locked materialisation. */
    private inner class StoredRead : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            walking.complete(Unit)
            executionContext.getArrayData(storedArray!!)!!.count()
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    private inner class SlowStep : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            stepped.incrementAndGet()
            walking.complete(Unit)
            Thread.sleep(stepMs)
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    val proxy: TestProxy = TestProxy(
        schemaRegistry = schemaRegistry,
        coroutineScope = scope ?: CoroutineScope(Dispatchers.Default),
        objectStore = objectStore,
        lock = lock,
        implementationFactoryOverride = { id, _ ->
            when (id) {
                WALK.id.get() -> SlowWalk()
                STEP.id.get() -> SlowStep()
                STORED.id.get() -> StoredRead()
                else -> null
            }
        },
    ).apply {
        schemaRegistry.registerConfig(
            tModule {
                operation(interfaceClass = Operation::class, protocolSchema = WALK)
                operation(interfaceClass = Operation::class, protocolSchema = STEP)
                operation(interfaceClass = Operation::class, protocolSchema = STORED)
            }
        )
    }

    /** `[rows × TString] · slow-walk` — a read whose cost is one synchronous walk over [rows] rows. */
    fun walk(rows: Int): TList<Data> =
        (listOf<Data>((1..rows).map { "row-$it".toTString(proxy.tContext) }.toTList(proxy.tContext)) +
            OperationDto(params = emptyMap(), schema = WALK))
            .toTList(proxy.tContext)

    /** `slow-step × steps` — a read whose cost is a chain of synchronous steps. */
    fun steps(steps: Int): TList<Data> =
        List<Data>(steps) { OperationDto(params = emptyMap(), schema = STEP) }.toTList(proxy.tContext)

    /** `stored-read` — one read of [storedArray]. */
    fun storedRead(): TList<Data> =
        listOf<Data>(OperationDto(params = emptyMap(), schema = STORED)).toTList(proxy.tContext)

    fun bytes(task: TList<Data>): ByteArray =
        proxy.serializer.serialize(task, isTopLevel = true, expectedType = Type.create(baseSchema = TList.Schema))

    /** A client over [transport], announcing like production does so the §6.5 gate admits it. */
    fun executor(transport: TaskTransport, scope: CoroutineScope): RemoteBinaryTaskExecutor {
        val parser = BinaryParser(proxy.schemaRegistry, proxy.schemaRegistry)
        val executor = RemoteBinaryTaskExecutor(BinarySerializer(), parser, transport, proxy.tContext, scope)
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
        return executor
    }

    companion object {
        val WALK: OperationSchema = operation(
            id = "cancellable-read-slow-walk",
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
        val STEP: OperationSchema = operation(
            id = "cancellable-read-slow-step",
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
        val STORED: OperationSchema = operation(
            id = "cancellable-read-stored",
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }
}

/**
 * A [TaskTransport] shaped **exactly like Android's**: `BinderTaskTransport` on the client and
 * `ProxyService`'s stub on the proxy, with a thread blocked in `runBlocking` standing in for the
 * Binder transaction. That blocking is the property under test — no coroutine cancellation reaches
 * a thread inside a Binder call — so nothing here is a cancellable coroutine in disguise.
 *
 * - [call] is `BinderTaskTransport.call` + `ProxyService.call`: `withContext(IO)` around a blocking
 *   `runBlocking { gateway.call(package, request, pid) }`.
 * - [callCancellableRead] is `BinderTaskTransport.callCancellableRead` line for line: the shared
 *   [BinderIpc.cancellableRead] over [BinderReadEndpoint], the code `ProxyService` delegates to.
 *   `cancelRead` is a Binder `oneway`, i.e. delivered on another binder thread: a `thread { }`.
 */
internal class BinderShapedTransport(
    private val proxy: TestProxy,
    private val callingPackage: String = "app.test.binder",
    private val callingPid: Int = 4242,
) : TaskTransport {
    private val endpoint = BinderReadEndpoint(proxy.gateway)

    /** Every read id a `oneway cancelRead` was sent for. */
    val cancelsSent: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    override val isConnected: Boolean = true
    override fun bind() {}
    override fun unbind() {}

    override suspend fun call(request: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        runBlocking { proxy.gateway.call(callingPackage, request, callingPid) }
    }

    override suspend fun callCancellableRead(request: ByteArray): ByteArray =
        BinderIpc.cancellableRead(
            blockingRead = { readId -> endpoint.callCancellableRead(callingPackage, callingPid, request, readId) },
            onewayCancel = { readId ->
                cancelsSent += readId
                thread(isDaemon = true, name = "binder-oneway") { endpoint.cancelRead(callingPackage, readId) }
            },
        )

    override fun sendFile(uri: String) {}
    override var onCallback: ((ByteArray) -> Unit)? = null
}
