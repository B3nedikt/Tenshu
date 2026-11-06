package app.tenshu.calendar.ui.interop

/**
 * A `.ics` file the host operating system handed to the app (CA-6c) — a macOS
 * document open, or a path on the command line.
 *
 * ⚠⚠ **Content, not a path.** Everything downstream — `CalendarInteropViewModel`,
 * `CalendarRepository.importIcs` — takes the file's *text*, which is what keeps the
 * whole flow testable off a device. The same split `CalendarFileAccess` draws for
 * the picker, drawn again for the OS-delivered case, so the two meet at the view
 * model instead of each growing their own path handling.
 *
 * ⚠ Sealed for the same reason [FilePickOutcome] is: a file that cannot be read has
 * to reach the user as a *message*, and squeezing it into an empty [Content] would
 * tell them the file was empty when it was not.
 */
sealed interface OpenedIcsFile {

    /**
     * The file's name, shown to the user and stored as `EventSource.origin`.
     *
     * ⚠ The *name*, never the path: it is displayed, and a document opened from
     * somewhere private should not put that directory on screen — or, worse, into a
     * synced row that every paired device then holds.
     */
    val name: String

    /** ⚠ [text] may be empty; the view model reports that as its own outcome. */
    data class Content(override val name: String, val text: String) : OpenedIcsFile

    data class Unreadable(override val name: String) : OpenedIcsFile
}
