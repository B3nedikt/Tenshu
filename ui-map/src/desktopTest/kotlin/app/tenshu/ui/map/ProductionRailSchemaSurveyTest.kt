package app.tenshu.ui.map

import app.tenshu.vectortile.MvtDecoder
import app.tenshu.vectortile.MvtGeometryType
import app.tenshu.vectortile.PmtilesDirectory
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **S5 — what the PRODUCTION basemap actually carries for rail**
 * (`docs/proposals/timeline-movement-capture.md` §17.5/§24).
 *
 * ### Why this test exists at all
 *
 * §13.2a surveyed the **Firenze demo archive** and concluded there are no station POIs. §17.5 then
 * noted that the *production* DACH tiles are a different cut — rail arrives in the `roads` layer as
 * `kind=rail`/`kind_detail`, not in the demo's `transit` layer — and that **whether they carry
 * `network`/`ref`/`route` had never been checked**. That gap is what decides a real piece of work:
 * whether "U5" and "S2" are nameable from what we already ship, or whether it needs our own
 * per-region OSM transit overlay (§17.6).
 *
 * ⚠ Deciding that from the demo archive would have been the same mistake §13.2a's warning names —
 * surveying one cut and designing on another.
 *
 * ### The fixture
 *
 * `munich.pmtiles` is a **13 MB bbox extract of the deployed `de-bayern.pmtiles`** (1.8 GB), pulled
 * by range requests from the live tiles CDN with `pmtiles extract --bbox=11.50,48.10,11.65,48.18`.
 * It is the same bytes the app streams, not a fresh Planetiler build — so the answer is about what
 * *ships*, not about what Protomaps could theoretically emit.
 *
 * ⚠ **Not committed** (13 MB, and it is a derived artefact). The test **skips** when it is absent, so
 * the suite stays green on a clean checkout while the survey remains re-runnable by anyone with the
 * one command in the KDoc above. ⚠ A skip is honest here in a way it would not be for a behavioural
 * test: this asserts a fact about an external dataset, not about our code.
 */
class ProductionRailSchemaSurveyTest {

    /**
     * ⚠ Under `build/`, so the 13 MB fixture is git-ignored by construction rather than by a rule
     * someone has to remember. Drop it here to run the survey; absent, the test skips.
     */
    private val archive = File("build/survey/munich.pmtiles")

    private fun readRange(offset: Long, length: Int): ByteArray =
        RandomAccessFile(archive, "r").use { raf ->
            raf.seek(offset)
            ByteArray(length).also { raf.readFully(it) }
        }

    private fun decompress(bytes: ByteArray, compression: Int): ByteArray =
        if (compression == 2) GZIPInputStream(bytes.inputStream()).use { it.readBytes() } else bytes

    /**
     * **§32.13 — is the VzG `ref` usable as a *corridor* identity?**
     *
     * §24.2 established that `ref` exists on heavy rail (216/306) and is a VzG line number, useless
     * as *service* identity ("nobody has waited for train 5500"). D-T1 (§32.12) raises a different
     * question, for which corridor identity is exactly right: **does a train → train interchange
     * change the `ref`, while a pass-through stop keeps it?** Two facts decide it, and neither had
     * been measured:
     *
     *  - **Q1 — consistency.** A corridor is many linestrings, one per tile and often per segment. If
     *    `ref` is not stable along a corridor, "the legs' refs differ" means nothing.
     *  - **Q2 — discrimination.** Where lines meet (München Ost), do the converging corridors carry
     *    *different* refs? If they share one, the signal cannot separate an interchange from a
     *    pass-through even in principle.
     */
    @Test
    fun surveyCorridorRefUsability() {
        if (!archive.exists()) {
            println("§32.13 corridor survey SKIPPED — no archive at ${archive.absolutePath}")
            return
        }
        val header = PmtilesDirectory.parseHeader(readRange(0, PmtilesDirectory.HEADER_BYTES))
        val rootDir = PmtilesDirectory.decodeDirectory(
            decompress(readRange(header.rootDirOffset, header.rootDirLength.toInt()), header.internalCompression),
        )
        val z = 14
        val (x0, y0) = PmtilesDirectory.lonLatToTile(z, 11.50, 48.18)
        val (x1, y1) = PmtilesDirectory.lonLatToTile(z, 11.65, 48.10)

        // ref -> names seen, and name -> refs seen: the two directions answer Q1 from both sides.
        val namesByRef = sortedMapOf<String, MutableSet<String>>()
        val refsByName = sortedMapOf<String, MutableSet<String>>()
        var heavyRail = 0
        var withRef = 0
        var withName = 0
        // Features whose geometry passes near München Ost (48.128, 11.605) — Q2's convergence point.
        val atOstbahnhof = mutableListOf<String>()

        for (x in x0..x1) {
            for (y in y0..y1) {
                val tileId = PmtilesDirectory.zxyToTileId(z, x, y)
                val range = PmtilesDirectory.tileRange(header, rootDir, tileId) { leaf ->
                    runCatching {
                        PmtilesDirectory.decodeDirectory(
                            decompress(readRange(leaf.start, leaf.length.toInt()), header.internalCompression),
                        )
                    }.getOrElse { emptyList() }
                } ?: continue
                val raw = runCatching { readRange(range.start, range.length.toInt()) }.getOrNull() ?: continue
                val tile = runCatching { MvtDecoder.decode(decompress(raw, header.tileCompression)) }.getOrNull() ?: continue
                val ostTile = PmtilesDirectory.lonLatToTile(z, 11.605, 48.128)
                for (layer in tile) {
                    for (f in layer.features) {
                        if (f.type != MvtGeometryType.LINESTRING) continue
                        if (f.tag("kind") != "rail") continue
                        if (f.tag("kind_detail") != "rail") continue // heavy rail only; subway is D-T1's
                        heavyRail++
                        val ref = f.tag("ref")
                        val name = f.tag("name")
                        if (ref != null) withRef++
                        if (name != null) withName++
                        if (ref != null && name != null) {
                            namesByRef.getOrPut(ref) { sortedSetOf() } += name
                            refsByName.getOrPut(name) { sortedSetOf() } += ref
                        }
                        if (x == ostTile.first && y == ostTile.second) {
                            atOstbahnhof += "ref=${ref ?: "-"} name=${name ?: "-"}"
                        }
                    }
                }
            }
        }

        println("§32.13 CORRIDOR SURVEY")
        println("  heavy-rail linestrings: $heavyRail   with ref: $withRef   with name: $withName")
        println("  --- Q1: is a ref stable along its corridor? (ref -> names) ---")
        namesByRef.forEach { (ref, names) -> println("    ref=$ref -> $names") }
        println("  --- Q1b: does one corridor name carry several refs? (name -> refs) ---")
        refsByName.forEach { (name, refs) -> println("    name=$name -> $refs") }
        println("  --- Q2: what converges in the Ostbahnhof tile? (${atOstbahnhof.size} features) ---")
        atOstbahnhof.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
            .take(12)
            .forEach { println("    ${it.value}x ${it.key}") }
        assertTrue(heavyRail > 0, "the fixture must contain heavy rail")
    }

    @Test
    fun surveyProductionRailTags() {
        if (!archive.exists()) {
            println("S5 survey SKIPPED — no archive at ${archive.absolutePath}")
            println("  reproduce: pmtiles extract https://tiles.service.tenshu.app/regions/2026-08/de-bayern.pmtiles \\")
            println("               ui-map/build/survey/munich.pmtiles --bbox=11.50,48.10,11.65,48.18")
            return
        }

        val header = PmtilesDirectory.parseHeader(readRange(0, PmtilesDirectory.HEADER_BYTES))
        val rootDir = PmtilesDirectory.decodeDirectory(
            decompress(readRange(header.rootDirOffset, header.rootDirLength.toInt()), header.internalCompression),
        )
        println("S5 SURVEY — ${archive.name}, zoom ${header.minZoom}..${header.maxZoom}")

        // Central Munich at z14 — derived from the bbox rather than hand-numbered, so the tile
        // coordinates cannot drift from the extract the fixture was cut with.
        val z = 14
        val (x0, y0) = PmtilesDirectory.lonLatToTile(z, 11.50, 48.18)
        val (x1, y1) = PmtilesDirectory.lonLatToTile(z, 11.65, 48.10)
        val layerKeys = sortedMapOf<String, MutableSet<String>>()
        val railKindDetails = sortedSetOf<String>()
        val railKeysByKind = mutableMapOf<String, MutableMap<String, Int>>()
        val railCountByKind = mutableMapOf<String, Int>()
        val poiKinds = sortedSetOf<String>()
        val poiKindDetails = sortedSetOf<String>()
        val namedStationPois = mutableListOf<String>()
        val railTagSamples = mutableListOf<String>()
        var railFeatures = 0
        var tilesRead = 0

        for (x in x0..x1) {
            for (y in y0..y1) {
                val tileId = PmtilesDirectory.zxyToTileId(z, x, y)
                val range = PmtilesDirectory.tileRange(header, rootDir, tileId) { leaf ->
                    runCatching {
                        PmtilesDirectory.decodeDirectory(
                            decompress(readRange(leaf.start, leaf.length.toInt()), header.internalCompression),
                        )
                    }.getOrElse { emptyList() }
                } ?: continue
                val raw = runCatching { readRange(range.start, range.length.toInt()) }.getOrNull() ?: continue
                val tile = runCatching { MvtDecoder.decode(decompress(raw, header.tileCompression)) }.getOrNull() ?: continue
                tilesRead++

                for (layer in tile) {
                    val keys = layerKeys.getOrPut(layer.name) { sortedSetOf() }
                    for (f in layer.features) {
                        keys += f.tags.keys
                        val isRail = f.tag("kind") == "rail" || f.tag("railway") != null
                        if (layer.name == "pois") {
                            val k = f.tag("kind"); val kd = f.tag("kind_detail")
                            k?.let { poiKinds += it }
                            kd?.let { poiKindDetails += it }
                            val looks = listOf(k, kd).filterNotNull().any { v ->
                                listOf("station", "rail", "train", "tram", "subway", "metro", "halt").any { v.contains(it) }
                            }
                            if (looks && namedStationPois.size < 10) {
                                namedStationPois += "z$z ${k} name=${f.tag("name")} min_zoom=${f.tag("min_zoom")}"
                            }
                        }
                        if (isRail && f.type == MvtGeometryType.LINESTRING) {
                            railFeatures++
                            val detail = f.tag("kind_detail") ?: "(none)"
                            railKindDetails += detail
                            railCountByKind[detail] = (railCountByKind[detail] ?: 0) + 1
                            val counts = railKeysByKind.getOrPut(detail) { mutableMapOf() }
                            f.tags.keys.forEach { k -> counts[k] = (counts[k] ?: 0) + 1 }
                            if (railTagSamples.size < 6) railTagSamples += "${layer.name}: ${f.tags}"
                        }
                    }
                }
            }
        }

        println("  tiles decoded: $tilesRead   rail linestrings: $railFeatures")
        println("  rail kind_detail values: $railKindDetails")
        railTagSamples.forEach { println("  sample $it") }

        // ⚠⚠ The decisive question is per-FEATURE, not per-layer. `network`/`shield_text`/`ref` all
        // appear in the `roads` layer's key set because ROAD features carry them (motorway shields);
        // asking "is the key in the layer" answers a different, useless question. Tally the keys that
        // actually appear on rail features, split by kind_detail.
        println("  --- identity keys per rail kind_detail ---")
        railKeysByKind.toSortedMap().forEach { (kind, counts) ->
            val total = railCountByKind[kind] ?: 0
            val identity = listOf("ref", "name", "network", "route")
                .joinToString(" ") { k -> "$k=${counts[k] ?: 0}/$total" }
            println("    $kind: $identity")
        }
        println("  --- pois: kind / kind_detail values (station-like?) ---")
        val stationLike = { v: String ->
            listOf("station", "rail", "train", "tram", "subway", "metro", "halt", "platform", "stop")
                .any { v.contains(it) }
        }
        println("    station-like kind:        ${poiKinds.filter(stationLike)}")
        println("    station-like kind_detail: ${poiKindDetails.filter(stationLike)}")
        println("    poi kinds total: ${poiKinds.size}   kind_detail total: ${poiKindDetails.size}")
        println("    named station-like POIs: $namedStationPois")

        assertTrue(tilesRead > 0, "no tiles decoded — is the bbox inside the extract?")
    }
}
