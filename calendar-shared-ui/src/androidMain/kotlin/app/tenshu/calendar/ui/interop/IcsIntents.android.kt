package app.tenshu.calendar.ui.interop

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `.ics` files Android hands to Calendar (CA-6d) — the receiving half of the
 * `text/calendar` intent filters in `calendar-android`'s manifest.
 *
 * ⚠ Two actions, because they are two different user gestures. *Open with* on a
 * file arrives as `ACTION_VIEW` with the URI in `data`; *Share* from a mail client
 * arrives as `ACTION_SEND` with it in `EXTRA_STREAM`. Declaring only the first
 * leaves Calendar missing from the share sheet, which is where a `.ics` attachment
 * most often is.
 */

/**
 * The `.ics` this intent is asking us to open, or `null` if it is not asking.
 *
 * ⚠⚠ **A launcher intent must return `null`.** `MainActivity` runs this on *every*
 * `onCreate`, including the ordinary tap on the app icon — which carries
 * `ACTION_MAIN` and no data. Anything less careful here would open the import sheet
 * on a plain launch.
 */
fun Intent.icsUri(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    // ⚠ `getParcelableExtra` is deprecated from API 33 in favour of the typed
    // overload, which does not exist below it. `minSdk` here is 24, so the
    // suppression is the compatible spelling rather than a shortcut — the value is
    // a framework-supplied `Uri` either way.
    @Suppress("DEPRECATION")
    Intent.ACTION_SEND -> getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    else -> null
}

/**
 * Reads [uri] into the shape the import flow takes.
 *
 * ⚠ Off the main thread, like the `CalendarFileAccess` picker read this mirrors: an
 * `.ics` is usually small and occasionally is not.
 *
 * ⚠ A failure is [OpenedIcsFile.Unreadable], never an exception. The URI came from
 * outside the app and its grant may already have lapsed — an app that crashes when
 * handed a stale `content://` is worse than one that says it could not read it.
 */
suspend fun ContentResolver.readOpenedIcs(uri: Uri): OpenedIcsFile = withContext(Dispatchers.IO) {
    val name = displayName(uri)
    val text = runCatching {
        openInputStream(uri)?.use { it.readBytes().decodeToString() }
    }.getOrNull()

    if (text == null) OpenedIcsFile.Unreadable(name) else OpenedIcsFile.Content(name, text)
}

/**
 * The file's name as the user knows it.
 *
 * ⚠⚠ Queried, not taken from the URI's last path segment. A `content://` URI's path
 * is routinely an opaque row id (`…/media/external/file/1234`), so the obvious
 * spelling would put a number where the sheet shows a file name and store that
 * number as `EventSource.origin`.
 *
 * ⚠ Blank rather than a guess when the provider offers no display name: the sheet
 * has its own fallback title, and inventing one here would put a fabricated name
 * into a synced row.
 */
private fun ContentResolver.displayName(uri: Uri): String = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }
}.getOrNull().orEmpty()
