package app.tenshu.calendar.ui.interop

import androidx.compose.runtime.Composable

/**
 * What a launched file operation came back with.
 *
 * ⚠ [Text] carries the file's **text**, because that is exactly what
 * `CalendarRepository.importIcs` takes — the platform layer's whole job is to
 * produce that string, and it holds no logic beyond doing so. The
 * `ContactImporter` seam (`:contacts-shared-ui`) has the same shape for the same
 * reason.
 */
sealed interface FilePickOutcome {
    data class Text(val text: String) : FilePickOutcome

    /** The user backed out. Distinct from a failure: it deserves no error message. */
    data object Cancelled : FilePickOutcome

    data class Failed(val problem: FileProblem) : FilePickOutcome
}

/** The result of writing a file out. */
sealed interface FileSaveOutcome {
    data object Saved : FileSaveOutcome
    data object Cancelled : FileSaveOutcome
    data class Failed(val problem: FileProblem) : FileSaveOutcome
}

/**
 * Why a platform read or write produced nothing.
 *
 * ⚠ Typed rather than a message, so the copy lives with the UI (ADR 0016) and the
 * platform actual never has to invent user-facing English — the
 * `ContactImportProblem` precedent.
 */
enum class FileProblem {
    /** The picker returned something that could not be read or written at all. */
    Unreadable,

    /** It was read, and held nothing. */
    Empty,
}

/**
 * The platform's file picker and saver, for `.ics` (CA-6b).
 *
 * ⭐ Modelled on `ContactImporter`: a `@Composable`-obtained seam, because the
 * Android actual has to hold `ActivityResult` launchers and those may only be
 * registered during composition.
 *
 * ⚠⚠ **This seam has a *save* half, which `ContactImporter` does not.** Contacts
 * only ever imports; a calendar is something people take *out* — that is half of
 * what "the migration story" means, and it is the direction that puts plaintext
 * calendar data outside the app (threat model A5).
 *
 * ⚠ `:calendar-shared-ui` is android + desktop only, so this is a plain
 * `expect`/`actual` with no iOS actual — an iOS Calendar shell does not exist
 * (CA-8), and its picker would be `UIDocumentPicker` rather than either of these.
 */
interface CalendarFileAccess {

    /**
     * Asks the user for an `.ics` file and reads it.
     *
     * [onOutcome] fires once, on the main thread, including when they cancel.
     * ⚠ The read itself must not run on the UI thread; the actuals move it off.
     */
    fun openIcs(onOutcome: (FilePickOutcome) -> Unit)

    /**
     * Asks the user where to put [text] and writes it.
     *
     * ⚠ [suggestedName] is a *suggestion* — every platform lets the user change it,
     * and none of them promises the name we asked for is the name on disk.
     */
    fun saveIcs(suggestedName: String, text: String, onOutcome: (FileSaveOutcome) -> Unit)
}

@Composable
expect fun rememberCalendarFileAccess(): CalendarFileAccess
