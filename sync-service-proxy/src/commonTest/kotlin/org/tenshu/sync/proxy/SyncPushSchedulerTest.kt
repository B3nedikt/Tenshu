package org.tenshu.sync.proxy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.BackgroundLiveness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Control's own sync-push job, the platform-free half (`control-background-work-off-fgs.md` §5.3):
 * when it is enqueued ([SyncPushScheduler]) and how long one run lasts ([SyncPushRun]).
 *
 * ⚠⚠ Enqueuing an expedited job on every rising edge of the AGGREGATE liveness is, during a backup,
 * once per file: 746 jobs for 300 photos (§3.4 there).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPushSchedulerTest {

    private class Rig(scope: TestScope, var pendingBytes: Long? = 0) {
        var contentProgress = 0L
        var planeWaitMs = 0L
        val liveness = BackgroundLiveness()
        val requests = mutableListOf<SyncPushRequest>()
        val contentDeferred = MutableStateFlow(false)
        var deferredPushes = 0
        val scheduler = SyncPushScheduler(
            backgroundLiveness = liveness,
            scope = scope.backgroundScope,
            pendingContentBytes = { pendingBytes },
            enqueue = { requests += it },
            contentDeferred = contentDeferred,
            enqueueDeferredContentPush = { deferredPushes++ },
            nowMs = { scope.testScheduler.currentTime },
            contentProgress = { contentProgress },
            planeWaitRemainingMs = { planeWaitMs },
        ).also { it.start() }
    }

    @Test
    fun aBacklogEnqueuesOneExpeditedPush() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.liveness.setActive(true, BackgroundLiveness.SYNC_DRAIN)
        runCurrent()
        assertEquals(listOf(SyncPushRequest(expedited = true, appendToRunning = true)), rig.requests)
    }

    @Test
    fun otherSourcesEnqueueNothing() = runTest {
        // A map download or an index pass has no push to run.
        val rig = Rig(this)
        runCurrent()
        rig.liveness.setActive(true, BackgroundLiveness.MAP_REGION_DOWNLOAD)
        rig.liveness.setActive(true, BackgroundLiveness.SCHEDULED_MEDIA_INDEX_PASS)
        runCurrent()
        assertTrue(rig.requests.isEmpty(), "got ${rig.requests}")
    }

    @Test
    fun aLargeBacklogRunsAsAnOrdinaryJob() = runTest {
        val rig = Rig(this, pendingBytes = SyncPushScheduler.EXPEDITE_MAX_BYTES + 1)
        runCurrent()
        rig.liveness.setActive(true, BackgroundLiveness.SYNC_DRAIN)
        runCurrent()
        assertEquals(false, rig.requests.single().expedited, "bulk bytes must not spend the EJ quota")
    }

    @Test
    fun aBacklogWhileAJobRunsIsQueuedBehindItNotDropped() = runTest {
        // ⚠⚠ The race: the job has reported back (workerFinished runs in its finally) but WorkManager
        // still reads it RUNNING; a KEEP enqueue there is dropped, and the raised source never rises
        // again. So every fresh backlog is APPENDED — queued behind whatever is there.
        val rig = Rig(this)
        runCurrent()
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.IDLE) // reported back, not yet returned
        rig.liveness.setActive(true, BackgroundLiveness.SYNC_DRAIN)
        runCurrent()
        assertEquals(listOf(SyncPushRequest(expedited = true, appendToRunning = true)), rig.requests)
    }

    @Test
    fun edgesBeforeTheQueuedRequestStartsAreCoalesced() = runTest {
        // Appending every edge would chain a job per edge; one queued request drains them all.
        val rig = Rig(this)
        runCurrent()
        repeat(3) {
            rig.liveness.setActive(true, BackgroundLiveness.SYNC_DRAIN)
            runCurrent()
            rig.liveness.setActive(false, BackgroundLiveness.SYNC_DRAIN)
            runCurrent()
        }
        assertEquals(1, rig.requests.size, "got ${rig.requests}")

        rig.scheduler.workerStarted() // the queued one starts: the next edge may queue again
        rig.liveness.setActive(true, BackgroundLiveness.SYNC_DRAIN)
        runCurrent()
        assertEquals(2, rig.requests.size)
    }

    @Test
    fun aRunThatFinishesIdleAsksForNothing() = runTest {
        val rig = Rig(this)
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.IDLE)
        assertTrue(rig.requests.isEmpty())
    }

    @Test
    fun aRunOutOfTimeContinuesAndAStalledOneWaits() = runTest {
        val rig = Rig(this)
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.MORE)
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.STALLED)
        assertEquals(
            listOf(
                SyncPushRequest(expedited = false, appendToRunning = true),
                SyncPushRequest(
                    expedited = false,
                    appendToRunning = false,
                    initialDelayMs = SyncPushScheduler.STALL_RETRY_DELAY_MS,
                ),
            ),
            rig.requests,
        )
    }

    @Test
    fun aStalledPushStaysParkedUntilItsRetry() = runTest {
        // ⚠⚠ Measured with every content PUT failing: unparked, a fresh edge 16 s after the STALLED
        // run starts another 8-minute run, so the retry delay spaces nothing out.
        val rig = Rig(this)
        runCurrent()
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.STALLED)
        assertEquals(1, rig.requests.size, "the delayed retry")

        rig.liveness.setActive(true, BackgroundLiveness.SYNC_DRAIN)
        runCurrent()
        assertEquals(1, rig.requests.size, "a fresh edge while parked enqueues nothing")
        assertEquals(false, rig.scheduler.shouldRun(isStallRetry = false), "a queued or backstop job stands down")
        assertEquals(true, rig.scheduler.shouldRun(isStallRetry = true), "the retry itself runs")

        advanceTimeBy(SyncPushScheduler.STALL_RETRY_DELAY_MS + 1)
        assertEquals(true, rig.scheduler.shouldRun(isStallRetry = false), "parking ends with the retry delay")
    }

    /**
     * `control-background-work-off-fgs.md` §5.7, measured: while parked, a blocked Control sees no network after every job, `deferred`
     * rises each time, and the backstop started (and stood down) every ~16 s for the whole park.
     */
    @Test
    fun theDeferredContentBackstopIsNotRegisteredWhileParked() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.STALLED)

        rig.contentDeferred.value = true
        runCurrent()
        assertEquals(0, rig.deferredPushes, "the stall retry owns the backlog while parked")
        advanceTimeBy(SyncPushScheduler.STALL_RETRY_DELAY_MS - SyncPushScheduler.PARK_POLL_MS)
        assertEquals(0, rig.deferredPushes, "for the whole park")
    }

    /**
     * `deferred` stays raised through the park, so no fresh edge re-registers the backstop when the
     * park ends by TIME — without the watcher, held Wi-Fi-only content waits for the hourly drain.
     */
    @Test
    fun aParkEndedByTime_bringsTheBackstopBack_whileContentIsStillHeld() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.contentDeferred.value = true
        runCurrent()
        assertEquals(1, rig.deferredPushes, "precondition: registered once before the stall")
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.STALLED)

        advanceTimeBy(SyncPushScheduler.STALL_RETRY_DELAY_MS + SyncPushScheduler.PARK_POLL_MS + 1)
        assertEquals(2, rig.deferredPushes, "the watcher re-registers it when the park times out, deferred never having fallen")
    }

    /** The same, when this process is gone and only the queued retry is left to run. */
    @Test
    fun theStallRetryRunning_bringsTheBackstopBack_whileContentIsStillHeld() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.contentDeferred.value = true
        runCurrent()
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.STALLED)
        val before = rig.deferredPushes

        assertEquals(true, rig.scheduler.shouldRun(isStallRetry = true))
        assertEquals(before + 1, rig.deferredPushes, "the retry registers the backstop")
        assertEquals(false, rig.scheduler.shouldRun(isStallRetry = false))
        assertEquals(before + 1, rig.deferredPushes, "and nothing else does")
    }

    /** The other branch: a park ended by PROGRESS, with content held. */
    @Test
    fun aParkEndedByProgress_bringsTheBackstopBack_whileContentIsStillHeld() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.contentDeferred.value = true
        runCurrent()
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.STALLED)
        val before = rig.deferredPushes

        rig.contentProgress += 1
        advanceTimeBy(SyncPushScheduler.PARK_POLL_MS + 1)
        assertEquals(before + 1, rig.deferredPushes)
    }

    /**
     * While the content plane waits, its backlog is not pushable, so a run ends
     * IDLE with files queued — and no edge comes, because the backlog never emptied. One run is queued
     * for when the wait ends, and only one.
     */
    @Test
    fun anIdleRunWhileThePlaneWaits_queuesOneRunForWhenTheWaitEnds() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.planeWaitMs = 40_000
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.IDLE)
        val retry = rig.requests.single()
        assertEquals(SyncPushScheduler.PLANE_WORK_NAME, retry.uniqueName)
        assertEquals(40_000 + SyncPushScheduler.PLANE_RETRY_SLACK_MS, retry.initialDelayMs)
        assertEquals(true, retry.appendToRunning, "the run asking may be the last plane retry, still RUNNING")

        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.IDLE)
        assertEquals(1, rig.requests.size, "one queued plane retry is enough")

        rig.planeWaitMs = 0
        advanceTimeBy(40_001)
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.IDLE)
        assertEquals(1, rig.requests.size, "a plane that does not wait asks for nothing")
    }

    @Test
    fun anythingButAFinishedFileAfterAStall_leavesItParked() = runTest {
        // With only content failing, metadata keeps landing; counting it would un-park the push into
        // another failing run every 2–3 minutes. The rig's progress is completed content only;
        // what feeds it is pinned in SyncPushInlineProgressTest.
        val rig = Rig(this)
        runCurrent()
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.STALLED)
        rig.liveness.setActive(true, BackgroundLiveness.SYNC_DRAIN)
        runCurrent()
        assertEquals(false, rig.scheduler.shouldRun(isStallRetry = false))
        assertEquals(1, rig.requests.size, "only the retry")
    }

    /**
     * §5.7, measured on the emulator: once the plane heals, Control's in-process loop may drain the
     * whole backlog while the push is parked — and a backup started after that would wait for the
     * 15-minute retry for its job unless a landed file ends the park.
     */
    @Test
    fun aFileLandingAfterAStall_endsThePark() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.contentProgress = 41
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.STALLED)
        assertEquals(false, rig.scheduler.shouldRun(isStallRetry = false), "precondition: parked")

        rig.contentProgress = 42                         // a file's content landed, on whatever path
        assertEquals(true, rig.scheduler.shouldRun(isStallRetry = false), "the plane answers: no longer parked")
        rig.liveness.setActive(true, BackgroundLiveness.SYNC_DRAIN)
        runCurrent()
        assertEquals(2, rig.requests.size, "and a fresh backlog gets its job now, beside the retry")
    }

    /**
     * §5.7, measured: during a park the `SYNC_DRAIN` source stays raised, so no fresh edge comes. When a
     * client's upload lands and its job ends, Android may kill the cached Control for an outstanding
     * binder call, and with no Control job queued nothing restarts it until the retry.
     */
    @Test
    fun aParkEndedByProgress_queuesThePushForTheBacklogAlreadyWaiting() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.liveness.setActive(true, BackgroundLiveness.SYNC_DRAIN)   // the backlog that stalled
        runCurrent()
        rig.scheduler.workerStarted()
        rig.scheduler.workerFinished(SyncPushRun.Outcome.STALLED)
        val beforeProgress = rig.requests.size

        advanceTimeBy(3 * SyncPushScheduler.PARK_POLL_MS)
        assertEquals(beforeProgress, rig.requests.size, "nothing landed: still parked, nothing queued")

        rig.contentProgress += 1                                       // a file lands on a client's call
        advanceTimeBy(SyncPushScheduler.PARK_POLL_MS + 1)
        assertEquals(beforeProgress + 1, rig.requests.size, "the waiting backlog gets its job within one poll")
        assertEquals(SyncPushScheduler.WORK_NAME, rig.requests.last().uniqueName)
    }

    @Test
    fun aDelayedRetryNeverHoldsTheNameAFreshBacklogIsEnqueuedUnder() {
        // ⚠⚠ Measured: under one name, the stall retry's 15-minute wait makes KEEP drop every fresh
        // backlog behind it, and a 100-photo backup's push waits for an unrelated backstop.
        val retry = SyncPushRequest(expedited = false, appendToRunning = false, initialDelayMs = 1)
        val fresh = SyncPushRequest(expedited = true, appendToRunning = false)
        assertEquals(SyncPushScheduler.WORK_NAME, fresh.uniqueName)
        assertEquals(SyncPushScheduler.RETRY_WORK_NAME, retry.uniqueName)
        assertTrue(retry.uniqueName != fresh.uniqueName)
    }

    @Test
    fun contentHeldByWifiOnlyRegistersTheUnmeteredPush() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.contentDeferred.value = true
        runCurrent()
        rig.contentDeferred.value = true // still held: no second registration
        runCurrent()
        assertEquals(1, rig.deferredPushes)
        assertTrue(rig.requests.isEmpty(), "held content must not start a connected push")
    }

    // --- SyncPushRun ---

    @Test
    fun oneRunCoversABurstThatRefillsWithinTheLinger() = runTest {
        // A backup: the backlog drains to zero after each file and the next file's blocks arrive a
        // moment later. One run must drain all of it.
        var backlog = 3
        var drains = 0
        val arrivals = ArrayDeque(listOf(1_000L to 2, 2_500L to 4)) // time → new backlog
        val run = SyncPushRun(
            drain = { drains++; backlog = 0; SyncPushDrain(stalled = false, sent = true) },
            backlog = {
                arrivals.firstOrNull()?.let { (at, n) -> if (currentTime >= at) { backlog += n; arrivals.removeFirst() } }
                backlog
            },
            nowMs = { currentTime },
        )
        val outcome = run.run()
        assertEquals(SyncPushRun.Outcome.IDLE, outcome)
        assertEquals(3, drains, "one drain per arrival, in a single run")
    }

    @Test
    fun aRunOutlastsAClientRequestThatLeavesNoBacklogUntilItEnds() = runTest {
        // ⚠⚠ Measured: a background backup uploads content inside the client's own call, so the push
        // backlog reads 0 while the client is still sending. The run must stay up until it is done.
        var clientBusyUntil = 20_000L
        var backlog = 0
        var drains = 0
        val run = SyncPushRun(
            drain = { drains++; backlog = 0; SyncPushDrain(stalled = false, sent = true) },
            backlog = { if (currentTime >= clientBusyUntil && drains == 0) { backlog = 5 }; backlog },
            nowMs = { currentTime },
            clientWorkInFlight = { currentTime < clientBusyUntil },
        )
        assertEquals(SyncPushRun.Outcome.IDLE, run.run())
        assertEquals(1, drains, "the backlog the client left at the end was drained by this same run")
        assertTrue(currentTime >= clientBusyUntil, "finished at $currentTime, before the client did")
    }

    @Test
    fun aLongClientOpThatLeavesNothingToPushDoesNotHoldTheRun() = runTest {
        // ⚠ A region download or a pairing wait is in flight for minutes and queues nothing. The
        // linger may extend for client work only while the backlog was non-zero recently.
        val run = SyncPushRun(
            drain = { SyncPushDrain(stalled = false, sent = false) },
            backlog = { 0 },
            nowMs = { currentTime },
            clientWorkInFlight = { true },
        )
        assertEquals(SyncPushRun.Outcome.IDLE, run.run())
        assertTrue(
            currentTime <= SyncPushRun.ACTIVITY_WINDOW_MS + SyncPushRun.LINGER_MS + SyncPushRun.POLL_MS,
            "an idle run sat for $currentTime ms",
        )
    }

    @Test
    fun aDrainThatNeitherStallsNorShrinksPausesAndEndsAsAStall() = runTest {
        // ⚠⚠ A content entry failing without flagging the tick (a content-plane 500, "someone already
        // emitting") leaves the backlog where it was. Looping straight back ran a drain and a relay pull
        // back to back for the whole budget, and MORE then chained another run, forever.
        var drains = 0
        // ⚠ Without a pause virtual time never advances, so a spinning run would never reach its budget:
        // cap the drains, so the failure is a red instead of a hung test task.
        val run = SyncPushRun(
            drain = {
                drains++
                check(drains < 1_000) { "spun: $drains drains without a pause" }
                SyncPushDrain(stalled = false, sent = false)
            },
            backlog = { 3 },
            nowMs = { currentTime },
        )
        assertEquals(SyncPushRun.Outcome.STALLED, run.run(), "nothing was ever sent: retry later, not now")
        assertTrue(
            currentTime <= SyncPushRun.NO_PROGRESS_LIMIT_MS + SyncPushRun.NO_PROGRESS_PAUSE_MS,
            "held the network for $currentTime ms without sending anything",
        )
        assertTrue(
            drains <= SyncPushRun.NO_PROGRESS_LIMIT_MS / SyncPushRun.NO_PROGRESS_PAUSE_MS + 1,
            "$drains drains — it spun",
        )
    }

    @Test
    fun aBacklogThatRefillsAsFastAsItDrainsIsNotAStall() = runTest {
        // ⚠⚠ Measured on the emulator: during a 120-photo background backup the backlog never
        // shrinks between two drains, because Drive keeps adding. A stall judged on "the backlog did
        // not shrink" would end a healthy run after three drains and park the rest of the push behind
        // the 15-minute retry. Only the drain's own verdict may call a stall.
        var drains = 0
        val run = SyncPushRun(
            // Every drain sends 40 while 40 arrive: the backlog never shrinks, the relay accepts all.
            drain = { drains++; SyncPushDrain(stalled = false, sent = true) },
            backlog = { if (drains < 10) 40 else 0 },
            nowMs = { currentTime },
        )
        assertEquals(SyncPushRun.Outcome.IDLE, run.run())
        assertEquals(10, drains)
    }

    @Test
    fun aStuckBacklogStallsInsteadOfSpinning() = runTest {
        var drains = 0
        // The relay is down: every drain reports a stall.
        val run = SyncPushRun(drain = { drains++; SyncPushDrain(stalled = true, sent = false) }, backlog = { 7 }, nowMs = { currentTime })
        assertEquals(SyncPushRun.Outcome.STALLED, run.run())
        assertEquals(SyncPushRun.MAX_STALLS, drains)
    }

    @Test
    fun aRunStopsAtItsTimeBudgetWithWorkLeft() = runTest {
        var backlog = 1_000_000
        val run = SyncPushRun(
            drain = { kotlinx.coroutines.delay(60_000); backlog -= 1; SyncPushDrain(stalled = false, sent = true) },
            backlog = { backlog },
            nowMs = { currentTime },
        )
        assertEquals(SyncPushRun.Outcome.MORE, run.run())
        assertTrue(currentTime <= SyncPushRun.MAX_RUN_MS + 60_000, "ended at $currentTime ms")
        advanceUntilIdle()
    }

    /**
     * A file that changes under every attempt re-sends its chunks each time and never completes.
     * Counting the chunks as progress would chain MORE runs for ever; a run that finished nothing
     * in its whole budget is a stall.
     */
    @Test
    fun aRunWhoseChunksNeverFinishAFile_endsAsAStallNotMore() = runTest {
        val run = SyncPushRun(
            drain = { kotlinx.coroutines.delay(60_000); SyncPushDrain(stalled = false, sent = true, completed = false) },
            backlog = { 1 },
            nowMs = { currentTime },
        )
        assertEquals(SyncPushRun.Outcome.STALLED, run.run())
        advanceUntilIdle()
    }
}

/**
 * `control-background-work-off-fgs.md` §5.2: a registered bulk task counts towards Android's
 * foreground service between its calls only once a constituent call arrived marked user-started.
 */
class TaskRegistryUserInitiatedTest {
    @Test
    fun onlyAMarkedRunningTaskCounts() = runTest {
        val registry = TaskRegistry(nowMs = { 0L })
        registry.register("task-folder", "drive/folder-upload")
        registry.register("task-import", "fit/health-import")
        assertEquals(2, registry.activeCount.value)
        assertEquals(0, registry.userInitiatedActiveCount.value, "registration alone says nothing")

        registry.markUserInitiated("task-folder")
        registry.markUserInitiated("per-call-minted-id") // not a registered task: ignored
        assertEquals(1, registry.userInitiatedActiveCount.value)

        registry.transition("task-folder", org.tenshu.sync.BackgroundTask.States.DONE)
        assertEquals(0, registry.userInitiatedActiveCount.value, "a finished task holds nothing")
    }
}
