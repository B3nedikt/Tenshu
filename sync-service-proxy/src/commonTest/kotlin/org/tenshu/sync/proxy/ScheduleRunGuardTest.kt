package org.tenshu.sync.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
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
import org.tenshu.sync.ScheduledRunDeferred
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.CancelScheduledImplementation
import org.tenshu.sync.proxy.impl.ListScheduledImplementation
import org.tenshu.sync.proxy.impl.ScheduleImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The generation guard ([ScheduleRunState]) and the in-place row write (`writeScheduleItem`): what
 * happens to a key's row when something else writes it **while that key's run is in flight**, and
 * when a row write fails.
 *
 * Every row for a key shares one `DataId`, so before the guard a run's completion overwrote
 * whatever was there: a re-registration was lost, a cancelled item came back.
 *
 * ⚠ Stepped through `tick()` with the op held on a latch, so "during the run" is a state the test
 * holds rather than a window it has to hit. The run itself is on real `Dispatchers.Default`.
 */
class ScheduleRunGuardTest {

    private val opId = "test/op/guarded"
    private val key = "guarded-job"

    private val headless = operation(
        id = opId,
        traits = listOf(TerminalOperation.Schema, HeadlessSafe.Schema),
    )

    /** Counts starts; each run waits on [gate] until the test lets it finish. */
    private class GatedOp : AsyncOperationImplementation {
        var gate = CompletableDeferred<Unit>()
        var started = CompletableDeferred<Unit>()
        var starts = 0

        /** What the run does once released — throw from here to end it in a failure or a deferral. */
        var onRelease: (() -> Unit)? = null

        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            starts++
            started.complete(Unit)
            gate.await()
            onRelease?.invoke()
            @Suppress("UNCHECKED_CAST")
            return null as T
        }

        fun rearm() {
            gate = CompletableDeferred()
            started = CompletableDeferred()
            onRelease = null
        }
    }

    /**
     * An in-memory store whose row writes can be made to throw.
     *
     * ⚠ A `ScheduleItem` is not one store write: its three nested specs are stored first, as
     * entries of their own, and the row's entry last. [failRowWrites] fails all of them — every
     * write except the array's own ref list, which is what a remove-then-add gets through before
     * it loses the row. [failRowEntryOnly] lets the specs land and fails the row's entry.
     */
    private class FailingRowStore(
        private val delegate: InMemoryObjectStore = InMemoryObjectStore(),
    ) : ObjectStore by delegate {
        var failRowWrites = false
        var failRowEntryOnly = false

        override fun storeObject(dbEntry: ObjectStore.DbEntry) {
            if (failRowWrites && dbEntry.type != TList.ID) error("disk full")
            if (failRowEntryOnly && dbEntry.type == ScheduleItem.ID) error("disk full")
            delegate.storeObject(dbEntry)
        }
    }

    private class Fixture(
        val proxy: TestProxy,
        val op: GatedOp,
        val runState: ScheduleRunState,
        val store: FailingRowStore,
        val scope: CoroutineScope,
    ) {
        fun loop(state: ScheduleRunState = runState) =
            ScheduleDrainLoop(proxy.executionContext, scope, tickIntervalMs = 10, runState = state, platformScheduler = NoOpPlatformScheduler)
    }

    private fun fixture(): Fixture {
        val op = GatedOp()
        val runState = ScheduleRunState()
        val store = FailingRowStore()
        val proxy = TestProxy(
            objectStore = store,
            implementationFactoryOverride = { id, _ ->
                when (id) {
                    Schedule.ID -> ScheduleImplementation(null, runState)
                    CancelScheduled.ID -> CancelScheduledImplementation(null, runState)
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
        return Fixture(proxy, op, runState, store, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    private suspend fun Fixture.schedule(
        periodMs: Long? = 10,
        kind: String = if (periodMs != null) ScheduleSpec.KIND_PERIODIC else ScheduleSpec.KIND_ONE_SHOT,
        initialDelayMs: Long? = null,
    ) {
        val ctx = proxy.executionContext.context
        proxy.executionContext.executeAsync<Data>(
            operationId = Schedule.ID,
            caller = null,
            params = mapOf(
                Schedule.Params.OP_ID to opId.toTString(ctx),
                Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(
                    ctx,
                    kind = kind,
                    minPeriodMs = periodMs,
                    initialDelayMs = initialDelayMs,
                ),
                Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(ctx),
                Schedule.Params.RETRY_POLICY to RetryPolicy.create(ctx),
                Schedule.Params.DEDUP_KEY to key.toTString(ctx),
            ),
        )
    }

    private suspend fun Fixture.cancel() {
        val ctx = proxy.executionContext.context
        proxy.executionContext.executeAsync<Data>(
            operationId = CancelScheduled.ID,
            caller = null,
            params = mapOf(CancelScheduled.Params.DEDUP_KEY to key.toTString(ctx)),
        )
    }

    private suspend fun Fixture.rows(): List<ScheduleItem> =
        proxy.executionContext.executeAsync<TList<Data>>(operationId = ListScheduled.ID, caller = null)
            .toList().map { it as ScheduleItem }

    private suspend fun Fixture.row(): ScheduleItem? = rows().singleOrNull()

    /** Real time, off the test dispatcher, so virtual time cannot skip the wait. */
    private suspend fun GatedOp.awaitStarted() =
        withContext(Dispatchers.Default) { withTimeout(10_000) { started.await() } }

    private fun ScheduleItem.period() = scheduleSpec.minPeriodMs?.get()

    // --- A registration during a run -----------------------------------------------------------

    /**
     * ⚠⚠ The lost registration. The completion used to write its *own* copy of the row — the old
     * spec, `attempts + 1` — over the one registered during the run, through their shared id.
     */
    @Test
    fun aRegistrationDuringARunSurvivesThatRunsCompletion() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = 10)
            val loop = f.loop()

            val first = loop.tick()
            f.op.awaitStarted()

            f.schedule(periodMs = 77)

            // The tick reaches the key while the old run is still going: it must not start again.
            assertTrue(loop.tick().isEmpty(), "a key in flight is not started beside itself")
            assertEquals(1, f.op.starts)

            f.op.gate.complete(Unit)
            first.joinAll()

            val row = f.row()!!
            assertEquals(77L, row.period(), "the row registered during the run is the one left")
            assertEquals(0, row.attempts.get().toInt(), "…untouched by the older run's completion")
            assertEquals(ScheduleItem.STATE_PENDING, row.state.get())
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    /**
     * A one-shot re-armed during its own run: the completion must not mark the *new* row `DONE`,
     * and the new row then fires in its own right — once.
     */
    @Test
    fun aOneShotReRegisteredDuringItsRunStaysPendingAndFiresOnceMore() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = null)
            val loop = f.loop()

            val first = loop.tick()
            f.op.awaitStarted()
            f.schedule(periodMs = null)
            f.op.gate.complete(Unit)
            first.joinAll()

            assertEquals(ScheduleItem.STATE_PENDING, f.row()!!.state.get(), "the newer registration is not completed by the older run")

            f.op.rearm()
            val second = loop.tick()
            f.op.awaitStarted()
            f.op.gate.complete(Unit)
            second.joinAll()

            assertEquals(2, f.op.starts, "the re-registered one-shot runs in its own right")
            assertEquals(ScheduleItem.STATE_DONE, f.row()!!.state.get())
            assertTrue(loop.tick().isEmpty(), "…and only once")
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    // --- Timing after a stand-down --------------------------------------------------------------

    /** Runs the op the loop just started to its end, and waits for the run's bookkeeping. */
    private suspend fun Fixture.finish(jobs: List<kotlinx.coroutines.Job>, onRelease: (() -> Unit)? = null) {
        op.onRelease = onRelease
        op.gate.complete(Unit)
        jobs.joinAll()
        op.rearm()
    }

    /**
     * ⚠⚠ A stood-down run's timing is not the new registration's. The loop used to decide what to
     * keep from the OLD item's kind: a key that came back as a one-shot during its periodic run
     * kept "already fired once" and went on the next tick instead of after its own initial delay.
     */
    @Test
    fun aKeyReRegisteredAsAOneShotDuringItsPeriodicRunWaitsItsOwnInitialDelay() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = 10)
            val loop = f.loop()
            val first = loop.tick()
            f.op.awaitStarted()

            f.schedule(periodMs = null, initialDelayMs = 30)
            f.finish(first)

            assertTrue(loop.tick().isEmpty(), "10 ms into a 30 ms initial delay")
            assertTrue(loop.tick().isEmpty(), "20 ms")
            val due = loop.tick()
            assertEquals(1, due.size, "…and due at 30 ms, as a one-shot nothing has run yet")
            f.op.awaitStarted()
            f.finish(due)
            assertEquals(ScheduleItem.STATE_DONE, f.row()?.state?.get())
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    /**
     * The same rule for a periodic key re-registered during its run: the registration left behind
     * has never run, so it starts from its own initial delay — none here, so the next tick — rather
     * than waiting out a period measured from a run that was not its own. One extra run is the
     * cheap error; a registration silently waiting 12 h is not.
     */
    @Test
    fun aPeriodicKeyReRegisteredDuringItsRunStartsItsTimingAfresh() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = 10_000)
            val loop = f.loop()
            val first = loop.tick()
            f.op.awaitStarted()

            f.schedule(periodMs = 10_000)
            f.finish(first)

            val next = loop.tick()
            assertEquals(1, next.size, "the new registration fires in its own right, not a period later")
            f.op.awaitStarted()
            f.finish(next)
            assertEquals(1, f.row()?.attempts?.get()?.toInt(), "and that run is the one counted on its row")
            assertTrue(loop.tick().isEmpty(), "after which the period applies")
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    /**
     * ⚠⚠ A deferral writes no row, so it used to skip the generation compare altogether and leave
     * its retry delay on the key. A registration that replaced the row during that run then waited
     * the OLD run's delay instead of its own.
     */
    @Test
    fun aDeferredRunsRetryDelayIsNotInheritedByARegistrationMadeDuringIt() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = 10)
            val loop = f.loop()
            val first = loop.tick()
            f.op.awaitStarted()

            f.schedule(periodMs = null, initialDelayMs = 30)
            f.finish(first) { throw ScheduledRunDeferred(10_000, "device busy") }

            assertTrue(loop.tick().isEmpty(), "10 ms into the new registration's 30 ms initial delay")
            assertTrue(loop.tick().isEmpty(), "20 ms")
            assertEquals(1, loop.tick().size, "due on its own delay, not the old run's 10 s deferral")
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    /** …nor by a later registration that reuses a key cancelled during a deferred run. */
    @Test
    fun aKeyCancelledDuringADeferredRunStartsCleanWhenItIsRegisteredAgain() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = 10)
            val loop = f.loop()
            val first = loop.tick()
            f.op.awaitStarted()

            f.cancel()
            f.finish(first) { throw ScheduledRunDeferred(10_000, "device busy") }
            assertTrue(loop.tick().isEmpty(), "cancelled: nothing to fire")

            f.schedule(periodMs = null)
            assertEquals(1, loop.tick().size, "a fresh one-shot with no delay fires on the next tick")
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    /** The other side: a deferral nothing interfered with still gets its retry delay. */
    @Test
    fun aDeferredRunNothingReplacedIsRetriedAfterItsOwnDelay() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = 10_000)
            val loop = f.loop()
            val first = loop.tick()
            f.op.awaitStarted()
            f.finish(first) { throw ScheduledRunDeferred(30, "device busy") }

            assertTrue(loop.tick().isEmpty(), "10 ms into a 30 ms deferral")
            assertTrue(loop.tick().isEmpty(), "20 ms")
            assertEquals(1, loop.tick().size, "retried after the deferral, not after the 10 s period")
            assertEquals(0, f.row()?.attempts?.get()?.toInt(), "a deferral is not an attempt")
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    // --- A cancel during a run -----------------------------------------------------------------

    /** ⚠⚠ The resurrected job: the completion used to add the row back after the cancel removed it. */
    @Test
    fun aCancelDuringARunStaysCancelled() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = 10)
            val loop = f.loop()

            val first = loop.tick()
            f.op.awaitStarted()
            f.cancel()
            f.op.gate.complete(Unit)
            first.joinAll()

            assertEquals(emptyList(), f.rows(), "a cancelled item is not written back by its run's completion")
            assertTrue(loop.tick().isEmpty(), "…and is not fired again")
            assertEquals(1, f.op.starts)
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    /**
     * A completion only ever updates a row that is there. This removal goes behind both writers —
     * no generation moves — which is what another process on a shared store would look like.
     */
    @Test
    fun aRowRemovedBehindTheLoopIsNotResurrected() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = 10)
            val loop = f.loop()

            val first = loop.tick()
            f.op.awaitStarted()
            val ctx = f.proxy.executionContext
            ctx.database.removeFromArray(scheduledItemsRef(ctx), ctx.getDataId(f.row()!!)!!)
            f.op.gate.complete(Unit)
            first.joinAll()

            assertEquals(emptyList(), f.rows(), "a completion never adds a row")
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    /** Both writers move the key's generation — the half of the guard the loop cannot do itself. */
    @Test
    fun scheduleAndCancelEachMoveTheKeysGeneration() = runTest {
        val f = fixture()
        try {
            assertEquals(0L, f.runState.generationOf(key))
            f.schedule()
            assertEquals(1L, f.runState.generationOf(key), "Schedule bumps")
            f.schedule()
            assertEquals(2L, f.runState.generationOf(key), "…on every registration, not only the first")
            f.cancel()
            assertEquals(3L, f.runState.generationOf(key), "CancelScheduled bumps")
            f.cancel()
            assertEquals(4L, f.runState.generationOf(key), "…even when there was no row to remove")
            assertEquals(0L, f.runState.generationOf("another-key"), "per key")
        } finally {
            f.scope.cancel()
        }
    }

    // --- A row write that fails ----------------------------------------------------------------

    /**
     * ⚠⚠ `Database.transaction` has no rollback. The writer used to remove the row and then add its
     * replacement, so an add that threw left the key with no row at all — silently unscheduled.
     */
    @Test
    fun aRegistrationWhoseRowWriteFailsLeavesThePreviousRowIntact() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = 10)

            f.store.failRowWrites = true
            assertFails { f.schedule(periodMs = 77) }
            f.store.failRowWrites = false

            val row = f.row()
            assertEquals(10L, row?.period(), "the previous registration is still there, whole")
            assertEquals(ScheduleItem.STATE_PENDING, row?.state?.get())

            // …and it still runs.
            val jobs = f.loop().tick()
            f.op.awaitStarted()
            f.op.gate.complete(Unit)
            jobs.joinAll()
            assertEquals(1, f.op.starts)
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    /**
     * A row write that fails **part-way** — the nested specs landed, the row's own entry did not.
     * ⚠ In place is not all-or-nothing: the row can be left with newer specs under its old entry.
     * What it guarantees is the thing remove-then-add lost: the key still has a row, and it runs.
     */
    @Test
    fun aRegistrationWhoseRowWriteFailsPartWayStillLeavesTheKeyARow() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = 10)

            f.store.failRowEntryOnly = true
            assertFails { f.schedule(periodMs = 77) }
            f.store.failRowEntryOnly = false

            assertEquals(ScheduleItem.STATE_PENDING, f.row()?.state?.get(), "the key still has its row")

            val jobs = f.loop().tick()
            f.op.awaitStarted()
            f.op.gate.complete(Unit)
            jobs.joinAll()
            assertEquals(1, f.op.starts)
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    /**
     * The same failure with a run in flight: the generation was bumped before the write threw, so
     * the completion stands down, the previous row stays `PENDING`, and it fires once more than
     * strictly needed — the cheap error, against a key left with no row.
     */
    @Test
    fun aFailedRegistrationDuringARunLeavesThePreviousRowPendingAndItFiresOnceMore() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = null)
            val loop = f.loop()

            val first = loop.tick()
            f.op.awaitStarted()
            f.store.failRowWrites = true
            assertFails { f.schedule(periodMs = null) }
            f.store.failRowWrites = false
            f.op.gate.complete(Unit)
            first.joinAll()

            assertEquals(ScheduleItem.STATE_PENDING, f.row()?.state?.get(), "the previous row is intact and still pending")

            f.op.rearm()
            val second = loop.tick()
            f.op.awaitStarted()
            f.op.gate.complete(Unit)
            second.joinAll()
            assertEquals(2, f.op.starts)
            assertEquals(ScheduleItem.STATE_DONE, f.row()?.state?.get())
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    /**
     * The loop's own completion write, failing. ⚠⚠ Two things: the row must survive (the
     * completion used to remove before it added), and the throw must not escape the run's
     * coroutine — it has no handler, so on Android an escaped throw kills Control.
     */
    @Test
    fun aCompletionWhoseRowWriteFailsLeavesTheRowAndDoesNotFailTheRun() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = 10)
            val loop = f.loop()

            val first = loop.tick()
            f.op.awaitStarted()
            f.store.failRowWrites = true
            f.op.gate.complete(Unit)
            first.joinAll()
            f.store.failRowWrites = false

            assertFalse(first.single().isCancelled, "a failed completion write does not fail the run's coroutine")
            val row = f.row()
            assertEquals(ScheduleItem.STATE_PENDING, row?.state?.get(), "the row is still there")
            assertEquals(0, row?.attempts?.get()?.toInt(), "…as it was before the write that failed")
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }

    // --- Restart -------------------------------------------------------------------------------

    /**
     * A restart loses every generation and every in-flight key, and no row is ever stored as
     * running — so a `PENDING` row simply fires. ⚠ A regression guard: it holds without the guard
     * too; it is here so a later persisted "running" state cannot strand a row unnoticed.
     */
    @Test
    fun afterARestartAPendingRowFiresOnce() = runTest {
        val f = fixture()
        try {
            f.schedule(periodMs = null)
            assertEquals(1L, f.runState.generationOf(key))

            // The process dies mid-run: the first loop and its run are gone, the row is untouched.
            val dead = f.loop()
            dead.tick()
            f.op.awaitStarted()
            dead.stopAndAwait()
            assertEquals(ScheduleItem.STATE_PENDING, f.row()?.state?.get())

            f.op.rearm()
            val restarted = f.loop(ScheduleRunState())
            val jobs = restarted.tick()
            f.op.awaitStarted()
            f.op.gate.complete(Unit)
            jobs.joinAll()

            assertEquals(ScheduleItem.STATE_DONE, f.row()?.state?.get())
            assertTrue(restarted.tick().isEmpty(), "it fires once")
            assertEquals(2, f.op.starts, "the run the dead process started, and the one after the restart")
        } finally {
            f.op.gate.complete(Unit)
            f.scope.cancel()
        }
    }
}
