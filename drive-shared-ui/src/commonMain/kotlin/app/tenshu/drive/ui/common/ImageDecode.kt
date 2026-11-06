package app.tenshu.drive.ui.common

/**
 * Whether this platform can actually render [fileName] in the in-app image
 * viewer, judged from its extension before any byte is fetched.
 *
 * [fileCategoryForName][org.tenshu.core.file.fileCategoryForName] answers "is
 * this an image?"; this answers the narrower "can *we* draw it?", and the two
 * genuinely differ. HEIC is the case that forced the distinction: Compose
 * Desktop decodes through Skia, whose JetBrains build carries no HEIF/HEVC
 * codec, so every iPhone photo dead-ends in "Could not decode image" on a Mac
 * unless the ImageIO helper is there to bridge it. Where the answer is `false`
 * the router sends the file to `UnsupportedPreviewScreen` instead, which offers
 * **Open in another app** — an honest hand-off beats a viewer that fails.
 *
 * Extension-based on purpose: routing happens on the file *name*, long before
 * the bytes arrive. A format that slips through (a mislabelled file, say) still
 * falls back safely — the viewer shows its decode error as it always has.
 *
 * ⚠ This stays Drive's, deliberately. It is a **routing** rule about which
 * screen a file opens on, not a decode; the decode itself moved to
 * [app.tenshu.ui.image.decodeToImageBitmap] in `:ui-core` when Timeline became
 * its third consumer (`timeline-photos.md` T-P1).
 */
expect fun isImagePreviewSupported(fileName: String): Boolean
