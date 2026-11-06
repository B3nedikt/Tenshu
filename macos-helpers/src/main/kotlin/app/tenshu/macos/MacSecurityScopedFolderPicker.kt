package app.tenshu.macos

import app.tenshu.macos.ffi.ObjC

/**
 * Presents the macOS folder picker and returns the selected directory path.
 *
 * This is deliberately only the AppKit primitive. The caller owns the UI decision and must invoke
 * pickFolder on the AppKit main thread; the returned path must be converted to a bookmark in the
 * same sandboxed process with MacSecurityScopedBookmark.create (Control does both,
 * `MacSecurityScopedFolderPickerBinding`).
 *
 * A cancelled picker and an unavailable AppKit runtime both return null.
 */
public object MacSecurityScopedFolderPicker {
    /** Whether AppKit's NSOpenPanel can be reached in this process. */
    val isAvailable: Boolean
        get() = ObjC.isAvailable && !ObjC.isNull(ObjC.cls("NSOpenPanel"))

    /**
     * Show a single-directory picker.
     *
     * Must run on the AppKit main thread (use `MacEmbeddedAuthentication.onMain` from a background
     * thread — never from Swing's EDT, which AppKit may call back into while the panel is open).
     * This method is synchronous because NSOpenPanel.runModal() owns the modal event loop until
     * the user responds.
     */
    public fun pickFolder(): String? {
        if (!isAvailable) return null
        return runCatching {
            ObjC.autoreleasePool {
                val panel = ObjC.send(ObjC.cls("NSOpenPanel"), "openPanel")
                if (ObjC.isNull(panel)) return@autoreleasePool null

                ObjC.sendVoidWithBool(panel, "setCanChooseFiles:", false)
                ObjC.sendVoidWithBool(panel, "setCanChooseDirectories:", true)
                ObjC.sendVoidWithBool(panel, "setAllowsMultipleSelection:", false)

                // ⚠⚠ Control is a menu-bar (LSUIElement) app and is not the active app when Drive
                // asks for a folder, so a plain runModal opens the panel BEHIND Drive's window.
                // `toFront` cannot fix that; only activation can (as for the app-lock sheet).
                // Hand activation back to whoever had it once the panel closes.
                val previous = ObjC.send(
                    ObjC.send(ObjC.cls("NSWorkspace"), "sharedWorkspace"),
                    "frontmostApplication",
                ).let { if (ObjC.isNull(it)) it else ObjC.send(it, "retain") }
                val application = ObjC.send(ObjC.cls("NSApplication"), "sharedApplication")
                if (!ObjC.isNull(application)) {
                    ObjC.sendVoidWithBool(application, "activateIgnoringOtherApps:", true)
                }
                val response = try {
                    ObjC.sendLong(panel, "runModal")
                } finally {
                    if (!ObjC.isNull(previous)) {
                        ObjC.sendWithLong(previous, "activateWithOptions:", 0L)
                        ObjC.release(previous)
                    }
                }
                if (response != NS_MODAL_RESPONSE_OK) {
                    return@autoreleasePool null
                }

                val urls = ObjC.send(panel, "URLs")
                if (ObjC.isNull(urls) || ObjC.sendLong(urls, "count") == 0L) {
                    return@autoreleasePool null
                }

                val url = ObjC.sendWithLong(urls, "objectAtIndex:", 0L)
                if (ObjC.isNull(url)) return@autoreleasePool null

                ObjC.kotlinString(ObjC.send(url, "path")).takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    // NSModalResponseOK is stable across the macOS SDKs supported by the app.
    private const val NS_MODAL_RESPONSE_OK = 1L
}
