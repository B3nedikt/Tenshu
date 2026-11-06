package app.tenshu.calendar.ui.interop

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
 * Desktop's file picker and saver (CA-6b).
 *
 * ⚠ `FileDialog` rather than Swing's `JFileChooser`: it is the platform's own
 * dialog on macOS and Windows, which is what a user expects to see, and it is what
 * `ContactImporter.desktop` and Drive's upload already use.
 */
@Composable
actual fun rememberCalendarFileAccess(): CalendarFileAccess {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        object : CalendarFileAccess {
            override fun openIcs(onOutcome: (FilePickOutcome) -> Unit) {
                scope.launch {
                    // The dialog is modal and must run on the AWT event thread; the
                    // file read must not.
                    val chosen = withContext(Dispatchers.Swing) { chooseIcsFile(FileDialog.LOAD) }
                        ?: return@launch onOutcome(FilePickOutcome.Cancelled)
                    onOutcome(withContext(Dispatchers.IO) { readIcs(chosen) })
                }
            }

            override fun saveIcs(
                suggestedName: String,
                text: String,
                onOutcome: (FileSaveOutcome) -> Unit,
            ) {
                scope.launch {
                    val chosen = withContext(Dispatchers.Swing) {
                        chooseIcsFile(FileDialog.SAVE, suggestedName)
                    } ?: return@launch onOutcome(FileSaveOutcome.Cancelled)
                    onOutcome(withContext(Dispatchers.IO) { writeIcs(chosen, text) })
                }
            }
        }
    }
}

/**
 * `null` when the user cancelled.
 *
 * ⚠ Owner-less (`Frame?` = null) rather than parented to the Compose window: the
 * seam is obtained from an arbitrary composable and has no `FrameWindowScope` to
 * read `window` from. The cost is that the dialog is not modal to the app window —
 * worth accepting to keep the picker out of every shell's `main()`. The
 * `ContactImporter` desktop actual made the same call.
 *
 * ⚠ The filename filter is honoured on Linux and Windows; macOS ignores it, which
 * only means the user can pick a file that then reports "nothing in that file" —
 * a message, not a failure.
 */
private fun chooseIcsFile(mode: Int, suggestedName: String? = null): File? {
    val title = if (mode == FileDialog.SAVE) "Export calendar" else "Import calendar"
    val dialog = FileDialog(null as Frame?, title, mode)
    suggestedName?.let { dialog.file = it }
    dialog.setFilenameFilter { _, name -> name.endsWith(".ics", ignoreCase = true) }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    val directory = dialog.directory ?: return null
    return File(directory, file)
}

private fun readIcs(file: File): FilePickOutcome {
    val text = runCatching { file.readBytes().decodeToString() }.getOrNull()
        ?: return FilePickOutcome.Failed(FileProblem.Unreadable)
    return if (text.isBlank()) FilePickOutcome.Failed(FileProblem.Empty)
    else FilePickOutcome.Text(text)
}

private fun writeIcs(file: File, text: String): FileSaveOutcome =
    runCatching {
        // ⚠ `writeBytes` truncates, which is what an overwrite has to do — a
        // shorter export over a longer file must not leave the old tail behind.
        file.writeBytes(text.encodeToByteArray())
        FileSaveOutcome.Saved as FileSaveOutcome
    }.getOrElse { FileSaveOutcome.Failed(FileProblem.Unreadable) }
