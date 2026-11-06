package app.tenshu.ui.map

import app.tenshu.vectortile.PmtilesDirectory
import app.tenshu.vectortile.MvtDecoder
import app.tenshu.vectortile.MvtGeometryType
import app.tenshu.vectortile.toLonLat
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end over the **real shipped archive**: PMTiles header → root directory → tile byte range →
 * gunzip → [MvtDecoder] → assertions about actual Firenze geography.
 *
 * Why this exists separately from `:vector-tile`'s synthetic suite: those tests pin the *spec*, and
 * would keep passing against a decoder that is self-consistently wrong. This one pins *reality* —
 * bytes produced by Planetiler, shipped in the APK, read the way production would read them. Every
 * earlier slice in this track lacked exactly this, and the M8 review showed how much a green suite
 * over self-authored fixtures can hide.
 *
 * It also stands as the executable record of the §13.2 tile-content survey: the `transit` layer is
 * real and rich, and — the finding that killed T2-as-designed — **there are no railway-station POIs**.
 */
class MvtRealArchiveTest {

    /** The demo archive, relative to the `:ui-map` module directory (Gradle's test working dir). */
    private val archive = File("src/androidMain/assets/tenshu_basemap.pmtiles")

    /** A z14 tile over central Firenze, inside the demo's z12–14 bounds. */
    private val z = 14
    private val tileX = 8704L
    private val tileY = 5971L

    private fun readRange(offset: Long, length: Int): ByteArray =
        RandomAccessFile(archive, "r").use { raf ->
            raf.seek(offset)
            ByteArray(length).also { raf.readFully(it) }
        }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    /** header → root dir → tile range → decompressed tile bytes. The production read path. */
    private fun decompressedTile(): ByteArray {
        val header = PmtilesDirectory.parseHeader(readRange(0, PmtilesDirectory.HEADER_BYTES))

        // The demo's directories and tiles are both gzip; assert rather than assume, since a
        // future rebuild could change either and the failure would otherwise be "noise in, noise out".
        assertEquals(2, header.internalCompression, "expected gzip internal compression")
        assertEquals(2, header.tileCompression, "expected gzip tile compression")
        assertEquals(1, header.tileType, "expected MVT tile type")

        val rootDir = PmtilesDirectory.decodeDirectory(
            gunzip(readRange(header.rootDirOffset, header.rootDirLength.toInt())),
        )
        val tileId = PmtilesDirectory.zxyToTileId(z, tileX, tileY)
        val range = PmtilesDirectory.tileRange(header, rootDir, tileId) { r ->
            PmtilesDirectory.decodeDirectory(gunzip(readRange(r.start, r.length.toInt())))
        }
        assertTrue(range != null, "tile $z/$tileX/$tileY not found in the demo archive")
        return gunzip(readRange(range!!.start, range.length.toInt()))
    }

    @Test
    fun decodesTheShippedArchivesLayers() {
        assertTrue(archive.exists(), "demo archive missing at ${archive.absolutePath}")

        val layers = MvtDecoder.decode(decompressedTile())
        val names = layers.map { it.name }.toSet()

        // The 13-layer Protomaps v4 basemap schema, as built by Planetiler.
        assertTrue("transit" in names, "no transit layer; got $names")
        assertTrue("roads" in names, "no roads layer; got $names")
        assertTrue("pois" in names, "no pois layer; got $names")

        layers.forEach {
            assertEquals(4096, it.extent, "${it.name} extent")
            assertEquals(2, it.version, "${it.name} MVT version")
            assertTrue(it.features.isNotEmpty(), "${it.name} decoded zero features")
        }
    }

    @Test
    fun theTransitLayerCarriesRealRailGeometry() {
        val transit = MvtDecoder.decode(decompressedTile()).single { it.name == "transit" }

        // Every transit feature is a line; a rail map-match walks these.
        assertTrue(transit.features.all { it.type == MvtGeometryType.LINESTRING })

        val rail = transit.features.filter { it.tagIs("railway", "rail") }
        val tram = transit.features.filter { it.tagIs("railway", "tram") }
        assertTrue(rail.isNotEmpty(), "no railway=rail features")
        assertTrue(tram.isNotEmpty(), "no railway=tram features — trams ride this layer too")

        // ⚠ `service` marks yards/sidings/crossovers — track a train does not *run* on. A matcher
        // that ignores this will happily match a depot.
        assertTrue(
            transit.features.any { it.tag("service") != null },
            "expected some service=yard/siding features to exclude",
        )

        // Geometry actually lands in Firenze once projected — the check that catches a mirrored
        // or mis-scaled projection, which self-consistent synthetic tests cannot.
        val point = rail.first { it.geometry.isNotEmpty() }.geometry.first().first()
        val (lon, lat) = transit.toLonLat(point, z, tileX, tileY)
        assertTrue(lon in 11.2..11.35, "lon $lon outside Firenze")
        assertTrue(lat in 43.7..43.85, "lat $lat outside Firenze")
    }

    @Test
    fun thisBundledArchiveHasNoRailwayStationPois() {
        // ⚠⚠ **What this can and cannot conclude.** It surveys the *bundled Firenze demo* archive, and
        // that archive really has no railway POI — not even for Santa Maria Novella, a major terminus.
        // The §13.2 conclusion drawn from it, "the basemap emits no station POIs at all", generalised
        // one Protomaps generation to every cut and was **false**: the deployed `de-bayern` archive
        // carries named `station`/`halt`/`stop`/`tram_stop`/`platform`, which `StopTileReader` reads
        // (§28.2). So this pins a fact about *this file*, which is all a bundled-asset test can pin.
        //
        // ⚠ The filter previously read `pmap:kind` only. Production spells it plain `kind`, so a
        // `kind=station` POI would have slipped through unnoticed and this test would have gone on
        // reporting "none" after the very change it exists to detect. Both namings are checked now.
        val pois = MvtDecoder.decode(decompressedTile()).single { it.name == "pois" }

        val stationKinds = setOf("station", "halt", "stop", "stop_position", "tram_stop", "platform")
        val railwayish = pois.features.filter { f ->
            val kind = f.tag("kind") ?: f.tag("pmap:kind")
            f.tags.keys.any { it.equals("railway", ignoreCase = true) } ||
                kind?.contains("railway") == true ||
                kind in stationKinds
        }
        assertEquals(
            emptyList(), railwayish,
            "a station POI appeared in the bundled archive — it was regenerated from a newer " +
                "Protomaps cut; StopTileReader can now serve the demo region too",
        )
    }
}
