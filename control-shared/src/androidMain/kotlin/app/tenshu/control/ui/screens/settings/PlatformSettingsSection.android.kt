package app.tenshu.control.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.tenshu.control.documents.DriveDocumentsAccess
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.settings_drive_documents_headline
import app.tenshu.control.shared.resources.settings_drive_documents_support
import app.tenshu.ui.chrome.AppListItem
import org.jetbrains.compose.resources.stringResource

/**
 * "Let other apps open your Drive files" — whether Tenshu Drive appears in the system file
 * dialogs ([app.tenshu.control.documents.DriveDocumentsProvider]). The preference file is the
 * source of truth; the local state only mirrors it for the switch.
 */
@Composable
actual fun PlatformSettingsSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(DriveDocumentsAccess.isEnabled(context)) }

    fun setEnabled(value: Boolean) {
        enabled = value
        DriveDocumentsAccess.setEnabled(context, value)
    }

    AppListItem(
        headline = stringResource(Res.string.settings_drive_documents_headline),
        supportingText = stringResource(Res.string.settings_drive_documents_support),
        leadingContent = { Icon(Icons.Default.FolderShared, contentDescription = null) },
        trailingContent = { Switch(checked = enabled, onCheckedChange = ::setEnabled) },
        onClick = { setEnabled(!enabled) },
    )
}
