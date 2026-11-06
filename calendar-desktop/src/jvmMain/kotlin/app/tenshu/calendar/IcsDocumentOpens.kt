package app.tenshu.calendar

import app.tenshu.calendar.ui.interop.IcsOpenRelay
import app.tenshu.calendar.ui.interop.OpenedIcsFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * `.ics` files the desktop OS hands to this app (CA-6c) — the receiving half of the
 * `text/calendar` file association declared in `build.gradle.kts`.
 *
 * ⚠⚠ **Two delivery routes, and macOS uses neither of the obvious ones.** Windows
 * and Linux launchers pass the document as a command-line argument; macOS passes
 * *nothing* on the command line and sends an `odoc` Apple Event, which the JVM
 * surfaces as [Desktop.setOpenFileHandler]. An implementation that read only `args`
 * would work everywhere except the platform this was built for. Both are wired
 * below.
 *
 * ⚠⚠ **Registered before the window exists.** On macOS the event for the
 * double-click that *launched* the app is delivered almost immediately, and any
 * handler installed later simply misses it — hence [start] running before
 * `application { }`, and [IcsOpenRelay]'s replay buffer holding that first file
 * until the composition is up to collect it.
 */
class IcsDocumentOpens(private val scope: CoroutineScope) {

    private val relay = IcsOpenRelay()

    /** What `App(openedIcsFiles = …)` collects. */
    val files: Flow<OpenedIcsFile> get() = relay.files

    /**
     * Wires both delivery routes. Call once, before the Compose `application`.
     *
     * ⚠ Every step is defensive: a headless or unusual desktop session throws from
     * [Desktop.getDesktop], and `APP_OPEN_FILE` is unsupported on plenty of Linux
     * setups. Neither is a reason to fail to start a calendar.
     */
    fun start(args: Array<String>) {
        args.map(::File).filter(::looksLikeIcs).forEach(::deliver)

        val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
        if (!desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) return
        runCatching {
            desktop.setOpenFileHandler { event -> event.files.orEmpty().forEach(::deliver) }
        }
    }

    /**
     * ⚠ The extension, not the content: this decides whether a command-line argument
     * is a document at all, and reading every argument to find out would mean opening
     * whatever path a launcher happened to pass. The OS-event route needs no such
     * filter — the association is what selected the file.
     */
    private fun looksLikeIcs(file: File): Boolean =
        file.extension.equals("ics", ignoreCase = true)

    private fun deliver(file: File) {
        scope.launch { relay.deliver(read(file)) }
    }

    /**
     * ⚠ `decodeToString()` — RFC 5545 §6 makes UTF-8 the default charset for
     * `text/calendar`, and the platform default would mangle a non-ASCII summary on
     * any machine that is not already on UTF-8.
     */
    private suspend fun read(file: File): OpenedIcsFile = withContext(Dispatchers.IO) {
        runCatching { OpenedIcsFile.Content(file.name, file.readBytes().decodeToString()) }
            .getOrElse { OpenedIcsFile.Unreadable(file.name) }
    }
}
