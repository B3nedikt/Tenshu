package app.tenshu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The surface family is one tonal ramp (Colors.kt, ADR 0001). Before it was spelled out, the
 * container roles fell back to M3 defaults keyed to a different page tone, and in dark mode cards
 * (`surfaceContainerLow`) rendered in the page colour — invisible.
 */
class SurfaceRampTest {

    private fun ColorScheme.ramp() = listOf(
        surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
        surfaceContainerHigh, surfaceContainerHighest,
    ).map { it.luminance() }

    // A card must stand off the page by a visible step, not a rounding error.
    private fun assertCardsVisible(scheme: ColorScheme, name: String) {
        val page = scheme.surface.luminance()
        val card = scheme.surfaceContainerLow.luminance()
        val ratio = (maxOf(page, card) + 0.05f) / (minOf(page, card) + 0.05f)
        assertTrue(ratio >= 1.04f, "$name: surfaceContainerLow vs surface contrast $ratio — cards blend into the page")
    }

    @Test
    fun lightContainersGetDarkerAndStandOffThePage() {
        val ramp = AppColorTokens.LightScheme.ramp()
        assertTrue(ramp.zipWithNext().all { (a, b) -> a > b }, "light ramp not descending: $ramp")
        assertCardsVisible(AppColorTokens.LightScheme, "light")
    }

    @Test
    fun darkContainersGetLighterAndStandOffThePage() {
        val ramp = AppColorTokens.DarkScheme.ramp()
        assertTrue(ramp.zipWithNext().all { (a, b) -> a < b }, "dark ramp not ascending: $ramp")
        assertCardsVisible(AppColorTokens.DarkScheme, "dark")
    }

    @Test
    fun backgroundIsThePage() {
        assertTrue(AppColorTokens.LightScheme.background == AppColorTokens.LightScheme.surface)
        assertTrue(AppColorTokens.DarkScheme.background == AppColorTokens.DarkScheme.surface)
    }
}
