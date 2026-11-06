package app.tenshu.drive.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The [FileSort] / [FileViewMode] <-> stored-`String` codec behind
 * [DisplayPreferences] (the [DrivePreferences] rows persist a self-describing
 * `TString`). Pins the encoding, the natural-order (`null`) sentinel, both
 * directions, and safe fallback on a malformed / unknown stored value.
 */
class DisplayPreferencesCodecTest {

    @Test
    fun sortRoundTrips_forEveryKeyAndDirection() {
        for (key in FileSortKey.entries) {
            for (descending in listOf(false, true)) {
                val sort = FileSort(key, descending)
                assertEquals(sort, sort.encode().decodeSort(), "round-trip $key descending=$descending")
            }
        }
    }

    @Test
    fun anAbsentOrEmptyStoredValueReadsAsTheDefault() {
        // ⚠ `""` used to decode to `null`, i.e. natural (store) order — a listing mode of its own.
        // SP-6 of `drive-sorted-folder-paging.md` retired that mode (D5), so an empty row means
        // only "this device has never chosen" and lands on D6's default. That is also the whole
        // of SP-6's migration: no stored value needs rewriting.
        assertEquals(FileSort.Default, "".decodeSort())
        assertEquals(FileSort.Default, "   ".decodeSort())
        assertEquals(FileSortKey.NAME, FileSort.Default.key, "D6: Name…")
        assertEquals(false, FileSort.Default.descending, "…ascending")
    }

    @Test
    fun sortEncodingIsTheDocumentedShape() {
        assertEquals("NAME:asc", FileSort(FileSortKey.NAME, descending = false).encode())
        assertEquals("SIZE:desc", FileSort(FileSortKey.SIZE, descending = true).encode())
    }

    @Test
    fun malformedSortDecodesToTheDefault() {
        assertEquals(FileSort.Default, "BOGUS".decodeSort())
        assertEquals(FileSort.Default, "BOGUS:desc".decodeSort())
    }

    @Test
    fun sortWithoutDirectionDefaultsAscending() {
        // A bare "<KEY>" (no ":asc/desc") decodes ascending — the safe default.
        assertEquals(FileSort(FileSortKey.NAME, descending = false), "NAME".decodeSort())
    }

    @Test
    fun viewModeDecode_isNameBased_withListFallback() {
        assertEquals(FileViewMode.GRID, FileViewMode.GRID.name.decodeViewMode())
        assertEquals(FileViewMode.LIST, FileViewMode.LIST.name.decodeViewMode())
        assertEquals(FileViewMode.LIST, "BOGUS".decodeViewMode())
        assertEquals(FileViewMode.LIST, "".decodeViewMode())
    }
}
