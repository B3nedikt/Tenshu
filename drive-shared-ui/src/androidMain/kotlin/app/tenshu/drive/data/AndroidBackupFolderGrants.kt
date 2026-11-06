package app.tenshu.drive.data

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [BackupFolderGrants] (Phase 3b). Releases the persistable read grant
 * taken via `takePersistableUriPermission` when a document-tree folder was added
 * for backup (`MainActivity`). `runCatching`: the grant may already be gone (the
 * user revoked it, or the tree was never persistable); releasing a missing grant
 * throws `SecurityException`, which is benign here.
 *
 * Not locally compilable in the headless build env (no Android SDK); correctness
 * by construction + Studio verification, like the other `Android*` actuals.
 */
class AndroidBackupFolderGrants(
    private val context: Context,
) : BackupFolderGrants {

    override suspend fun release(uri: String) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        Unit
    }
}
