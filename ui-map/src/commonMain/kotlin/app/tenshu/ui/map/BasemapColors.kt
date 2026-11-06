package app.tenshu.ui.map

import androidx.compose.ui.graphics.Color
import app.tenshu.basemap.MapPalette
import kotlin.math.roundToInt

/*
 * The colour boundary between `:basemap-core` and Compose.
 *
 * `:basemap-core` represents a colour the way MapLibre does — an opaque `#RRGGBB` string (see
 * `MapPalette`'s KDoc for why that, and not an ARGB `Int` or a value class). Compose draws with
 * `androidx.compose.ui.graphics.Color`. Exactly two conversions are needed, and both live here so
 * neither module has to know about the other's colour type:
 *
 * - [toComposeColor] — hex → `Color`, for the schematic Canvas fallback, which paints real Compose
 *   colours rather than emitting a style;
 * - [hex] — `Color` → hex, for the one colour a *renderer* contributes rather than the palette:
 *   `MaterialTheme.colorScheme.primary`, which paints the route line.
 *
 * The pair round-trips, which is what `BasemapColorsTest` pins.
 */

/**
 * `#RRGGBB` → an opaque Compose [Color].
 *
 * Hand-rolled rather than a platform parser — this is `commonMain`. Malformed input throws, which is
 * the honest outcome: the only producer is `mapPalette`, so a failure here means the palette itself
 * is broken and silently painting black would hide it.
 */
internal fun String.toComposeColor(): Color {
    val hex = removePrefix("#")
    require(hex.length == 6) { "expected an opaque #RRGGBB colour, got '$this'" }
    fun channel(at: Int): Int = hex.substring(at, at + 2).toInt(16)
    return Color(red = channel(0), green = channel(2), blue = channel(4))
}

/**
 * `#RRGGBB` for a MapLibre paint value, from a low-chroma Compose [Color].
 *
 * Hand-rolled rather than `String.format` — that is JVM-only, and this file is `commonMain`.
 */
internal fun Color.hex(): String {
    fun channel(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)
    fun byteHex(v: Int): String {
        val digits = "0123456789ABCDEF"
        return "${digits[(v shr 4) and 0xF]}${digits[v and 0xF]}"
    }
    return "#${byteHex(channel(red))}${byteHex(channel(green))}${byteHex(channel(blue))}"
}

/**
 * The six palette entries the schematic Canvas actually paints with, as Compose colours.
 *
 * The schematic draws no labels, so [MapPalette.label] / [MapPalette.labelHalo] have no counterpart
 * here — they exist only for the style's symbol layers.
 */
internal data class SchematicPalette(
    val land: Color,
    val block: Color,
    val road: Color,
    val roadCasing: Color,
    val water: Color,
    val park: Color,
)

/** Converts once per render, at the boundary, rather than per draw call. */
internal fun MapPalette.forCanvas(): SchematicPalette = SchematicPalette(
    land = land.toComposeColor(),
    block = block.toComposeColor(),
    road = road.toComposeColor(),
    roadCasing = roadCasing.toComposeColor(),
    water = water.toComposeColor(),
    park = park.toComposeColor(),
)
