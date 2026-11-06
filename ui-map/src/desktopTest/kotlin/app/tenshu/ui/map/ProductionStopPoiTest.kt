package app.tenshu.ui.map

import app.tenshu.vectortile.PmtilesDirectory
import app.tenshu.vectortile.StopTileReader
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **§28.2 — the offline stop tier against the tiles that actually ship.**
 *
 * `StopTileReaderTest` pins the selection rules on synthetic layers. This asks the only question those
 * cannot: **does the deployed basemap answer the two real sites at all?** It is the test that decides
 * whether the offline tier is a feature or an empty code path — and it is the reason §13.2a's
 * "no station POIs anywhere" was caught as a demo-archive artefact rather than a fact.
 *
 * ### The fixture
 *
 * The same 13 MB bbox extract of the deployed `de-bayern.pmtiles` that `ProductionRailSchemaSurveyTest`
 * uses, cut by range requests from the live CDN. ⚠ **Not committed** (13 MB, derived), so this test
 * **skips** when it is absent and the suite stays green on a clean checkout. A skip is honest here in a
 * way it would not be for a behavioural test: the subject is an external dataset.
 *
 * ⚠ Coordinates are public landmarks (a plaza and a mainline station), never a private location.
 */
class ProductionStopPoiTest {

    private val archive = File("build/survey/munich.pmtiles")

    /** ⚠ Reproduce the fixture with the command `ProductionRailSchemaSurveyTest` documents. */
    private fun skipIfAbsent(): Boolean {
        if (archive.exists()) return false
        println("§28.2 stop-POI test SKIPPED — no archive at ${archive.absolutePath}")
        println("  reproduce: pmtiles extract https://tiles.service.tenshu.app/regions/2026-08/de-bayern.pmtiles \\")
        println("               ui-map/build/survey/munich.pmtiles --bbox=11.50,48.10,11.65,48.18")
        return true
    }

    private fun readRange(offset: Long, length: Int): ByteArray =
        RandomAccessFile(archive, "r").use { raf ->
            raf.seek(offset)
            ByteArray(length).also { raf.readFully(it) }
        }

    private fun decompress(bytes: ByteArray, compression: Int): ByteArray =
        if (compression == 2) GZIPInputStream(bytes.inputStream()).use { it.readBytes() } else bytes

    private fun nearestStop(lat: Double, lng: Double, withinM: Double) = runBlocking {
        StopTileReader.nearestStop(
            lat = lat,
            lng = lng,
            withinM = withinM,
            readRange = { offset, length -> runCatching { readRange(offset, length) }.getOrNull() },
            decompress = ::decompress,
        )
    }

    @Test
    fun theUBahnStationThatWasNamedMercedesBenzIsNameable() {
        if (skipIfAbsent()) return
        // The reported failure: a six-minute wait on this platform was labelled after a car showroom.
        val hit = nearestStop(lat = 48.1425, lng = 11.5775, withinM = 300.0)
        assertEquals("Odeonsplatz", hit?.name)
        // ⚠⚠ And it comes from the `station`, not from the `stop` node 2 m NEARER (90.1 m vs 92.6 m) —
        // real-data proof that tiering, not distance, decided it. Distance alone would answer `stop`.
        assertEquals("station", hit?.kind)
    }

    @Test
    fun theMainlineStationThatWasNamedInformationIsNameable() {
        if (skipIfAbsent()) return
        // The other reported failure: 27 minutes at Ostbahnhof labelled from an `information=board`.
        val hit = nearestStop(lat = 48.1272, lng = 11.6045, withinM = 300.0)
        assertTrue(
            hit?.name?.contains("Ost") == true,
            "expected a München Ost / Ostbahnhof name, got ${hit?.name}",
        )
    }

    @Test
    fun aStopIsNotInventedInAResidentialStreet() {
        // ⚠ The failure mode that matters more than a miss: naming a dwell after a station it was
        // merely near. A point in the Englischer Garten, 300 m from anything, must answer nothing.
        if (skipIfAbsent()) return
        assertNull(nearestStop(lat = 48.1520, lng = 11.5960, withinM = 60.0))
    }

    @Test
    fun theArchiveIsDeeperThanTheDemoOne() {
        // ⚠⚠ Pins the premise behind reading the *deepest available* zoom rather than a constant: the
        // bundled demo stops at z14 and this cut goes further, so a hard-coded 14 would silently read a
        // coarser tile than the one that ships.
        if (skipIfAbsent()) return
        val header = PmtilesDirectory.parseHeader(readRange(0, PmtilesDirectory.HEADER_BYTES))
        assertTrue(header.maxZoom >= 15, "maxZoom ${header.maxZoom}")
    }
}
