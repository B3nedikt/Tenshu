package app.tenshu.ingest.backup

/**
 * One of the user's own backup constraints, which a *Back up now* may be told to ignore **once**
 * (`drive-synced-folders-phase2.md` §4.1a) — the shape of Google Photos' "back up over mobile data
 * this time".
 *
 * ⚠ Only the user's *preferences* are here. No network, no storage and the user's own Stop are
 * not choices a confirmation can lift, so they are deliberately not members.
 */
enum class BackupConstraint { WIFI_ONLY, CHARGING_ONLY }

/**
 * A *Back up now* tap, held by [SyncedFolderController.userRequest] while its run is in flight.
 *
 * ⚠ A class, not a data class: two taps that ignore the same constraints are still two requests,
 * and the controller clears the one it served by identity so a second tap is never lost to the
 * first one's clean-up.
 */
class UserBackupRequest(val ignore: Set<BackupConstraint> = emptySet())

/** What one WorkManager-driven drain ([SyncedFolderController.drainInBackground]) ended with. */
enum class BackgroundDrainResult {
    /** This device's sync identity is not resolved yet — worth a retry. */
    NOT_READY,

    /** Nothing left for this pass: drained, paused by policy, or disabled. */
    COMPLETE,

    /** The job's budget ran out at a file boundary with files still to go — continue in a new job. */
    YIELDED,
}

/** These settings with [constraints] lifted — for one user-confirmed run, never written back. */
fun BackupSettings.ignoring(constraints: Set<BackupConstraint>): BackupSettings =
    if (constraints.isEmpty()) this
    else copy(
        wifiOnly = wifiOnly && BackupConstraint.WIFI_ONLY !in constraints,
        chargingOnly = chargingOnly && BackupConstraint.CHARGING_ONLY !in constraints,
    )

/**
 * The user's own constraints that are, right now, the **only** reason a *Back up now* would pause —
 * what the screen may offer to ignore once.
 *
 * Empty when the gate already allows the backup, and ⚠ also when anything else would still stop it
 * (backup off, Stop, no storage, no network): offering "use mobile data this once?" on a phone with
 * no connection is a choice whose answer changes nothing. Both constraints are returned together
 * when both block, so one confirmation covers the run rather than the second one surfacing only
 * after the first was accepted.
 */
fun overridableBlockers(
    settings: BackupSettings,
    storage: StorageStatusModel?,
    network: BackupNetworkStatus,
    charging: Boolean,
): Set<BackupConstraint> {
    val all = setOf(BackupConstraint.WIFI_ONLY, BackupConstraint.CHARGING_ONLY)
    if (evaluateBackupGate(settings.ignoring(all), storage, network, charging) != BackupGate.Allowed) {
        return emptySet()
    }
    return buildSet {
        if (settings.wifiOnly && network != BackupNetworkStatus.UNMETERED) add(BackupConstraint.WIFI_ONLY)
        if (settings.chargingOnly && !charging) add(BackupConstraint.CHARGING_ONLY)
    }
}

/**
 * Whether the Android foreground service should be started — **only for a run the user started**.
 *
 * ⚠⚠ Play's `dataSync` policy: a foreground service may carry a transfer the user initiated, and
 * "daily auto backup of all new photos" is its named counter-example. Automatic drains (a media
 * change, the WorkManager job, a settings write) run without one, under WorkManager's own limits.
 * Keyed on the frame's own stamp, never on a second flow — see [SyncedFolderController.userRequest].
 */
fun startsForegroundService(state: BackupState): Boolean =
    state is BackupState.BackingUp && state.userInitiated

/**
 * The state the foreground service renders. Identical to [state] for a user run; an automatic
 * drain's progress reads as idle, so a service still showing the previous user run's summary
 * **stops** instead of re-promoting itself for a drain nobody asked for.
 */
fun foregroundServiceState(state: BackupState): BackupState =
    if (state is BackupState.BackingUp && !state.userInitiated) BackupState.UpToDate else state
