package app.tenshu.drive.ui

import app.tenshu.drive.data.FolderUploadSummary
import app.tenshu.drive.data.UploadProgress
import app.tenshu.drive.ui.common.addfileactions.FolderUploadOutcome
import app.tenshu.drive.ui.common.addfileactions.FolderUploadState
import app.tenshu.drive.ui.common.folderupload.FolderUploadNotificationPhase
import app.tenshu.drive.ui.common.folderupload.folderUploadNotificationContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure copy + phase mapping for the Drive-side folder-upload notification
 * (`docs/proposals/drive-folder-upload.md` §5d). This is the verifiable core
 * of the §5d feature; the Android Service / manifest / DI is
 * correctness-by-construction mirroring `:control`'s
 * `ProxyService`/`ForegroundTaskLiveness` 1:1 (no Android SDK in this env).
 */
class FolderUploadNotificationTest {

    @Test
    fun nullState_producesNoNotification() {
        assertNull(folderUploadNotificationContent(null))
    }

    @Test
    fun inFlight_totalKnown_titleIsOneBasedNofM_textIsLeafName() {
        val content = folderUploadNotificationContent(
            FolderUploadState(UploadProgress("photo.jpg", completed = 2, total = 10)),
        )!!
        // completed + 1 ⇒ 1-based "current" file index.
        assertEquals("Uploading file 3 of 10", content.title)
        assertEquals("photo.jpg", content.text)
        assertEquals(FolderUploadNotificationPhase.IN_FLIGHT, content.phase)
    }

    @Test
    fun inFlight_nestedName_textIsLeafOnly() {
        val content = folderUploadNotificationContent(
            FolderUploadState(UploadProgress("trip/2024/beach.jpg", 0, 4)),
        )!!
        assertEquals("Uploading file 1 of 4", content.title)
        assertEquals("beach.jpg", content.text)
    }

    @Test
    fun inFlight_totalNotYetKnown_titleIsUploadingFiles() {
        val content = folderUploadNotificationContent(
            FolderUploadState(UploadProgress("", completed = 0, total = 0)),
        )!!
        assertEquals("Uploading files…", content.title)
        assertEquals("", content.text)
        assertEquals(FolderUploadNotificationPhase.IN_FLIGHT, content.phase)
    }

    @Test
    fun completed_noFailures_titleIsUploadedNFiles() {
        val content = folderUploadNotificationContent(
            FolderUploadState(
                progress = UploadProgress("x", 42, 42),
                outcome = FolderUploadOutcome.Completed(FolderUploadSummary(42, 42, 0, 0)),
            ),
        )!!
        assertEquals("Uploaded 42 files", content.title)
        assertEquals("", content.text)
        assertEquals(FolderUploadNotificationPhase.TERMINAL, content.phase)
    }

    @Test
    fun completed_withFailures_appendsFailedCount() {
        val content = folderUploadNotificationContent(
            FolderUploadState(
                progress = UploadProgress("x", 40, 42),
                outcome = FolderUploadOutcome.Completed(FolderUploadSummary(42, 40, 0, 2)),
            ),
        )!!
        assertEquals("Uploaded 40 files · 2 failed", content.title)
        assertEquals(FolderUploadNotificationPhase.TERMINAL, content.phase)
    }

    @Test
    fun cancelled_titleIsPartialProgress() {
        val content = folderUploadNotificationContent(
            FolderUploadState(
                progress = UploadProgress("big.bin", 7, 20),
                outcome = FolderUploadOutcome.Cancelled(UploadProgress("big.bin", 7, 20)),
            ),
        )!!
        assertEquals("Upload cancelled — 7 of 20 uploaded", content.title)
        assertEquals(FolderUploadNotificationPhase.TERMINAL, content.phase)
    }

    @Test
    fun failed_titleIsPartialProgressWithDerivedFailedCount() {
        val content = folderUploadNotificationContent(
            FolderUploadState(
                progress = UploadProgress("doc.pdf", 5, 12),
                outcome = FolderUploadOutcome.Failed,
            ),
        )!!
        // Failed carries no summary; failed = total - completed (clamped ≥0).
        assertEquals("Upload finished — 5 of 12 uploaded, 7 failed", content.title)
        assertEquals(FolderUploadNotificationPhase.TERMINAL, content.phase)
    }

    @Test
    fun failed_duringEnumeration_zeroFailed() {
        val content = folderUploadNotificationContent(
            FolderUploadState(
                progress = UploadProgress("", 0, 0),
                outcome = FolderUploadOutcome.Failed,
            ),
        )!!
        assertEquals("Upload finished — 0 of 0 uploaded, 0 failed", content.title)
    }
}
