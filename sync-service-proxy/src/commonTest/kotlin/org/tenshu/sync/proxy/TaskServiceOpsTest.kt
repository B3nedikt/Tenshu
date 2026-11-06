package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.sync.RenewTaskLease
import org.tenshu.sync.BackgroundTask
import org.tenshu.sync.GenericTaskSpec
import org.tenshu.sync.RegisterTask
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.TransitionTask
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.RenewTaskLeaseImplementation
import org.tenshu.sync.proxy.impl.RegisterTaskImplementation
import org.tenshu.sync.proxy.impl.TransitionTaskImplementation
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TS-3 (`core-task-composite.md` §5–§6): the TaskService ops over the TS-2
 * sync lane — register opens the lane (the explicit-open gate), lane-tagged
 * saves stage, a terminal transition seals the final checkpoint and drops the
 * liveness contribution; the D9 lease sweep fails an abandoned task and its
 * lane auto-checkpoints. Also covers the ProxyGateway threading contract at
 * the `ExecutionContext` level (`requestSyncLaneId` fallback in `saveData`).
 */
class TaskServiceOpsTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var observer: SyncObserver
    private var now: Long = 1_000_000L
    private lateinit var registry: TaskRegistry

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = testProxy.tContext.syncCryptoManager()
        syncManager = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate()
        )
        syncManager.initialize("mock phrase")

        observer = SyncObserver(
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
        )
        testProxy.executionContext.observerRegistry.add(observer)
        registry = TaskRegistry(nowMs = { now }, leaseMs = 10_000L)
    }

    private fun pendingBlocks(): List<SyncBlock> =
        testProxy.executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = testProxy.tContext,
                    type = SyncBlock.ID,
                    relationId = SyncService.Collections.PENDING_BLOCKS
                ),
                isEntity = false,
                elementSchemaId = SyncBlock.ID
            )
        )?.toList()?.filterIsInstance<SyncBlock>() ?: emptyList()

    private fun entity(key: String, value: Long): Entity<SyntheticTestData> = Entity.create(
        context = testProxy.tContext,
        composite = SyntheticTestData.Factory.create(testProxy.tContext, key, value),
        properties = emptyMap()
    )

    private suspend fun registerTask(taskId: String): BackgroundTask {
        val impl = RegisterTaskImplementation(registry) { observer }
        return impl.execute(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = testProxy.executionContext.implementationFactory,
            caller = null,
            params = mapOf(
                RegisterTask.Params.TASK_ID to taskId.toTString(testProxy.tContext),
                RegisterTask.Params.SPEC to GenericTaskSpec.create(testProxy.tContext, "test-kind"),
            )
        )
    }

    private suspend fun transition(taskId: String, kind: String): BackgroundTask {
        val impl = TransitionTaskImplementation(registry) { observer }
        return impl.execute(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = testProxy.executionContext.implementationFactory,
            caller = null,
            params = mapOf(
                TransitionTask.Params.TASK_ID to taskId.toTString(testProxy.tContext),
                TransitionTask.Params.TRANSITION to kind.toTString(testProxy.tContext),
            )
        )
    }

    @Test
    fun registerStageComplete_sealsOneBatchAndReleasesLiveness() = runTest {
        val row = registerTask("t1")
        assertEquals(BackgroundTask.States.RUNNING, row.state.get())
        assertEquals(GenericTaskSpec.ID, row.specType.get(), "row must carry the spec's concrete type")
        assertEquals(1, registry.activeCount.value, "a registered task holds the liveness count")

        // Lane-tagged saves stage (register opened the lane).
        testProxy.executionContext.saveData(entity("a", 1L), WriteOrigin.USER, syncLaneId = "t1")
        testProxy.executionContext.saveData(entity("b", 2L), WriteOrigin.USER, syncLaneId = "t1")
        observer.awaitEmitIdle()
        assertEquals(0, pendingBlocks().size, "task saves must stage, not emit per-save")

        val done = transition("t1", TransitionTask.Transitions.COMPLETE)
        assertEquals(BackgroundTask.States.DONE, done.state.get())
        assertEquals(1, pendingBlocks().size, "terminal transition must seal the final checkpoint")
        assertEquals(0, registry.activeCount.value, "a terminal task must release the liveness count")
    }

    @Test
    fun requestSyncLaneId_threadsLaneThroughSaveDataFallback() = runTest {
        registerTask("t1")

        // The ProxyGateway contract: a withTask-tagged constituent call's
        // ExecutionContext carries requestSyncLaneId; the op impl calls plain
        // saveData(data) and the save still joins the lane.
        val requestContext = testProxy.executionContext.withEvaluationScope(
            testProxy.executionContext.sessionScope,
            requestSyncLaneId = "t1"
        )
        requestContext.saveData(entity("via-request", 5L))
        observer.awaitEmitIdle()
        assertEquals(0, pendingBlocks().size, "request-scoped lane id must stage the save")

        transition("t1", TransitionTask.Transitions.COMPLETE)
        assertEquals(1, pendingBlocks().size)
    }

    @Test
    fun renewLease_expiredTaskFailsAndLaneAutoCheckpoints() = runTest {
        registerTask("t1")
        testProxy.executionContext.saveData(entity("staged", 9L), WriteOrigin.USER, syncLaneId = "t1")
        observer.awaitEmitIdle()

        // The heartbeat renews the lease.
        now += 8_000L
        RenewTaskLeaseImplementation(registry).execute<BackgroundTask>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = testProxy.executionContext.implementationFactory,
            caller = null,
            params = mapOf(
                RenewTaskLease.Params.TASK_ID to "t1".toTString(testProxy.tContext),
            )
        )
        now += 8_000L
        assertEquals(0, registry.sweepExpired().size, "renewLease must have renewed the lease")

        // Let it expire: sweep fails it; the sweep hook closes the lane,
        // auto-checkpointing the staged delta so nothing strands (D9).
        now += 11_000L
        val expired = registry.sweepExpired()
        assertEquals(listOf("t1"), expired)
        expired.forEach { observer.closeSyncLane(it) }
        assertEquals(1, pendingBlocks().size, "lease expiry must seal the staged deltas")
        assertEquals(BackgroundTask.States.FAILED, registry.get("t1")!!.state)
        assertEquals(0, registry.activeCount.value)
    }

    @Test
    fun terminalTransition_isIdempotent() = runTest {
        registerTask("t1")
        transition("t1", TransitionTask.Transitions.CANCEL)
        val again = transition("t1", TransitionTask.Transitions.COMPLETE)
        assertEquals(
            BackgroundTask.States.CANCELLED, again.state.get(),
            "a second transition must not overwrite the terminal state"
        )
    }
}
