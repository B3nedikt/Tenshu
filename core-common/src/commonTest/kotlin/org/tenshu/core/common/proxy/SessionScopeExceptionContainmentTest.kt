package org.tenshu.core.common.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.Data
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.trait.traitResolver
import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A `CoroutineExceptionHandler` installed on the proxy's **service** scope must reach every
 * `sessionScope.launch` in the proxy.
 *
 * ⚠⚠ That containment is the only thing standing between a fire-and-forget background task and a
 * dead Control. Proxy init is full of `sessionScope.launch { … }` — service init hooks, long-lived
 * row publishers, the detached background-op path — and an uncaught throw in any of them otherwise
 * reaches the thread's uncaught handler, which on Android kills the process. Registering a 6-hourly
 * maintenance tick took Control down exactly this way (`OfflineKeeperScheduleFailureTest`).
 *
 * ⚠ The mechanism is **inheritance, not configuration**, which is why it needs a test of its own:
 * `ClientRegistry.mintSession` derives each session scope as
 * `parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job])`, so the handler rides
 * along in `parent.coroutineContext`. Rewriting that line as the more obvious-looking
 * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` would silently drop the handler and
 * restore the crash, with nothing else failing.
 */
class SessionScopeExceptionContainmentTest {

    @Test
    fun aThrowInASessionScopeLaunchReachesTheServiceScopeHandler() = runTest(timeout = TIMEOUT) {
        val caught = CompletableDeferred<Throwable>()
        val serviceScope = CoroutineScope(
            Dispatchers.Default + CoroutineExceptionHandler { _, e -> caught.complete(e) }
        )

        val registry = registry(serviceScope)
        val context = registry.getExecutionContext("client1", "session1")

        context.sessionScope.launch { error("a background task failed") }

        val e = withContext(Dispatchers.Default) { withTimeout(WAIT) { caught.await() } }
        assertTrue(
            e.message?.contains("a background task failed") == true,
            "the session scope did not inherit the service scope's handler, so this throw would " +
                "have reached the thread's uncaught handler instead — on Android, a process kill",
        )
    }

    private fun registry(serviceScope: CoroutineScope): ClientRegistry {
        val schemaRegistry = createCoreTestRegistry()
        val tContext = createTestContext(schemaRegistry)
        return ClientRegistry(
            database = NoopDatabase,
            traitResolver = tContext.traitResolver(),
            implementationFactory = { null },
            clientConnectionFactory = {
                object : ClientConnection {
                    override fun init() {}
                    override fun send(data: Data) {}
                    override fun destroy() {}
                }
            },
            materializer = CallstackMaterializer(schemaRegistry, tContext),
            pureOperationRunner = tContext.components[PureOperationRunner.ID] as PureOperationRunner,
            baseContext = tContext,
            serviceScope = serviceScope,
        )
    }

    private object NoopDatabase : Database {
        override fun getData(dataId: DataId, asEntity: Boolean, projection: Projection?): Data? = null
        override fun saveData(data: Data) {}
        override fun deleteData(dataId: DataId) {}
        override fun getArrayData(
            reference: ArrayDatabaseReference,
            projection: Projection?
        ): Sequence<Data> = emptySequence()

        override fun getComposite(reference: CompositeDatabaseReference): Data? = null
        override fun addToArray(reference: ArrayDatabaseReference, value: Data, origin: WriteOrigin) {}
        override fun removeFromArray(
            reference: ArrayDatabaseReference,
            dataId: DataId,
            origin: WriteOrigin
        ) {}

        override fun setArrayListener(
            reference: ArrayDatabaseReference,
            onAdded: (Data, WriteOrigin) -> Unit,
            onRemoved: (DataId, WriteOrigin) -> Unit
        ): Long = 0L

        override fun removeArrayListener(handle: Long) {}
        override fun <R> transaction(block: () -> R): R = block()
    }

    private companion object {
        val TIMEOUT = 30.seconds
        val WAIT = 5.seconds
    }
}
