package app.tenshu.ingest.backup

import kotlinx.coroutines.flow.Flow

/**
 * The drain app's view of the network + power state the auto-backup loop gates on
 * while the app is alive (`docs/proposals/drive-synced-folders.md` §3.8).
 *
 * The proposal points at Control's `NetworkStateProvider`, but that lives in
 * `:control-shared` and the in-process gate runs in whichever app owns the drain,
 * so it needs its own seam. This mirrors `NetworkStateProvider`'s shape (Koin-wired
 * Android/Desktop actuals, not `expect`/`actual`) and folds the charging signal
 * in alongside the network one so there is a single binding to wire. The
 * Phase-2 WorkManager tier maps the same two switches onto native
 * `Constraints` (§3.8) — this seam is only the app-alive path.
 *
 * ⚠ Written when this was Drive-only; it moved to `:app-ingest` with the rest of
 * the engine in PH-4b (`photos-app.md` §19.2) and is no longer Drive-owned.
 */
interface BackupEnvironment {
    /** The current metered/unmetered/none classification of the active network. */
    fun network(): BackupNetworkStatus

    /** Whether the device is charging / on external power right now. */
    fun isCharging(): Boolean

    /**
     * Emits when [network] or [isCharging] may have moved. A coarse "look again" signal, like
     * [MediaSourceProvider.observeChanges]: the controller re-reads both getters under its gate.
     *
     * ⚠ Abstract, not defaulted to an empty flow: without it the status line keeps the last
     * gate verdict until the next media event, so "Paused — on mobile data" outlived the mobile
     * data. A platform that genuinely cannot change (desktop) says so explicitly.
     */
    fun changes(): Flow<Unit>
}

/** Mirror of Control's `NetworkStatus` (kept app-local; see [BackupEnvironment]). */
enum class BackupNetworkStatus { UNMETERED, METERED, NONE }

/**
 * Pure policy resolver: may the backup loop drain right now given the user's two
 * switches and the observed environment? Extracted so it is desktop-unit-tested
 * independently of any Android runtime (same split as Control's
 * `constraintAllowsSync`).
 *
 * - No network at all ⇒ never (nothing can upload).
 * - "Wi-Fi only" ⇒ requires an [BackupNetworkStatus.UNMETERED] network.
 * - "Only while charging" ⇒ requires [charging].
 *
 * Storage availability is a *separate* hard gate (§3.6) checked by the
 * controller, not folded in here.
 */
fun backupConstraintsAllow(
    wifiOnly: Boolean,
    chargingOnly: Boolean,
    network: BackupNetworkStatus,
    charging: Boolean,
): Boolean {
    if (network == BackupNetworkStatus.NONE) return false
    if (wifiOnly && network != BackupNetworkStatus.UNMETERED) return false
    if (chargingOnly && !charging) return false
    return true
}
