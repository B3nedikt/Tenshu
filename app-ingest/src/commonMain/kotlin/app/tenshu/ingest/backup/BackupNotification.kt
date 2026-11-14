package app.tenshu.ingest.backup

import app.tenshu.liveness.LivenessNotificationContent
import app.tenshu.liveness.LivenessPhase

/**
 * Pure copy + phase mapping for the Synced-Folders auto-backup notification
 * (`docs/proposals/drive-synced-folders.md` §5 step 6), the Android FGS liveness
 * anchor's content. Mirrors `folderUploadNotificationContent`: Android-free so the
 * copy is asserted in a desktop unit test; the Android builder is a thin adapter.
 *
 * A notification is shown only while a drain is **in flight** (holds the FGS) and
 * for the brief terminal **summary**; the idle / paused / up-to-date states show
 * nothing (the §5 footer carries those in-app — there's no value badging the
 * status bar with "paused — on cellular").
 *
 * `leafName` is intentionally not applied: backup file names are gallery leaf
 * names already (no nested relative path like folder-upload), so the raw name is
 * the leaf.
 */
/**
 * @param failureText the platform's catalogue copy for a typed [BackupFailureKind]. ⚠ Required, not
 *   defaulted: a default would be an English sentence reaching a user whose phone is not in English,
 *   which is what typing the cause exists to stop (`photos-app.md` §40).
 */
fun backupNotificationContent(
    state: BackupState,
    failureText: (BackupFailureKind) -> String,
): LivenessNotificationContent? = when (state) {
    is BackupState.BackingUp -> LivenessNotificationContent(
        title = if (state.total > 0) {
            "Backing up ${state.completed + 1} of ${state.total}"
        } else {
            "Backing up…"
        },
        text = state.currentFileName,
        phase = LivenessPhase.IN_FLIGHT,
    )

    is BackupState.JustFinished -> LivenessNotificationContent(
        title = backupSummaryTitle(state.uploaded, state.failed, state.alreadyInRealm),
        // ⚠ The title has counted failures all along and never said why; the body is empty on a
        // clean run anyway, so the reason costs nothing here. A clean run that skipped files the
        // realm already held says so instead — otherwise "nothing uploaded" reads as broken.
        text = when {
            state.failed > 0 -> state.failureKind?.let(failureText) ?: state.failureReason.orEmpty()
            state.alreadyInRealm > 0 -> "${state.alreadyInRealm} already in your Drive"
            else -> ""
        },
        phase = LivenessPhase.TERMINAL,
    )

    // ⚠ PausedByUser joins the other silent states deliberately. The user pressed Stop a second
    // ago and is looking at the screen that has the Resume control on it; a notification saying
    // "paused" would be the phone repeating their own action back at them.
    BackupState.Disabled,
    BackupState.PausedByUser,
    BackupState.UpToDate,
    is BackupState.Paused -> null
}

private fun backupSummaryTitle(uploaded: Int, failed: Int, alreadyInRealm: Int): String {
    val base = when (uploaded) {
        0 -> if (failed == 0 && alreadyInRealm > 0) "Nothing new to back up" else "Backup complete"
        1 -> "Backed up 1 file"
        else -> "Backed up $uploaded files"
    }
    return if (failed > 0) "$base · $failed failed" else base
}
