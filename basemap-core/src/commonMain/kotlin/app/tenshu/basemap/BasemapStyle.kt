package app.tenshu.basemap

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The hand-built MapLibre style JSON for the offline basemap (`docs/proposals/maps-app.md` §6;
 * labels per [`basemap-labels.md`](../../../../../../../docs/proposals/basemap-labels.md)).
 *
 * **Compose-free by construction.** It was already pure `buildJsonObject` with no platform API; the
 * one Compose import was [MapPalette]'s colour type, which is now a hex string. Living here — rather
 * than beside a renderer — is what makes the whole style unit-testable (`BasemapStyleTest`) and, more
 * to the point, what lets the House Rule #1 no-egress guard travel with the style to every consumer,
 * Compose or SwiftUI.
 *
 * The public surface is the **String** pair ([offlineBasemapStyleJson] / [routeBasemapStyleJson]).
 * That is the form both renderers want (`BaseStyle.Json(String)` in maplibre-compose,
 * `MLNStyle`/`styleJSON` in MapLibre's native iOS SDK) and it keeps kotlinx-serialization off the
 * exported Obj-C surface. The [JsonObject] builders stay `internal` for the tests, which want
 * structure rather than text.
 */

/**
 * The glyph URL template (LB-1). SDF glyph ranges are **shipped in the APK** under
 * `src/androidMain/assets/glyphs/<fontstack>/<range>.pbf`, so rendering text costs no egress and
 * House Rule #1 is intact — the rule bans *fetching* glyphs, not labels.
 *
 * ⚠ The `{fontstack}` segment is substituted with the layer's `text-font` value verbatim, so the
 * asset directory names must match [FONT_REGULAR] / [FONT_MEDIUM] **exactly**, spaces included.
 */
internal const val GLYPHS_URL = "asset://glyphs/{fontstack}/{range}.pbf"

/**
 * The same atlas addressed as a `file://` **directory** — the form every non-Android platform uses.
 *
 * `asset://` is an Android-only scheme (it is the APK's asset manager); elsewhere the very same glyph
 * ranges ride in the app bundle and are reached by absolute path. [glyphsRoot] is that directory as a
 * `file://` URL, *without* a trailing slash. Still zero egress — House Rule #1 is about the bytes
 * being on the device, not about which local scheme names them, and
 * `BasemapStyleTest.everyUrlInTheStyleIsDeviceLocal` accepts `file://` for exactly that reason.
 */
fun fileGlyphsUrl(glyphsRoot: String): String = "$glyphsRoot/{fontstack}/{range}.pbf"

/** Fontstack names — must equal the asset directory names under `assets/glyphs/`. */
internal const val FONT_REGULAR = "Noto Sans Regular"
internal const val FONT_MEDIUM = "Noto Sans Medium"

/**
 * The offline basemap style as a JSON **string** — the form MapLibre consumes on every platform.
 *
 * See [offlineBasemapStyle] for what it contains. Callers must pass [labelLanguage] through
 * [labelLanguageFor]; [glyphsUrl] defaults to Android's `asset://` template and is
 * [fileGlyphsUrl] everywhere else.
 */
fun offlineBasemapStyleJson(
    pal: MapPalette,
    tileUrl: String,
    labelLanguage: String? = null,
    glyphsUrl: String = GLYPHS_URL,
): String = offlineBasemapStyle(pal, tileUrl, labelLanguage, glyphsUrl).toString()

/**
 * A minimal MapLibre style over the bundled PMTiles (Protomaps source-layers:
 * earth / landcover / landuse / water / roads / buildings / boundaries / places / pois), coloured
 * from the theme-aware [MapPalette]. Roads get a casing under a thinner fill, both zoom-interpolated.
 *
 * [labelLanguage] is the `name:<lang>` suffix to prefer for place and POI labels, or null for the
 * endonym. Callers must pass it through [labelLanguageFor] — see [labelName] for why the gate, not
 * the coalesce, is the load-bearing part.
 *
 * Fully offline: the only URLs are [tileUrl] (`pmtiles://file://…`) and [GLYPHS_URL] (`asset://`),
 * both device-local. No `sprite` — we ship no icon atlas, and a missing sprite is only a problem for
 * layers that reference one (we have none).
 */
internal fun offlineBasemapStyle(
    pal: MapPalette,
    tileUrl: String,
    labelLanguage: String? = null,
    glyphsUrl: String = GLYPHS_URL,
): JsonObject = buildJsonObject {
    put("version", 8)
    put("name", "Tenshu Offline")
    put("glyphs", glyphsUrl)
    putJsonObject("sources") {
        putJsonObject("protomaps") {
            put("type", "vector")
            put("url", tileUrl)
        }
    }
    putJsonArray("layers") {
        add(backgroundLayer(pal.land))
        add(fillLayer("earth", "earth", pal.land))
        add(fillLayer("landcover", "landcover", pal.park, opacity = 0.55))
        add(fillLayer("landuse", "landuse", pal.park, opacity = 0.6))
        add(fillLayer("water", "water", pal.water))
        add(fillLayer("buildings", "buildings", pal.block, opacity = 0.85, minZoom = 13))
        add(lineLayer("roads-casing", pal.roadCasing, listOf(10.0 to 1.4, 14.0 to 4.5, 17.0 to 11.0)))
        add(lineLayer("roads", pal.road, listOf(10.0 to 0.5, 14.0 to 2.2, 17.0 to 7.0)))
        add(boundariesLayer(pal.roadCasing))
        // Symbol layers last so labels draw above every fill/line — and in *priority* order:
        // MapLibre places symbols layer by layer, so an earlier layer wins a collision against a
        // later one. Locality beats neighbourhood beats street name, which is the order a reader
        // wants when only three or four labels fit in a 220 dp snippet.
        add(placesLocalityLayer(pal, labelLanguage))
        add(placesNeighbourhoodLayer(pal, labelLanguage))
        // Landmarks before streets: "Ponte Vecchio" orients a reader better than a side street does.
        add(poisLandmarkLayer(pal, labelLanguage))
        add(roadsLabelLayer(pal, "roads-labels-major", listOf("major_road", "medium_road")))
        add(roadsLabelLayer(pal, "roads-labels-minor", listOf("minor_road"), MINOR_ROAD_LABEL_ZOOM))
        add(roadsLabelLayer(pal, "roads-labels-path", listOf("path"), PATH_LABEL_ZOOM))
    }
}

private fun backgroundLayer(color: String): JsonObject = buildJsonObject {
    put("id", "background")
    put("type", "background")
    putJsonObject("paint") { put("background-color", color) }
}

private fun fillLayer(
    id: String,
    sourceLayer: String,
    color: String,
    opacity: Double = 1.0,
    minZoom: Int? = null,
): JsonObject = buildJsonObject {
    put("id", id)
    put("type", "fill")
    put("source", "protomaps")
    put("source-layer", sourceLayer)
    minZoom?.let { put("minzoom", it) }
    putJsonObject("paint") {
        put("fill-color", color)
        if (opacity != 1.0) put("fill-opacity", opacity)
    }
}

/** A `line` layer over the `roads` source-layer, width = `interpolate(linear, zoom, stops…)`. */
private fun lineLayer(
    id: String,
    color: String,
    widthStops: List<Pair<Double, Double>>,
): JsonObject = buildJsonObject {
    put("id", id)
    put("type", "line")
    put("source", "protomaps")
    put("source-layer", "roads")
    putJsonObject("layout") {
        put("line-cap", "round")
        put("line-join", "round")
    }
    putJsonObject("paint") {
        put("line-color", color)
        putJsonArray("line-width") {
            add("interpolate")
            addJsonArray { add("linear") }
            addJsonArray { add("zoom") }
            widthStops.forEach { (z, w) -> add(z); add(w) }
        }
    }
}

private fun boundariesLayer(color: String): JsonObject = buildJsonObject {
    put("id", "boundaries")
    put("type", "line")
    put("source", "protomaps")
    put("source-layer", "boundaries")
    putJsonObject("paint") {
        put("line-color", color)
        put("line-opacity", 0.5)
        putJsonArray("line-dasharray") { add(2.0); add(2.0) }
    }
}

/**
 * City / town / village names (LB-1) — the top of the label hierarchy.
 *
 * Two Protomaps-authored attributes do the cartography for us rather than hand-tuned zoom stops
 * (`basemap-labels.md` D3): per-feature `min_zoom` gates *when* a name earns its place, and
 * `sort_key` (ascending = more important; Firenze scores −740441) resolves collisions. Both are
 * `coalesce`d — a feature missing either would otherwise make the whole expression fail its type
 * check and drop the layer.
 *
 * The label is the reader's language when we ship glyphs for it, else the endonym ("München") — see
 * [labelName] / [labelLanguageFor] (D4).
 */
private fun placesLocalityLayer(pal: MapPalette, lang: String?): JsonObject = buildJsonObject {
    put("id", "places-locality")
    put("type", "symbol")
    put("source", "protomaps")
    put("source-layer", "places")
    putJsonArray("filter") {
        add("all")
        addJsonArray { add("=="); add(tileAttr("kind")); add("locality") }
        add(minZoomGate())
    }
    putJsonObject("layout") {
        put("text-field", labelName(lang))
        putJsonArray("text-font") { add(FONT_MEDIUM) }
        putJsonArray("text-size") {
            add("interpolate")
            addJsonArray { add("linear") }
            addJsonArray { add("zoom") }
            add(10.0); add(11.0)
            add(14.0); add(13.0)
            add(16.0); add(15.0)
        }
        put("text-max-width", 7.0)
        put("text-padding", 4.0)
        put("symbol-sort-key", sortKeyBy("sort_key"))
    }
    putJsonObject("paint") { labelPaint(pal) }
}

/**
 * Neighbourhood / quarter names (LB-2). Styled as an *area* label rather than a point one — smaller,
 * Regular weight, uppercase and letter-spaced — the standard cartographic tell that this names a
 * district you are inside rather than a spot you can stand on. Sits below [placesLocalityLayer] in
 * collision priority, so a city name never loses its place to a quarter.
 */
private fun placesNeighbourhoodLayer(pal: MapPalette, lang: String?): JsonObject = buildJsonObject {
    put("id", "places-neighbourhood")
    put("type", "symbol")
    put("source", "protomaps")
    put("source-layer", "places")
    putJsonArray("filter") {
        add("all")
        addJsonArray { add("=="); add(tileAttr("kind")); add("neighbourhood") }
        add(minZoomGate())
    }
    putJsonObject("layout") {
        put("text-field", labelName(lang))
        putJsonArray("text-font") { add(FONT_REGULAR) }
        putJsonArray("text-size") {
            add("interpolate")
            addJsonArray { add("linear") }
            addJsonArray { add("zoom") }
            add(13.0); add(9.5)
            add(16.0); add(11.5)
        }
        put("text-transform", "uppercase")
        put("text-letter-spacing", 0.08)
        put("text-max-width", 8.0)
        put("text-padding", 6.0)
        put("symbol-sort-key", sortKeyBy("sort_key"))
    }
    putJsonObject("paint") { labelPaint(pal, haloWidth = 1.0) }
}

/**
 * POI kinds that earn a label on a map snippet (LB-3).
 *
 * ⚠ **An allowlist is required; `min_zoom` alone is not enough.** Protomaps' per-feature `min_zoom`
 * is calibrated for a full-screen map, and for some kinds it is far too eager: one z14 tile over
 * central Florence puts **38–61 `library` features** (convent and institute libraries) through the
 * gate, which is pure noise on a 220 dp snippet. Restricting to landmark kinds cuts the same tile to
 * **10–14** candidates — Boboli, Ponte Vecchio, the Uffizi, Santa Croce — of which collision draws a
 * couple.
 *
 * Conversely `place_of_worship` needs **no** special handling: Protomaps already holds churches to
 * `min_zoom` 16, so they cost nothing at snippet zoom and arrive when the camera earns them.
 */
internal val LANDMARK_POI_KINDS = listOf(
    "attraction", "museum", "monument", "memorial", "artwork", "castle", "viewpoint", "theatre",
    "park", "garden", "zoo", "aquarium",
    "townhall", "university", "station", "aerodrome", "place_of_worship",
)

/**
 * Named landmarks (LB-3) — the layer that turns "a street grid" into "a place you recognise".
 *
 * ⚠ POIs carry **no `sort_key`**, so collision order would otherwise be arbitrary and a random
 * chapel could out-rank the Duomo. `min_zoom` doubles as the ranking signal instead: Protomaps sets
 * it lower for more prominent features, and lower sorts first — exactly the semantics
 * `symbol-sort-key` wants.
 */
private fun poisLandmarkLayer(pal: MapPalette, lang: String?): JsonObject = buildJsonObject {
    put("id", "pois-landmark")
    put("type", "symbol")
    put("source", "protomaps")
    put("source-layer", "pois")
    putJsonArray("filter") {
        add("all")
        add(minZoomGate())
        addJsonArray {
            add("any")
            LANDMARK_POI_KINDS.forEach { kind ->
                addJsonArray { add("=="); add(tileAttr("kind")); add(kind) }
            }
        }
    }
    putJsonObject("layout") {
        put("text-field", labelName(lang))
        putJsonArray("text-font") { add(FONT_REGULAR) }
        putJsonArray("text-size") {
            add("interpolate")
            addJsonArray { add("linear") }
            addJsonArray { add("zoom") }
            add(14.0); add(9.5)
            add(17.0); add(11.0)
        }
        put("text-max-width", 8.0)
        put("text-padding", 4.0)
        put("symbol-sort-key", sortKeyBy("min_zoom"))
    }
    putJsonObject("paint") { labelPaint(pal, haloWidth = 1.0) }
}

/**
 * The label text: the device-language name when we can render it, else the endonym (D4).
 *
 * ⚠⚠ **The gate is the load-bearing half, not the coalesce.** The tiles carry ~20 `name:<lang>`
 * variants including `el`, `he`, `ja`, `ko` — asking for one whose script is outside the shipped
 * glyph ranges renders a row of tofu boxes, which is strictly worse than the endonym. So the caller
 * passes a language only after [labelLanguageFor] has cleared it. A null [lang] means "use `name`".
 *
 * Roads deliberately do **not** get this: street names are endonyms by nature (you do not translate
 * "Via dei Pandolfini"), and the tiles barely carry `name:<lang>` for them anyway.
 */
private fun labelName(lang: String?): JsonArray = if (lang == null) {
    buildJsonArray { add("get"); add("name") }
} else {
    buildJsonArray {
        add("coalesce")
        addJsonArray { add("get"); add("name:$lang") }
        addJsonArray { add("get"); add("name") }
    }
}

/**
 * Languages whose script is fully covered by the shipped glyph ranges (`0-255` Basic Latin +
 * Latin-1, `256-511` Latin Extended-A) — see `ui-map-agent.md` *Labels & glyphs*.
 *
 * Deliberately **not** an "is it Latin-ish" heuristic: Vietnamese is Latin but needs Latin Extended
 * Additional (`0x1E00`), which we do not ship, so it is absent and correctly falls back to the
 * endonym. Add a language here only when its glyph ranges are actually bundled.
 */
internal val GLYPH_COVERED_LANGUAGES = setOf(
    "en", "de", "fr", "es", "it", "nl", "pt", "ca", "gl", "eu",
    "da", "sv", "nb", "nn", "no", "fi", "is",
    "cs", "sk", "pl", "sl", "hr", "hu", "ro", "et", "lv", "lt", "tr", "sq",
)

/**
 * Maps a platform language tag (`"de"`, `"de-DE"`, `"pt-BR"`) to the `name:<lang>` suffix to use, or
 * null when we ship no glyphs for that script and must fall back to the endonym.
 *
 * ⚠ Note this cannot protect against the *endonym itself* being unrenderable — a Greek region's
 * `name` is Greek regardless of the reader's locale. That is a glyph-range question for whenever the
 * region catalog leaves Latin script, not a localisation one.
 */
fun labelLanguageFor(languageTag: String?): String? {
    val primary = languageTag?.substringBefore('-')?.substringBefore('_')?.lowercase()
    return primary?.takeIf { it in GLYPH_COVERED_LANGUAGES }
}

/**
 * Street names (LB-2), drawn along the road line — one layer per road class.
 *
 * ⚠ **This is about density, not styling.** One z14 tile over central Florence carries **294 named
 * road features**, 153 of them `path` (footways, steps, alleys), and the tile's own `min_zoom` does
 * not separate them — it puts `minor_road` and `path` at 13, i.e. visible at our default 14.5
 * camera. Labelling that set in a 220 dp snippet is noise, so road class earns its label by camera
 * zoom: arterials always, minor roads at [MINOR_ROAD_LABEL_ZOOM], footpaths at [PATH_LABEL_ZOOM].
 *
 * Measured on device rather than guessed: at the 14.5 snippet camera this yields **one** street name
 * over central Florence, not a wall of them — MapLibre's collision engine and the available line
 * length bound the count long before the feature count does. `symbol-spacing` was tried at 350 to
 * thin repeats and made no observable difference, so it stays at the 250 px default.
 *
 * ⚠⚠ **The gate is the layer's `minzoom`, NOT a `["zoom"]` test inside the filter.** Filters are
 * evaluated per *tile*, so `["zoom"]` there is the tile's canonical zoom and is capped by the
 * archive's `maxzoom` — on the demo archive (maxzoom 14) a `>= 15` filter branch can **never** fire,
 * and on the deployed regions (maxzoom 15) a `>= 16` branch never fires either, overzoom included.
 * Layer `minzoom` is compared against the *camera*, so it behaves as intended at every zoom.
 *
 * `medium_road` exists only in the older demo archive's vocabulary (v4 folded it away), so it rides
 * with the arterials — see [tileAttr] on why two archive generations are in play.
 */
private fun roadsLabelLayer(
    pal: MapPalette,
    id: String,
    kinds: List<String>,
    minZoom: Int? = null,
): JsonObject = buildJsonObject {
    put("id", id)
    put("type", "symbol")
    put("source", "protomaps")
    put("source-layer", "roads")
    minZoom?.let { put("minzoom", it) }
    putJsonArray("filter") {
        add("all")
        add(minZoomGate())
        addJsonArray {
            add("any")
            kinds.forEach { kind ->
                addJsonArray { add("=="); add(tileAttr("kind")); add(kind) }
            }
        }
    }
    putJsonObject("layout") {
        put("symbol-placement", "line")
        put("text-max-angle", 40.0)
        putJsonArray("text-field") { add("get"); add("name") }
        putJsonArray("text-font") { add(FONT_REGULAR) }
        putJsonArray("text-size") {
            add("interpolate")
            addJsonArray { add("linear") }
            addJsonArray { add("zoom") }
            add(14.0); add(9.5)
            add(17.0); add(11.5)
        }
        put("text-padding", 3.0)
        // Arterials before side streets when both fit (sort_rank ascending = more important).
        put("symbol-sort-key", sortKeyBy("sort_rank"))
    }
    putJsonObject("paint") { labelPaint(pal, haloWidth = 1.0) }
}

/**
 * Minor roads earn a label from the snippet's own camera zoom (the default is 14.5). Verified on
 * device: at this threshold central Florence draws one street name, not a wall of them.
 */
internal const val MINOR_ROAD_LABEL_ZOOM = 14

/** Footpaths, steps and alleys later still — they outnumber every other class ~2:1. */
internal const val PATH_LABEL_ZOOM = 16

/** Shared label paint. Every label is haloed — unhaloed text over the block fills is unreadable. */
private fun JsonObjectBuilder.labelPaint(pal: MapPalette, haloWidth: Double = 1.2) {
    put("text-color", pal.label)
    put("text-halo-color", pal.labelHalo)
    put("text-halo-width", haloWidth)
}

/**
 * Reads a tile attribute under **either** Protomaps naming.
 *
 * ⚠⚠ **We ship two archive generations at once.** The deployed regions
 * (`deploy/tiles/build/catalog.json`, `tileSchemaVersion: 4`) name attributes `kind` / `min_zoom` /
 * `sort_key`; the **bundled demo asset** is an older generation that prefixes them `pmap:kind` /
 * `pmap:min_zoom`. The vocabulary is identical (`locality`, `neighbourhood`, `major_road`, …) — only
 * the keys differ — so one `coalesce` covers both and every label layer works on both.
 *
 * This is deliberate tolerance, not an accident: the demo archive is the **only** local archive that
 * still carries the `transit` layer M9's rail matching depends on, so it cannot simply be
 * re-extracted from a current v4 build. See `ui-map-agent.md` *Bundled tiles*. Drop the `pmap:` arm
 * once the demo and the deployed regions come from one pipeline generation.
 */
private fun tileAttr(name: String): JsonArray = buildJsonArray {
    add("coalesce")
    addJsonArray { add("get"); add(name) }
    addJsonArray { add("get"); add("pmap:$name") }
}

/**
 * `min_zoom <= zoom` — the per-feature visibility gate Protomaps bakes into every label layer (D3).
 *
 * ⚠ The final `0` is load-bearing: a `get` on an absent attribute yields null, and a null inside
 * `<=` fails the expression's type check, which drops the **whole layer** rather than the offending
 * feature.
 */
private fun minZoomGate(): JsonArray = buildJsonArray {
    add("<=")
    addJsonArray {
        add("coalesce")
        addJsonArray { add("get"); add("min_zoom") }
        addJsonArray { add("get"); add("pmap:min_zoom") }
        add(0)
    }
    addJsonArray { add("zoom") }
}

/** Collision order from the tile's own ranking attribute (ascending = more important). */
private fun sortKeyBy(name: String): JsonArray = buildJsonArray {
    add("coalesce")
    addJsonArray { add("get"); add(name) }
    addJsonArray { add("get"); add("pmap:$name") }
    add(0)
}

/**
 * The route-overlaid basemap style as a JSON **string** — see [routeBasemapStyle].
 *
 * [routeColorHex] is an opaque `#RRGGBB` like every [MapPalette] entry, and is the one colour a
 * *renderer* supplies rather than the palette: on the Compose side it comes from
 * `MaterialTheme.colorScheme.primary`, on the SwiftUI side from the app's accent colour.
 */
fun routeBasemapStyleJson(
    pal: MapPalette,
    tileUrl: String,
    route: List<BasemapCoord>,
    routeColorHex: String,
    labelLanguage: String? = null,
    glyphsUrl: String = GLYPHS_URL,
): String = routeBasemapStyle(pal, tileUrl, route, routeColorHex, labelLanguage, glyphsUrl).toString()

/**
 * The offline basemap style ([offlineBasemapStyle]) with the route overlaid: the route's own GeoJSON
 * `LineString` + endpoint `Point`s as inline `geojson` sources, and a `line` + `circle` layer painting
 * them above the basemap layers. Rebuilt (rather than mutated) because [JsonObject] is immutable — the
 * base sources/layers are copied through verbatim, keeping House Rule #1 intact (no new tile/font/icon
 * fetch; the only added bytes are the route coordinates themselves).
 *
 * ⚠ Because this **rebuilds** the root object rather than copying it, every root key the base style
 * sets must be carried across explicitly — [GLYPHS_URL] included, or the base's inherited symbol
 * layers would have no font to resolve against. `BasemapStyleTest` asserts both styles agree.
 */
internal fun routeBasemapStyle(
    pal: MapPalette,
    tileUrl: String,
    route: List<BasemapCoord>,
    routeColorHex: String,
    labelLanguage: String? = null,
    glyphsUrl: String = GLYPHS_URL,
): JsonObject {
    val base = offlineBasemapStyle(pal, tileUrl, labelLanguage, glyphsUrl)
    val baseSources = base["sources"]?.jsonObject ?: JsonObject(emptyMap())
    val baseLayers = base["layers"]?.jsonArray ?: JsonArray(emptyList())
    return buildJsonObject {
        put("version", 8)
        put("name", "Tenshu Route")
        put("glyphs", glyphsUrl)
        putJsonObject("sources") {
            baseSources.forEach { (k, v) -> put(k, v) }
            putJsonObject("route") {
                put("type", "geojson")
                put("data", routeLineStringFeature(route))
            }
            putJsonObject("route-endpoints") {
                put("type", "geojson")
                put("data", endpointsFeatureCollection(route))
            }
        }
        putJsonArray("layers") {
            baseLayers.forEach { add(it) }
            add(routeLineLayer(routeColorHex))
            add(routeEndpointsLayer(routeColorHex))
        }
    }
}

/** A GeoJSON `Feature` whose geometry is the route as a `LineString` (coordinates = `[lng, lat]`). */
private fun routeLineStringFeature(route: List<BasemapCoord>): JsonObject = buildJsonObject {
    put("type", "Feature")
    putJsonObject("geometry") {
        put("type", "LineString")
        putJsonArray("coordinates") {
            route.forEach { c -> addJsonArray { add(c.longitude); add(c.latitude) } }
        }
    }
    putJsonObject("properties") {}
}

/** A GeoJSON `FeatureCollection` of the route's two endpoints (start + end) as `Point`s. */
private fun endpointsFeatureCollection(route: List<BasemapCoord>): JsonObject = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        add(pointFeature(route.first()))
        add(pointFeature(route.last()))
    }
}

private fun pointFeature(c: BasemapCoord): JsonObject = buildJsonObject {
    put("type", "Feature")
    putJsonObject("geometry") {
        put("type", "Point")
        putJsonArray("coordinates") { add(c.longitude); add(c.latitude) }
    }
    putJsonObject("properties") {}
}

/**
 * A `line` layer over the inline `route` source, rounded cap/join, width zoom-interpolated around
 * ~4dp. Mirrors `:ui-map`'s `RoutePolyline` stroke so the geographic render reads like the schematic
 * sketch.
 */
private fun routeLineLayer(colorHex: String): JsonObject = buildJsonObject {
    put("id", "route-line")
    put("type", "line")
    put("source", "route")
    putJsonObject("layout") {
        put("line-cap", "round")
        put("line-join", "round")
    }
    putJsonObject("paint") {
        put("line-color", colorHex)
        putJsonArray("line-width") {
            add("interpolate")
            addJsonArray { add("linear") }
            addJsonArray { add("zoom") }
            add(4.0); add(2.5)
            add(10.0); add(4.0)
            add(16.0); add(7.0)
        }
    }
}

/** A `circle` layer marking the two route endpoints (start/end dots). */
private fun routeEndpointsLayer(colorHex: String): JsonObject = buildJsonObject {
    put("id", "route-endpoints")
    put("type", "circle")
    put("source", "route-endpoints")
    putJsonObject("paint") {
        put("circle-color", colorHex)
        put("circle-radius", 4.5)
        put("circle-stroke-width", 1.5)
        put("circle-stroke-color", "#FFFFFF")
    }
}
