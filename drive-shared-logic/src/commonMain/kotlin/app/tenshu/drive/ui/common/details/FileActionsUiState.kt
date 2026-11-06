package app.tenshu.drive.ui.common.details

import app.tenshu.drive.domain.StorageGateDecision
import app.tenshu.ingest.backup.BackupDestination
import app.tenshu.drive.ui.model.FileUiModel

data class FileActionsUiState(
    val file: FileUiModel,
    /**
     * Set when the user tapped *Link* (`docs/file-sharing.md`).
     * The host (`FileActionsBottomSheet`) renders `CreateShareLinkSheet` when
     * non-null; cleared on dismiss.
     */
    val showCreateShareLinkForPath: String? = null,
    /**
     * Whether this file is currently kept offline on this device — the offline
     * *intent* (`DrivePreferences.OfflineFiles`), not a true byte-presence check
     * (`docs/proposals/drive-offline-online.md` §2). Drives the Download row's
     * label/icon: `false` → "Make available offline" / CloudDownload; `true` →
     * "Remove download" / CloudOff.
     */
    val isAvailableOffline: Boolean = false,
    /**
     * Whether this row is a folder. Folders show a folder-level sync-rule override
     * toggle ([folderKeptOffline]) instead of the per-file pin Download row
     * (`docs/proposals/drive-offline-online.md` §4.10 Phase 2b).
     */
    val isFolder: Boolean = false,
    /**
     * For a folder, whether its **effective** sync policy is
     * [org.tenshu.core.file.sync.SyncPolicy.KEEP_OFFLINE] (the longest-matching
     * override, or the device default). Drives the folder-override row's
     * label/icon: `true` → "Make folder online-only"; `false` → "Make folder available offline".
     */
    val folderKeptOffline: Boolean = false,
    /**
     * Non-null when *Link* was blocked by the Tier-1 storage gate
     * (`docs/proposals/storage-capacity-gating.md` §2). The host renders
     * `StorageGateDialog` instead of the create-link sheet.
     */
    val storageGateBlock: StorageGateDecision? = null,
    /**
     * Non-null when *Delete* landed on a Synced-Folders destination and is waiting on the
     * user reading what that means (`drive-synced-folders.md` §3.9). The host renders
     * `BackupDeleteDialog`; the delete does not run until it is confirmed.
     */
    val backupDeleteWarning: BackupDestination? = null,
)