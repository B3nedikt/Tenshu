package org.tenshu.sdk.ios

import app.tenshu.control.data.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The `Event` → [ControlEventRow] flattening that Control's SwiftUI feed renders.
 *
 * Runs on `iosSimulatorArm64Test`, which is the point: it exercises the mapping on the same
 * K/N target the framework ships, so a shared-model change that breaks the feed fails here
 * rather than in a device build nobody runs.
 *
 * ⚠ What this canNOT catch is the Swift half — whether `HomeView` renders each kind, or calls
 * `presenter.get` to page. Nothing in the Kotlin gates compiles Swift; that is
 * `ios-smoke.sh --build-only control` plus a look at the screen.
 */
class ControlEventRowTest {

    private val t = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun an_added_file_and_an_added_folder_are_different_kinds() {
        // `isFolder` survives the flattening as the kind — the feed's title ("File Added" vs
        // "Folder Added") and Compose's "Added file" vs "Added folder" prefix come from this bit.
        val file = ControlEventRow.from(
            Event.FileEvent.Added("1", t, "/Backup/photo.jpg", isFolder = false),
        )
        val folder = ControlEventRow.from(
            Event.FileEvent.Added("2", t, "/Backup/Trip", isFolder = true),
        )

        assertEquals(ControlEventKind.FILE_ADDED, file.kind)
        assertEquals(ControlEventKind.FOLDER_ADDED, folder.kind)
        assertEquals("/Backup/photo.jpg", file.name)
        assertEquals(0, file.otherFilesCount)
    }

    @Test
    fun a_burst_carries_its_first_name_and_the_others_count() {
        // otherFilesCount is what drives the singular/plural sentence in Swift, so a burst that
        // lost its count would render "and 0 other files added".
        val row = ControlEventRow.from(
            Event.FileEvent.MultipleAdded("3", t, "a.txt", otherFilesCount = 4),
        )

        assertEquals(ControlEventKind.MULTIPLE_FILES_ADDED, row.kind)
        assertEquals("a.txt", row.name)
        assertEquals(4, row.otherFilesCount)
    }

    @Test
    fun deletions_and_app_events_map_to_their_kinds() {
        assertEquals(
            ControlEventKind.FILE_DELETED,
            ControlEventRow.from(Event.FileEvent.Deleted("4", t, "old.txt")).kind,
        )
        assertEquals(
            ControlEventKind.FOLDER_DELETED,
            ControlEventRow.from(Event.FileEvent.Deleted("4b", t, "/Taxes", isFolder = true)).kind,
        )
        assertEquals(
            ControlEventKind.APP_REGISTERED,
            ControlEventRow.from(Event.AppEvent.Registered("5", t, "Drive")).kind,
        )
        assertEquals(
            ControlEventKind.APP_REMOVED,
            ControlEventRow.from(Event.AppEvent.Removed("6", t, "Notes")).kind,
        )
    }

    @Test
    fun the_id_is_preserved_because_it_is_the_swiftui_list_identity() {
        // `ForEach(…, id: \.element.id)` — a dropped or duplicated id makes SwiftUI reuse the
        // wrong row on every feed update, which looks like corrupted content rather than a
        // key bug. Compose keys on the same field (`itemKey { it.id }`).
        val ids = listOf(
            Event.FileEvent.Added("a", t, "x", isFolder = false),
            Event.FileEvent.MultipleAdded("b", t, "y", otherFilesCount = 1),
            Event.FileEvent.Deleted("c", t, "z"),
            Event.AppEvent.Registered("d", t, "App"),
            Event.AppEvent.Removed("e", t, "App"),
        ).map { ControlEventRow.from(it).id }

        assertEquals(listOf("a", "b", "c", "d", "e"), ids)
    }

    @Test
    fun the_timestamp_is_formatted_by_the_shared_formatter_not_left_raw() {
        // Asserting the shape, not the exact text: the label is NSDateFormatter output at the
        // simulator's locale, so pinning a literal would pin the test host's region. What must
        // hold is that something locale-formatted came back — a raw epoch or an empty string
        // both mean the shared TimestampUtil was bypassed.
        val label = ControlEventRow.from(Event.FileEvent.Added("1", t, "x", isFolder = false))
            .timestampLabel

        assertTrue(label.isNotBlank(), "expected a formatted timestamp")
        assertTrue(
            label != t.toEpochMilliseconds().toString() && label != t.toString(),
            "expected formatted output, got a raw instant: $label",
        )
    }
}
