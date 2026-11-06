package app.tenshu.calendar.ui.interop

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The one buffer between "the OS handed us a file" and "the composition is up to
 * collect it" (CA-6c/CA-6d).
 *
 * ⚠⚠ **[replay] is the whole point.** Every platform delivers the *first* file
 * before there is a composition to receive it: on macOS the `odoc` Apple Event for
 * the double-click that launched the app, on Android the `ACTION_VIEW` intent that
 * `onCreate` reads before `setContent` has run. Holding one emission is what makes
 * the launch case work at all, and it is the reason this is a `SharedFlow` rather
 * than a callback the shells each invent.
 *
 * ⚠ Common rather than per-platform because the *buffering* decisions — replay one,
 * drop the oldest, never suspend — are the part that must not drift between shells.
 * Each platform still owns its own delivery route and its own file reading; those
 * genuinely differ (`Desktop.setOpenFileHandler` + `File`, versus an `Intent` +
 * `ContentResolver`).
 *
 * ⚠ In `:calendar-shared-logic` rather than beside `CalendarFileAccess`, for two
 * reasons: nothing here touches Compose, and `:calendar-shared-ui` has no test source
 * set at all — so the replay guarantee this type exists for would have been asserted
 * nowhere. It is also the module iOS consumes, which is where an *Open in* handler
 * would reach for it.
 */
class IcsOpenRelay {

    private val _files = MutableSharedFlow<OpenedIcsFile>(
        // ⚠ Holds the launch-time file for a collector that does not exist yet.
        replay = 1,
        extraBufferCapacity = 8,
        // ⚠ With a buffer this size and this policy, [deliver] can never fail or
        // block — which is what lets it be called from a lifecycle callback.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** What `App(openedIcsFiles = …)` collects. */
    val files: Flow<OpenedIcsFile> = _files.asSharedFlow()

    /**
     * Hands a file to the app. Never suspends, never fails.
     *
     * ⚠ Safe to call before anything collects: that is the normal case on a launch.
     */
    fun deliver(file: OpenedIcsFile) {
        _files.tryEmit(file)
    }
}
