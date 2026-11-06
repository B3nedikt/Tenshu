package app.tenshu.basemap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ The web's basemap style is THIS module's, or the build is red. `@tenshu/map-kit` imports a
 * committed JSON document; this pins it to [webStyleBundleJson] byte for byte, so a change to
 * [offlineBasemapStyle], [mapPalette], the glyph-covered languages or [BASEMAP_MAX_ZOOM] that is not
 * regenerated (`./gradlew :basemap-core:generateWebBasemapStyles`) fails here rather than shipping a
 * native-only style. Opened by relative path, like [DemoArchiveBoundsTest].
 */
class WebStyleBundleParityTest {

    private val committed = File("../$WEB_STYLE_BUNDLE_PATH")

    @Test
    fun theCommittedWebStyleIsTheGeneratedOne() {
        assertTrue(committed.exists(), "missing ${committed.absolutePath} — run :basemap-core:generateWebBasemapStyles")
        assertEquals(
            webStyleBundleJson(),
            committed.readText(),
            "web/map-kit's basemap style drifted from :basemap-core — run ./gradlew :basemap-core:generateWebBasemapStyles",
        )
    }

    @Test
    fun substitutingALanguageIntoTheLocalizedTemplate_isExactlyTheNativeStyleForThatLanguage() {
        val bundle = webStyleBundle()
        val languages = bundle["labelLanguages"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(GLYPH_COVERED_LANGUAGES.sorted(), languages)
        listOf("light" to false, "dark" to true).forEach { (name, dark) ->
            val styles = bundle["styles"]!!.jsonObject[name]!!.jsonObject
            languages.forEach { lang ->
                assertEquals(
                    offlineBasemapStyle(mapPalette(dark), WEB_TILE_URL_TOKEN, lang, WEB_GLYPHS_TOKEN),
                    substituteLabelLanguage(styles["localized"]!!, lang),
                    "$name/$lang",
                )
            }
            assertEquals(offlineBasemapStyle(mapPalette(dark), WEB_TILE_URL_TOKEN, null, WEB_GLYPHS_TOKEN), styles["endonym"])
        }
    }

    @Test
    fun theOnlyUrlsAreTheTwoTokens() {
        // House Rule #1 / the brief: no http(s) anywhere in what the web gets; map-kit fills the tokens.
        val strings = mutableListOf<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.values.forEach(::walk)
                is kotlinx.serialization.json.JsonArray -> e.forEach(::walk)
                is JsonPrimitive -> if (e.isString) strings += e.content
            }
        }
        walk(webStyleBundle()["styles"]!!)
        assertTrue(strings.none { "://" in it }, strings.filter { "://" in it }.toString())
        assertTrue(WEB_TILE_URL_TOKEN in strings && WEB_GLYPHS_TOKEN in strings)
    }

    @Test
    fun maxZoomIsTheNativeClamp() {
        assertEquals(BASEMAP_MAX_ZOOM, Json.parseToJsonElement(committed.readText()).jsonObject["maxZoom"]!!.jsonPrimitive.content.toDouble())
    }
}
