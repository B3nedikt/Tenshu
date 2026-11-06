package org.tenshu.sync.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.schema.operation
import org.tenshu.core.toTString
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.ScheduledRunDeferred
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.ScheduleImplementation
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.TerminalOperation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Media pipeline P4 (`media-processing-pipeline-sweep.md` F3.1 / F8): the in-process loop's three
 * outcomes, and items no longer blocking each other.
 *
 * ⚠⚠ Every test here drives the loop through [ScheduleDrainLoop.start], never `tick()`, so the
 * same file compiles against the pre-P4 loop and fails there — that is the mutation check.
 *
 * ⚠ The two kinds of clock are deliberate. The retry-interval tests use **virtual time** with a
 * *synchronous* op, which runs inline on the test dispatcher, so `advanceTimeBy` is exact. The
 * concurrency tests use **real** `Dispatchers.Default` and latches, with real-time `withTimeout`
 * (inside `withContext(Dispatchers.Default)`, so virtual time cannot skip past it): ordering is the
 * thing under test there, and a single-threaded test dispatcher would serialise it away.
 */
class ScheduleDrainLoopConcurrencyTest {

    private fun headless(id: String) = operation(
        id = id,
        traits = listOf(TerminalOperation.Schema, HeadlessSafe.Schema),
    )

    private fun proxy(ops: Map<String, Any>): TestProxy =
        TestProxy(
            implementationFactoryOverride = { id, _ ->
                when (id) {
                    Schedule.ID -> ScheduleImplementation(null)
                    else -> ops[id] as? org.tenshu.core.common.proxy.executor.OperationImplementation
                }
            }
        ).apply {
            schemaRegistry.registerConfig(syncServiceCommonModule)
            schemaRegistry.registerConfig(
                tModule {
                    ops.keys.forEach { operation(interfaceClass = Operation::class, protocolSchema = headless(it)) }
                }
            )
        }

    private suspend fun TestProxy.schedule(
        opId: String,
        periodMs: Long,
        kind: String = ScheduleSpec.KIND_PERIODIC,
    ) {
        val ctx = executionContext.context
        executionContext.executeAsync<Data>(
            operationId = Schedule.ID,
            caller = null,
            params = mapOf(
                Schedule.Params.OP_ID to opId.toTString(ctx),
                Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(
                    ctx,
                    kind = kind,
                    minPeriodMs = periodMs.takeIf { kind == ScheduleSpec.KIND_PERIODIC },
                ),
                Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(ctx),
                Schedule.Params.RETRY_POLICY to RetryPolicy.create(ctx),
                Schedule.Params.DEDUP_KEY to opId.toTString(ctx),
            ),
        )
    }

    /** Runs [body] on each call — synchronously, on the dispatching coroutine. */
    private class SyncOp(val body: (call: Int) -> Unit) : SyncOperationImplementation {
        var calls = 0
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            calls++
            body(calls)
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    /** Suspends in [body] — dispatched the production way, through `AsyncReference` and an `await`. */
    private class AsyncOp(val body: suspend () -> Unit) : AsyncOperationImplementation {
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            body()
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    // --- Retry interval (virtual time) ---------------------------------------------------------

    private val minute = 60_000L
    private val twelveHours = 12 * 60 * minute

    /**
     * ⚠⚠ The F3.1 defect: the pass fired at startup, declined (screen on), and the loop reset the
     * whole 12 h period. A deferral must come back after its own interval — 15 min — and a run that
     * then completes must put the full period back.
     */
    @Test
    fun aDeferredRunIsRetriedAfterFifteenMinutesNotTwelveHours() = runTest {
        val op = SyncOp { call ->
            if (call == 1) throw ScheduledRunDeferred(15 * minute, "device busy")
        }
        val p = proxy(mapOf("test/op/defers-once" to op))
        p.schedule("test/op/defers-once", periodMs = twelveHours)

        val loop = ScheduleDrainLoop(p.executionContext, backgroundScope, tickIntervalMs = minute, platformScheduler = NoOpPlatformScheduler)
        loop.start()
        runCurrent()
        assertEquals(1, op.calls, "a periodic item fires on the loop's first tick")

        advanceTimeBy(14 * minute + minute / 2)
        assertEquals(1, op.calls, "not before the deferral's interval")

        advanceTimeBy(minute)
        assertEquals(2, op.calls, "a deferred run is retried ~15 min later, not after the 12 h period")

        advanceTimeBy(twelveHours - 2 * minute)
        assertEquals(2, op.calls, "a run that completed resets the full period")

        advanceTimeBy(3 * minute)
        assertEquals(3, op.calls, "…and the period then fires as before")
    }

    /** "A run that throws keeps today's behaviour": an ordinary failure waits the full period. */
    @Test
    fun aRunThatThrowsStillWaitsTheFullPeriod() = runTest {
        val op = SyncOp { throw IllegalStateException("boom") }
        val p = proxy(mapOf("test/op/throws" to op))
        p.schedule("test/op/throws", periodMs = twelveHours)

        val loop = ScheduleDrainLoop(p.executionContext, backgroundScope, tickIntervalMs = minute, platformScheduler = NoOpPlatformScheduler)
        loop.start()
        runCurrent()
        assertEquals(1, op.calls)

        advanceTimeBy(twelveHours - minute)
        assertEquals(1, op.calls, "a failure is not a deferral: no early retry")

        advanceTimeBy(2 * minute)
        assertEquals(2, op.calls)
    }

    /**
     * The production path: an *async* op's deferral crosses `scope.async { }.await()` on a real
     * dispatcher. ⚠ That is where kotlinx-coroutines' JVM stack-trace recovery would substitute a
     * copy of the exception; the interval only survives if the original instance arrives.
     */
    @Test
    fun anAsyncOpsDeferralSurvivesTheAwaitAndIsHonoured() = runTest {
        val secondCall = CompletableDeferred<Unit>()
        val calls = Mutex()
        var count = 0
        val op = AsyncOp {
            val n = calls.withLock { ++count }
            if (n == 1) throw ScheduledRunDeferred(50, "device busy")
            secondCall.complete(Unit)
        }
        val p = proxy(mapOf("test/op/async-defers" to op))
        p.schedule("test/op/async-defers", periodMs = twelveHours)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            ScheduleDrainLoop(p.executionContext, scope, tickIntervalMs = 10, platformScheduler = NoOpPlatformScheduler).start()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { secondCall.await() }
            }
        } finally {
            scope.cancel()
        }
    }

    // --- Concurrency (real dispatcher, latches) ------------------------------------------------

    /**
     * ⚠⚠ F8: a multi-hour index pass used to stop `drainSync`, `replayPendingMoves` and the offline
     * keeper ticking in-process — the loop awaited each item in turn.
     */
    @Test
    fun aLongItemDoesNotBlockAnotherItem() = runTest {
        val longStarted = CompletableDeferred<Unit>()
        val releaseLong = CompletableDeferred<Unit>()
        val quickRan = CompletableDeferred<Unit>()
        val long = AsyncOp {
            longStarted.complete(Unit)
            releaseLong.await()
        }
        val quick = AsyncOp { quickRan.complete(Unit) }
        // ⚠ The long item is registered FIRST, so a serial loop reaches it before the quick one.
        val p = proxy(mapOf("test/op/long" to long, "test/op/quick" to quick))
        p.schedule("test/op/long", periodMs = 10)
        p.schedule("test/op/quick", periodMs = 10)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            ScheduleDrainLoop(p.executionContext, scope, tickIntervalMs = 10, platformScheduler = NoOpPlatformScheduler).start()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { longStarted.await() }
                withTimeout(10_000) { quickRan.await() }
            }
            assertEquals(false, releaseLong.isCompleted, "the quick item ran while the long one was still running")
        } finally {
            releaseLong.complete(Unit)
            scope.cancel()
        }
    }

    /**
     * Per-key single flight: an item due on every tick, whose run outlasts many ticks, is started
     * once — never a second time beside itself. The quick item is the witness that ticks did pass.
     */
    @Test
    fun anItemNeverRunsTwiceAtOnce() = runTest {
        val lock = Mutex()
        var running = 0
        var maxRunning = 0
        var starts = 0
        val releaseLong = CompletableDeferred<Unit>()
        val long = AsyncOp {
            lock.withLock {
                starts++
                running++
                maxRunning = maxOf(maxRunning, running)
            }
            try {
                releaseLong.await()
            } finally {
                lock.withLock { running-- }
            }
        }
        val witnessTicks = CompletableDeferred<Unit>()
        var witnessRuns = 0
        val witness = AsyncOp {
            if (lock.withLock { ++witnessRuns } >= 10) witnessTicks.complete(Unit)
        }
        val p = proxy(mapOf("test/op/long" to long, "test/op/witness" to witness))
        p.schedule("test/op/long", periodMs = 10)
        p.schedule("test/op/witness", periodMs = 10)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            ScheduleDrainLoop(p.executionContext, scope, tickIntervalMs = 10, platformScheduler = NoOpPlatformScheduler).start()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { witnessTicks.await() }
                // A few more ticks' worth of real time for a second start to show up, if it would.
                delay(100)
            }
            lock.withLock {
                assertEquals(1, starts, "the long item was started once across many due ticks")
                assertEquals(1, maxRunning, "one item never overlaps itself")
            }
        } finally {
            releaseLong.complete(Unit)
            scope.cancel()
        }
    }
}
