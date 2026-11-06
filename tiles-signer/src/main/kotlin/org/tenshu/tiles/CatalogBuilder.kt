package org.tenshu.tiles

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Assembles a [CatalogDoc] from the region definitions + the produced `pmtiles` extracts. For each
 * region it reads `<extractsDir>/<id>.pmtiles`, records its byte size and **sha256 (hex — the same
 * digest the client's download gate recomputes, streamed here because bundles are too big to hold;
 * see [sha256File])**, and points `path` at the immutable dated build location
 * `regions/<buildDate>/<id>.pmtiles` (D2).
 */
object CatalogBuilder {

    private val json = Json { prettyPrint = false; encodeDefaults = true }
    private val lenientJson = Json { ignoreUnknownKeys = true }

    /**
     * [precomputed] supplies `{byteSize, sha256}` for regions whose bundle is **not on this
     * machine** — the D11 split: the box that cut a 20 GB planet emits `hashes.json`, the box that
     * holds the release key signs from it. A local extract always wins over a precomputed entry
     * (hashing what you actually have is strictly better evidence); the precomputed map is the
     * fallback, and a region with neither is a hard error rather than a silently-wrong catalog.
     */
    fun buildCatalog(
        defs: RegionDefinitionsFile,
        extractsDir: File,
        precomputed: Map<String, BundleDigest> = emptyMap(),
        /**
         * Where `<id>.tlne` overlays live (§33 L3), or `null` when this build has none.
         *
         * ⚠⚠ **A missing overlay is not an error, unlike a missing bundle.** Plenty of regions have
         * no rail at all, and a region whose overlay has not been built yet must still get a tile
         * catalogue — making the overlay a precondition would let a transit feature block map
         * delivery. So the bundle keeps its hard error and the overlay is simply absent.
         */
        overlaysDir: File? = null,
        precomputedOverlays: Map<String, BundleDigest> = emptyMap(),
        overlayFormatVersion: Int = 0,
    ): CatalogDoc {
        val regions = defs.regions.map { def ->
            val bundle = File(extractsDir, "${def.id}.pmtiles")
            val digest = when {
                bundle.isFile -> BundleDigest(bundle.length(), sha256File(bundle).toHex())
                else -> precomputed[def.id] ?: error(
                    "No bundle and no precomputed digest for region '${def.id}': expected " +
                        "${bundle.path}, or an entry in the --hashes file. Run tiles-build.sh " +
                        "(which writes build/hashes.json) on the machine that cut the extract.",
                )
            }
            val overlayFile = overlaysDir?.let { File(it, "${def.id}.tlne") }
            val overlay = when {
                overlayFile != null && overlayFile.isFile ->
                    BundleDigest(overlayFile.length(), sha256File(overlayFile).toHex())
                // Same local-wins-over-precomputed rule as the bundle: hashing what you actually
                // have is strictly better evidence than being told.
                else -> precomputedOverlays[def.id]
            }
            CatalogRegion(
                id = def.id,
                name = def.name,
                // The region's coverage box, `west,south,east,north` — the same order `bbox` is
                // authored in and the exact form `GeoBounds.parseBbox` reads on the client. This was
                // shipped as `""`, which silently disabled coverage-aware resolution: `parseBbox`
                // returns null for every region, so `RegionResolver.coveringRegion` always returns
                // null and the terminus falls back to "the first installed/catalog region". Harmless
                // with a single region; wrong the moment there are two (a Firenze place could resolve
                // to a Bayern bundle). See maps-region-delivery.md D7 / §7.1 track 2d.
                coverage = def.bbox.joinToString(","),
                byteSize = digest.byteSize,
                sha256 = digest.sha256,
                tileSchemaVersion = defs.tileSchemaVersion,
                path = "regions/${defs.buildDate}/${def.id}.pmtiles",
                overlayPath = if (overlay != null) "regions/${defs.buildDate}/${def.id}.tlne" else "",
                overlayByteSize = overlay?.byteSize ?: 0,
                overlaySha256 = overlay?.sha256.orEmpty(),
                overlayFormatVersion = if (overlay != null) overlayFormatVersion else 0,
            )
        }
        return CatalogDoc(
            schemaVersion = defs.schemaVersion,
            buildDate = defs.buildDate,
            regions = regions,
        )
    }

    /** The exact bytes written to `catalog.json` — and therefore the exact bytes signed + verified. */
    fun encodeCatalogBytes(doc: CatalogDoc): ByteArray =
        json.encodeToString(CatalogDoc.serializer(), doc).encodeToByteArray()

    fun parseDefinitions(file: File): RegionDefinitionsFile =
        lenientJson.decodeFromString(RegionDefinitionsFile.serializer(), file.readText())

    fun parseDigests(file: File): BundleDigestFile =
        lenientJson.decodeFromString(BundleDigestFile.serializer(), file.readText())
}

/** Lowercase hex, matching `ByteArray.toHexString()` used by the client's sha256 gate. */
internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
