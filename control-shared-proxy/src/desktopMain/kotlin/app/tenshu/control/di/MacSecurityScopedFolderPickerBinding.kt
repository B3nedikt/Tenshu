package app.tenshu.control.di

import app.tenshu.control.data.SecurityScopedGrantStore
import app.tenshu.macos.MacEmbeddedAuthentication
import app.tenshu.macos.MacSecurityScopedBookmark
import app.tenshu.macos.MacSecurityScopedFolderPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tenshu.core.ingest.SecurityScopedFolderGrant
import org.tenshu.ingest.proxy.SecurityScopedFolderPicker

/**
 * Control-owned macOS picker capability. The dialog and bookmark creation both happen in
 * Control, and the bookmark stays in Control's [store]: Drive receives only a handle
 * (grant id + display name), never a host path or the bookmark bytes.
 *
 * ⚠⚠ Dispatched from [Dispatchers.IO], never Swing's EDT: `onMain` blocks its caller until the
 * modal panel closes, and parking the EDT that long freezes Control's window and invites the
 * AppKit→EDT deadlock (AppKit calls back into AWT synchronously while the panel runs).
 */
internal class MacSecurityScopedFolderPickerBinding(
    private val store: SecurityScopedGrantStore,
) : SecurityScopedFolderPicker {
    override suspend fun pick(): String? = withContext(Dispatchers.IO) {
        val path = MacEmbeddedAuthentication.onMain { MacSecurityScopedFolderPicker.pickFolder() }
            ?: return@withContext null
        val bookmark = MacSecurityScopedBookmark.create(path) ?: return@withContext null
        val displayName = java.io.File(path).name.ifBlank { path }
        SecurityScopedFolderGrant.encode(store.put(bookmark), displayName)
    }
}
