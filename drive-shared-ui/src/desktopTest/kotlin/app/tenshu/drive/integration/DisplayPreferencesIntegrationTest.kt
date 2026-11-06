package app.tenshu.drive.integration

import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FileSortKey
import app.tenshu.drive.data.FileViewMode
import app.tenshu.drive.data.TenshuDisplayPreferences
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.common.TenshuClient
import kotlin.test.assertEquals

/**
 * End-to-end round-trip of [TenshuDisplayPreferences] over the real in-process
 * preference service: a `setViewMode` / `setSort` write must resolve this device's
 * identity, persist the per-device row, and re-surface through the reactive read —
 * the persistence that makes the listing's sort + view mode survive navigation and
 * restart (`docs/proposals/drive-listing-sort-display.md` §6).
 */
class DisplayPreferencesIntegrationTest {

    private lateinit var prefs: TenshuDisplayPreferences

    private lateinit var appKoin: AppIntegrationKoin

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
        prefs = TenshuDisplayPreferences(appKoin.get<TenshuClient>())
    }

    @After
    fun tearDown() {
        appKoin.stop()
    }

    @Test
    fun viewMode_roundTripsThroughTheDeviceRow() = runBlocking {
        assertEquals(FileViewMode.LIST, prefs.observeViewMode().first(), "default is list")

        prefs.setViewMode(FileViewMode.GRID)
        val got = withTimeout(5_000) { prefs.observeViewMode().first { it == FileViewMode.GRID } }
        assertEquals(FileViewMode.GRID, got, "grid persisted + read back")
    }

    @Test
    fun sort_roundTripsThroughTheDeviceRow() = runBlocking {
        // ⚠ This used to end by clearing back to natural order (`setSort(null)`). SP-6 of
        // `drive-sorted-folder-paging.md` retired that mode (D5): there is no "no sort", so a
        // device that has never chosen reads D6's default instead of a null.
        assertEquals(FileSort.Default, prefs.observeSort().first(), "unset device reads the default")

        val chosen = FileSort(FileSortKey.SIZE, descending = true)
        prefs.setSort(chosen)
        val stored = withTimeout(5_000) { prefs.observeSort().first { it == chosen } }
        assertEquals(chosen, stored, "sort persisted + read back")

        val back = FileSort(FileSortKey.NAME, descending = false)
        prefs.setSort(back)
        val restored = withTimeout(5_000) { prefs.observeSort().first { it == back } }
        assertEquals(back, restored, "a later choice replaces the stored one")
    }
}
