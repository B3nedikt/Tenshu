package app.tenshu.maps.ui.theme

import androidx.compose.ui.graphics.Color
import app.tenshu.maps.model.PlaceCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Ph4 (docs/proposals/maps-app.md §6) — the by-category accent seam (§4). The pure
 * `categoryAccent(...)` is unit-tested: default is a clean Amethyst no-op; enabled derives a
 * distinct per-category colour via the oklch converter.
 */
class CategoryAccentsTest {

    private val primary = Color(0xFF5A4A96)
    private val secondaryContainer = Color(0xFFE6DEFF)

    @Test
    fun disabled_isAmethystNoOp() {
        val a = categoryAccent(PlaceCategory.Cafe, enabled = false, dark = false, primary, secondaryContainer)
        assertEquals(primary, a.accent)
        assertEquals(secondaryContainer, a.soft)
    }

    @Test
    fun enabled_derivesDistinctPerCategory() {
        val cafe = categoryAccent(PlaceCategory.Cafe, enabled = true, dark = false, primary, secondaryContainer)
        val park = categoryAccent(PlaceCategory.Park, enabled = true, dark = false, primary, secondaryContainer)
        // Derived (not the Amethyst no-op) and category-distinct.
        assertNotEquals(primary, cafe.accent)
        assertNotEquals(cafe.accent, park.accent)
    }

    @Test
    fun oklch_producesInGamutChannels() {
        val c = oklchToColor(0.56f, 0.13f, 55f)
        assertTrue(c.red in 0f..1f && c.green in 0f..1f && c.blue in 0f..1f)
        // A warm café hue (55°) should be red-leaning, not grey.
        assertTrue(c.red > c.blue)
    }
}
