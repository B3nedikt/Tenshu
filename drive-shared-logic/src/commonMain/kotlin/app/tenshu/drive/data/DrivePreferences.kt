package app.tenshu.drive.data

import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.StringAdapter

/**
 * Drive's app-specific synced preferences (`core-preference-service.md` §3.8 —
 * app preferences live in `:<app>-shared`). Both are **DEVICE-scoped**: the
 * folder-listing display is a per-device choice (a phone and a desktop can differ),
 * one synced row each, so it survives restart and rides to the same device across
 * sessions.
 *
 * Both ride the `String` adapter (a self-describing `TString` value) with the codec
 * living in [DisplayPreferences] — no new [org.tenshu.preference.PreferenceAdapter]
 * needed, and any consumer can read the raw value through the schema.
 */
object DrivePreferences {

    /**
     * The listing's "Sort by" selection, encoded by [DisplayPreferences]:
     * `"<KEY>:<asc|desc>"` (e.g. `"NAME:asc"`).
     *
     * ⚠ `""` used to mean **natural (store) order**, which was a listing mode of its own — the
     * fast unsorted path. SP-6 of `drive-sorted-folder-paging.md` retired that mode (D5), so the
     * empty default now means only "this device has not chosen yet" and decodes to
     * [FileSort.Default], Name ascending (D6). Nothing needs migrating: a device that stored a
     * real selection keeps it, and one that stored `""` — or has no row at all — lands on the
     * default it would have been given anyway.
     */
    val ListSort = Preference(
        id = "drive.listing.sort",
        scope = PreferenceScope.DEVICE,
        default = "",
        adapter = StringAdapter,
    )

    /**
     * The listing's list-vs-grid display, stored as a [FileViewMode] name
     * (`"LIST"` / `"GRID"`). Default `LIST`.
     */
    val ListViewMode = Preference(
        id = "drive.listing.viewMode",
        scope = PreferenceScope.DEVICE,
        default = FileViewMode.LIST.name,
        adapter = StringAdapter,
    )
}
