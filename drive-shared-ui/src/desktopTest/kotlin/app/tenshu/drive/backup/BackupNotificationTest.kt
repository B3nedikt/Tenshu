package app.tenshu.drive.backup

import app.tenshu.ingest.backup.BackupFailureKind
import app.tenshu.ingest.backup.BackupPauseReason
import app.tenshu.ingest.backup.BackupState
import app.tenshu.ingest.backup.backupNotificationContent
import app.tenshu.liveness.LivenessPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Android-free copy + phase mapping of the auto-backup notification (`drive-synced-folders.md` §5 step 6). */
class BackupNotificationTest {

    /** The platform catalogue, as the Android service supplies it from `R.string`. */
    private fun notification(state: BackupState) =
        backupNotificationContent(state) { kind -> "catalogue:$kind" }

    @Test
    fun backingUp_withTotal_isOneBasedInFlight() {
        val content = notification(BackupState.BackingUp(completed = 2, total = 5, currentFileName = "IMG_9.jpg"))
        assertEquals("Backing up 3 of 5", content?.title)
        assertEquals("IMG_9.jpg", content?.text)
        assertEquals(LivenessPhase.IN_FLIGHT, content?.phase)
    }

    @Test
    fun justFinished_singularPluralAndFailed() {
        assertEquals("Backed up 1 file", notification(BackupState.JustFinished(1, 0))?.title)
        assertEquals("Backed up 4 files", notification(BackupState.JustFinished(4, 0))?.title)
        assertEquals(
            "Backed up 4 files · 2 failed",
            notification(BackupState.JustFinished(4, 2))?.title,
        )
        assertEquals(
            LivenessPhase.TERMINAL,
            notification(BackupState.JustFinished(1, 0))?.phase,
        )
    }

    /** The title counts the failures; the body carries the *reason*. */
    @Test
    fun justFinished_withFailures_putsTheReasonInTheBody() {
        val content = notification(
            BackupState.JustFinished(4, 2, failureReason = "no operation for wire tag 84cf"),
        )
        assertEquals("Backed up 4 files · 2 failed", content?.title)
        assertEquals("no operation for wire tag 84cf", content?.text)
    }

    /** ⚠ A clean run must not grow a body line just because the field exists. */
    @Test
    fun justFinished_withoutFailures_hasNoBody() {
        assertEquals("", notification(BackupState.JustFinished(4, 0))?.text)
    }

    /** A file skipped because the realm already holds it is neither uploaded nor failed — but it is
     *  not nothing, and a summary that drops it reads as "backup did nothing". */
    @Test
    fun justFinished_countsWhatTheRealmAlreadyHeld() {
        val allHeld = notification(BackupState.JustFinished(0, 0, alreadyInRealm = 3))
        assertEquals("Nothing new to back up", allHeld?.title)
        assertEquals("3 already in your Drive", allHeld?.text)
        val some = notification(BackupState.JustFinished(2, 0, alreadyInRealm = 1))
        assertEquals("Backed up 2 files", some?.title)
        assertEquals("1 already in your Drive", some?.text)
    }

    @Test
    fun idlePausedUpToDate_showNothing() {
        assertNull(notification(BackupState.Disabled))
        assertNull(notification(BackupState.UpToDate))
        assertNull(notification(BackupState.Paused(BackupPauseReason.METERED_NETWORK)))
    }

    /**
     * ⚠⚠ A cause the engine knows is the platform's catalogue copy, never an English sentence from
     * the engine: the realm read failing reaches the body through the typed kind.
     */
    @Test
    fun justFinished_withATypedCause_putsTheCataloguesCopyInTheBody() {
        val content = notification(
            BackupState.JustFinished(0, 3, failureKind = BackupFailureKind.COULD_NOT_CHECK_REALM),
        )
        assertEquals("catalogue:COULD_NOT_CHECK_REALM", content?.text)
    }
}
