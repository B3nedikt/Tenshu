package app.tenshu.drive.ui.common.folderupload

import app.tenshu.drive.data.UploadProgress
import app.tenshu.drive.ui.common.addfileactions.FolderUploadOutcome
import app.tenshu.drive.ui.common.addfileactions.FolderUploadState
import app.tenshu.liveness.LivenessNotificationContent
import app.tenshu.liveness.LivenessNotificationHandle
import app.tenshu.liveness.LivenessPhase

/**
 * Pure copy + phase mapping for the Drive-side folder-upload notification
 * (`docs/proposals/drive-folder-upload.md` §5d).
 *
 * **Why this is Android-free and lives here.** §5d's notification *is* the
 * defect-3 liveness anchor: a Drive foreground service for a backgrounded
 * in-flight folder upload keeps the Drive-resident `FolderUploadController`
 * loop process alive (see `core-background-processing.md` §"Phase L2-LIVE →
 * Known gap"). Mirroring the Control-side pattern
 * (`ForegroundTaskLiveness` + `TaskLivenessHandle`), the *edge logic* and the
 * *copy* are kept free of Android imports so they are unit-testable on the
 * JVM/desktop; the Android effect (a foreground service + notification) lives
 * behind [FolderUploadNotificationHandle], implemented in `:drive`.
 *
 * The notification is a thin, lossless projection of the existing
 * `FolderUploadController.state` — no new data plumbing, no proxy
 * involvement; everything is Drive-owned, Drive-process, Drive-state
 * (the §5d isolation invariant).
 */

/**
 * The folder-upload notification surface is now the generic
 * [app.tenshu.drive.ui.common.liveness] one (§9 — generalised so Synced-Folders
 * auto-backup reuses the same liveness anchor). These aliases keep the
 * folder-upload call sites (and the Android `FolderUploadForegroundService`)
 * source-compatible.
 */
typealias FolderUploadNotificationPhase = LivenessPhase
typealias FolderUploadNotificationContent = LivenessNotificationContent
typealias FolderUploadNotificationHandle = LivenessNotificationHandle

/**
 * Map one observed [FolderUploadController.state] value to notification
 * content, or `null` when nothing should be shown (no upload / dismissed).
 *
 * Copy is the §5d spec verbatim:
 * - in-flight, total known: title `Uploading file {completed + 1} of {total}`
 *   (1-based current), text the current file's leaf name.
 * - in-flight, total not yet known (enumeration still running, `total == 0`):
 *   title `Uploading files…`, text the current file's leaf name (may be
 *   empty before the first file is reported).
 * - terminal `Completed(n)`: `Uploaded {n} files` (append ` · {failed}
 *   failed` when `failed > 0`).
 * - terminal `Cancelled`: `Upload cancelled — {completed} of {total}
 *   uploaded`.
 * - terminal `Failed`: `Upload finished — {completed} of {total} uploaded,
 *   {failed} failed`.
 *
 * The §5d terminal copy is a single self-contained summary line, so it is
 * rendered as the notification **title** with an empty body (the in-flight
 * two-line title/filename shape does not apply once the upload has ended).
 */
fun folderUploadNotificationContent(
    state: FolderUploadState?,
): FolderUploadNotificationContent? {
    state ?: return null
    val outcome = state.outcome
    val progress = state.progress

    if (outcome == null) {
        val title =
            if (progress.total > 0) {
                "Uploading file ${progress.completed + 1} of ${progress.total}"
            } else {
                "Uploading files…"
            }
        return FolderUploadNotificationContent(
            title = title,
            text = leafName(progress.currentFileName),
            phase = FolderUploadNotificationPhase.IN_FLIGHT,
        )
    }

    val title = when (outcome) {
        is FolderUploadOutcome.Completed -> {
            val summary = outcome.summary
            val base = "Uploaded ${summary.uploaded} files"
            if (summary.failed > 0) "$base · ${summary.failed} failed" else base
        }

        is FolderUploadOutcome.Cancelled -> {
            val partial = outcome.partial
            "Upload cancelled — ${partial.completed} of ${partial.total} uploaded"
        }

        FolderUploadOutcome.Failed ->
            "Upload finished — ${progress.completed} of ${progress.total} " +
                "uploaded, ${failedCount(progress)} failed"

        FolderUploadOutcome.InsufficientStorage ->
            "Not enough storage to upload this folder"
    }
    return FolderUploadNotificationContent(
        title = title,
        text = "",
        phase = FolderUploadNotificationPhase.TERMINAL,
    )
}

/**
 * Best-effort `failed` for the [FolderUploadOutcome.Failed] copy. The
 * `Failed` outcome carries no summary (the loop threw before producing one),
 * so the count is derived from progress: everything not yet reported complete
 * at the failure point. `total == 0` (failed during enumeration) ⇒ 0.
 */
private fun failedCount(progress: UploadProgress): Int =
    (progress.total - progress.completed).coerceAtLeast(0)

/**
 * The leaf (last path segment) of a possibly nested name. `FolderEntry`
 * relative paths are POSIX (`/`-separated, `docs/proposals/drive-folder-upload.md`
 * §3.2); the notification shows the file only (§5d "leaf only for v1").
 */
private fun leafName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\')
