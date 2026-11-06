package app.tenshu.drive.data

/**
 * Desktop has no launch path to Control in v1 (separate JVM process; no IPC
 * launch). The gate dialog still informs the user; wiring an actual
 * launch/focus is the Phase-5 follow-up (`docs/proposals/storage-capacity-gating.md`
 * §4). No-op for now.
 */
class DesktopStorageSetupLauncher : StorageSetupLauncher {
    override fun openStorageSetup() {
        // Intentionally no-op on desktop in v1.
    }
}
