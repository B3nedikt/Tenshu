package app.tenshu.timeline.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.min

/**
 * Personalisation — the calm tint at the heart of the Chapters look (Ph4,
 * docs/proposals/timeline-app.md §5 / timeline-agent.md §5). It ONLY lightens *soft
 * surfaces* and shifts *accent* marks toward a photo's dominant colour; primary text,
 * outlines, and structural colour stay Amethyst. At `strength <= 0` (or no colour) it is a
 * clean no-op — pure Amethyst.
 *
 * [personalise] is a pure function (theme roles passed in) so it is unit-testable; the
 * `@Composable` [rememberPersonalisation] reads them from `MaterialTheme.colorScheme`.
 */
@Immutable
data class Personalisation(
    /** Accent marks (eyebrows, connector dots, small icons). Amethyst `primary` at strength 0. */
    val accent: Color,
    /** Soft surfaces (tinted icon circles, cards). Amethyst `primaryContainer` at strength 0. */
    val soft: Color,
    /** An extremely faint background wash — null unless `strength > 0.4`. */
    val tint: Color?,
)

/**
 * @param dominantHex a photo's dominant colour ("#rrggbb"), or null for no photo.
 * @param strength 0..1 personalisation strength (default 0.7).
 * @param primary Amethyst `primary` — the base accent.
 * @param primaryContainer Amethyst `primaryContainer` — the base soft surface.
 */
fun personalise(
    dominantHex: String?,
    strength: Float,
    primary: Color,
    primaryContainer: Color,
): Personalisation {
    val dominant = dominantHex?.let(::parseHexColor)
    if (strength <= 0f || dominant == null) {
        return Personalisation(accent = primary, soft = primaryContainer, tint = null)
    }
    val accent = lerp(primary, darken(dominant), min(1f, strength * 0.7f))
    val soft = lerp(primaryContainer, soften(dominant), min(1f, strength * 0.85f))
    val tint = if (strength > 0.4f) lerp(Color.White, dominant, 0.06f) else null
    return Personalisation(accent = accent, soft = soft, tint = tint)
}

/** `@Composable` convenience over [personalise], sourcing the Amethyst roles from the theme. */
@Composable
fun rememberPersonalisation(dominantHex: String?, strength: Float): Personalisation =
    personalise(
        dominantHex = dominantHex,
        strength = strength,
        primary = MaterialTheme.colorScheme.primary,
        primaryContainer = MaterialTheme.colorScheme.primaryContainer,
    )

// ── helpers (trivial RGB lerps, per §5) ────────────────────────────────────
private fun darken(c: Color): Color = lerp(c, Color.Black, 0.12f)
private fun soften(c: Color): Color = lerp(c, Color.White, 0.55f)

/** Parses "#rrggbb" / "rrggbb" (also tolerates "#aarrggbb"). Returns null when malformed. */
internal fun parseHexColor(hex: String): Color? {
    val h = hex.removePrefix("#")
    val value = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(value)
        else -> null
    }
}
