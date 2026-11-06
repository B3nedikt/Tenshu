package org.tenshu.sync.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.schema.operation
import org.tenshu.core.toTString
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.TerminalOperation
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.ListScheduled
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleItem
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.ListScheduledImplementation
import org.tenshu.sync.proxy.impl.ScheduleImplementation
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two interleavings of [ScheduleDrainLoop.tick]'s row read that only a real second thread can
 * produce, each forced at the exact point it needs rather than hoped for.
 *
 * The seam is the store's own lock: [HookedLock] runs a callback on the **ticking thread**, around
 * that thread's next outermost store call — which in `tick()` is its read of the schedule rows. A
 * JVM test because the callbacks block a thread.
 */
class ScheduleDrainLoopSnapshotRaceTest {

    private val opId = "test/op/one-shot"

    private val headless = operation(
        id = opId,
        traits = listOf(TerminalOperation.Schema, HeadlessSafe.Schema),
    )

    /** A reentrant store lock that can run a callback around one outermost store call. */
    private class HookedLock : DatabaseLock {
        private class Hook(val thread: Thread?, val before: (() -> Unit)?, val after: (() -> Unit)?)

        private val delegate = ReentrantLockDatabaseLock()
        private val depth = ThreadLocal.withInitial { 0 }
        private val armed = AtomicReference<Hook?>(null)

        /** Arms the hooks for the calling thread's next outermost store call. One shot. */
        fun armForThisThread(before: (() -> Unit)? = null, after: (() -> Unit)? = null) {
            armed.set(Hook(Thread.currentThread(), before, after))
        }

        /** Arms the hooks for the next outermost store call on whichever thread makes it. One shot. */
        fun armForAnyThread(before: (() -> Unit)? = null, after: (() -> Unit)? = null) {
            armed.set(Hook(null, before, after))
        }

        override fun <R> withLock(block: () -> R): R {
            val hook = armed.get()?.takeIf {
                depth.get() == 0 && (it.thread == null || it.thread === Thread.currentThread()) &&
                    armed.compareAndSet(it, null)
            }
            // ⚠ Before the lock is taken, so the callback may itself write to the store.
            hook?.before?.invoke()
            depth.set(depth.get() + 1)
            val result = try {
                delegate.withLock(block)
            } finally {
                depth.set(depth.get() - 1)
            }
            // ⚠ After the lock is released: the call has returned its snapshot to the caller.
            hook?.after?.invoke()
            return result
        }
    }

    private class GatedOp : AsyncOperationImplementation {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val starts = AtomicInteger()

        /** Completed if the op itself — not merely the run awaiting it — is cancelled. */
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            starts.incrementAndGet()
            started.complete(Unit)
            try {
                gate.await()
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    /** Runs inline on the run's own coroutine, holding its thread for [holdMs]. */
    private class SlowSyncOp(private val holdMs: Long) : SyncOperationImplementation {
        val started = CountDownLatch(1)
        @Volatile var finished = false

        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            started.countDown()
            Thread.sleep(holdMs)
            finished = true
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    /** Queues what is dispatched to it until the test runs it — "launched, not yet started". */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runOne(): Boolean = queue.poll()?.also { it.run() } != null
        fun runAll() { while (runOne()) Unit }
    }

    private fun proxy(lock: DatabaseLock, op: OperationImplementation, runState: ScheduleRunState): TestProxy =
        TestProxy(
            lock = lock,
            implementationFactoryOverride = { id, _ ->
                when (id) {
                    Schedule.ID -> ScheduleImplementation(null, runState)
                    ListScheduled.ID -> ListScheduledImplementation()
                    opId -> op
                    else -> null
                }
            }
        ).apply {
            schemaRegistry.registerConfig(syncServiceCommonModule)
            schemaRegistry.registerConfig(
                tModule { operation(interfaceClass = Operation::class, protocolSchema = headless) }
            )
        }

    private suspend fun TestProxy.scheduleOneShot() {
        val ctx = executionContext.context
        executionContext.executeAsync<Data>(
            operationId = Schedule.ID,
            caller = null,
            params = mapOf(
                Schedule.Params.OP_ID to opId.toTString(ctx),
                Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(ctx, kind = ScheduleSpec.KIND_ONE_SHOT),
                Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(ctx),
                Schedule.Params.RETRY_POLICY to RetryPolicy.create(ctx),
                Schedule.Params.DEDUP_KEY to opId.toTString(ctx),
            ),
        )
    }

    private suspend fun TestProxy.row(): ScheduleItem =
        executionContext.executeAsync<TList<Data>>(operationId = ListScheduled.ID, caller = null)
            .toList().single() as ScheduleItem

    private suspend fun realTime(block: suspend () -> Unit) =
        withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    /** Blocks the calling thread until [jobs] are done, or [timeoutMs] passes. */
    private fun awaitBlocking(jobs: List<Job>, timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (jobs.any { !it.isCompleted } && System.nanoTime() < deadline) Thread.sleep(2)
    }

    /**
     * ⚠⚠ The tick used to snapshot the rows *before* it took the loop's lock. A one-shot whose run
     * finished in between — row written `DONE`, key forgotten and freed — was still `PENDING` in
     * that stale snapshot, with a fresh accumulator and no initial delay: it fired a second time.
     *
     * Forced here: the moment the tick's row read returns, the test lets the in-flight run finish
     * and waits for it. With the read under the lock the run cannot complete until the tick is
     * done (the wait simply times out), and the tick has already seen the key in flight.
     */
    @Test
    fun aOneShotThatCompletesWhileATickHoldsItsSnapshotIsNotRunTwice() = runTest {
        val lock = HookedLock()
        val op = GatedOp()
        val runState = ScheduleRunState()
        val p = proxy(lock, op, runState)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            p.scheduleOneShot()
            val loop = ScheduleDrainLoop(p.executionContext, scope, tickIntervalMs = 10, runState = runState, platformScheduler = NoOpPlatformScheduler)

            val first = loop.tick()
            realTime { op.started.await() }

            lock.armForThisThread(after = {
                op.gate.complete(Unit)
                awaitBlocking(first, timeoutMs = 500)
            })
            val second = loop.tick()

            realTime { (first + second).joinAll() }
            assertEquals(1, op.starts.get(), "a one-shot runs once")
            assertTrue(second.isEmpty(), "the tick that raced the completion started nothing")
            assertEquals(ScheduleItem.STATE_DONE, p.row().state.get())
        } finally {
            op.gate.complete(Unit)
            scope.cancel()
        }
    }

    /**
     * A registration that lands between the tick's generation read and its row read would pair the
     * *new* row with the *old* generation; that run's completion would then stand down from its own
     * row and the one-shot would run twice. The tick reads the generations again after the rows and
     * leaves a key that moved for the next tick.
     */
    @Test
    fun aKeyRegisteredAcrossTheTicksRowReadIsLeftForTheNextTick() = runTest {
        val lock = HookedLock()
        val op = GatedOp()
        val runState = ScheduleRunState()
        val p = proxy(lock, op, runState)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            p.scheduleOneShot()
            val loop = ScheduleDrainLoop(p.executionContext, scope, tickIntervalMs = 10, runState = runState, platformScheduler = NoOpPlatformScheduler)

            // What a `Schedule` for the key does first, landing just before the tick reads the rows.
            lock.armForThisThread(before = { runState.bump(opId) })
            assertTrue(loop.tick().isEmpty(), "a key whose generation moved across the read is not started")
            assertEquals(0, op.starts.get())

            val jobs = loop.tick()
            realTime { op.started.await() }
            op.gate.complete(Unit)
            realTime { jobs.joinAll() }

            assertEquals(ScheduleItem.STATE_DONE, p.row().state.get(), "…and the next tick runs it to completion")
            assertTrue(loop.tick().isEmpty())
            assertEquals(1, op.starts.get(), "once")
        } finally {
            op.gate.complete(Unit)
            scope.cancel()
        }
    }

    // --- Stopping ------------------------------------------------------------------------------
    //
    // The invariant under test: a run is a child of the supervisor `stop()` cancels, and a
    // coroutine cancelled before its body starts never runs its body — so once `stop()` has
    // returned no run begins, whenever it is launched. One test per point at which `stop()` can
    // land relative to a tick, each forced rather than raced.

    private class Stopping(
        val lock: HookedLock,
        val op: GatedOp,
        val proxy: TestProxy,
        val scope: CoroutineScope,
        val loop: ScheduleDrainLoop,
    )

    private suspend fun stopping(dispatcher: CoroutineDispatcher = Dispatchers.Default): Stopping {
        val lock = HookedLock()
        val op = GatedOp()
        val runState = ScheduleRunState()
        val p = proxy(lock, op, runState)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        p.scheduleOneShot()
        return Stopping(lock, op, p, scope, ScheduleDrainLoop(p.executionContext, scope, tickIntervalMs = 10, runState = runState, platformScheduler = NoOpPlatformScheduler))
    }

    private fun Stopping.close() {
        op.gate.complete(Unit)
        scope.cancel()
    }

    /** Waits (real time) for every launched run to finish, then a little longer for one that should not exist. */
    private suspend fun Stopping.settle(jobs: List<Job> = emptyList()) {
        // Bounded, and never throws: a run that wrongly began is waiting on its op, and the
        // assertions that follow are what should report it.
        realTime { withTimeoutOrNull(2_000) { jobs.joinAll() } }
        realTime { withTimeoutOrNull(2_000) { while (loop.activeRuns > 0) delay(5) } }
        realTime { delay(200) }
    }

    private suspend fun Stopping.assertNothingBegan() {
        assertEquals(0, op.starts.get(), "no run begins once stop() has returned")
        assertEquals(0, loop.activeRuns, "and the key of a run that never began is freed")
        assertEquals(ScheduleItem.STATE_PENDING, proxy.row().state.get(), "its row untouched")
    }

    /**
     * 1 — `stop()` before the tick takes the loop's lock. ⚠⚠ The ordering a stop *count* got
     * wrong: a tick that reads the count after the bump adopts the new value, and the run it
     * launches passes its own check.
     */
    @Test
    fun stopBeforeATickTakesTheLock_theRunItLaunchesNeverBegins() = runTest {
        val s = stopping()
        try {
            s.loop.stop()
            val launched = s.loop.tick()
            assertEquals(1, launched.size, "the tick still launches; the launch is what cannot begin")
            s.settle(launched)
            s.assertNothingBegan()
        } finally {
            s.close()
        }
    }

    /** 2 — `stop()` after the tick has taken the lock, before it reads the rows. */
    @Test
    fun stopBetweenTheLockAndTheRowRead_theRunItLaunchesNeverBegins() = runTest {
        val s = stopping()
        try {
            s.lock.armForThisThread(before = { s.loop.stop() })
            val launched = s.loop.tick()
            assertEquals(1, launched.size)
            s.settle(launched)
            s.assertNothingBegan()
        } finally {
            s.close()
        }
    }

    /** 3 — `stop()` after the row read, before the launch. */
    @Test
    fun stopBetweenTheRowReadAndTheLaunch_theRunItLaunchesNeverBegins() = runTest {
        val s = stopping()
        try {
            s.lock.armForThisThread(after = { s.loop.stop() })
            val launched = s.loop.tick()
            assertEquals(1, launched.size)
            s.settle(launched)
            s.assertNothingBegan()
        } finally {
            s.close()
        }
    }

    /**
     * 4 — `stop()` after the launch, before the run's body has started: the run is launched and
     * sitting in its dispatcher's queue. Held there by a dispatcher that runs nothing until told.
     */
    @Test
    fun stopBetweenTheLaunchAndTheRunsFirstStatement_theRunNeverBegins() = runTest {
        val dispatcher = ManualDispatcher()
        val s = stopping(dispatcher)
        try {
            val launched = s.loop.tick()
            assertEquals(1, launched.size)
            assertEquals(1, s.loop.activeRuns, "launched, and queued")

            s.loop.stop()
            dispatcher.runAll()
            realTime { delay(200) }

            assertEquals(true, launched.single().isCompleted, "it was given its turn")
            s.assertNothingBegan()
        } finally {
            s.close()
        }
    }

    /**
     * 5 — `stop()` after the op was dispatched. This run is **cancelled, not prevented**: it stops
     * waiting for its op, writes no outcome and frees its key. `stop()` itself waits for none of it.
     *
     * ⚠⚠ **The op is not what gets cancelled.** `ExecutionContext.executeAsync` starts an async op
     * on the *session's* scope and the run only awaits it, so cancelling the run abandons the wait;
     * the op carries on until it ends or its session does. Pinned here as it is, so a change to
     * that shows up: this was already so when the loop awaited the op itself.
     */
    @Test
    fun stopAfterTheOpWasDispatched_cancelsTheRunAndWritesNoOutcome() = runTest {
        val s = stopping()
        try {
            val launched = s.loop.tick()
            realTime { s.op.started.await() }

            s.loop.stop()

            val runEnded = withContext(Dispatchers.Default) { withTimeoutOrNull(5_000) { launched.joinAll() } }
            assertNotNull(runEnded, "a run already dispatching is cancelled by stop()")
            assertEquals(true, launched.single().isCancelled)
            s.settle()
            assertEquals(0, s.loop.activeRuns, "its key is freed")
            val row = s.proxy.row()
            assertEquals(ScheduleItem.STATE_PENDING, row.state.get(), "a cancelled run writes no outcome")
            assertEquals(0, row.attempts.get().toInt())
            assertEquals(false, s.op.cancelled.isCompleted, "the op itself is abandoned, not cancelled (see above)")
        } finally {
            s.close()
        }
    }

    /**
     * 1–3 again, through the real loop: its own tick is held mid-pass on its own thread while
     * `stop()` runs to completion on another, and only then let go to launch.
     */
    @Test
    fun stopWhileTheRunningLoopsTickIsMidPass_theRunItLaunchesNeverBegins() = runTest {
        val s = stopping()
        try {
            val inTick = CompletableDeferred<Unit>()
            val stopReturned = CountDownLatch(1)
            s.lock.armForAnyThread(before = {
                inTick.complete(Unit)
                stopReturned.await(10, TimeUnit.SECONDS)
            })
            s.loop.start()
            realTime { inTick.await() }

            s.loop.stop()
            stopReturned.countDown()

            s.settle()
            s.assertNothingBegan()
        } finally {
            s.close()
        }
    }

    /**
     * `stopAndAwait()` against a tick that is mid-pass: when it returns, every run the loop
     * launched has finished — here, without ever beginning.
     *
     * ⚠ The tick is held for a fixed 300 ms, because `stopAndAwait()` waits for the tick and so
     * cannot be the thing that releases it. The fixed code's outcome does not depend on the hold.
     */
    @Test
    fun aRunLaunchedAcrossStopAndAwaitDoesNotOutliveIt() = runTest {
        val s = stopping()
        try {
            val inTick = CompletableDeferred<Unit>()
            s.lock.armForAnyThread(after = {
                inTick.complete(Unit)
                Thread.sleep(300)
            })
            s.loop.start()
            realTime { inTick.await() }

            realTime { s.loop.stopAndAwait() }
            assertEquals(0, s.loop.activeRuns, "every run the loop launched has finished")

            realTime { delay(300) }
            s.assertNothingBegan()
        } finally {
            s.close()
        }
    }

    /**
     * Why `stopAndAwait()` joins the loop **before** it collects the runs. The loop runs on a
     * dispatcher this thread drives by hand; `stopAndAwait()` is called from a second thread while
     * the tick is held mid-pass. The tick then launches its run — which will never begin, but is
     * still unfinished, parked in the queue — and ends. `stopAndAwait()` must not return until
     * that run has been given its turn.
     *
     * ⚠ Same fixed hold as above, for the same reason.
     */
    @Test
    fun stopAndAwaitWaitsForARunLaunchedAfterItWasCalled() = runTest {
        val dispatcher = ManualDispatcher()
        val s = stopping(dispatcher)
        try {
            val inTick = CountDownLatch(1)
            s.lock.armForAnyThread(after = {
                inTick.countDown()
                Thread.sleep(300)
            })
            s.loop.start()

            val activeAtReturn = AtomicInteger(-1)
            val stopper = Thread {
                inTick.await(10, TimeUnit.SECONDS)
                runBlocking { s.loop.stopAndAwait() }
                activeAtReturn.set(s.loop.activeRuns)
            }.apply { start() }

            // The loop's first slice: the held tick, its launch, and the loop's own end.
            assertEquals(true, dispatcher.runOne())
            assertEquals(1, s.loop.activeRuns, "the run is launched and parked")
            // The run is NOT given its turn yet: a stopAndAwait() that does not wait for it returns here.
            stopper.join(500)
            val deadline = System.nanoTime() + 10_000_000_000
            while (stopper.isAlive && System.nanoTime() < deadline) {
                dispatcher.runAll()
                stopper.join(5)
            }

            assertEquals(0, activeAtReturn.get(), "no launched run was unfinished when stopAndAwait() returned")
            s.assertNothingBegan()
        } finally {
            s.close()
        }
    }

    /**
     * `stopAndAwait()` against a run that is on a thread: an op that never suspends cannot be
     * interrupted, and `stopAndAwait()` returns only once that run is over.
     */
    @Test
    fun stopAndAwaitReturnsOnlyOnceARunStillOnItsThreadHasFinished() = runTest {
        val lock = HookedLock()
        val op = SlowSyncOp(holdMs = 400)
        val runState = ScheduleRunState()
        val p = proxy(lock, op, runState)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            p.scheduleOneShot()
            val loop = ScheduleDrainLoop(p.executionContext, scope, tickIntervalMs = 10, runState = runState, platformScheduler = NoOpPlatformScheduler)
            loop.start()
            assertEquals(true, op.started.await(10, TimeUnit.SECONDS))

            realTime { loop.stopAndAwait() }

            assertEquals(true, op.finished, "the run had finished when stopAndAwait() returned")
            assertEquals(0, loop.activeRuns)
        } finally {
            scope.cancel()
        }
    }

    /**
     * A cancelled supervisor cannot be revived, so `start()` after `stop()` has to bring a fresh
     * one — or the restarted loop would launch runs that never begin, for ever.
     */
    @Test
    fun aRestartedLoopDispatchesAgain() = runTest {
        val s = stopping()
        try {
            s.loop.start()
            realTime { s.op.started.await() }
            realTime { s.loop.stopAndAwait() }
            assertEquals(1, s.op.starts.get())
            assertEquals(ScheduleItem.STATE_PENDING, s.proxy.row().state.get())

            s.loop.start()
            val ranAgain = withContext(Dispatchers.Default) {
                withTimeoutOrNull(5_000) { while (s.op.starts.get() < 2) delay(5) }
            }
            assertNotNull(ranAgain, "the restarted loop dispatches the item")
        } finally {
            s.loop.stop()
            s.close()
        }
    }
}
