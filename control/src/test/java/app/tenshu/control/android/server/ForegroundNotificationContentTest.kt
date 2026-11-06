package app.tenshu.control.android.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the foreground-service notification says (`drive-upload-progress.md` P5). Android-free, so
 * the choice between the two messages is testable on the JVM — the `Service` that renders it is not.
 */
class ForegroundNotificationContentTest {

    @Test
    fun anUploadPassOutranksTheTaskCount() {
        // "2 background tasks" is true and useless; "Uploading 3 of 12" is something the user can
        // connect to what they just did.
        assertEquals(
            ForegroundNotificationContent.Uploading(current = 3, total = 12),
            foregroundNotificationContent(taskCount = 2, passTotal = 12, passCompleted = 2),
        )
    }

    @Test
    fun noPassFallsBackToTheLivenessCount() {
        // passTotal is cleared at every tick boundary, so "no pass" is the steady state and the
        // notification must go back to what kept the service promoted in the first place.
        assertEquals(
            ForegroundNotificationContent.Tasks(count = 2),
            foregroundNotificationContent(taskCount = 2, passTotal = 0, passCompleted = 0),
        )
    }

    @Test
    fun theCurrentFileIsOneBased() {
        // "Uploading 1 of 3" while the first file is in flight; "0 of 3" would read as though
        // nothing had started.
        assertEquals(
            ForegroundNotificationContent.Uploading(current = 1, total = 3),
            foregroundNotificationContent(taskCount = 1, passTotal = 3, passCompleted = 0),
        )
    }

    @Test
    fun anIngestRunStandsTheUploadMessageDown() {
        // §14.1: during a Synced-Folders backup the drain FEEDS the queue these figures measure, so
        // passTotal is a snapshot that grows between passes ("20 of 400" → "20 of 500") — while
        // Drive posts its own notification with the drain's honest total. Two notifications, one
        // operation, different numbers; the one that knows less stands down.
        assertEquals(
            ForegroundNotificationContent.Tasks(count = 2),
            foregroundNotificationContent(
                taskCount = 2,
                passTotal = 400,
                passCompleted = 20,
                ingestRunning = true,
            ),
        )
    }

    @Test
    fun anEndedIngestRunHandsTheUploadMessageBack() {
        // The stand-down lasts exactly as long as the run: once the drain reports anything but
        // RUNNING, the queue has one consumer again and the pass figures are trustworthy.
        assertEquals(
            ForegroundNotificationContent.Uploading(current = 21, total = 400),
            foregroundNotificationContent(
                taskCount = 2,
                passTotal = 400,
                passCompleted = 20,
                ingestRunning = false,
            ),
        )
    }

    @Test
    fun theFinalCompletionDoesNotOverrunTheTotal() {
        // The drain's last callback reports completed == total; without the cap that renders
        // "4 of 3".
        assertEquals(
            ForegroundNotificationContent.Uploading(current = 3, total = 3),
            foregroundNotificationContent(taskCount = 1, passTotal = 3, passCompleted = 3),
        )
    }

    @Test
    fun anIndexPassOutranksTheTaskCount() {
        // §34 — a pass the user started from Settings is the reason the phone is warm; "2 background
        // tasks" is the message that made them go looking for a fault.
        assertEquals(
            ForegroundNotificationContent.Indexing(done = 300, total = 9000),
            foregroundNotificationContent(
                taskCount = 2,
                passTotal = 0,
                passCompleted = 0,
                indexing = 300 to 9000,
            ),
        )
    }

    @Test
    fun anUploadOutranksAnIndexPass() {
        // Both are running — the upload is the thing the user is waiting on, so it keeps the line.
        assertEquals(
            ForegroundNotificationContent.Uploading(current = 3, total = 12),
            foregroundNotificationContent(
                taskCount = 2,
                passTotal = 12,
                passCompleted = 2,
                indexing = 300 to 9000,
            ),
        )
    }

    @Test
    fun anIndexPassOverAnEmptyLibraryIsNotAMessage() {
        // ⚠⚠ A pass with nothing to do reports 0 of 0 — it is running, so the holder is not null, but
        // "Indexing 0 of 0" is a notification about nothing. Fall through to the task count.
        assertEquals(
            ForegroundNotificationContent.Tasks(count = 2),
            foregroundNotificationContent(
                taskCount = 2,
                passTotal = 0,
                passCompleted = 0,
                indexing = 0 to 0,
            ),
        )
    }

    @Test
    fun theIndexedCountDoesNotOverrunTheTotal() {
        // The pass's last tick reports done == total; a later one must not render "9001 of 9000".
        assertEquals(
            ForegroundNotificationContent.Indexing(done = 9000, total = 9000),
            foregroundNotificationContent(
                taskCount = 1,
                passTotal = 0,
                passCompleted = 0,
                indexing = 9001 to 9000,
            ),
        )
    }

    @Test
    fun anIndexPassStandsDownWithTheUploadWhileAnIngestRunsIfTheresNothingToIndex() {
        // ⚠ The ingest stand-down is about the UPLOAD figures only (§14.1). It must not promote the
        // index message over them — with no pass running the answer is still the task count.
        assertEquals(
            ForegroundNotificationContent.Tasks(count = 2),
            foregroundNotificationContent(
                taskCount = 2,
                passTotal = 400,
                passCompleted = 20,
                ingestRunning = true,
            ),
        )
    }

    @Test
    fun anIndexPassTakesTheLineWhileAnIngestHasStoodTheUploadDown() {
        // ⚠⚠ The stand-down hands the line back, and indexing is the next-best true thing to say —
        // the app's own drain notification is already telling the upload story.
        assertEquals(
            ForegroundNotificationContent.Indexing(done = 300, total = 9000),
            foregroundNotificationContent(
                taskCount = 2,
                passTotal = 400,
                passCompleted = 20,
                ingestRunning = true,
                indexing = 300 to 9000,
            ),
        )
    }
}
