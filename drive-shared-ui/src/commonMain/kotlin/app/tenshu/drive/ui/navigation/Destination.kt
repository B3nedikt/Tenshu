package app.tenshu.drive.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

interface Destination {

    interface TopLevel : Destination {
        val label: String
        val icon: ImageVector
    }

    @Serializable
    data object Home : TopLevel {
        override val label: String get() = "Home"
        override val icon: ImageVector get() = Icons.Default.Home
    }

    @Serializable
    data object Files : TopLevel {
        override val label: String get() = "Files"
        override val icon: ImageVector get() = Icons.Default.Folder
    }

    @Serializable
    data object Shared : TopLevel {
        override val label: String get() = "Shared"
        override val icon: ImageVector get() = Icons.Default.Share
    }

    @Serializable
    data object Settings : Destination {
        val label: String get() = "Settings"
        val icon: ImageVector get() = Icons.Default.Settings
    }

    /** Settings → Open-source licences (`docs/oss-licences.md`), reached from [Settings]. */
    @Serializable
    data object Licences : Destination

    /** Synced Folders auto-backup settings
     *  (`docs/proposals/drive-synced-folders.md` §5), reached from [Settings]. */
    @Serializable
    data object SyncedFolders : Destination

    @Serializable
    data class Folder(val path: String) : Destination

    @Serializable
    data class FilePreview(val path: String) : Destination

    @Serializable
    data class FileDetails(val path: String) : Destination

    /**
     * "Move to" destination picker for the files/folders at [sourcePaths] — one
     * item for the per-file *Move* action, the whole selection for multi-select.
     * [folderPath] is the folder currently being browsed in the picker (starts at
     * the sources' shared parent, deepens as the user drills in). [sourcePaths] is
     * carried unchanged so each level can exclude the sources' own rows (which
     * also blocks moving into a descendant).
     */
    @Serializable
    data class MovePicker(
        val sourcePaths: List<String>,
        val folderPath: String,
    ) : Destination

    /**
     * The invitee-side redeem flow (`docs/proposals/drive-manage-access-ux.md`
     * §3). [link] is pre-filled by an Android deep link (`tenshu-invite:` /
     * `https://tenshu.app/i`); `null` for the Shared screen's paste entry.
     */
    @Serializable
    data class RedeemInvite(val link: String? = null) : Destination

    /**
     * The owner-side *Manage access* surface for the folder at [path]
     * (`docs/proposals/drive-manage-access-ux.md` §10) — a full screen, not a
     * sheet: a unified people list whose row treatment encodes invite status,
     * plus a + FAB into the invite-link sheet. Reached from the file-actions
     * sheet's folder-only *Manage access* row.
     */
    @Serializable
    data class ManageAccess(val path: String) : Destination

    /**
     * Browse a shared realm's `SharedFile` tree
     * (`docs/proposals/drive-shared-realm-file-model.md` §6 Phase 3d.2b). [realmId]
     * is the shared realm; [parentFileId] is the folder within it (realm root = `""`);
     * [title] labels the screen (the mount name at the root, a subfolder name when drilling).
     */
    @Serializable
    data class SharedBrowse(
        val realmId: String,
        val parentFileId: String,
        val title: String,
    ) : Destination
}
