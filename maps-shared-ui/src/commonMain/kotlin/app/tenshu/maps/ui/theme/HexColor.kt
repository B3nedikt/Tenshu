package app.tenshu.maps.ui.theme

import androidx.compose.ui.graphics.Color

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
