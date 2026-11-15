package app.tenshu.drive.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ The web's Drive settings (`tenshu-sdk-js` `ViewSettings.kt`, web-apps-layout.md L6) write the SAME
 * device rows native Drive reads — [DrivePreferences.ListSort] and [DrivePreferences.ListViewMode] —
 * but `drive-shared-logic` has no JS target, so the SDK re-declares them. This reads that file and
 * fails if the ids, scope, defaults or the codec's vocabulary drift from native's.
 */
class WebDriveSettingsMatchNativeTest {

    private val sdk: String by lazy {
        // The module's own directory is the working directory of its test task.
        val file = File("../tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/ViewSettings.kt")
        assertTrue(file.isFile, "ViewSettings.kt not found from ${File(".").absolutePath}")
        file.readText()
    }

    @Test
    fun theSortAndViewModeRowsAreNatives() {
        assertTrue(
            sdk.contains("id = \"${DrivePreferences.ListSort.id}\", scope = PreferenceScope.${DrivePreferences.ListSort.scope}, default = \"${DrivePreferences.ListSort.default}\""),
            "the SDK's sort row must be native's ListSort",
        )
        assertTrue(
            sdk.contains("id = \"${DrivePreferences.ListViewMode.id}\", scope = PreferenceScope.${DrivePreferences.ListViewMode.scope}, default = \"${DrivePreferences.ListViewMode.default}\""),
            "the SDK's view-mode row must be native's ListViewMode",
        )
    }

    @Test
    fun theCodecsVocabularyIsNatives() {
        val keys = Regex("""SORT_KEYS = setOf\(([^)]*)\)""").find(sdk)!!.groupValues[1]
            .split(",").map { it.trim().trim('"') }.toSet()
        assertEquals(FileSortKey.entries.map { it.name }.toSet(), keys, "the SDK must know exactly native's sort keys")
        val modes = Regex("""VIEW_MODES = setOf\(([^)]*)\)""").find(sdk)!!.groupValues[1]
            .split(",").map { it.trim().trim('"') }.toSet()
        assertEquals(FileViewMode.entries.map { it.name }.toSet(), modes, "the SDK must know exactly native's view modes")
        // Native's encoding, by example: what the SDK writes must decode the same way here.
        assertEquals(FileSort(FileSortKey.MODIFIED, descending = true), "MODIFIED:desc".decodeSort())
        assertEquals("NAME:asc", FileSort(FileSortKey.NAME, descending = false).encode())
    }
}
