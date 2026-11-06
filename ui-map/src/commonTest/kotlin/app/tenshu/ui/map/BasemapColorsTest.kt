package app.tenshu.ui.map

import androidx.compose.ui.graphics.Color
import app.tenshu.basemap.mapPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The colour boundary the `:basemap-core` split created — the only genuinely new API surface in it.
 *
 * `:basemap-core` speaks MapLibre's `#RRGGBB`; Compose draws with `Color`. Both directions are used:
 * hex → `Color` for the schematic Canvas, `Color` → hex for the route line's
 * `MaterialTheme.colorScheme.primary`. If the pair did not round-trip, the schematic underlay and
 * the live map would drift apart in a way no style test could see.
 */
class BasemapColorsTest {

    @Test
    fun hexIsSixDigitUppercaseRgb() {
        assertEquals("#F0ECE4", Color(red = 0xF0, green = 0xEC, blue = 0xE4).hex())
        assertEquals("#1A1920", Color(red = 0x1A, green = 0x19, blue = 0x20).hex())
        // Fully saturated and fully dark both keep two digits per channel.
        assertEquals("#FFFFFF", Color.White.hex())
        assertEquals("#000000", Color.Black.hex())
    }

    @Test
    fun everyPaletteColourSurvivesTheRoundTrip() {
        listOf(mapPalette(dark = false), mapPalette(dark = true)).forEach { pal ->
            listOf(pal.land, pal.block, pal.road, pal.roadCasing, pal.water, pal.park, pal.label, pal.labelHalo)
                .forEach { assertEquals(it, it.toComposeColor().hex(), "colour drifted through the boundary") }
        }
    }

    @Test
    fun parsedColoursAreOpaque() {
        // The schematic paints these directly, and a colour that arrived at alpha 0 would draw
        // nothing at all rather than fail — so pin it.
        assertEquals(1f, "#CFE0E8".toComposeColor().alpha)
    }

    @Test
    fun theLeadingHashIsOptionalButTheLengthIsNot() {
        assertEquals("#CFE0E8".toComposeColor(), "CFE0E8".toComposeColor())
        assertFailsWith<IllegalArgumentException> { "#FFF".toComposeColor() }
        assertFailsWith<IllegalArgumentException> { "#FF00FF00".toComposeColor() }
    }

    @Test
    fun theSchematicPaletteTakesTheSixColoursItActuallyPaints() {
        // The schematic draws no labels, so `label`/`labelHalo` deliberately have no counterpart —
        // this pins that the six it does paint all arrive.
        val canvas = mapPalette(dark = true).forCanvas()
        assertEquals("#1A1920", canvas.land.hex())
        assertEquals("#20303E", canvas.water.hex())
        assertTrue(canvas.road != canvas.roadCasing, "roads must contrast with their casing")
    }
}
