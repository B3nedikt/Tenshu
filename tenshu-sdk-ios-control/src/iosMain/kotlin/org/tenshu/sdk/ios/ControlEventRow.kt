package org.tenshu.sdk.ios

import app.tenshu.control.data.Event
import org.tenshu.core.common.util.TimestampUtil

/**
 * What kind of thing happened, as a closed set the SwiftUI feed switches on.
 *
 * A Kotlin `enum class` on purpose, not a `String` token: SKIE bridges it as a real Swift
 * enum with camelCased cases, so the `switch` in `HomeView` is **exhaustive** — adding a
 * case here fails the Swift build instead of silently falling into a `default:` that
 * renders the wrong row. (String tokens are the right shape when the value is genuinely
 * open-ended, as with `AddFileActionsViewModel`'s error tokens; this set is closed.)
 *
 * Folder vs file is carried by the kind ([FOLDER_ADDED] / [FOLDER_DELETED]) because the feed's
 * *title* differs ("Folder Added" vs "File Added"), not just its detail line. Both come from
 * the shared `Event`'s `isFolder`, which `NotificationMapper` derives from the notification's
 * type — a delete from a producer that predates `FolderDeletedNotification` reads as a file.
 */
enum class ControlEventKind {
    FILE_ADDED,
    FOLDER_ADDED,
    MULTIPLE_FILES_ADDED,
    FILE_DELETED,
    FOLDER_DELETED,
    /** CR-2 B4 P3: a change lost to a file another device deleted. */
    CHANGE_LOST,
    APP_REGISTERED,
    APP_REMOVED,
}

/**
 * One row of Control's realm-activity feed, flattened for SwiftUI.
 *
 * Why a flat row rather than handing `Event` itself to Swift — `Event` is a **two-level**
 * sealed hierarchy (`Event` → `FileEvent`/`AppEvent` → the leaves), so rendering it natively
 * would mean nested `onEnum(of:)` switches in the view; and every file leaf carries a
 * `preview: ByteArray?`, whose entire Swift surface is `size` + `get(index:)` — one bridged
 * call per byte. Flattening here keeps `KotlinByteArray` out of the view layer by
 * construction. It is also what this file's neighbours already do: `ControlSurface` maps the
 * genesis/recovery sealed step machines to plain values on the Kotlin side "never switching
 * sealed cases in Swift".
 *
 * ⚠ No preview bytes are carried, and that is currently lossless: `NotificationMapper`
 * passes `preview = null` at every one of its construction sites and never populates
 * `MultipleAdded.previews`, so the Compose feed's `PreviewImage` branch is dead code today.
 * When a producer starts filling them, add an `NSData?` here (see `DrivePreviewSurface
 * .loadRowThumbnail` for the `memScoped`/`allocArrayOf` conversion) — never a `ByteArray`.
 *
 * Copy lives in Swift (ADR 0016): this type carries the *facts* — which kind, whose name,
 * how many others — and `HomeView` turns them into sentences.
 *
 * ⚠ [timestampLabel] is the one deliberate exception, and it is not copy: it is a
 * *locale-formatted datum*, and the platform-correct formatter already exists as
 * `TimestampUtil` (an `expect object` whose iOS actual is `NSDateFormatter` at
 * `dateStyle = .medium` / `timeStyle = .short`). Formatting here means the iOS feed reads
 * exactly as the Compose one does, with one implementation instead of two kept in step by
 * hand. Safe to precompute because the format is **absolute**, not relative — a
 * "3 minutes ago" label would go stale in a snapshot and would have to be a live value.
 */
class ControlEventRow internal constructor(
    val id: String,
    val kind: ControlEventKind,
    /**
     * The file, folder or app the event is about; for a burst, the first file's name.
     *
     * ⚠ For file events this is the **full path**, not the leaf — `NotificationMapper` fills
     * it from `notification.path.get()`. The Compose feed renders the same full path, so this
     * mirrors rather than diverges; shortening it is a change to the shared mapper, worth
     * doing for both platforms at once rather than only here.
     */
    val name: String,
    /** How many *further* files a [ControlEventKind.MULTIPLE_FILES_ADDED] burst carried; 0 otherwise. */
    val otherFilesCount: Int,
    /** Absolute, localized, formatted by the same shared [TimestampUtil] the Compose feed uses. */
    val timestampLabel: String,
) {
    companion object {
        /**
         * Flatten one shared [Event]. Total by construction — the `when` is exhaustive over a
         * sealed hierarchy, so a new event subtype fails this compile rather than reaching the
         * feed as a blank row.
         */
        fun from(event: Event): ControlEventRow = when (event) {
            is Event.FileEvent.Added -> ControlEventRow(
                id = event.id,
                kind = if (event.isFolder) ControlEventKind.FOLDER_ADDED else ControlEventKind.FILE_ADDED,
                name = event.fileName,
                otherFilesCount = 0,
                timestampLabel = TimestampUtil.formatTimestamp(event.timestamp),
            )

            is Event.FileEvent.MultipleAdded -> ControlEventRow(
                id = event.id,
                kind = ControlEventKind.MULTIPLE_FILES_ADDED,
                name = event.firstFileName,
                otherFilesCount = event.otherFilesCount,
                timestampLabel = TimestampUtil.formatTimestamp(event.timestamp),
            )

            is Event.FileEvent.Deleted -> ControlEventRow(
                id = event.id,
                kind = if (event.isFolder) ControlEventKind.FOLDER_DELETED else ControlEventKind.FILE_DELETED,
                name = event.fileName,
                otherFilesCount = 0,
                timestampLabel = TimestampUtil.formatTimestamp(event.timestamp),
            )

            is Event.FileEvent.ChangeLost -> ControlEventRow(
                id = event.id,
                kind = ControlEventKind.CHANGE_LOST,
                name = event.fileName,
                otherFilesCount = 0,
                timestampLabel = TimestampUtil.formatTimestamp(event.timestamp),
            )

            is Event.AppEvent.Registered -> ControlEventRow(
                id = event.id,
                kind = ControlEventKind.APP_REGISTERED,
                name = event.appName,
                otherFilesCount = 0,
                timestampLabel = TimestampUtil.formatTimestamp(event.timestamp),
            )

            is Event.AppEvent.Removed -> ControlEventRow(
                id = event.id,
                kind = ControlEventKind.APP_REMOVED,
                name = event.appName,
                otherFilesCount = 0,
                timestampLabel = TimestampUtil.formatTimestamp(event.timestamp),
            )
        }
    }
}
