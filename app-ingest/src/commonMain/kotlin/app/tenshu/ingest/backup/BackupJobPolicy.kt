package app.tenshu.ingest.backup

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/*
 * The automatic-backup job's policy, Android-free so it is desktop-tested; the WorkManager worker
 * (`BackupWorker`) is a thin adapter over it (`drive-synced-folders-phase2.md` §4.1a).
 */

/**
 * How long one automatic backup job drains before it yields and hands over to a continuation.
 *
 * ⚠ Below the OS's ~10-minute job limit on purpose: at the limit the OS **cancels** the worker,
 * which can land mid-file — between Control saving the file's row and journalling its bytes, the
 * one window a cancel is not free in (§4.1b). Yielding is checked at file boundaries only, so the
 * minute left over is the room for the file in flight to finish.
 */
val BACKUP_JOB_BUDGET: Duration = 9.minutes

/**
 * The OS-side conditions an automatic backup job waits for — the user's switches plus two the OS
 * is better placed to judge. An off-policy phone then costs no wake at all; the drain's own gate
 * ([evaluateBackupGate]) stays the authority and pauses anything that slips through.
 */
data class BackupJobConstraints(
    /** Wi-Fi-only → an unmetered network; otherwise any connection. */
    val unmeteredNetwork: Boolean,
    /** Charging-only → charging. */
    val charging: Boolean,
    /** Background backup is not worth a low battery; *Back up now* is unaffected. */
    val batteryNotLow: Boolean,
    /** Control writes each file to local storage before it uploads; don't fill a full phone. */
    val storageNotLow: Boolean,
)

fun backupJobConstraints(wifiOnly: Boolean, chargingOnly: Boolean): BackupJobConstraints =
    BackupJobConstraints(
        unmeteredNetwork = wifiOnly,
        charging = chargingOnly,
        batteryNotLow = true,
        storageNotLow = true,
    )

/** What a backup job does before it drains, from the tri-state [BackupPreferences.readEnabledOrNull]. */
enum class BackupJobStart {
    /** Backup is on — drain. */
    RUN,

    /** A **stored** `false` — backup is off; cancel every backup job. */
    STOP_ALL,

    /** Could not read — retry later, and ⚠ cancel nothing: unknown is not "off". */
    RETRY,
}

fun backupJobStart(enabled: Boolean?): BackupJobStart = when (enabled) {
    true -> BackupJobStart.RUN
    false -> BackupJobStart.STOP_ALL
    null -> BackupJobStart.RETRY
}

/** What the worker does after a drain. */
enum class BackupJobFollowUp {
    /** Identity not resolved yet — WorkManager's retry with backoff. */
    RETRY,

    /** The budget ran out with files left — enqueue a continuation job, then succeed. */
    CONTINUE,

    /** Done for now — the content trigger and the periodic floor wake the next one. */
    DONE,
}

fun backupJobFollowUp(result: BackgroundDrainResult): BackupJobFollowUp = when (result) {
    BackgroundDrainResult.NOT_READY -> BackupJobFollowUp.RETRY
    BackgroundDrainResult.YIELDED -> BackupJobFollowUp.CONTINUE
    BackgroundDrainResult.COMPLETE -> BackupJobFollowUp.DONE
}
