package org.tenshu.mapregion.proxy

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **§33 L3 — the overlay fields on the wire.**
 *
 * ⚠⚠ Both directions of compatibility, because only one of them is free. `ignoreUnknownKeys` already
 * handles a *newer* catalogue reaching an *older* client — extra fields are dropped. It does **not**
 * handle the other direction: a newer client reading a catalogue published before overlays existed
 * would fail to decode on a missing required field, and every region would vanish rather than simply
 * having no line data. That is what the defaults are for, and
 * [aCatalogueFromBeforeOverlaysExistedStillParses] is the test that would catch losing them.
 */
class RegionCatalogOverlayParseTest {

    private val withOverlay = """
        {"schemaVersion":1,"buildDate":"2026-08","regions":[
          {"id":"de-bayern","name":"Bayern","coverage":"8.9,47.2,13.8,50.5","byteSize":123,
           "sha256":"aa","tileSchemaVersion":4,"path":"regions/2026-08/de-bayern.pmtiles",
           "overlayPath":"regions/2026-08/de-bayern.tlne","overlayByteSize":754,
           "overlaySha256":"bb","overlayFormatVersion":1}
        ]}
    """.trimIndent()

    private val beforeOverlays = """
        {"schemaVersion":1,"buildDate":"2026-07","regions":[
          {"id":"at","name":"Austria","coverage":"9.5,46.3,17.1,49.0","byteSize":9,
           "sha256":"cc","tileSchemaVersion":4,"path":"regions/2026-07/at.pmtiles"}
        ]}
    """.trimIndent()

    @Test
    fun anOverlayEntryIsCarriedThrough() {
        val region = parseRegionCatalog(withOverlay).regions.single()
        assertEquals("regions/2026-08/de-bayern.tlne", region.overlayPath)
        assertEquals(754, region.overlayByteSize)
        assertEquals("bb", region.overlaySha256)
        assertEquals(1, region.overlayFormatVersion)
    }

    @Test
    fun aCatalogueFromBeforeOverlaysExistedStillParses() {
        // ⚠⚠ The direction `ignoreUnknownKeys` does not cover. Without defaults this throws and the
        // client loses EVERY region — a total map outage caused by a transit feature.
        val region = parseRegionCatalog(beforeOverlays).regions.single()
        assertEquals("at", region.regionId)
        assertEquals("regions/2026-07/at.pmtiles", region.path, "the tile bundle is unaffected")
        assertEquals("", region.overlayPath)
        assertEquals(0, region.overlayByteSize)
        assertEquals(0, region.overlayFormatVersion)
    }

    @Test
    fun aRegionWithNoRailParsesAsHavingNoOverlayRatherThanFailing() {
        // Blank is the ordinary case, not an error: plenty of regions have no rail at all.
        val json = withOverlay.replace(
            """"overlayPath":"regions/2026-08/de-bayern.tlne"""",
            """"overlayPath":""""",
        )
        assertEquals("", parseRegionCatalog(json).regions.single().overlayPath)
    }
}
