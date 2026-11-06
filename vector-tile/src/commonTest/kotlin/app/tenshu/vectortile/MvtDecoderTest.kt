package app.tenshu.vectortile

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decoder coverage over **synthetic** tiles built by [MvtWriter] below — the wire format exercised
 * exactly, in isolation. The complement is `:ui-map`'s `MvtRealArchiveTest`, which runs this decoder
 * over the real shipped Protomaps archive; synthetic tests pin the spec, the real one pins reality.
 *
 * The cases that matter most are the geometry ones. MVT's command stream has a running cursor that
 * persists **across** commands, and resetting it on each `MoveTo` is the classic mis-implementation —
 * it yields geometry that looks plausible (right shape, right scale) but sits in the wrong place, so
 * a map-match would silently never hit.
 */
class MvtDecoderTest {

    @Test
    fun decodesLayerMetadataAndTags() {
        val tile = MvtWriter.tile {
            layer(name = "transit", extent = 4096, version = 2) {
                feature(type = 2, tags = mapOf("railway" to "rail", "service" to "siding")) {
                    moveTo(10, 20)
                    lineTo(5, 0)
                }
            }
        }

        val layers = MvtDecoder.decode(tile)
        assertEquals(1, layers.size)
        val layer = layers.single()
        assertEquals("transit", layer.name)
        assertEquals(4096, layer.extent)
        assertEquals(2, layer.version)

        val f = layer.features.single()
        assertEquals(MvtGeometryType.LINESTRING, f.type)
        assertEquals("rail", f.tag("railway"))
        assertEquals("siding", f.tag("service"))
        assertTrue(f.tagIs("railway", "rail"))
        assertEquals(null, f.tag("absent"))
    }

    @Test
    fun geometryCursorPersistsAcrossCommands() {
        // MoveTo(10,10) then LineTo(+5,+5), LineTo(+5,+5) → absolute (10,10) (15,15) (20,20).
        // If the cursor were reset per command the second ring would restart at the delta itself.
        val tile = MvtWriter.tile {
            layer("t") {
                feature(type = 2) {
                    moveTo(10, 10)
                    lineTo(5, 5)
                    lineTo(5, 5)
                }
            }
        }
        val geom = MvtDecoder.decode(tile).single().features.single().geometry
        assertEquals(1, geom.size)
        assertEquals(listOf(MvtPoint(10, 10), MvtPoint(15, 15), MvtPoint(20, 20)), geom.single())
    }

    @Test
    fun aSecondMoveToStartsANewRingWithoutResettingTheCursor() {
        // Two parts. The second MoveTo's delta is relative to where the first part ENDED (20,20),
        // so part two begins at (25,25) — not at (5,5).
        val tile = MvtWriter.tile {
            layer("t") {
                feature(type = 2) {
                    moveTo(10, 10)
                    lineTo(10, 10)
                    moveTo(5, 5)
                    lineTo(5, 5)
                }
            }
        }
        val geom = MvtDecoder.decode(tile).single().features.single().geometry
        assertEquals(2, geom.size)
        assertEquals(listOf(MvtPoint(10, 10), MvtPoint(20, 20)), geom[0])
        assertEquals(listOf(MvtPoint(25, 25), MvtPoint(30, 30)), geom[1])
    }

    @Test
    fun closePathRepeatsTheRingsFirstPoint() {
        val tile = MvtWriter.tile {
            layer("t") {
                feature(type = 3) {
                    moveTo(0, 0)
                    lineTo(10, 0)
                    lineTo(0, 10)
                    closePath()
                }
            }
        }
        val ring = MvtDecoder.decode(tile).single().features.single().geometry.single()
        assertEquals(MvtPoint(0, 0), ring.first())
        assertEquals(MvtPoint(0, 0), ring.last())
        assertEquals(4, ring.size)
    }

    @Test
    fun negativeDeltasDecodeThroughZigZag() {
        val tile = MvtWriter.tile {
            layer("t") { feature(type = 2) { moveTo(100, 100); lineTo(-40, -60) } }
        }
        val geom = MvtDecoder.decode(tile).single().features.single().geometry.single()
        assertEquals(listOf(MvtPoint(100, 100), MvtPoint(60, 40)), geom)
    }

    @Test
    fun pointFeatureDecodesAsASinglePointRing() {
        val tile = MvtWriter.tile { layer("pois") { feature(type = 1) { moveTo(2048, 2048) } } }
        val f = MvtDecoder.decode(tile).single().features.single()
        assertEquals(MvtGeometryType.POINT, f.type)
        assertEquals(listOf(listOf(MvtPoint(2048, 2048))), f.geometry)
    }

    @Test
    fun decodesEveryValueType() {
        val tile = MvtWriter.tile {
            layer("t") {
                featureTyped(
                    type = 1,
                    typedTags = listOf(
                        "s" to MvtWriter.V.Str("hello"),
                        "i" to MvtWriter.V.Int(42),
                        "neg" to MvtWriter.V.SInt(-7),
                        "b" to MvtWriter.V.Bool(true),
                        "d" to MvtWriter.V.Dbl(1.5),
                    ),
                ) { moveTo(1, 1) }
            }
        }
        val tags = MvtDecoder.decode(tile).single().features.single().tags
        assertEquals(MvtValue.Str("hello"), tags["s"])
        assertEquals(MvtValue.Int64(42), tags["i"])
        assertEquals(MvtValue.Int64(-7), tags["neg"])
        assertEquals(MvtValue.Bool(true), tags["b"])
        assertEquals(MvtValue.Num(1.5), tags["d"])
    }

    // ── robustness: tile bytes are untrusted CDN input ───────────────────────

    @Test
    fun unknownFieldsAreSkippedSoAFutureSchemaStillDecodes() {
        val tile = MvtWriter.tile {
            layer("t", extraUnknownField = true) { feature(type = 2) { moveTo(1, 1); lineTo(1, 1) } }
        }
        val layer = MvtDecoder.decode(tile).single()
        assertEquals("t", layer.name)
        assertEquals(1, layer.features.size)
    }

    @Test
    fun malformedInputYieldsEmptyNeverThrows() {
        assertEquals(emptyList(), MvtDecoder.decode(byteArrayOf()))
        assertEquals(emptyList(), MvtDecoder.decode(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
        // A valid tag claiming a length that runs past the end.
        assertEquals(emptyList(), MvtDecoder.decode(byteArrayOf(0x1A, 0x7F, 0x01)))
    }

    @Test
    fun truncationLeavesWhateverParsedCleanly() {
        val full = MvtWriter.tile {
            layer("t") { feature(type = 2) { moveTo(10, 10); lineTo(10, 10) } }
        }
        // Chop the tail: the decoder must not throw, and must not invent geometry.
        for (cut in 1 until full.size) {
            val partial = full.copyOfRange(0, cut)
            val layers = MvtDecoder.decode(partial) // must not throw
            assertTrue(layers.size <= 1)
        }
    }

    @Test
    fun aVarintLongerThanSixtyFourBitsIsRefused() {
        // Ten 0x80 continuation bytes then a terminator — a classic shift-overflow probe.
        val bomb = ByteArray(12) { 0x80.toByte() }.also { it[11] = 0x01 }
        assertEquals(emptyList(), MvtDecoder.decode(bomb))
    }

    // ── projection ───────────────────────────────────────────────────────────

    @Test
    fun tileOriginProjectsToTheTilesNorthWestCorner() {
        // z14/8704/5971 is the Firenze demo tile. Tile y runs downward, so (0,0) is the NORTH-west
        // corner — inverting that mirrors every result across the equator.
        val layer = MvtLayer("t", version = 2, extent = 4096, features = emptyList())
        val nw = layer.toLonLat(MvtPoint(0, 0), z = 14, tileX = 8704, tileY = 5971)
        assertTrue(abs(nw.lon - 11.25) < 0.001, "lon was ${nw.lon}")
        // 43.787°N — verified against an independent Web-Mercator inverse, and correctly inside
        // Firenze (43.77°N), which is the city this demo archive covers.
        assertTrue(abs(nw.lat - 43.7870) < 0.001, "lat was ${nw.lat}")

        // Moving down-tile (+y) must go SOUTH, i.e. latitude decreases.
        val south = layer.toLonLat(MvtPoint(0, 4095), z = 14, tileX = 8704, tileY = 5971)
        assertTrue(south.lat < nw.lat, "y+ must head south: ${south.lat} vs ${nw.lat}")
        // Moving right (+x) must go EAST.
        val east = layer.toLonLat(MvtPoint(4095, 0), z = 14, tileX = 8704, tileY = 5971)
        assertTrue(east.lon > nw.lon, "x+ must head east")
    }
}
