package org.tenshu.sdk.ios

import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.SyncRulesPreferences
import app.tenshu.drive.ui.screens.settings.SyncPreferencesViewModel

/**
 * Wires the Drive **settings** view models for the SwiftUI client (full-parity step B).
 *
 * Sync Preferences (`SyncPreferencesViewModel`) binds cleanly: its only dependencies are the offline +
 * sync-rule preference seams, which have Noop impls on iOS today (the real per-device preference store
 * is `:preference-service` over the proxy — the on-device data path). Toggles update optimistically and
 * persist once a real Control serves prefs.
 *
 * The **Synced Folders** settings screen is deliberately not here: it needs the auto-backup infra
 * (`SyncedFolderController` / `MediaSourceProvider` / `BackupFolderGrants`), which is the iOS-hostile,
 * Phase-4-gated feature (reframe-not-port, proposal §9.4) with no iOS implementation yet.
 */
object DriveSettingsSurface {

    /**
     * Sync Preferences over the INSTALLED preference seams (P5): the real `:preference-service`
     * store once a live runtime installed it, the Noop defaults over the fakes.
     */
    fun syncPreferences(): SyncPreferencesViewModel =
        syncPreferences(
            DriveReadSurface.installedOfflinePreferences(),
            DriveReadSurface.installedSyncRulesPreferences(),
        )

    /** Canonical builder — explicit preference seams (tests inject recording fakes). */
    fun syncPreferences(
        offlinePreferences: OfflinePreferences,
        syncRulesPreferences: SyncRulesPreferences,
    ): SyncPreferencesViewModel = SyncPreferencesViewModel(offlinePreferences, syncRulesPreferences)
}
