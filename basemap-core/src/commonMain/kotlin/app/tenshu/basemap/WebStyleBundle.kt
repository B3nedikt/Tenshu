package app.tenshu.basemap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The offline basemap style **for the web** (`web-app-suite.md` §4, W-MAP): this module's style,
 * emitted at build time as one JSON document that `@tenshu/map-kit` imports, so the web never
 * re-derives a layer in TypeScript.
 *
 * ⚠⚠ Generated, committed, and parity-tested: `./gradlew :basemap-core:generateWebBasemapStyles`
 * writes [WEB_STYLE_BUNDLE_PATH]; `WebStyleBundleParityTest` fails when the committed file is not
 * byte-identical to [webStyleBundleJson], so a style change here cannot ship to native alone.
 *
 * The three things only the browser knows travel as **tokens**, which map-kit replaces structurally:
 *  - [WEB_TILE_URL_TOKEN] — the one vector source's `url` (map-kit: `pmtiles://<region key>`);
 *  - [WEB_GLYPHS_TOKEN] — `glyphs` (map-kit: the map app's own origin + `/glyphs/{fontstack}/{range}.pbf`);
 *  - [WEB_LABEL_LANGUAGE_TOKEN] — in the `localized` variant only, the `name:<lang>` key; map-kit
 *    substitutes a language from `labelLanguages` (the glyph-covered set [labelLanguageFor] admits) and
 *    uses the `endonym` variant for every other language. The parity test proves that substitution
 *    yields exactly [offlineBasemapStyle] for every admitted language.
 *
 * [BASEMAP_MAX_ZOOM] rides along as `maxZoom`, so the web clamps to the number native clamps to
 * without spelling it a second time (`ios-apple-maps-option.md` §8.2 F5).
 */

/** Repo-relative path of the committed bundle. */
const val WEB_STYLE_BUNDLE_PATH = "web/map-kit/src/style/basemap-styles.generated.json"

internal const val WEB_TILE_URL_TOKEN = "@tenshu/tile-url@"
internal const val WEB_GLYPHS_TOKEN = "@tenshu/glyphs-url@"
internal const val WEB_LABEL_LANGUAGE_TOKEN = "@tenshu/label-language@"

private val prettyJson = Json { prettyPrint = true }

/** The committed document, as text (pretty-printed, newline-terminated, key order fixed). */
fun webStyleBundleJson(): String = prettyJson.encodeToString(JsonElement.serializer(), webStyleBundle()) + "\n"

internal fun webStyleBundle(): JsonObject = buildJsonObject {
    put("generatedBy", "./gradlew :basemap-core:generateWebBasemapStyles — do not edit by hand")
    put("maxZoom", BASEMAP_MAX_ZOOM)
    put("tileUrlToken", WEB_TILE_URL_TOKEN)
    put("glyphsToken", WEB_GLYPHS_TOKEN)
    put("labelLanguageToken", WEB_LABEL_LANGUAGE_TOKEN)
    putJsonArray("labelLanguages") { GLYPH_COVERED_LANGUAGES.sorted().forEach { add(it) } }
    putJsonObject("styles") {
        listOf("light" to false, "dark" to true).forEach { (name, dark) ->
            val pal = mapPalette(dark)
            putJsonObject(name) {
                put("endonym", offlineBasemapStyle(pal, WEB_TILE_URL_TOKEN, null, WEB_GLYPHS_TOKEN))
                put("localized", offlineBasemapStyle(pal, WEB_TILE_URL_TOKEN, WEB_LABEL_LANGUAGE_TOKEN, WEB_GLYPHS_TOKEN))
            }
        }
    }
}

/** What map-kit does to `localized` (`web/map-kit/src/style/basemapStyle.ts`): every token string → [language]. */
internal fun substituteLabelLanguage(element: JsonElement, language: String): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (_, v) -> substituteLabelLanguage(v, language) })
    is JsonArray -> JsonArray(element.map { substituteLabelLanguage(it, language) })
    is JsonPrimitive ->
        if (element.isString && element.content.contains(WEB_LABEL_LANGUAGE_TOKEN)) {
            JsonPrimitive(element.content.replace(WEB_LABEL_LANGUAGE_TOKEN, language))
        } else {
            element
        }
}
