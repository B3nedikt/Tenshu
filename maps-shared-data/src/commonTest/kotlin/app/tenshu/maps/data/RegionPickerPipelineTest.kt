package app.tenshu.maps.data

import app.tenshu.maps.model.RegionItem
import app.tenshu.maps.model.GeoPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the Maps region picker end-to-end over the in-process proxy (`InProcessMapRegionProxy`):
 * the client-backed [ClientRegionRepository] reads `catalog()` + `installed()` through a real
 * `TenshuClient`, joins them, and `delete()` evicts — the closest headless approximation of the
 * picker running over the real service. Proves the whole pipe: repo → client → proxy op → store.
 */
class RegionPickerPipelineTest {

    @Test
    fun catalogJoinsInstalledStateAndDeleteEvicts() = runTest {
        val proxy = InProcessMapRegionProxy(serviceScope = backgroundScope)
        val repo = proxy.repository

        val regions = repo.regions().regionsOrFail()
        assertEquals(setOf("de-bayern", "at", "ch"), regions.map { it.regionId }.toSet())

        // The dev seed pre-installs "at"; the others are available.
        assertTrue(regions.single { it.regionId == "at" }.installed)
        assertFalse(regions.single { it.regionId == "de-bayern" }.installed)
        assertEquals(210_000_000L, regions.single { it.regionId == "de-bayern" }.byteSize)

        // Deleting the installed region clears its install state.
        repo.delete("at")
        val after = repo.regions().regionsOrFail()
        assertFalse(after.single { it.regionId == "at" }.installed)

        // The "Get" flow downloads an available region (simulated in the dev graph) → installed.
        assertFalse(repo.regions().regionsOrFail().single { it.regionId == "de-bayern" }.installed)
        assertTrue(repo.download("de-bayern"))
        assertTrue(repo.regions().regionsOrFail().single { it.regionId == "de-bayern" }.installed)
    }

    @Test
    fun streamResolvesTheRegionCoveringThePlace_notMerelyTheFirstOne() = runTest {
        val proxy = InProcessMapRegionProxy(serviceScope = backgroundScope)
        val repo = proxy.repository

        // The catalog is ordered de-bayern, at, ch — so "the first region" (the old behaviour) is
        // always de-bayern, whatever the place's coordinate.
        val bern = GeoPoint(latitude = 46.95, longitude = 7.44)   // Switzerland
        val wien = GeoPoint(latitude = 48.21, longitude = 16.37)  // Austria
        val münchen = GeoPoint(latitude = 48.14, longitude = 11.58) // Bayern

        assertEquals("ch", repo.renderableStream(bern)?.regionId)
        assertEquals("at", repo.renderableStream(wien)?.regionId)
        assertEquals("de-bayern", repo.renderableStream(münchen)?.regionId)

        // A place outside every coverage box (and a place with no coordinate at all) still gets a
        // basemap — the terminus falls back to the first catalog region rather than blanking.
        val reykjavik = GeoPoint(latitude = 64.15, longitude = -21.94)
        assertEquals("de-bayern", repo.renderableStream(reykjavik)?.regionId)
        assertEquals("de-bayern", repo.renderableStream(null)?.regionId)
    }
}


/**
 * ⚠ Fails the test rather than degrading, because a pipeline test that quietly accepted
 * [RegionsRead.Unavailable] would pass against a proxy serving nothing — which is the state this
 * file exists to distinguish from an empty catalogue.
 */
private fun RegionsRead.regionsOrFail(): List<RegionItem> = when (this) {
    is RegionsRead.Regions -> regions
    is RegionsRead.Unavailable ->
        throw AssertionError("expected a catalogue, got Unavailable: $detail")
}
