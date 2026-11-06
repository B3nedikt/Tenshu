package app.tenshu.maps.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.tenshu.maps.model.PlaceCategory
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The optional **by-category** accent seam (Ph4, docs/proposals/maps-app.md §6 / handoff §4)
 * — analogous to Timeline's `personalise()` and Fit's `activityColor`. Default chrome is pure
 * Amethyst; when the setting is on, *marks only* (the category glyph + the pin/marker stroke)
 * shift to a calm per-type hue. Text, outlines, and structural fills never move, so contrast
 * stays safe.
 *
 * At the default ([enabled] = false) it is a clean no-op: [CategoryAccent.accent] = Amethyst
 * `primary`, [CategoryAccent.soft] = `secondaryContainer`. When on, the accent is *derived*
 * (not pasted) from the mock's oklch hue per category at one lightness/chroma per theme —
 * see [oklchToColor]. [accent] is a pure function (theme roles passed in) so it is
 * unit-testable; the `@Composable` [rememberCategoryAccent] sources the roles from the theme.
 */
@Immutable
data class CategoryAccent(
    /** The glyph / pin-stroke colour. */
    val accent: Color,
    /** The soft avatar-circle fill. */
    val soft: Color,
)

/** Per-category hue (deg) for the by-category tint — the mock's `CATEGORY[cat].hue` (§ handoff). */
private fun hueOf(category: PlaceCategory): Float = when (category) {
    PlaceCategory.Home -> 300f
    PlaceCategory.Work -> 260f
    PlaceCategory.Cafe -> 55f
    PlaceCategory.Restaurant -> 28f
    PlaceCategory.Park -> 150f
    PlaceCategory.Store -> 200f
    PlaceCategory.Culture -> 330f
    PlaceCategory.Transit -> 235f
    PlaceCategory.Viewpoint -> 190f
}

/**
 * The accent for [category]. When [enabled] is false, returns the Amethyst no-op
 * ([primary] / [secondaryContainer]); when true, derives the per-category hue at the mock's
 * lightness/chroma for the theme ([dark]).
 */
fun categoryAccent(
    category: PlaceCategory,
    enabled: Boolean,
    dark: Boolean,
    primary: Color,
    secondaryContainer: Color,
): CategoryAccent {
    if (!enabled) return CategoryAccent(accent = primary, soft = secondaryContainer)
    val h = hueOf(category)
    // oklch(L C H) per theme — matches the mock's categoryColor / categorySoft.
    val accent = if (dark) oklchToColor(0.80f, 0.11f, h) else oklchToColor(0.56f, 0.13f, h)
    val soft = if (dark) oklchToColor(0.34f, 0.06f, h) else oklchToColor(0.94f, 0.04f, h)
    return CategoryAccent(accent = accent, soft = soft)
}

/** `@Composable` convenience — sources the Amethyst roles from the theme. */
@Composable
fun rememberCategoryAccent(
    category: PlaceCategory,
    enabled: Boolean,
    dark: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
): CategoryAccent = categoryAccent(
    category = category,
    enabled = enabled,
    dark = dark,
    primary = MaterialTheme.colorScheme.primary,
    secondaryContainer = MaterialTheme.colorScheme.secondaryContainer,
)

/**
 * Convert an oklch colour (L 0..1, C chroma, H degrees) to sRGB [Color]. oklch → oklab →
 * linear sRGB (the standard Björn Ottosson matrices) → gamma-encoded sRGB, clamped to gamut.
 * Faithful to the design's `oklch(...)` marks so the accent is derived, not hand-pasted.
 */
internal fun oklchToColor(l: Float, c: Float, hDeg: Float): Color {
    val hRad = hDeg * (PI_F / 180f)
    val a = c * cos(hRad)
    val b = c * sin(hRad)

    // oklab → LMS (cube of the intermediate), then LMS → linear sRGB.
    val l_ = l + 0.3963377774f * a + 0.2158037573f * b
    val m_ = l - 0.1055613458f * a - 0.0638541728f * b
    val s_ = l - 0.0894841775f * a - 1.2914855480f * b
    val lC = l_ * l_ * l_
    val mC = m_ * m_ * m_
    val sC = s_ * s_ * s_

    val r = 4.0767416621f * lC - 3.3077115913f * mC + 0.2309699292f * sC
    val g = -1.2684380046f * lC + 2.6097574011f * mC - 0.3413193965f * sC
    val bl = -0.0041960863f * lC - 0.7034186147f * mC + 1.7076147010f * sC

    return Color(
        red = gamma(r).coerceIn(0f, 1f),
        green = gamma(g).coerceIn(0f, 1f),
        blue = gamma(bl).coerceIn(0f, 1f),
    )
}

private const val PI_F = 3.1415927f

/** Linear-sRGB → gamma-encoded sRGB. */
private fun gamma(x: Float): Float {
    val v = x.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
}
