package app.tenshu.calendar.ui.interop

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every MIME type an exported calendar turns up as.
 *
 * ⚠ The wildcard is the backstop, and it earns its place: file providers routinely
 * report a `.ics` as `application/octet-stream`, and a picker that cannot see the
 * file the user is looking at is worse than one that lets them pick the wrong
 * thing. The `VCARD_MIME_TYPES` precedent, for the same reason.
 */
private val ICS_MIME_TYPES = arrayOf("text/calendar", "text/x-vcalendar", "*/*")

/** ⚠ The registered type for `.ics`; what `CreateDocument` names the new file. */
private const val ICS_MIME_TYPE = "text/calendar"

/**
 * Android's file picker and saver (CA-6b).
 *
 * ⚠⚠ **The Storage Access Framework, not a storage permission.** `OpenDocument`
 * and `CreateDocument` hand back a URI the user chose, with a grant scoped to that
 * one file — so Calendar declares **no** storage permission at all and never asks
 * for one. Reading a `.ics` off shared storage the other way would need
 * `READ_EXTERNAL_STORAGE` and would put every file on the device in reach for the
 * sake of one.
 */
@Composable
actual fun rememberCalendarFileAccess(): CalendarFileAccess {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The pending callbacks bridge each launcher's async result back to the call
    // that started it (the `ContactImporter` pattern).
    val pendingOpen = remember { mutableStateOf<((FilePickOutcome) -> Unit)?>(null) }
    val pendingSave = remember { mutableStateOf<((FileSaveOutcome) -> Unit)?>(null) }
    // ⚠ The bytes have to be held across the launch: `CreateDocument` returns a
    // destination, not a stream, so what to write is only known here.
    val pendingText = remember { mutableStateOf("") }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val onOutcome = pendingOpen.value ?: return@rememberLauncherForActivityResult
        pendingOpen.value = null
        if (uri == null) {
            onOutcome(FilePickOutcome.Cancelled)
            return@rememberLauncherForActivityResult
        }
        scope.launch { onOutcome(readText(context.contentResolver, uri)) }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ICS_MIME_TYPE)
    ) { uri ->
        val onOutcome = pendingSave.value ?: return@rememberLauncherForActivityResult
        pendingSave.value = null
        val text = pendingText.value
        pendingText.value = ""
        if (uri == null) {
            onOutcome(FileSaveOutcome.Cancelled)
            return@rememberLauncherForActivityResult
        }
        scope.launch { onOutcome(writeText(context.contentResolver, uri, text)) }
    }

    return remember(context, scope) {
        object : CalendarFileAccess {
            override fun openIcs(onOutcome: (FilePickOutcome) -> Unit) {
                // A launcher already in flight owns the callback; a second launch
                // would orphan the first one's result.
                if (pendingOpen.value != null) return
                pendingOpen.value = onOutcome
                openDocument.launch(ICS_MIME_TYPES)
            }

            override fun saveIcs(
                suggestedName: String,
                text: String,
                onOutcome: (FileSaveOutcome) -> Unit,
            ) {
                if (pendingSave.value != null) return
                pendingSave.value = onOutcome
                pendingText.value = text
                createDocument.launch(suggestedName)
            }
        }
    }
}

private suspend fun readText(resolver: ContentResolver, uri: Uri): FilePickOutcome =
    withContext(Dispatchers.IO) {
        val text = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return@withContext FilePickOutcome.Failed(FileProblem.Unreadable)

        if (text.isBlank()) FilePickOutcome.Failed(FileProblem.Empty)
        else FilePickOutcome.Text(text)
    }

/**
 * ⚠ `"wt"`, not `"w"`. Without the truncate flag, overwriting an existing longer
 * file leaves the old tail behind — the export ends mid-`VEVENT` and reads as a
 * corrupt calendar rather than as a shorter one.
 */
private suspend fun writeText(
    resolver: ContentResolver,
    uri: Uri,
    text: String,
): FileSaveOutcome = withContext(Dispatchers.IO) {
    runCatching {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) }
            ?: return@runCatching null
        FileSaveOutcome.Saved
    }.getOrNull() ?: FileSaveOutcome.Failed(FileProblem.Unreadable)
}
