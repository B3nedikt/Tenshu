package app.tenshu.basemap

/**
 * Basemap colours — low-chroma, so the pin is the only saturated mark (Maps design handoff §2).
 * [label] and [labelHalo] keep that rule: label text is neutral (near-black on light, near-white on
 * dark) and the halo is the land colour it sits on, so names read without competing with the pin.
 *
 * ## Why every colour here is a `#RRGGBB` string
 *
 * This used to be `androidx.compose.ui.graphics.Color`, and it was the *only* reason a 598-line file
 * of pure JSON construction imported Compose. Moving the style into a Compose-free module needed a
 * colour representation, and the three candidates are not equivalent for the consumer this module
 * exists to serve:
 *
 * - **A hex `String` (chosen).** It is what the style already serialises to — MapLibre paint
 *   properties take exactly `"#RRGGBB"` — so there is no conversion at all on the path that matters,
 *   and the constants below are now written as the literals they emit rather than as `Color(0xFF…)`
 *   values that had to be round-tripped through `.hex()`. Across the Obj-C bridge a `String` arrives
 *   as a Swift `String` with no surprises.
 * - **An ARGB `Int`.** Compact and conventional, but it exports as Swift `Int32`, and the natural
 *   Swift literal for an opaque colour — `0xFFF0ECE4` — does not fit a signed 32-bit integer, so the
 *   obvious call site is a compile error. `Long` fixes the range and reads as an arbitrary number.
 * - **A `@JvmInline value class` over one of those.** The type-safe option on the Kotlin side, and
 *   the wrong one at the boundary: Kotlin/Native mangles the names of declarations whose signatures
 *   mention an inline class, so the Swift-facing entry points would be exported under names no one
 *   would guess — defeating the single purpose of the extraction.
 *
 * The cost accepted is that malformed hex is not a compile error. It is bounded: [mapPalette] is the
 * only function that mints a palette, and [BASEMAP_COLOR_PATTERN] pins the shape in test.
 */
data class MapPalette(
    val land: String,
    val block: String,
    val road: String,
    val roadCasing: String,
    val water: String,
    val park: String,
    val label: String,
    val labelHalo: String,
)

/**
 * The shape every colour in a [MapPalette] must have: opaque `#RRGGBB`, uppercase.
 *
 * Opaque is not an omission — no layer in the style paints with a colour's alpha channel; the two
 * places that want translucency (`fill-opacity`, `line-opacity`) set it as its own paint property,
 * where MapLibre can interpolate it.
 */
const val BASEMAP_COLOR_PATTERN: String = "^#[0-9A-F]{6}$"

/** The light and dark basemap palettes. The only place a [MapPalette] is minted. */
fun mapPalette(dark: Boolean): MapPalette = if (dark) {
    MapPalette(
        land = "#1A1920", block = "#232128", road = "#3A3742",
        roadCasing = "#14131A", water = "#20303E", park = "#1F2A20",
        label = "#E8E4EC", labelHalo = "#1A1920",
    )
} else {
    MapPalette(
        land = "#F0ECE4", block = "#E7E1D6", road = "#FFFFFF",
        roadCasing = "#D8D2C6", water = "#CFE0E8", park = "#DBE6D4",
        label = "#2A2822", labelHalo = "#F7F4EE",
    )
}
