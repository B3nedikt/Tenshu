package app.tenshu.ui.map

import app.tenshu.vectortile.GeoBounds
import app.tenshu.vectortile.LonLatPolyline
import app.tenshu.vectortile.RailMatcher
import app.tenshu.vectortile.RailTileReader
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **whole proxy-side pipeline** against the real shipped archive: bbox → covering tiles → byte
 * ranges → gunzip → MVT decode → running-track filter → projected rail lines.
 *
 * This is the piece M9-4 adds, exercised exactly as the proxy will run it — the only difference being
 * where the bytes come from (a file here, `StreamingRegionSource` there). Both `decompress` and
 * `readRange` are injected for precisely that reason.
 */
class RailTileReaderRealArchiveTest {

    private val archive = File("src/androidMain/assets/tenshu_basemap.pmtiles")

    private suspend fun readRange(offset: Long, length: Int): ByteArray? =
        runCatching {
            RandomAccessFile(archive, "r").use { raf ->
                raf.seek(offset)
                ByteArray(length).also { raf.readFully(it) }
            }
        }.getOrNull()

    private fun decompress(bytes: ByteArray, compression: Int): ByteArray =
        if (compression == 2) GZIPInputStream(bytes.inputStream()).use { it.readBytes() } else bytes

    /** Central Firenze — the demo archive's coverage. */
    private val firenze = GeoBounds(minLon = 11.24, minLat = 43.77, maxLon = 11.27, maxLat = 43.79)

    @Test
    fun readsRailLinesForABoundingBox() = runTest {
        assertTrue(archive.exists(), "demo archive missing at ${archive.absolutePath}")

        val lines = RailTileReader.railLinesIn(firenze, ::readRange, ::decompress)
        assertTrue(lines.isNotEmpty(), "no rail lines read for a bbox over central Firenze")

        // Running track only — the §13.2a filter survived the whole pipeline.
        assertTrue(lines.all { it.kind in setOf("rail", "tram", "light_rail", "subway", "narrow_gauge") })
        assertTrue(lines.any { RailMatcher.isExclusiveTrack(it.kind) }, "expected some heavy rail")

        // Geometry landed in the right place.
        val p = lines.first().points.first()
        assertTrue(p.lon in 11.2..11.35, "lon ${p.lon} outside Firenze")
        assertTrue(p.lat in 43.7..43.85, "lat ${p.lat} outside Firenze")
    }

    @Test
    fun aBoxOutsideTheArchiveYieldsNothingRatherThanFailing() = runTest {
        val atlantic = GeoBounds(minLon = -30.0, minLat = 30.0, maxLon = -29.9, maxLat = 30.1)
        assertEquals(emptyList(), RailTileReader.railLinesIn(atlantic, ::readRange, ::decompress))
    }

    @Test
    fun anUnreadableArchiveYieldsNothingRatherThanThrowing() = runTest {
        assertEquals(emptyList(), RailTileReader.railLinesIn(firenze, { _, _ -> null }, ::decompress))
    }

    @Test
    fun theTileBudgetIsHonoured() = runTest {
        var reads = 0
        val counting: suspend (Long, Int) -> ByteArray? = { o, l -> reads++; readRange(o, l) }
        RailTileReader.railLinesIn(firenze, counting, ::decompress, maxTiles = 1)
        // header + root dir + at most one tile.
        assertTrue(reads <= 3, "maxTiles=1 should read at most 3 ranges, read $reads")
    }

    @Test
    fun linesSurviveTheWireCodecRoundTrip() = runTest {
        // The shape that crosses the proxy→app boundary.
        val line = RailTileReader.railLinesIn(firenze, ::readRange, ::decompress).first()
        val decoded = LonLatPolyline.decode(LonLatPolyline.encode(line.points))
        assertEquals(line.points.size, decoded.size)
        // E7 quantisation is the only loss — ~1 cm, far below anything matching cares about.
        for (i in line.points.indices) {
            assertTrue(kotlin.math.abs(decoded[i].lat - line.points[i].lat) < 1e-6)
            assertTrue(kotlin.math.abs(decoded[i].lon - line.points[i].lon) < 1e-6)
        }
    }
}
