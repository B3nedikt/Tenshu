package org.tenshu.sync.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `control-background-work-off-fgs.md` §5.6: an item the OS scheduler owns has exactly one driver.
 *
 *  - the in-process [ScheduleDrainLoop] never dispatches it ([PlatformScheduler.ownsDispatch]);
 *  - a registration that fails for it leaves no row behind (`ScheduleImplementation`), because a
 *    row nothing runs is the shape the media index pass had on Android for its whole life;
 *  - the OS-woken worker runs it as a child ([dispatchScheduledOp]), so stopping the job stops the op.
 */
class PlatformOwnedScheduleTest {

    private val ownedOp = operation(id = "test/op/owned", traits = listOf(TerminalOperation.Schema, HeadlessSafe.Schema))
    private val loopOp = operation(id = "test/op/loop", traits = listOf(TerminalOperation.Schema, HeadlessSafe.Schema))

    /** Counts runs; [hold] makes a run wait until it is cancelled, and records that it was. */
    private class CountingOp(private val hold: Boolean = false) : AsyncOperationImplementation {
        var runs = 0
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            runs++
            started.complete(Unit)
            if (hold) {
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    /** Owns the keys in [owned]; [failRegister] makes `register` throw the way WorkManager's builder did. */
    private class FakeOsScheduler(
        private val owned: Set<String>,
        var failRegister: Boolean = false,
    ) : PlatformScheduler {
        val registered = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override fun register(item: ScheduleItem, punctual: Boolean) {
            if (failRegister) throw IllegalArgumentException("Cannot set backoff criteria on an idle mode job")
            registered += item.dedupKey.get()
        }
        override fun cancel(dedupKey: String) { cancelled += dedupKey }
        override fun cancelAll() {}
        override fun ownsDispatch(item: ScheduleItem, punctual: Boolean): Boolean = !punctual && item.dedupKey.get() in owned
    }

    private class Fixture(val proxy: TestProxy, val owned: CountingOp, val loop: CountingOp)

    private fun fixture(
        scheduler: PlatformScheduler?,
        hold: Boolean = false,
        session: CoroutineScope? = null,
    ): Fixture {
        val owned = CountingOp(hold)
        val loop = CountingOp()
        val proxy = TestProxy(
            coroutineScope = session ?: CoroutineScope(Dispatchers.Default),
            implementationFactoryOverride = { id, _ ->
                when (id) {
                    Schedule.ID -> ScheduleImplementation(scheduler)
                    ListScheduled.ID -> ListScheduledImplementation()
                    CancelScheduled.ID -> CancelScheduledImplementation(scheduler)
                    ownedOp.id.get() -> owned
                    loopOp.id.get() -> loop
                    else -> null
                }
            }
        ).apply {
            schemaRegistry.registerConfig(syncServiceCommonModule)
            schemaRegistry.registerConfig(
                tModule {
                    operation(interfaceClass = Operation::class, protocolSchema = ownedOp)
                    operation(interfaceClass = Operation::class, protocolSchema = loopOp)
                }
            )
        }
        return Fixture(proxy, owned, loop)
    }

    private suspend fun TestProxy.schedule(opId: String, key: String) {
        val ctx = executionContext.context
        executionContext.executeAsync<Data>(
            operationId = Schedule.ID,
            caller = null,
            params = mapOf(
                Schedule.Params.OP_ID to opId.toTString(ctx),
                Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(ctx, kind = ScheduleSpec.KIND_PERIODIC, minPeriodMs = 10),
                Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(ctx),
                Schedule.Params.RETRY_POLICY to RetryPolicy.create(ctx),
                Schedule.Params.DEDUP_KEY to key.toTString(ctx),
            ),
        )
    }

    private suspend fun TestProxy.keys(): List<String> =
        executionContext.executeAsync<TList<Data>>(operationId = ListScheduled.ID, caller = null)
            .toList().map { (it as ScheduleItem).dedupKey.get() }

    @Test
    fun theLoopLeavesAnOwnedItemToThePlatform_andStillDrivesTheRest() = runTest {
        val scheduler = FakeOsScheduler(owned = setOf("owned-job"))
        val f = fixture(scheduler)
        f.proxy.schedule(ownedOp.id.get(), "owned-job")
        f.proxy.schedule(loopOp.id.get(), "loop-job")

        val loop = ScheduleDrainLoop(
            executionContext = f.proxy.executionContext,
            coroutineScope = CoroutineScope(Dispatchers.Default),
            tickIntervalMs = 10,
            platformScheduler = scheduler,
        )
        repeat(3) { loop.tick().joinAll() }

        assertEquals(0, f.owned.runs, "an item the OS scheduler owns must run only under its job")
        assertTrue(f.loop.runs >= 1, "an item nobody else owns is still the loop's to run")
        assertEquals(listOf("owned-job", "loop-job"), scheduler.registered)
    }

    @Test
    fun aFailedRegistrationOfAnOwnedItem_leavesNoRow_andIsReported() = runTest {
        val scheduler = FakeOsScheduler(owned = setOf("owned-job"))
        val f = fixture(scheduler)
        // A row from an earlier, successful registration: the failed one must not leave it posing
        // as scheduled either.
        f.proxy.schedule(ownedOp.id.get(), "owned-job")
        assertEquals(listOf("owned-job"), f.proxy.keys())

        scheduler.failRegister = true
        assertFailsWith<TenshuException> { f.proxy.schedule(ownedOp.id.get(), "owned-job") }

        assertEquals(emptyList(), f.proxy.keys(), "a schedule nothing would run must not be persisted")
        assertEquals(listOf("owned-job"), scheduler.cancelled, "and no OS work may stay behind under its key")
    }

    @Test
    fun aFailedRegistrationOfAnItemTheLoopDrives_keepsItsRow() = runTest {
        val scheduler = FakeOsScheduler(owned = emptySet(), failRegister = true)
        val f = fixture(scheduler)

        assertFailsWith<TenshuException> { f.proxy.schedule(loopOp.id.get(), "loop-job") }

        assertEquals(listOf("loop-job"), f.proxy.keys(), "the loop is a real driver of this one; only app-closed wakes are lost")
    }

    @Test
    fun aScheduledOpStopsWithTheJobThatDispatchedIt() = runTest {
        val f = fixture(scheduler = null, hold = true)
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                // The worker's coroutine, as WorkManager runs it.
                val job = launch { f.proxy.executionContext.dispatchScheduledOp(ownedOp.id.get()) }
                f.owned.started.await()

                // The OS stops the job: its 10-minute limit, a constraint lost.
                job.cancelAndJoin()

                // ⚠⚠ Must already be true when the job has ended — a detached op would still be
                // waiting, holding its lock, with no job behind it and so no network.
                assertTrue(f.owned.cancelled.isCompleted, "the op must unwind with the job that ran it")
            }
        }
    }

    /**
     * Run as the job's child, the op is outside its session's teardown. A session torn down under it
     * (a client unregisters, `ProxyService.onDestroy`) must still end the op, and the job is told to
     * retry in a live session rather than counted as done.
     */
    @Test
    fun aScheduledOpStopsWhenItsSessionEnds_andTheJobIsToldToRetry() = runTest {
        val session = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val f = fixture(scheduler = null, hold = true, session = session)
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                val run = async {
                    runCatching { f.proxy.executionContext.dispatchScheduledOp(ownedOp.id.get()) }
                }
                f.owned.started.await()

                session.coroutineContext[kotlinx.coroutines.Job]!!.cancel()   // the session is torn down
                val outcome = run.await()

                assertTrue(f.owned.cancelled.isCompleted, "the op must not outlive its session")
                assertTrue(
                    outcome.exceptionOrNull() is org.tenshu.sync.ScheduledRunDeferred,
                    "the job retries in a live session; got $outcome",
                )
            }
        }
    }

    /**
     * Pins what the Android module installs on the holder the worker reads. Reverting
     * [install] to plain `executeAsync` leaves the op running after its job is stopped.
     */
    @Test
    fun theInstalledDispatcherStopsTheOpWithItsJob() = runTest {
        val f = fixture(scheduler = null, hold = true)
        val holder = ScheduledOpDispatcherHolder().also { it.install(f.proxy.executionContext) }
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                val job = launch { holder.dispatch!!(ownedOp.id.get()) }
                f.owned.started.await()
                job.cancelAndJoin()
                assertTrue(f.owned.cancelled.isCompleted, "the installed dispatch must run the op as the job's child")
            }
        }
    }

    /** A cold wake reaches the worker a moment before the session installs the dispatcher. */
    @Test
    fun aDispatcherInstalledAMomentLaterIsWaitedFor_andAbsentOneIsNot() = runTest {
        val holder = ScheduledOpDispatcherHolder()
        val waiting = async { holder.awaitDispatch() }
        kotlinx.coroutines.delay(300)
        holder.dispatch = { }
        assertTrue(waiting.await() != null, "installed 300 ms in: waited for")
        assertEquals(null, ScheduledOpDispatcherHolder().awaitDispatch(timeoutMs = 500), "never installed: null after the timeout")
    }
}
