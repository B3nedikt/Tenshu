package app.tenshu.contacts.ui.screens.importing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop import (`docs/proposals/contacts-app.md` Ph7): a `.vcf` file, and only that.
 *
 * There is no desktop equivalent of the two contact sources — macOS, Windows and the Linux
 * desktops each have their own address-book store behind their own API, and every one of
 * them exports `.vcf`. Adding three native integrations to reach data the user can already
 * hand over in one step is not a trade worth making, so [ContactImportSource.OneContact] and
 * [ContactImportSource.AddressBook] report unsupported and the sheet simply does not offer
 * them.
 */
@Composable
actual fun rememberContactImporter(): ContactImporter {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        object : ContactImporter {
            override fun supports(source: ContactImportSource): Boolean =
                source == ContactImportSource.File

            override fun import(
                source: ContactImportSource,
                onOutcome: (ContactImportOutcome) -> Unit,
            ) {
                if (source != ContactImportSource.File) {
                    onOutcome(ContactImportOutcome.Cancelled)
                    return
                }
                scope.launch {
                    // The dialog is modal and must run on the AWT event thread; the file read
                    // must not. Drive-desktop opens its upload dialog the same way.
                    val chosen = withContext(Dispatchers.Swing) { chooseVcardFile() }
                    if (chosen == null) {
                        onOutcome(ContactImportOutcome.Cancelled)
                        return@launch
                    }
                    onOutcome(withContext(Dispatchers.IO) { readCards(chosen) })
                }
            }
        }
    }
}

/**
 * `null` when the user cancelled.
 *
 * Owner-less (`Frame?` = null) rather than parented to the Compose window: the seam is
 * obtained from an arbitrary composable and has no `FrameWindowScope` to read `window` from.
 * The cost is that the dialog is not modal to the app window — worth accepting to keep the
 * picker out of every shell's `main()`.
 */
private fun chooseVcardFile(): File? {
    val dialog = FileDialog(null as Frame?, "Import contacts", FileDialog.LOAD)
    // Honoured on Linux and Windows; macOS ignores it, which only means the user can select
    // a file that then reports "no contacts in that file" — a message, not a failure.
    dialog.setFilenameFilter { _, name -> name.endsWith(".vcf", ignoreCase = true) }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    val directory = dialog.directory ?: return null
    return File(directory, file)
}

private fun readCards(file: File): ContactImportOutcome {
    val text = runCatching { file.readBytes().decodeToString() }.getOrNull()
        ?: return ContactImportOutcome.Failed(ImportFailure.Unreadable)
    return when {
        text.isBlank() -> ContactImportOutcome.Failed(ImportFailure.NoCards)
        else -> ContactImportOutcome.Cards(text)
    }
}
