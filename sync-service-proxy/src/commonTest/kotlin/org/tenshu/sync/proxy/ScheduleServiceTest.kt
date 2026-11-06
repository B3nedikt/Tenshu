package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
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
import org.tenshu.core.toTString
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.TerminalOperation
import org.tenshu.sync.CancelScheduled
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.ListScheduled
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleItem
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.CancelScheduledImplementation
import org.tenshu.sync.proxy.impl.ListScheduledImplementation
import org.tenshu.sync.proxy.impl.ScheduleImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5 (docs/proposals/core-background-processing.md §8) acceptance tests —
 * the security-correct inversion of the old "fails without a schedule":
 *
 *  1. a non-`HeadlessSafe` op cannot be scheduled (rejected at
 *     `ScheduleService.schedule(...)`, **and** the drain loop re-checks);
 *  2. in-process periodic dispatch fires a registered `ScheduleItem`;
 *  3. `dedupKey` coalesces repeat enqueueings to one pending item.
 */
class ScheduleServiceTest {

    private val headlessOp = operation(
        id = "test/op/headless-drain",
        traits = listOf(TerminalOperation.Schema, HeadlessSafe.Schema)
    )

    private val uiOp = operation(
        id = "test/op/needs-ui",
        traits = listOf(TerminalOperation.Schema) // NOT HeadlessSafe
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

    /** Records register/cancel calls so the Phase-6 wiring is assertable. */
    private class RecordingPlatformScheduler : PlatformScheduler {
        val registered = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var cancelAllCount = 0
        override fun register(item: ScheduleItem, punctual: Boolean) { registered += item.dedupKey.get() }
        override fun cancel(dedupKey: String) { cancelled += dedupKey }
        override fun cancelAll() { cancelAllCount++ }
    }

    private fun proxy(
        counter: IntArray,
        platformScheduler: PlatformScheduler? = null,
    ): TestProxy =
        TestProxy(
            implementationFactoryOverride = { id, _ ->
                when (id) {
                    Schedule.ID -> ScheduleImplementation(platformScheduler)
                    ListScheduled.ID -> ListScheduledImplementation()
                    CancelScheduled.ID -> CancelScheduledImplementation(platformScheduler)
                    headlessOp.id.get() -> RecordingOp(counter)
                    else -> null
                }
            }
        ).apply {
            schemaRegistry.registerConfig(syncServiceCommonModule)
            schemaRegistry.registerConfig(
                tModule {
                    operation(interfaceClass = Operation::class, protocolSchema = headlessOp)
                    operation(interfaceClass = Operation::class, protocolSchema = uiOp)
                }
            )
        }

    private fun TestProxy.specs() = Triple(
        ScheduleSpec.create(
            executionContext.context,
            kind = ScheduleSpec.KIND_PERIODIC,
            minPeriodMs = 10
        ),
        ConstraintSpec.create(executionContext.context),
        RetryPolicy.create(executionContext.context)
    )

    private suspend fun TestProxy.schedule(opId: String, dedupKey: String): Data? {
        val (s, c, r) = specs()
        return executionContext.executeAsync<Data>(
            operationId = Schedule.ID,
            caller = null,
            params = mapOf(
                Schedule.Params.OP_ID to opId.toTString(executionContext.context),
                Schedule.Params.SCHEDULE_SPEC to s,
                Schedule.Params.CONSTRAINT_SPEC to c,
                Schedule.Params.RETRY_POLICY to r,
                Schedule.Params.DEDUP_KEY to dedupKey.toTString(executionContext.context)
            )
        )
    }

    private suspend fun TestProxy.listScheduled(): List<Data> {
        val result = executionContext.executeAsync<TList<Data>>(
            operationId = ListScheduled.ID,
            caller = null
        )
        return result.toList()
    }

    @Test
    fun nonHeadlessSafeOp_isRejected_atScheduleAndNotPersisted() = runTest {
        val p = proxy(IntArray(1))

        // Phase 3 of `docs/proposals/core-error-channel.md`: impls throw
        // typed `TenshuException`. In-process consumers calling through
        // `ExecutionContext.executeAsync` see the throw directly (no
        // catch-all). The cross-process wire path (ProxyGateway →
        // LocalTaskExecutor) is what unwraps to `TResult.Failure`.
        kotlin.test.assertFailsWith<org.tenshu.core.TenshuException> {
            p.schedule(uiOp.id.get(), "ui-job")
        }
        assertEquals(0, p.listScheduled().size, "nothing must be persisted on reject")
    }

    @Test
    fun headlessSafeOp_periodic_isDispatchedByTheDrainLoop() = runTest {
        val counter = IntArray(1)
        val p = proxy(counter)

        p.schedule(headlessOp.id.get(), "drain-job")
        assertEquals(1, p.listScheduled().size)

        val loop = ScheduleDrainLoop(
            executionContext = p.executionContext,
            coroutineScope = CoroutineScope(Dispatchers.Default),
            tickIntervalMs = 10,
            platformScheduler = NoOpPlatformScheduler,
        )

        loop.tick().joinAll() // initialDelay (none) crossed → first fire
        assertEquals(1, counter[0], "periodic item dispatched once on the first due tick")

        loop.tick().joinAll() // minPeriodMs (10) == tickInterval → fires again
        assertTrue(counter[0] >= 2, "periodic item re-fires on subsequent due ticks")
    }

    @Test
    fun dedupKey_coalescesRepeatEnqueueings_toOnePendingItem() = runTest {
        val p = proxy(IntArray(1))

        p.schedule(headlessOp.id.get(), "sync-drain")
        p.schedule(headlessOp.id.get(), "sync-drain")
        p.schedule(headlessOp.id.get(), "sync-drain")

        val items = p.listScheduled()
        assertEquals(1, items.size, "same dedupKey coalesces to one pending ScheduleItem")
        assertEquals("sync-drain", (items.single() as ScheduleItem).dedupKey.get())
    }

    private suspend fun TestProxy.cancelScheduled(dedupKey: String) {
        executionContext.executeAsync<Data>(
            operationId = CancelScheduled.ID,
            caller = null,
            params = mapOf(
                CancelScheduled.Params.DEDUP_KEY to dedupKey.toTString(executionContext.context)
            )
        )
    }

    // --- Phase 6/7: the OS-scheduler seam (the second driver). ---

    @Test
    fun schedule_registersWithThePlatformScheduler() = runTest {
        val scheduler = RecordingPlatformScheduler()
        val p = proxy(IntArray(1), scheduler)

        p.schedule(headlessOp.id.get(), "drain-job")

        assertEquals(
            listOf("drain-job"),
            scheduler.registered,
            "a persisted ScheduleItem is handed to the OS scheduler too",
        )
    }

    @Test
    fun rejectedNonHeadlessSafeOp_isNotRegisteredWithThePlatformScheduler() = runTest {
        val scheduler = RecordingPlatformScheduler()
        val p = proxy(IntArray(1), scheduler)

        kotlin.test.assertFailsWith<org.tenshu.core.TenshuException> {
            p.schedule(uiOp.id.get(), "ui-job")
        }

        assertTrue(
            scheduler.registered.isEmpty(),
            "a rejected op is never handed to the OS scheduler (fail-closed before register)",
        )
    }

    @Test
    fun cancelScheduled_cancelsOnThePlatformScheduler() = runTest {
        val scheduler = RecordingPlatformScheduler()
        val p = proxy(IntArray(1), scheduler)

        p.schedule(headlessOp.id.get(), "drain-job")
        p.cancelScheduled("drain-job")

        assertEquals(listOf("drain-job"), scheduler.cancelled)
        assertEquals(0, p.listScheduled().size, "the persisted item is removed too")
    }
}
