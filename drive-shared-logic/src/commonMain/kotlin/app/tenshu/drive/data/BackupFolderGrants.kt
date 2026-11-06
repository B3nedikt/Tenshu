package app.tenshu.drive.data

/**
 * Platform seam for folder grants. On Android this releases a persisted SAF URI grant.
 * On sandboxed macOS, the implementation asks Control to present the picker and returns an
 * opaque Control-owned security-scoped bookmark grant; Drive never receives a host path.
 */
sealed interface BackupFolderPickResult {
    data class Added(val id: String) : BackupFolderPickResult
    data object Cancelled : BackupFolderPickResult
    /** This Control does not have the picker operation: it needs an update. */
    data object Unavailable : BackupFolderPickResult
    /** The pick failed for another reason (Control not reachable, picker error). */
    data object Failed : BackupFolderPickResult
}

interface BackupFolderGrants {
    /** Present the platform folder picker and report cancellation separately from unavailability. */
    suspend fun pick(): BackupFolderPickResult = BackupFolderPickResult.Cancelled

    /** Release a platform grant when [id] is removed; tolerant of an absent grant. */
    suspend fun release(id: String)
}

/** Desktop / preview default when no platform picker is available. */
object NoopBackupFolderGrants : BackupFolderGrants {
    override suspend fun release(id: String) = Unit
}
