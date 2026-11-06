package app.tenshu.vectortile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PmtilesDirectoryTest {

    /** The PMTiles v3 spec's worked Hilbert tile-id examples — the decoder's correctness anchor. */
    @Test
    fun zxyToTileId_matchesSpecExamples() {
        // z0 + the z1 Hilbert order (spec §Tile IDs): (0,0)→1 (0,1)→2 (1,1)→3 (1,0)→4.
        assertEquals(0L, PmtilesDirectory.zxyToTileId(0, 0, 0))
        assertEquals(1L, PmtilesDirectory.zxyToTileId(1, 0, 0))
        assertEquals(2L, PmtilesDirectory.zxyToTileId(1, 0, 1))
        assertEquals(3L, PmtilesDirectory.zxyToTileId(1, 1, 1))
        assertEquals(4L, PmtilesDirectory.zxyToTileId(1, 1, 0))
        // First id of zoom z is (0,0) with Hilbert d=0 → the accumulator (4^z - 1)/3.
        assertEquals(5L, PmtilesDirectory.zxyToTileId(2, 0, 0))
        assertEquals(21L, PmtilesDirectory.zxyToTileId(3, 0, 0))
        assertEquals(85L, PmtilesDirectory.zxyToTileId(4, 0, 0))
        // Real-world cross-check: the Firenze centre tile id observed on-device in the earlier
        // MapLibre-render investigation (z14/8704/5972).
        assertEquals(317221125L, PmtilesDirectory.zxyToTileId(14, 8704, 5972))
    }

    @Test
    fun parseHeader_readsV3Layout() {
        // The bundled Firenze demo's real 127-byte header (from an xxd dump).
        val h = ByteArray(PmtilesDirectory.HEADER_BYTES)
        "PMTiles".encodeToByteArray().copyInto(h)
        h[7] = 3
        fun putU64(o: Int, v: Long) { for (i in 0 until 8) h[o + i] = ((v shr (8 * i)) and 0xff).toByte() }
        putU64(8, 127); putU64(16, 138)          // root dir
        putU64(24, 265); putU64(32, 21713)       // metadata
        putU64(40, 21978); putU64(48, 0)         // leaf dirs (none)
        putU64(56, 21978); putU64(64, 1274304)   // tile data
        h[97] = 2  // internal_compression = gzip
        h[98] = 2  // tile_compression = gzip — distinct from the above, and unparsed before M9
        h[99] = 1  // tile_type = MVT
        h[100] = 12; h[101] = 14

        val header = PmtilesDirectory.parseHeader(h)
        assertEquals(127L, header.rootDirOffset)
        assertEquals(138L, header.rootDirLength)
        assertEquals(21978L, header.tileDataOffset)
        assertEquals(1274304L, header.tileDataLength)
        assertEquals(1296282L, header.archiveSize)
        assertEquals(2, header.internalCompression)
        // ⚠ Byte 98 is the *tile payload* compression, not the directory compression at 97. It went
        // unparsed until M9, which meant a would-be tile reader saw gzip bytes and no reason why.
        assertEquals(2, header.tileCompression)
        assertEquals(1, header.tileType)
        assertEquals(12, header.minZoom)
        assertEquals(14, header.maxZoom)
    }

    @Test
    fun decodeDirectory_readsColumnarVarintEntries() {
        // Two contiguous tile entries: id 5 (off 0, len 100) and id 6 (off 100, len 200).
        val dir = byteArrayOf(
            0x02,                    // count
            0x05, 0x01,              // tileId deltas → 5, 6
            0x01, 0x01,              // runLengths
            0x64, 0xC8.toByte(), 0x01, // lengths → 100, 200
            0x01, 0x00,              // offsets → 0, contiguous(=100)
        )
        val entries = PmtilesDirectory.decodeDirectory(dir)
        assertEquals(2, entries.size)
        assertEquals(PmtilesDirectory.Entry(5, 1, 0, 100), entries[0])
        assertEquals(PmtilesDirectory.Entry(6, 1, 100, 200), entries[1])
    }

    @Test
    fun coveringRanges_alwaysIncludesHeaderRootDirMetadata_andSomeTiles() {
        val header = PmtilesDirectory.Header(
            rootDirOffset = 127, rootDirLength = 138, metadataOffset = 265, metadataLength = 21713,
            leafDirsOffset = 21978, leafDirsLength = 0, tileDataOffset = 21978, tileDataLength = 1274304,
            internalCompression = 2, minZoom = 12, maxZoom = 14,
        )
        // A full z12..z14 grid over the Firenze centre so every covering tile resolves.
        val entries = buildList {
            for (z in 12..14) {
                val (cx, cy) = PmtilesDirectory.lonLatToTile(z, 11.2558, 43.7696)
                for (dx in -1..1) for (dy in -1..1) {
                    add(PmtilesDirectory.Entry(PmtilesDirectory.zxyToTileId(z, cx + dx, cy + dy), 1, 0, 1000))
                }
            }
        }.sortedBy { it.tileId }

        val ranges = PmtilesDirectory.coveringRanges(header, entries, lon = 11.2558, lat = 43.7696, marginTiles = 1)
        // header + root dir + metadata always present.
        assertTrue(ranges.any { it.start == 0L && it.length == 127L })
        assertTrue(ranges.any { it.start == 127L && it.length == 138L })
        assertTrue(ranges.any { it.start == 265L && it.length == 21713L })
        // Plus tile-data ranges for the covering tiles.
        assertTrue(ranges.count { it.start >= header.tileDataOffset } >= 9)
    }

    @Test
    fun coveringRangesForRoute_coversTheWholeCorridor_andDedupesSharedTiles() {
        val header = PmtilesDirectory.Header(
            rootDirOffset = 127, rootDirLength = 138, metadataOffset = 265, metadataLength = 21713,
            leafDirsOffset = 21978, leafDirsLength = 0, tileDataOffset = 21978, tileDataLength = 1274304,
            internalCompression = 2, minZoom = 12, maxZoom = 14,
        )
        // Two separated points (a route's ends) across central Firenze; a full grid around each so
        // every covering tile resolves.
        val route = listOf(11.2470 to 43.7690, 11.2585 to 43.7688)
        val entries = buildList {
            for (z in 12..14) {
                for ((lon, lat) in route) {
                    val (cx, cy) = PmtilesDirectory.lonLatToTile(z, lon, lat)
                    for (dx in -1..1) for (dy in -1..1) {
                        // Distinct per-tile offset (= tileId) so each covering tile yields a distinct
                        // byte range — makes the dedup assertion below meaningful.
                        val id = PmtilesDirectory.zxyToTileId(z, cx + dx, cy + dy)
                        add(PmtilesDirectory.Entry(id, 1, id, 1000))
                    }
                }
            }
        }.distinctBy { it.tileId }.sortedBy { it.tileId }

        val ranges = PmtilesDirectory.coveringRangesForRoute(header, entries, route, marginTiles = 1)
        // Prelude always present.
        assertTrue(ranges.any { it.start == 0L && it.length == 127L })
        assertTrue(ranges.any { it.start == 127L && it.length == 138L })
        assertTrue(ranges.any { it.start == 265L && it.length == 21713L })
        // Both endpoints' tiles are covered — at the low zoom they share the same tile (deduped to
        // one range), at the high zoom they diverge, so the corridor spans more tiles than a single
        // point would (> the 9-per-point a lone snippet fetches at one zoom).
        val tileRanges = ranges.filter { it.start >= header.tileDataOffset }
        assertTrue(tileRanges.size > 9, "route corridor should exceed a single-point cover")
        // No duplicate tile ranges (dedup by globally-unique tile id worked).
        assertEquals(tileRanges.size, tileRanges.distinct().size)
        // An empty route yields just the prelude.
        assertEquals(3, PmtilesDirectory.coveringRangesForRoute(header, entries, emptyList()).size)
    }

    @Test
    fun coveringRanges_resolvesEveryTileThatDescendsThroughTheSameLeaf() {
        // A big archive's root directory is mostly leaf pointers — the live `test-toscana` region's
        // root is 62 bytes for 22,018 tiles — so nearly every tile in a viewport descends through the
        // SAME leaf. A "seen" set that skipped the second visit resolved only the first tile per leaf
        // and silently dropped the rest; MapLibre then read holes where the directory promised tiles
        // and failed them with `invalid tag exception`. This is that regression, in miniature.
        val header = PmtilesDirectory.Header(
            rootDirOffset = 127, rootDirLength = 40, metadataOffset = 167, metadataLength = 100,
            leafDirsOffset = 300, leafDirsLength = 200, tileDataOffset = 500, tileDataLength = 100_000,
            internalCompression = 1, minZoom = 12, maxZoom = 12,
        )
        val (cx, cy) = PmtilesDirectory.lonLatToTile(12, 11.33, 43.32)
        val covering = buildList {
            for (dx in -1..1) for (dy in -1..1) add(PmtilesDirectory.zxyToTileId(12, cx + dx, cy + dy))
        }.sorted()

        // ONE root entry: a leaf pointer (runLength == 0) covering the whole id space.
        val rootDir = listOf(PmtilesDirectory.Entry(tileId = 0, runLength = 0, offset = 0, length = 200))
        // The leaf holds every covering tile, each a distinct 1000-byte tile range.
        val leaf = covering.mapIndexed { i, id ->
            PmtilesDirectory.Entry(tileId = id, runLength = 1, offset = i * 1000L, length = 1000)
        }
        var leafReads = 0

        val ranges = PmtilesDirectory.coveringRanges(
            header = header, rootDir = rootDir, lon = 11.33, lat = 43.32, marginTiles = 1,
            readLeaf = { leafReads++; leaf },
        )

        val tileRanges = ranges.filter { it.start >= header.tileDataOffset }
        assertEquals(covering.size, tileRanges.size, "every tile behind the shared leaf must resolve")
        assertEquals(1, leafReads, "the leaf should be decoded once, not per tile")
    }

    @Test
    fun coveringRangesForRoute_dropsAZoomWholeWhenTheCorridorExceedsTheCap_andReportsIt() {
        val header = PmtilesDirectory.Header(
            rootDirOffset = 127, rootDirLength = 138, metadataOffset = 265, metadataLength = 21713,
            leafDirsOffset = 21978, leafDirsLength = 0, tileDataOffset = 21978, tileDataLength = 1274304,
            internalCompression = 2, minZoom = 12, maxZoom = 14,
        )
        val route = listOf(11.2470 to 43.7690, 11.2585 to 43.7688)
        val entries = buildList {
            for (z in 12..14) {
                for ((lon, lat) in route) {
                    val (cx, cy) = PmtilesDirectory.lonLatToTile(z, lon, lat)
                    for (dx in -1..1) for (dy in -1..1) {
                        val id = PmtilesDirectory.zxyToTileId(z, cx + dx, cy + dy)
                        add(PmtilesDirectory.Entry(id, 1, id, 1000))
                    }
                }
            }
        }.distinctBy { it.tileId }.sortedBy { it.tileId }

        // A cap of 1 is below every zoom's corridor here, so every zoom is dropped → prelude only, and
        // each drop is reported (a silent truncation would leave the route on scattered holes).
        val dropped = mutableListOf<Pair<Int, Int>>()
        val capped = PmtilesDirectory.coveringRangesForRoute(
            header = header,
            rootDir = entries,
            points = route,
            marginTiles = 1,
            maxTilesPerZoom = 1,
            onZoomDropped = { z, tiles -> dropped += z to tiles },
        )
        assertEquals(3, capped.size, "every zoom dropped → prelude only")
        assertEquals(listOf(12, 13, 14), dropped.map { it.first })
        assertTrue(dropped.all { it.second > 1 }, "reported tile counts should exceed the cap")

        // A generous cap changes nothing (the default path stays exact — the guard only bites on the
        // pathological corridor it exists for).
        assertEquals(
            PmtilesDirectory.coveringRangesForRoute(header, entries, route, marginTiles = 1),
            PmtilesDirectory.coveringRangesForRoute(
                header, entries, route, marginTiles = 1, maxTilesPerZoom = 10_000,
            ),
        )
    }
}
