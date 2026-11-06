package org.tenshu.inference.proxy.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The order of starts and cancels in the model download's background session
 * (`ios-on-device-inference.md` §14.11). `ModelDownload.swift` does what this class answers and
 * nothing else, so these cases are that file's logic, run without a `URLSession`. ⚠ What they
 * cannot show is that the Swift calls it at the right moments.
 */
class ModelTransferLedgerTest {

    private val ref = "siglip.text.0123456789abcdef"

    private fun live(vararg taskIds: Long): List<ModelSessionTransfer> = taskIds.map { ModelSessionTransfer(it, ref) }

    @Test
    fun aStartACancelOvertookIsDroppedWhenTheSessionAnswers() {
        val ledger = ModelTransferLedger()
        // Kotlin starts; the session has not answered yet; the user cancels.
        val start: Long = ledger.startAsked(ref)
        val cancel = ledger.cancelAsked(ref)
        assertTrue(cancel.taskIds.isEmpty(), "there is no task yet to cancel")

        // ⚠⚠ The late answer must not make the task: a cancelled model would download.
        assertFalse(ledger.admit(ref, start, live()))
        assertTrue(ledger.cancelFromSession(ref, cancel.asked, live()).isEmpty())
    }

    @Test
    fun aCancelAskedForWhileTheTaskWasBeingMadeLeavesItNeverRun() {
        val ledger = ModelTransferLedger()
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live()))
        // Between the admission and the task existing.
        val cancel = ledger.cancelAsked(ref)
        assertTrue(cancel.taskIds.isEmpty())

        assertFalse(ledger.bind(ref, start, taskId = 1), "the task was resumed after its cancel")
        // And the ref is free for whoever asks next.
        assertTrue(ledger.admit(ref, ledger.startAsked(ref), live()))
    }

    @Test
    fun aDownloadAskedForAgainAfterACancelGetsATaskOfItsOwnAndTheCancelDoesNotReachIt() {
        val ledger = ModelTransferLedger()
        val first: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, first, live()))
        assertTrue(ledger.bind(ref, first, taskId = 1))

        // Cancelled, and asked for again at once (with the other cellular choice, say), before
        // the session has answered the cancel and while task 1 is still listed as running.
        val cancel = ledger.cancelAsked(ref)
        assertEquals(listOf(1L), cancel.taskIds)
        val second: Long = ledger.startAsked(ref)

        // ⚠⚠ Not suppressed by the task that was cancelled: it would inherit that task's request.
        assertTrue(ledger.admit(ref, second, live(1)))
        assertTrue(ledger.bind(ref, second, taskId = 2))
        // ⚠⚠ And the cancel's late answer stops the old task only.
        assertEquals(listOf(1L), ledger.cancelFromSession(ref, cancel.asked, live(1, 2)))
    }

    @Test
    fun onlyTheTaskThatHoldsARefNowSpeaksForIt() {
        val ledger = ModelTransferLedger()
        val first: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, first, live()))
        assertTrue(ledger.bind(ref, first, taskId = 1))
        assertTrue(ledger.speaksFor(1, ref))

        // Cancelled and asked for again at once, with the same choice: the same ref, a new task.
        ledger.cancelAsked(ref)
        // ⚠ From the moment the cancel is asked for, before any new task exists.
        assertFalse(ledger.speaksFor(1, ref))
        val second: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, second, live(1)))
        assertTrue(ledger.bind(ref, second, taskId = 2))

        // ⚠⚠ The old task's failure, queued before its cancel reached it, arrives now. It is not
        // the new download's failure; nor is a file it delivers the new download's file.
        assertFalse(ledger.speaksFor(1, ref))
        assertTrue(ledger.speaksFor(2, ref))
        // And once the old task has ended, nothing about the new one has changed.
        ledger.ended(1)
        assertTrue(ledger.speaksFor(2, ref))
        assertFalse(ledger.speaksFor(2, "another.ref"))
    }

    @Test
    fun aTaskCancelledWhileItWasBeingMadeNeverSpeaks() {
        val ledger = ModelTransferLedger()
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live()))
        ledger.cancelAsked(ref)
        assertFalse(ledger.bind(ref, start, taskId = 1))

        assertFalse(ledger.speaksFor(1, ref))
    }

    @Test
    fun aTaskOfAnEarlierProcessSpeaksForItsRefUntilACancelIsAskedFor() {
        val ledger = ModelTransferLedger()
        // Task 7 was started by a process that is gone: what it delivers is this journal's.
        assertTrue(ledger.speaksFor(7, ref))

        ledger.cancelAsked(ref)
        assertFalse(ledger.speaksFor(7, ref))
        // And not once a task of this process has taken the ref over either.
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live(7)))
        assertTrue(ledger.bind(ref, start, taskId = 8))
        assertFalse(ledger.speaksFor(7, ref))
        assertTrue(ledger.speaksFor(8, ref))
    }

    @Test
    fun progressIsThrottledPerTaskSoARetryIsHeardFromItsFirstMebibyte() {
        val ledger = ModelTransferLedger()
        val step = 1L shl 20
        val first: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, first, live()))
        assertTrue(ledger.bind(ref, first, taskId = 1))
        // Far into its download: 500 MiB reported.
        assertTrue(ledger.reportsProgress(1, ref, 500 * step, step))
        assertFalse(ledger.reportsProgress(1, ref, 500 * step + step - 1, step), "inside the step")
        assertTrue(ledger.reportsProgress(1, ref, 501 * step, step))

        // Cancelled, and asked for again with the same choice: the same ref, a new task.
        ledger.cancelAsked(ref)
        val second: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, second, live(1)))
        assertTrue(ledger.bind(ref, second, taskId = 2))

        // ⚠⚠ Heard from its first step, not from the 501st: its bar does not sit at zero until
        // it has passed the point the cancelled task had reached.
        assertTrue(ledger.reportsProgress(2, ref, step, step))
        assertFalse(ledger.reportsProgress(2, ref, step + 1, step))
        assertTrue(ledger.reportsProgress(2, ref, 2 * step, step))
        // And the cancelled task's progress, still arriving, is nobody's.
        assertFalse(ledger.reportsProgress(1, ref, 600 * step, step))
    }

    // ─── A report is checked now and acted on later.

    @Test
    fun aReportedTaskKeepsItsRefUntilItsReportIsSettled() {
        val ledger = ModelTransferLedger()
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live()))
        assertTrue(ledger.bind(ref, start, taskId = 1))

        // The session completes task 1 with a failure: it is reported, and Kotlin queues it.
        val report: ModelTransferReport = assertNotNull(ledger.completed(1, ref, cancelled = false))
        assertTrue(report.stands())

        // ⚠⚠ The session no longer lists the task. Kotlin, asking what is live from a step that
        // holds its lock, must still be told the ref is held, and must not be able to start it.
        assertEquals(listOf(ref), ledger.liveRefs(live()))
        assertFalse(ledger.admit(ref, ledger.startAsked(ref), live()))

        // Kotlin has acted on the report: the ref is free, and what follows is a new download.
        report.settled()
        assertEquals(emptyList(), ledger.liveRefs(live()))
        assertTrue(ledger.admit(ref, ledger.startAsked(ref), live()))
    }

    // ─── One authority for who holds a ref.

    @Test
    fun aStartAskedForBeforeATransferOfItsRefWasActedOnIsDropped() {
        val ledger = ModelTransferLedger()
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live()))
        assertTrue(ledger.bind(ref, start, taskId = 1))

        // A step asks for the ref again. (It was told the ref was free: by an answer taken
        // before the task existed, or by a process that had not seen the task yet.)
        val stale: Long = ledger.startAsked(ref)
        // Meanwhile task 1 delivers, is reported, and Kotlin stages its file and settles.
        assertNotNull(ledger.completed(1, ref, cancelled = false)).settled()

        // ⚠⚠ Only now does the session answer the stale start. Nobody holds the ref any more,
        // and no cancel was asked for; the start is refused all the same, or half a gigabyte
        // that is already staged is fetched a second time.
        assertFalse(ledger.admit(ref, stale, live()), "a start decided before the transfer was acted on was admitted")
        // ⚠ A start asked for AFTER it is a new decision, and is admitted.
        assertTrue(ledger.admit(ref, ledger.startAsked(ref), live()))
    }

    @Test
    fun aTransferTheSystemEndedWithoutAReportDropsNoStart() {
        val ledger = ModelTransferLedger()
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live()))
        assertTrue(ledger.bind(ref, start, taskId = 1))
        val replacement: Long = ledger.startAsked(ref)
        // The system cancels task 1 (the user force-quit): nothing is reported, nothing was acted on.
        assertNull(ledger.completed(1, ref, cancelled = true))
        // ⚠ The replacement is what restarts it: the rule above is for a transfer Kotlin acted on.
        assertTrue(ledger.admit(ref, replacement, live()))
    }

    @Test
    fun whatIsLiveIsWhoHoldsARefAndNotWhatTheSessionLists() {
        val ledger = ModelTransferLedger()
        // A task of an earlier process, which the session lists.
        assertEquals(listOf(ref), ledger.liveRefs(live(7)))

        // ⚠⚠ Its cancel is asked for and has not reached the task: the session still lists it.
        // Told the ref is live, the download asked for after the cancel would start nothing,
        // and then the task goes.
        val cancel = ledger.cancelAsked(ref)
        assertEquals(emptyList(), ledger.liveRefs(live(7)), "a task whose cancel is on its way was listed as live")
        assertEquals(listOf(7L), ledger.cancelFromSession(ref, cancel.asked, live(7)))

        // ⚠⚠ And the other way: a task that has ended in the session and has not been called
        // back yet is not listed, and holds its ref all the same.
        val other = "siglip.text.fedcba9876543210.wifi"
        val start: Long = ledger.startAsked(other)
        assertTrue(ledger.admit(other, start, live(7)))
        assertTrue(ledger.bind(other, start, taskId = 8))
        assertEquals(listOf(other), ledger.liveRefs(live()), "a transfer that ended and has not reported was not listed")
    }

    @Test
    fun aReportThatWaitedBehindACancelOfItsRefNoLongerStands() {
        val ledger = ModelTransferLedger()
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live()))
        assertTrue(ledger.bind(ref, start, taskId = 1))
        val report: ModelTransferReport = assertNotNull(ledger.completed(1, ref, cancelled = false))
        assertTrue(report.stands())

        // Queued ahead of it in Kotlin: a cancel of the download, and the same ref asked for again.
        ledger.cancelAsked(ref)
        val again: Long = ledger.startAsked(ref)

        // ⚠⚠ The report is about a transfer nobody wants any more, whatever it says.
        assertFalse(report.stands(), "a report whose ref was cancelled since still stood")
        report.settled()
        // ⚠ And settling it marks nothing: the start asked for after the cancel, which the
        // session answers only now, is the new download's and is admitted.
        assertTrue(ledger.admit(ref, again, live()), "the new download's start was dropped for the old one's report")
        assertFalse(report.stands())
    }

    @Test
    fun aCompletionThatIsNobodysIsNotReportedAndHoldsNothing() {
        val ledger = ModelTransferLedger()
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live()))
        assertTrue(ledger.bind(ref, start, taskId = 1))

        // A cancel: its completion reports nothing, and the ref is free at once.
        assertNull(ledger.completed(1, ref, cancelled = true))
        assertEquals(emptyList(), ledger.liveRefs(live()))
        val again: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, again, live()))
        assertTrue(ledger.bind(ref, again, taskId = 2))

        // A task that never held the ref (an earlier process's, seen beside the holder).
        assertNull(ledger.completed(9, ref, cancelled = false))
        assertEquals(listOf(ref), ledger.liveRefs(live(2)))
        assertTrue(ledger.speaksFor(2, ref))
    }

    @Test
    fun aCancelAskedForWhileAReportWaitsEndsTheHoldAllTheSame() {
        val ledger = ModelTransferLedger()
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live()))
        assertTrue(ledger.bind(ref, start, taskId = 1))
        val report: ModelTransferReport = assertNotNull(ledger.completed(1, ref, cancelled = false))

        // The user cancels and asks again before Kotlin has got to the queued report.
        ledger.cancelAsked(ref)
        assertEquals(emptyList(), ledger.liveRefs(live()))
        val again: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, again, live()))
        assertTrue(ledger.bind(ref, again, taskId = 2))
        // And the late settlement of task 1 takes nothing from task 2.
        report.settled()
        assertTrue(ledger.speaksFor(2, ref))
        assertFalse(ledger.admit(ref, ledger.startAsked(ref), live(2)))
    }

    // ─── A relaunch: tasks of the process that is gone.

    @Test
    fun anEarlierTaskThatFailsBeforeTheSessionIsAskedSpeaksAndThenFreesItsRef() {
        val ledger = ModelTransferLedger()
        // Its failure is the first this process hears of task 7.
        assertTrue(ledger.speaksFor(7, ref))
        ledger.ended(7)
        // Kotlin then finds the artifact missing and may start it again.
        assertTrue(ledger.admit(ref, ledger.startAsked(ref), live()))
    }

    @Test
    fun anEarlierTaskThatEndedBeforeTheSessionWasAskedDoesNotSpeakOverItsReplacement() {
        val ledger = ModelTransferLedger()
        // Task 7 has ended in the session, its report still queued. The session lists nothing,
        // so the launch's resume starts the artifact again.
        ledger.observe(live())
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live()))
        assertTrue(ledger.bind(ref, start, taskId = 8))

        // ⚠⚠ Now 7's failure arrives. Reported, Kotlin would remove the replacement's record
        // and cancel task 8. And its file, had it delivered one, is not task 8's file.
        assertFalse(ledger.speaksFor(7, ref))
        assertTrue(ledger.speaksFor(8, ref))
        // Nor once it has been heard and forgotten.
        ledger.ended(7)
        assertTrue(ledger.speaksFor(8, ref))
    }

    @Test
    fun anEarlierTaskTheSessionListedSpeaksUntilItEndsAndKeepsItsRefTillThen() {
        val ledger = ModelTransferLedger()
        ledger.observe(live(7))
        // Held: nothing is started beside it.
        assertFalse(ledger.admit(ref, ledger.startAsked(ref), live(7)))
        // It fails after the session was asked: that is this download's failure.
        assertTrue(ledger.speaksFor(7, ref))
        ledger.ended(7)
        assertTrue(ledger.admit(ref, ledger.startAsked(ref), live()))
    }

    @Test
    fun ofTwoEarlierTasksForOneRefOnlyTheFirstSeenSpeaks() {
        val ledger = ModelTransferLedger()
        ledger.observe(live(7, 9))

        // ⚠ Decided when the session listed them, not by which of the two reports first.
        assertFalse(ledger.speaksFor(9, ref))
        assertTrue(ledger.speaksFor(7, ref))
        // ⚠ And the second does not inherit the ref when the first ends: what it delivers
        // or fails is nobody's, and a cancel reaches both.
        ledger.ended(7)
        assertFalse(ledger.speaksFor(9, ref))
        val cancel = ledger.cancelAsked(ref)
        assertEquals(listOf(9L), ledger.cancelFromSession(ref, cancel.asked, live(9)))
    }

    @Test
    fun aTaskFirstSeenAfterACancelHoldsNothing() {
        val ledger = ModelTransferLedger()
        val cancel = ledger.cancelAsked(ref)
        // Task 7 turns up only now: it is what the cancel was for.
        assertFalse(ledger.speaksFor(7, ref))
        assertTrue(ledger.admit(ref, ledger.startAsked(ref), live(7)))
        assertEquals(listOf(7L), ledger.cancelFromSession(ref, cancel.asked, live(7)))
    }

    @Test
    fun aRefThatIsHeldIsNotStartedASecondTimeWhoeverAsks() {
        val ledger = ModelTransferLedger()
        // Two runtimes of one process each ask, and each got an empty answer from the session:
        // the second answer was taken before the first one's task existed.
        val one: Long = ledger.startAsked(ref)
        val two: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, one, live()))
        assertFalse(ledger.admit(ref, two, live()))
        assertTrue(ledger.bind(ref, one, taskId = 1))
        assertFalse(ledger.admit(ref, ledger.startAsked(ref), live(1)))

        // Until its task has ended.
        ledger.ended(1)
        assertTrue(ledger.admit(ref, ledger.startAsked(ref), live()))
    }

    @Test
    fun aTaskOfAnEarlierProcessHoldsItsRefUntilACancelIsAskedFor() {
        val ledger = ModelTransferLedger()
        // Task 7 was started by a process that is gone; only the session knows it.
        assertFalse(ledger.admit(ref, ledger.startAsked(ref), live(7)))

        val cancel = ledger.cancelAsked(ref)
        assertTrue(cancel.taskIds.isEmpty(), "it is not this process's to cancel at once")
        assertEquals(listOf(7L), ledger.cancelFromSession(ref, cancel.asked, live(7)))
        // Asked for again while 7 is still listed: it no longer holds the ref.
        assertTrue(ledger.admit(ref, ledger.startAsked(ref), live(7)))
    }

    @Test
    fun anotherRefIsNobodysBusiness() {
        val ledger = ModelTransferLedger()
        val other = "siglip.vision.fedcba9876543210"
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live()))
        assertTrue(ledger.bind(ref, start, taskId = 1))

        val cancel = ledger.cancelAsked(other)
        assertTrue(cancel.taskIds.isEmpty())
        assertTrue(ledger.cancelFromSession(other, cancel.asked, live(1)).isEmpty())
        assertTrue(ledger.admit(other, ledger.startAsked(other), live(1)))
    }

    // ─── A build that offers no model, met by what an enabled build left.

    @Test
    fun aBuildThatOffersNoModelCancelsEveryTaskAndLetsNoneSpeak() {
        val ledger = ModelTransferLedger(offered = false)
        // Two tasks an enabled build started are still in the session.
        assertEquals(listOf(7L, 8L), ledger.notOffered(live(7, 8)), "a task was left running for a build that installs nothing")

        // ⚠⚠ And nothing of theirs is taken: no file, no progress, no report, no hold.
        assertFalse(ledger.speaksFor(7, ref))
        assertFalse(ledger.reportsProgress(7, ref, written = 5L shl 20, step = 1L shl 20))
        assertNull(ledger.completed(7, ref, cancelled = false))
        assertEquals(emptyList(), ledger.liveRefs(live(8)))
        assertFalse(ledger.admit(ref, ledger.startAsked(ref), live()))
    }

    @Test
    fun aBuildThatOffersTheModelCancelsNothingOnThatAccount() {
        val ledger = ModelTransferLedger()
        assertEquals(emptyList(), ledger.notOffered(live(7, 8)))
        assertTrue(ledger.speaksFor(7, ref))
    }

    // ─── A delivery is two questions around the move.

    @Test
    fun aDeliveryIsAskedForBeforeTheMoveAndAgainAfterIt() {
        val ledger = ModelTransferLedger()
        val start: Long = ledger.startAsked(ref)
        assertTrue(ledger.admit(ref, start, live()))
        assertTrue(ledger.bind(ref, start, taskId = 1))

        // Nobody interrupts: let through, and kept.
        assertTrue(ledger.beginDelivery(1, ref))
        assertTrue(ledger.endDelivery(1, ref))

        // A cancel lands during the move: let through, and NOT kept.
        assertTrue(ledger.beginDelivery(1, ref))
        ledger.cancelAsked(ref)
        assertFalse(ledger.endDelivery(1, ref), "a file moved into place after its download was cancelled was kept")

        // A cancel landed before: not let through at all.
        assertFalse(ledger.beginDelivery(1, ref))
    }
}
