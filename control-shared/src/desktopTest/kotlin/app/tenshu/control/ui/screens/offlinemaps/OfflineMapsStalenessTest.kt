package app.tenshu.control.ui.screens.offlinemaps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The staleness rule behind Control's "Update available" row (`maps-region-delivery.md` §7.1 track 3b).
 */
class OfflineMapsStalenessTest {

    private fun region(residentSha: String?, catalogSha: String = "sha-v2") = offlineMapRegion(
        regionId = "ch", name = "Schweiz", byteSize = 120_000_000L,
        catalogSha256 = catalogSha, residentSha256 = residentSha,
    )

    @Test
    fun aResidentRegionWhoseBytesMovedOnInTheCatalogOffersAnUpdate() {
        val stale = region(residentSha = "sha-v1")
        assertTrue(stale.installed)
        assertTrue(stale.updateAvailable)
    }

    @Test
    fun aResidentRegionAtTheCatalogsCurrentBytesDoesNot() {
        val current = region(residentSha = "sha-v2")
        assertTrue(current.installed)
        assertFalse(current.updateAvailable, "same sha256 = same bytes; nothing to re-download")
    }

    @Test
    fun aRegionThatIsNotResidentIsNeverStale_itIsSimplyNotInstalled() {
        val absent = region(residentSha = null)
        assertFalse(absent.installed)
        assertFalse(absent.updateAvailable, "an uninstalled region offers a download, not an update")
    }

    // --- the catalog-availability rule -------------------------------------------------------

    private val residentAt = RegionFacts("at", "Österreich", 95_000_000L, "sha-at")
    private val catalogAt = RegionFacts("at", "Österreich", 95_000_000L, "sha-at")
    private val catalogBayern = RegionFacts("de-bayern", "Bayern", 420_000_000L, "sha-by")

    @Test
    fun anUnreadableCatalogStillReportsWhatIsOnTheDevice() {
        val snapshot = offlineMapsSnapshot(installed = listOf(residentAt), catalog = null)

        assertTrue(snapshot.catalogUnavailable)
        // The regression: this used to be an empty list, hiding the regions that still work offline.
        assertEquals(listOf("at"), snapshot.regions.map { it.regionId })
        assertTrue(snapshot.regions.single().installed)
        assertFalse(
            snapshot.regions.single().updateAvailable,
            "with no catalog there is nothing to compare against, so claiming an update is inventing evidence",
        )
    }

    @Test
    fun anEmptyCatalogIsNotTheSameAsAnUnreadableOne() {
        val snapshot = offlineMapsSnapshot(installed = emptyList(), catalog = emptyList())

        assertFalse(
            snapshot.catalogUnavailable,
            "the catalog was read and is genuinely empty — the screen may say so",
        )
        assertTrue(snapshot.regions.isEmpty())
    }

    @Test
    fun aReadableCatalogJoinsTheInstallIndex() {
        val snapshot = offlineMapsSnapshot(
            installed = listOf(residentAt),
            catalog = listOf(catalogAt, catalogBayern),
        )

        assertFalse(snapshot.catalogUnavailable)
        assertEquals(listOf("at", "de-bayern"), snapshot.regions.map { it.regionId })
        assertTrue(snapshot.regions.first { it.regionId == "at" }.installed)
        assertFalse(snapshot.regions.first { it.regionId == "de-bayern" }.installed)
    }
}
