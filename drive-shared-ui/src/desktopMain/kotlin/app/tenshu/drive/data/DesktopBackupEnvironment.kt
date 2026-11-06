package app.tenshu.drive.data

import app.tenshu.ingest.backup.BackupEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import app.tenshu.ingest.backup.BackupNetworkStatus
/**
 * Desktop [BackupEnvironment]. A desktop is assumed always-on and wired (the
 * resident daemon model, `docs/proposals/drive-synced-folders.md` §3.4), so the
 * network is treated as unmetered and the machine as on external power — the
 * Wi-Fi-only / charging-only switches are no-ops on desktop (they exist for the
 * Android/mobile path). Mirrors `DesktopNetworkStateProvider`'s
 * always-unmetered stance.
 */
class DesktopBackupEnvironment : BackupEnvironment {
    override fun network(): BackupNetworkStatus = BackupNetworkStatus.UNMETERED
    override fun isCharging(): Boolean = true
    override fun changes(): Flow<Unit> = emptyFlow()
}
