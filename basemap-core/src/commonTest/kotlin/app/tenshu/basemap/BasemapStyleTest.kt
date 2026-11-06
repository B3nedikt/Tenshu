package app.tenshu.basemap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hand-built MapLibre style JSON (`BasemapStyle.kt`), including the LB-1 label layer
 * (`docs/proposals/basemap-labels.md`). The *render* is visual and verified on device, but the style
 * is pure data — so its load-bearing contracts are unit-testable here, which is why the style lives
 * in this Compose-free module rather than beside a renderer.
 */
class BasemapStyleTest {

    private val palette = mapPalette(dark = false)
    private val tileUrl = "pmtiles://file:///data/user/0/app.tenshu/files/region.pmtiles"

    private fun baseStyle(): JsonObject = offlineBasemapStyle(palette, tileUrl)

    private fun routeStyle(): JsonObject = routeBasemapStyle(
        pal = palette,
        tileUrl = tileUrl,
        route = listOf(BasemapCoord(48.137, 11.575), BasemapCoord(48.150, 11.580)),
        routeColorHex = "#336699",
    )

    /** The glyph atlas as a bundle-relative path — the form every non-Android platform uses. */
    private val bundleGlyphsUrl =
        fileGlyphsUrl("file:///var/containers/App.app/compose-resources/files/glyphs")

    private fun bundleBaseStyle(): JsonObject =
        offlineBasemapStyle(palette, tileUrl, labelLanguage = null, glyphsUrl = bundleGlyphsUrl)

    private fun bundleRouteStyle(): JsonObject = routeBasemapStyle(
        pal = palette,
        tileUrl = tileUrl,
        route = listOf(BasemapCoord(48.137, 11.575), BasemapCoord(48.150, 11.580)),
        routeColorHex = "#336699",
        glyphsUrl = bundleGlyphsUrl,
    )

    private fun JsonObject.layers(): List<JsonObject> =
        getValue("layers").jsonArray.map { it.jsonObject }

    private fun JsonObject.layer(id: String): JsonObject =
        layers().single { it["id"]?.jsonPrimitive?.content == id }

    /** The style's symbol layers, in style order — which is also their collision priority. */
    private fun labelLayers(): List<JsonObject> =
        baseStyle().layers().filter { it["type"]?.jsonPrimitive?.content == "symbol" }

    // --- House Rule #1: every URL in the style is device-local -------------------------------

    /**
     * **The guard worth having regardless of the rest of this module.** Adding `glyphs` opens the one
     * door through which a remote URL could enter the style — a pasted `fonts.openmaptiles.org`
     * template, a demotiles source, a sprite CDN — and none of it would fail loudly on a dev machine
     * with network. It would just quietly fetch, breaking House Rule #1 on every user's device.
     *
     * So: walk every string the style contains, at any depth, and require anything URL-shaped to use
     * a device-local scheme. Recursion (rather than checking known keys) is deliberate — it covers
     * keys nobody has added yet.
     *
     * ⚠ This guard belongs wherever the style is *authored*, not wherever it is rendered. It moved
     * here with the style precisely so a SwiftUI renderer over the same JSON inherits it for free.
     */
    @Test
    fun everyUrlInTheStyleIsDeviceLocal() {
        val localSchemes = listOf("pmtiles://file://", "asset://", "file://")
        val remoteMarkers = listOf("http://", "https://", "//")

        fun check(element: JsonElement, path: String) {
            when (element) {
                is JsonObject -> element.forEach { (k, v) -> check(v, "$path.$k") }
                is JsonArray -> element.forEachIndexed { i, v -> check(v, "$path[$i]") }
                is JsonPrimitive -> {
                    if (!element.isString) return
                    val value = element.content
                    val looksRemote = remoteMarkers.any { value.contains(it) }
                    val isLocal = localSchemes.any { value.startsWith(it) }
                    assertTrue(
                        !looksRemote || isLocal,
                        "House Rule #1: non-local URL at $path -> '$value'. The basemap may only " +
                            "reference bytes already on the device (bundled tiles, APK glyphs).",
                    )
                }
            }
        }

        check(baseStyle(), "style")
        check(routeStyle(), "routeStyle")
        // The bundle-path shape of the very same styles. `asset://` is Android's APK asset manager
        // and has no counterpart elsewhere, so iOS/desktop address the identical glyph atlas by
        // absolute path — and the guard must walk THAT shape too, or the platforms whose glyph URL is
        // assembled at runtime are the ones it never covers.
        check(bundleBaseStyle(), "bundleStyle")
        check(bundleRouteStyle(), "bundleRouteStyle")
    }

    /**
     * The **public** surface is the string, so the guard has to hold for what actually ships, not
     * only for the internal builder the other cases walk.
     */
    @Test
    fun thePublicStringEntryPointsEmitExactlyTheBuiltStyle() {
        assertEquals(
            baseStyle(),
            Json.parseToJsonElement(offlineBasemapStyleJson(palette, tileUrl)).jsonObject,
        )
        assertEquals(
            routeStyle(),
            Json.parseToJsonElement(
                routeBasemapStyleJson(
                    pal = palette,
                    tileUrl = tileUrl,
                    route = listOf(BasemapCoord(48.137, 11.575), BasemapCoord(48.150, 11.580)),
                    routeColorHex = "#336699",
                ),
            ).jsonObject,
        )
    }

    /**
     * `asset://` is Android-only, so the glyph template is a parameter and every other platform passes
     * a `file://` directory. Both are device-local, which is what House Rule #1 actually requires —
     * the rule bans *fetching* glyphs, not any particular local scheme.
     */
    @Test
    fun theBundleGlyphTemplateIsDeviceLocalAndKeepsTheFontstackPlaceholders() {
        val url = fileGlyphsUrl("file:///var/containers/App.app/compose-resources/files/glyphs")

        assertTrue(url.startsWith("file://"), "bundle glyphs must resolve from the app bundle")
        // MapLibre substitutes both placeholders verbatim; losing either fetches nothing at all.
        assertTrue("{fontstack}" in url && "{range}" in url, "glyph template lost a placeholder")
        assertEquals(url, bundleBaseStyle().getValue("glyphs").jsonPrimitive.content)
        // ⚠ The route style REBUILDS the root rather than copying it, so an overridden glyphs URL
        // has to be carried across by hand exactly as GLYPHS_URL does — the same trap
        // `routeStyleCarriesGlyphsAcrossTheRebuild` guards for the default.
        assertEquals(url, bundleRouteStyle().getValue("glyphs").jsonPrimitive.content)
    }

    /** The default stays Android's `asset://`, so no existing call site changes behaviour. */
    @Test
    fun theGlyphsUrlDefaultsToTheAndroidAssetTemplate() {
        assertEquals(GLYPHS_URL, baseStyle().getValue("glyphs").jsonPrimitive.content)
        assertEquals(GLYPHS_URL, routeStyle().getValue("glyphs").jsonPrimitive.content)
    }

    @Test
    fun styleReferencesOnlyTheTileArchiveAndBundledGlyphs() {
        val style = baseStyle()
        assertEquals(GLYPHS_URL, style.getValue("glyphs").jsonPrimitive.content)
        assertTrue(GLYPHS_URL.startsWith("asset://"), "glyphs must resolve from APK assets")
        assertEquals(
            tileUrl,
            style.getValue("sources").jsonObject.getValue("protomaps").jsonObject
                .getValue("url").jsonPrimitive.content,
        )
        // No sprite: we ship no icon atlas, so declaring one would be a fetch we cannot satisfy.
        assertTrue("sprite" !in style, "style must not declare a sprite — no icon atlas is bundled")
    }

    /**
     * `routeBasemapStyle` rebuilds the root object instead of copying it, so a root key added to the
     * base style is silently dropped from the route style unless carried across by hand. That is
     * exactly how the route map would end up with symbol layers and no font to resolve them.
     */
    @Test
    fun routeStyleCarriesGlyphsAcrossTheRebuild() {
        assertEquals(
            baseStyle().getValue("glyphs").jsonPrimitive.content,
            routeStyle().getValue("glyphs").jsonPrimitive.content,
        )
    }

    @Test
    fun routeStyleKeepsEveryBaseLayerAndAddsTheRoute() {
        val baseIds = baseStyle().layers().map { it.getValue("id").jsonPrimitive.content }
        val routeIds = routeStyle().layers().map { it.getValue("id").jsonPrimitive.content }
        assertTrue(routeIds.containsAll(baseIds), "route style dropped base layers: ${baseIds - routeIds.toSet()}")
        assertTrue(routeIds.containsAll(listOf("route-line", "route-endpoints")))
    }

    // --- The LB-1 label layer -----------------------------------------------------------------

    @Test
    fun localityLabelsReadNamesFromThePlacesSourceLayer() {
        val layer = baseStyle().layer("places-locality")
        assertEquals("symbol", layer.getValue("type").jsonPrimitive.content)
        assertEquals("protomaps", layer.getValue("source").jsonPrimitive.content)
        assertEquals("places", layer.getValue("source-layer").jsonPrimitive.content)

        val layout = layer.getValue("layout").jsonObject
        // The endonym (D4) — `name`, never `name:<locale>`, which could demand glyphs we don't ship.
        assertEquals(
            listOf("get", "name"),
            layout.getValue("text-field").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /** A fontstack must name a directory we ship, or that layer's labels silently fail to draw. */
    @Test
    fun everyLabelLayerUsesAShippedFontstack() {
        val fonts = labelLayers().flatMap {
            it.getValue("layout").jsonObject.getValue("text-font").jsonArray
                .map { f -> f.jsonPrimitive.content }
        }
        assertTrue(fonts.isNotEmpty())
        assertTrue(
            fonts.all { it == FONT_REGULAR || it == FONT_MEDIUM },
            "text-font must reference a bundled fontstack; got ${fonts.distinct()}",
        )
    }

    /**
     * ⚠⚠ **We ship two archive generations at once**, and this test is the only thing stopping a
     * tidy-up from breaking one of them. The deployed regions name attributes `kind` / `min_zoom`;
     * the bundled demo — the fallback every fresh install renders, and the only local archive still
     * carrying the `transit` layer M9 needs — prefixes them `pmap:`. Same vocabulary, different keys.
     *
     * Read the `pmap:` arm as "do not delete until the demo and the regions come from one pipeline
     * generation", not as dead legacy code.
     */
    @Test
    fun labelsReadAttributesUnderBothProtomapsNamings() {
        val filter = baseStyle().layer("places-locality").getValue("filter").toString()
        assertTrue("\"kind\"" in filter, "must read the v4 attribute name")
        assertTrue("pmap:kind" in filter, "must also read the demo archive's pmap:-prefixed name")
        assertTrue("min_zoom" in filter && "pmap:min_zoom" in filter, "same for the min_zoom gate")
    }

    /**
     * Applied across *all* label layers so a new one inherits the rule: a `get` on an absent
     * attribute yields null, and a null inside `<=` or `symbol-sort-key` fails the expression's type
     * check — which drops the **whole layer**, not just the offending feature.
     */
    @Test
    fun everyLabelLayerGatesOnCoalescedTileAttributes() {
        labelLayers().forEach { layer ->
            val id = layer.getValue("id").jsonPrimitive.content
            val filter = layer.getValue("filter").toString()
            assertTrue("min_zoom" in filter, "$id: must gate on the tile's own min_zoom (D3)")
            assertTrue("coalesce" in filter, "$id: min_zoom must be coalesced")
            val sortKey = layer.getValue("layout").jsonObject.getValue("symbol-sort-key").toString()
            assertTrue("coalesce" in sortKey, "$id: symbol-sort-key must be coalesced")
        }
    }

    /** Text over the block fills is unreadable without a halo — half of the legibility complaint. */
    @Test
    fun everyLabelIsHaloedInBothThemes() {
        listOf(false, true).forEach { dark ->
            offlineBasemapStyle(mapPalette(dark), tileUrl).layers()
                .filter { it["type"]?.jsonPrimitive?.content == "symbol" }
                .forEach { layer ->
                    val id = layer.getValue("id").jsonPrimitive.content
                    val paint = layer.getValue("paint").jsonObject
                    val color = paint.getValue("text-color").jsonPrimitive.content
                    val halo = paint.getValue("text-halo-color").jsonPrimitive.content
                    assertTrue(
                        paint.getValue("text-halo-width").jsonPrimitive.content.toDouble() > 0.0,
                        "$id dark=$dark",
                    )
                    assertTrue(color != halo, "$id dark=$dark: halo must contrast with the text")
                }
        }
    }

    // --- The LB-2 label set --------------------------------------------------------------------

    @Test
    fun neighbourhoodLabelsReadAsAreaLabels() {
        val layout = baseStyle().layer("places-neighbourhood").getValue("layout").jsonObject
        assertEquals("uppercase", layout.getValue("text-transform").jsonPrimitive.content)
        assertTrue(layout.getValue("text-letter-spacing").jsonPrimitive.content.toDouble() > 0.0)
        assertEquals(
            listOf(FONT_REGULAR),
            layout.getValue("text-font").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /**
     * **The density guard.** One z14 tile over central Florence carries 294 named road features, 153
     * of them footpaths, all at `min_zoom` 13–14 — so the tile's own gate alone would dump the lot
     * onto a 220 dp snippet. Road class earns its label by camera zoom instead.
     *
     * ⚠⚠ The gate must be the layer's **`minzoom`**, never a `["zoom"]` branch inside the filter:
     * filters are evaluated per tile, so filter-`zoom` is capped by the archive's `maxzoom` and a
     * `>= 15` branch can never fire on the maxzoom-14 demo archive. This test exists because that
     * distinction is invisible until you notice no street name has ever rendered.
     */
    @Test
    fun roadLabelsGateRoadClassOnCameraZoomNotTileZoom() {
        val major = baseStyle().layer("roads-labels-major")
        val minor = baseStyle().layer("roads-labels-minor")
        val path = baseStyle().layer("roads-labels-path")

        assertEquals("line", major.getValue("layout").jsonObject.getValue("symbol-placement").jsonPrimitive.content)
        assertTrue("minzoom" !in major, "arterials label at every zoom the snippet uses")
        assertEquals(MINOR_ROAD_LABEL_ZOOM, minor.getValue("minzoom").jsonPrimitive.content.toInt())
        assertEquals(PATH_LABEL_ZOOM, path.getValue("minzoom").jsonPrimitive.content.toInt())

        listOf(major, minor, path).forEach { layer ->
            val id = layer.getValue("id").jsonPrimitive.content
            val filter = layer.getValue("filter").toString()
            // Exactly one `["zoom"]` — the per-feature min_zoom gate, which is *correct* against
            // tile zoom. A second one would mean road class had been gated there too.
            assertEquals(
                1,
                Regex("""\["zoom"]""").findAll(filter).count(),
                "$id: road class must not be gated by filter-zoom — that is capped by archive maxzoom",
            )
            assertTrue(
                "sort_rank" in layer.getValue("layout").jsonObject.getValue("symbol-sort-key").toString(),
                "$id: arterials must win collisions against side streets",
            )
        }
    }

    /**
     * MapLibre places symbols layer by layer, so layer order *is* collision priority. A city name
     * must never lose its spot to a street name in a snippet that fits three labels.
     */
    @Test
    fun labelLayersAreOrderedByCollisionPriority() {
        assertEquals(
            listOf(
                "places-locality",
                "places-neighbourhood",
                "pois-landmark",
                "roads-labels-major",
                "roads-labels-minor",
                "roads-labels-path",
            ),
            labelLayers().map { it.getValue("id").jsonPrimitive.content },
        )
    }

    // --- The LB-3 additions: landmarks + localised names ---------------------------------------

    /**
     * ⚠ **`min_zoom` alone is not a sufficient POI gate.** Protomaps calibrates it for a full-screen
     * map, and for `library` it is far too eager — 38–61 convent/institute libraries pass it in one
     * z14 tile over central Florence, which is pure noise on a 220 dp snippet. The allowlist is what
     * cuts that to ~10–14 genuine landmarks, so it is not decoration.
     */
    @Test
    fun landmarkPoisAreAllowlistedNotJustMinZoomGated() {
        val layer = baseStyle().layer("pois-landmark")
        assertEquals("pois", layer.getValue("source-layer").jsonPrimitive.content)

        val filter = layer.getValue("filter").toString()
        listOf("attraction", "museum", "park", "place_of_worship").forEach {
            assertTrue(it in filter, "landmark kind '$it' must be allowlisted")
        }
        assertTrue("library" !in filter, "library is the noise case the allowlist exists to exclude")
        assertTrue("min_zoom" in filter, "the per-feature gate still applies on top of the allowlist")
    }

    /**
     * POIs carry no `sort_key`, so without this the collision winner is arbitrary and a random chapel
     * can out-rank the Duomo. `min_zoom` doubles as the prominence ranking (lower = more prominent =
     * sorts first).
     */
    @Test
    fun landmarkPoisRankByMinZoomBecauseTheyHaveNoSortKey() {
        val sortKey = baseStyle().layer("pois-landmark")
            .getValue("layout").jsonObject.getValue("symbol-sort-key").toString()
        assertTrue("min_zoom" in sortKey, "POI collision order must fall back to min_zoom")
        assertTrue("coalesce" in sortKey, "and stay coalesced across both archive namings")
    }

    /**
     * ⚠⚠ **The gate is the load-bearing half of D4, not the coalesce.** The tiles carry ~20
     * `name:<lang>` variants including `el` / `he` / `ja` / `ko`; asking for one whose script is
     * outside the shipped glyph ranges renders tofu boxes — strictly worse than the endonym. So a
     * language is only ever used after `labelLanguageFor` clears it.
     */
    @Test
    fun onlyLanguagesWeShipGlyphsForAreUsedForLabels() {
        assertEquals("de", labelLanguageFor("de"))
        assertEquals("de", labelLanguageFor("de-DE"))
        assertEquals("pt", labelLanguageFor("pt_BR"))
        assertEquals("cs", labelLanguageFor("CS"))

        // Scripts we ship no glyphs for must fall back to the endonym, not render tofu.
        listOf("el", "he", "ja", "ko", "ru", "zh", "ar", "th").forEach {
            assertEquals(null, labelLanguageFor(it), "$it has no bundled glyphs — must fall back")
        }
        // Latin but needs Latin Extended Additional, which we do not ship.
        assertEquals(null, labelLanguageFor("vi"))
        assertEquals(null, labelLanguageFor(null))
        assertEquals(null, labelLanguageFor(""))
    }

    @Test
    fun placeAndPoiLabelsUseTheLocalisedNameWithAnEndonymFallback() {
        val localised = offlineBasemapStyle(palette, tileUrl, labelLanguage = "de")
        listOf("places-locality", "places-neighbourhood", "pois-landmark").forEach { id ->
            val field = localised.layer(id).getValue("layout").jsonObject
                .getValue("text-field").toString()
            assertTrue("name:de" in field, "$id: must prefer the reader's language")
            assertTrue("coalesce" in field && "\"name\"" in field, "$id: must fall back to the endonym")
        }
    }

    /**
     * Street names are endonyms by nature — you do not translate "Via dei Pandolfini" — and the tiles
     * barely carry `name:<lang>` for roads anyway.
     */
    @Test
    fun roadLabelsStayEndonymsEvenWhenALanguageIsSet() {
        val field = offlineBasemapStyle(palette, tileUrl, labelLanguage = "de")
            .layer("roads-labels-major").getValue("layout").jsonObject
            .getValue("text-field").toString()
        assertTrue("name:de" !in field, "road labels must not be localised")
    }

    @Test
    fun noLanguageMeansPlainEndonymLabels() {
        val field = baseStyle().layer("places-locality").getValue("layout").jsonObject
            .getValue("text-field").toString()
        assertTrue("coalesce" !in field, "with no language the label is a plain get")
        assertTrue("name" in field)
    }

    /** The route style inherits the base style's labels, localisation included. */
    @Test
    fun routeStyleCarriesTheLabelLanguageThrough() {
        val route = routeBasemapStyle(
            pal = palette,
            tileUrl = tileUrl,
            route = listOf(BasemapCoord(48.137, 11.575), BasemapCoord(48.150, 11.580)),
            routeColorHex = "#336699",
            labelLanguage = "de",
        )
        val field = route.layers().single { it["id"]?.jsonPrimitive?.content == "places-locality" }
            .getValue("layout").jsonObject.getValue("text-field").toString()
        assertTrue("name:de" in field)
    }

    // --- The colour representation ---------------------------------------------------------------

    /**
     * The palette is now written in the form the style emits, so this is the whole of the former
     * `Color(0xFF…)` → `.hex()` round-trip: the literal *is* the paint value.
     */
    @Test
    fun paletteColoursAreOpaqueUppercaseRgbStrings() {
        val pattern = Regex(BASEMAP_COLOR_PATTERN)
        listOf(mapPalette(dark = false), mapPalette(dark = true)).forEach { pal ->
            listOf(pal.land, pal.block, pal.road, pal.roadCasing, pal.water, pal.park, pal.label, pal.labelHalo)
                .forEach { assertTrue(pattern.matches(it), "not an opaque #RRGGBB colour: '$it'") }
        }
        assertEquals("#F0ECE4", mapPalette(dark = false).land)
        assertEquals("#1A1920", mapPalette(dark = true).land)
    }

    /** …and it reaches the style unchanged, which is the only reason the representation was chosen. */
    @Test
    fun aPaletteColourReachesThePaintPropertyVerbatim() {
        val background = baseStyle().layer("background").getValue("paint").jsonObject
        assertEquals(palette.land, background.getValue("background-color").jsonPrimitive.content)
    }
}
