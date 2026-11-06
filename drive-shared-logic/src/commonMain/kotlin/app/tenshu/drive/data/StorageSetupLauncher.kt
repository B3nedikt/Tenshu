package app.tenshu.drive.data

/**
 * Platform seam for the storage-gate dialog's "set up / get more storage" CTA
 * (`docs/proposals/storage-capacity-gating.md` §4). Drive and Control are
 * separate apps, so taking the user to storage setup is platform-specific:
 * Android launches Control via its package; desktop has no launch path in v1
 * (a deep-link to Control's Services screen is the Phase-5 follow-up).
 */
interface StorageSetupLauncher {
    fun openStorageSetup()
}
