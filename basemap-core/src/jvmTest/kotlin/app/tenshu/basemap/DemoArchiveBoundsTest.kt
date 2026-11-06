package app.tenshu.basemap

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AM-4b against the **real bundled archive**, not a synthetic header.
 *
 * The unit tests above prove the rule; this proves the *input* to it, which is the half that can
 * quietly stop being true: the coverage check is only as good as the bbox the demo archive actually
 * declares, and nothing else in the build reads it. A regenerated extract with a different — or an
 * unset — bbox would silently turn every hero outside its area schematic, or turn the check off.
 *
 * ⚠ The archive is opened **by relative path in the Android asset tree**, the idiom
 * `:ui-map`'s `RailTileReaderRealArchiveTest` established: that tree is the single source of truth
 * and every consumer (the iOS bundle, the Android assets) mirrors it, so a test that reads a copy
 * would be testing the copy.
 */
class DemoArchiveBoundsTest {

    private val archive = File("../ui-map/src/androidMain/assets/tenshu_basemap.pmtiles")

    @Test
    fun theBundledDemoDeclaresFlorence() {
        assertTrue(archive.exists(), "demo archive missing at ${archive.absolutePath}")
        val header = archive.inputStream().use { it.readNBytes(127) }

        val bounds = assertNotNull(
            basemapBoundsFromPmtilesHeader(header),
            "the bundled demo declares no bbox — the AM-4b coverage check is INERT without it, and " +
                "every hero silently reverts to pre-AM-4b behaviour",
        )

        // Florence sits at roughly 43.77 N, 11.25 E. Asserted as a loose box rather than exact
        // numbers so re-cutting the extract does not fail this, while moving it to another city does.
        assertTrue(
            bounds.minLatitude > 43.0 && bounds.maxLatitude < 44.5,
            "unexpected latitude span: $bounds",
        )
        assertTrue(
            bounds.minLongitude > 10.5 && bounds.maxLongitude < 12.0,
            "unexpected longitude span: $bounds",
        )
    }

    @Test
    fun aFlorenceHeroDrawsAndALisbonHeroDoesNot() {
        // The end-to-end statement of the bug, against the real bbox: the same call that used to
        // hand Lisbon a Florence archive (and render a route on an empty background) now answers
        // Schematic, which is the branch the AM-4 affordance hangs off.
        val header = archive.inputStream().use { it.readNBytes(127) }
        val demo = assertNotNull(basemapBoundsFromPmtilesHeader(header))

        val pontevecchio = boundsOf(listOf(BasemapCoord(43.7679, 11.2531)))
        val belem = boundsOf(listOf(BasemapCoord(38.6979, -9.2060)))

        assertEquals(
            BasemapSource.BundledDemo,
            resolveBasemapSource(null, false, true, demoBounds = demo, target = pontevecchio),
        )
        assertEquals(
            BasemapSource.Schematic,
            resolveBasemapSource(null, false, true, demoBounds = demo, target = belem),
        )
    }
}
