package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.TerminalOperation
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.ListScheduled
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleItem
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.ListScheduledImplementation
import org.tenshu.sync.proxy.impl.ScheduleImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The preference-service Phase 5 "call-site slice" (desktop-verifiable):
 * [BackgroundSyncScheduler] turns a [ConstraintSpec] flow into a single,
 * coalesced, periodic [ScheduleItem] via the real [ScheduleImplementation], and
 * a new constraint reschedules it. Pairs with `BackgroundSyncConstraintsTest`
 * (which proves preference → ConstraintSpec) to cover the whole chain; real
 * sync-drain op + production wiring + enforcement are bgproc Phase 6.
 */
class BackgroundSyncSchedulerTest {

    private val headlessOp = operation(
        id = "test/op/headless-sync-drain",
        traits = listOf(TerminalOperation.Schema, HeadlessSafe.Schema)
    )

    private class RecordingOp(val counter: IntArray) : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            counter[0]++
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    private fun proxy(counter: IntArray): TestProxy =
        TestProxy(
            implementationFactoryOverride = { id, _ ->
                when (id) {
                    Schedule.ID -> ScheduleImplementation()
                    ListScheduled.ID -> ListScheduledImplementation()
                    headlessOp.id.get() -> RecordingOp(counter)
                    else -> null
                }
            }
        ).apply {
            schemaRegistry.registerConfig(syncServiceCommonModule)
            schemaRegistry.registerConfig(
                tModule {
                    operation(interfaceClass = Operation::class, protocolSchema = headlessOp)
                }
            )
        }

    private suspend fun TestProxy.scheduledItems(): List<ScheduleItem> {
        val result = executionContext.executeAsync<TList<Data>>(
            operationId = ListScheduled.ID,
            caller = null
        )
        return result.toList().map { it as ScheduleItem }
    }

    // scope/constraints feed start() only; these tests drive reschedule() directly.
    private fun TestProxy.scheduler(opId: String) = BackgroundSyncScheduler(
        executionContext = executionContext,
        constraints = emptyFlow(),
        scope = CoroutineScope(Dispatchers.Default),
        opId = opId,
        periodMs = 10,
    )

    @Test
    fun reschedulesSingleSyncDrainItemAsConstraintChanges() = runTest {
        val p = proxy(IntArray(1))
        val ctx = p.executionContext.context
        val scheduler = p.scheduler(headlessOp.id.get())

        scheduler.reschedule(ConstraintSpec.create(ctx, network = ConstraintSpec.NETWORK_UNMETERED))

        val first = p.scheduledItems()
        assertEquals(1, first.size, "the first constraint registers one item")
        assertEquals("sync-drain", first.single().dedupKey.get())
        assertEquals(
            ConstraintSpec.NETWORK_UNMETERED,
            first.single().constraintSpec.network.get(),
            "WifiOnly default → UNMETERED constraint on the scheduled item"
        )

        // A new constraint (e.g. WifiOnly toggled off) reschedules in place.
        scheduler.reschedule(ConstraintSpec.create(ctx, network = ConstraintSpec.NETWORK_ANY))

        val second = p.scheduledItems()
        assertEquals(1, second.size, "dedupKey coalesces — still exactly one pending item")
        assertEquals(
            ConstraintSpec.NETWORK_ANY,
            second.single().constraintSpec.network.get(),
            "the reschedule carries the updated constraint"
        )
    }

    @Test
    fun scheduledItemIsDispatchedByTheDrainLoop() = runTest {
        val counter = IntArray(1)
        val p = proxy(counter)
        val ctx = p.executionContext.context

        p.scheduler(headlessOp.id.get()).reschedule(ConstraintSpec.create(ctx))
        assertEquals(1, p.scheduledItems().size)

        val loop = ScheduleDrainLoop(
            executionContext = p.executionContext,
            coroutineScope = CoroutineScope(Dispatchers.Default),
            tickIntervalMs = 10,
            platformScheduler = NoOpPlatformScheduler,
        )
        loop.tick().joinAll()

        assertTrue(counter[0] >= 1, "the drain loop dispatches the scheduled head-less op")
    }
}
