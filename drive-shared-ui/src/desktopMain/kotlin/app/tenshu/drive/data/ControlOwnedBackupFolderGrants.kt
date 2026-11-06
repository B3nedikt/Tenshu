package app.tenshu.drive.data

import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.UnsupportedOperationError
import org.tenshu.core.common.TenshuClient
import org.tenshu.ingest.client.ingestService
import org.tenshu.core.ingest.pickSecurityScopedFolder

/**
 * Desktop grant bridge. The binary app-to-Control transport carries only the opaque grant
 * handle; Control presents the native picker and keeps the bookmark in its own sandbox.
 */
class ControlOwnedBackupFolderGrants(
    private val client: TenshuClient,
) : BackupFolderGrants {
    override suspend fun pick(): BackupFolderPickResult =
        when (val result = client.ingestService.pickSecurityScopedFolder()) {
            is TResult.Success<*> -> {
                val id = (result.value as? TString)?.get()?.takeIf { it.isNotBlank() }
                if (id == null) BackupFolderPickResult.Cancelled else BackupFolderPickResult.Added(id)
            }
            // ⚠⚠ Only the typed "this Control does not have the operation" answer is skew.
            // Control not running, a transport drop or a picker failure is not fixed by
            // updating Control, and telling the user to update would send them the wrong way.
            is TResult.Failure<*> ->
                if (result.error is UnsupportedOperationError) {
                    BackupFolderPickResult.Unavailable
                } else {
                    BackupFolderPickResult.Failed
                }
            else -> BackupFolderPickResult.Failed
        }

    override suspend fun release(id: String) = Unit
}
