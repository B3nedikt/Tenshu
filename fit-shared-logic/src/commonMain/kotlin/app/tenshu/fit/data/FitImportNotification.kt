package app.tenshu.fit.data

import app.tenshu.fit.model.ImportStatus
import app.tenshu.liveness.LivenessNotificationContent
import app.tenshu.liveness.LivenessPhase

/**
 * Pure copy + phase mapping for Fit's import foreground-service notification — the Fit analogue of
 * Drive's `folderUploadNotificationContent`, over the generic [app.tenshu.liveness] surface.
 *
 * A Google Takeout import writes tens of thousands of records over minutes; without a foreground
 * service the OS reclaims a backgrounded Fit and the import dies (and, worse, its rate is what
 * pressures Control's memory, so it *must* be able to run without the user parked on a heavy list
 * screen). This maps the repository's [ImportStatus] to the notification the service shows.
 *
 * Only a **running** import promotes the service; the terminal states show a brief summary and then
 * demote. The advisory states — sample-data, missing permission, no source — are UI banners, not
 * long-running work, so they never take a foreground service (they map to `null`). Copy is hardcoded
 * English, matching Drive's notification (localisation of the notification is a later concern).
 */
fun fitImportNotificationContent(status: ImportStatus): LivenessNotificationContent? = when (status) {
    is ImportStatus.Running -> LivenessNotificationContent(
        title = "Importing health data",
        text = if (status.imported > 0) "${status.imported} records so far" else "Starting…",
        phase = LivenessPhase.IN_FLIGHT,
    )

    is ImportStatus.Imported -> LivenessNotificationContent(
        title = "Import complete",
        text = "${status.itemCount} records imported",
        phase = LivenessPhase.TERMINAL,
    )

    is ImportStatus.NoData -> LivenessNotificationContent(
        title = "Nothing to import",
        text = "",
        phase = LivenessPhase.TERMINAL,
    )

    is ImportStatus.Failed -> LivenessNotificationContent(
        title = "Import failed",
        text = "",
        phase = LivenessPhase.TERMINAL,
    )

    // Idle, and the advisory banner states (Sample / PermissionsMissing / SourceUnavailable) are not
    // running work — no foreground service, no notification.
    // WaitingForControl is a refusal before any work started (ADR 0025 §8.6) — nothing running either.
    ImportStatus.Idle, ImportStatus.SourceUnavailable,
    is ImportStatus.Sample, is ImportStatus.PermissionsMissing, is ImportStatus.WaitingForControl -> null
}
