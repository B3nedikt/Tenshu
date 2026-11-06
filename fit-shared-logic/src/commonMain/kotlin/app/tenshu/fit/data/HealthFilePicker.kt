package app.tenshu.fit.data

/**
 * Opens the platform's file picker so the user can hand the app a Google Takeout export (or a bare
 * GPX/TCX/FIT activity), and imports what comes back.
 *
 * Like [HealthPermissionRequester], this is the one step of the flow the shared layer cannot take:
 * only the app shell owns an `Activity` (Android's Storage Access Framework) or a window (a desktop
 * file dialog). The shell reads the bytes and calls `FitRepository.importFiles`, whose status the UI
 * is already rendering — so a picked file reports itself exactly like any other import.
 *
 * A DI-bound interface, not `expect`/`actual`: platforms that cannot pick a file simply do not bind
 * it, and the "Add a source" row hides rather than offering something that does nothing.
 */
fun interface HealthFilePicker {

    /** Opens the picker. Returns immediately — the import runs once the user has chosen. */
    fun pick()
}
