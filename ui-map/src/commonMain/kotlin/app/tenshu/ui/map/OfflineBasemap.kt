package app.tenshu.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Place
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.basemap.mapPalette
import app.tenshu.ui.map.resources.Res
import app.tenshu.ui.map.resources.basemap_apple_attribution
import app.tenshu.ui.map.resources.basemap_offline_attribution
import app.tenshu.ui.map.resources.basemap_osm_attribution
import app.tenshu.ui.map.resources.basemap_scale_200m
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

/**
 * The reusable offline vector basemap surface (extracted from Maps Ph7b — docs/proposals/
 * maps-app.md §6; see `ui-map-agent.md`). The render is platform-specific behind this one API:
 *
 * - **Android** ([`OfflineBasemap.android.kt`]) renders a **live MapLibre Native basemap** over a
 *   bundled offline PMTiles region — fully offline, keyless, no glyph/sprite/tile egress
 *   (House Rule #1).
 * - **Desktop** ([`OfflineBasemap.desktop.kt`]) renders the **schematic Canvas** basemap — the
 *   MapLibre-Compose desktop target is early-stage (offline sources unsupported), so a live map
 *   would be blank or would breach House Rule #1.
 *
 * [content] is overlaid on the basemap (a `BoxScope`, centre-anchored) — pass [BasemapChrome] for
 * the standard single-pin + attribution + scale. When [center] is null a per-[seed] location
 * inside the bundled region is used (mock/demo); [seed] also drives the schematic's deterministic
 * layout. Both platforms share [BasemapChrome], so the two renders are visually interchangeable.
 *
 * [regionBundlePath] is the app-side terminus of Organic-Maps region-bundle delivery
 * (docs/proposals/maps-region-delivery.md D5/D6): an **absolute path to a downloaded `.pmtiles`
 * region** already staged on local disk. When non-null (and the file exists) the Android render
 * loads THAT region instead of the bundled Firenze demo; a missing file, or any MapLibre load
 * failure, falls back to the bundled demo and then to [SchematicBasemap] so the surface never
 * blanks (see `resolveBasemapSource` in `:basemap-core`). Desktop ignores it (schematic-only),
 * exactly as it ignores [center].
 *
 * [streamSource] (D10 streamed default): when non-null the Android render **streams** — it fetches
 * only the covering tiles for the camera into a sparse file (see [RegionStreamSource]) rather than
 * requiring a fully-downloaded [regionBundlePath]. A stream failure falls back to
 * [regionBundlePath] → demo → schematic, so the surface never blanks. Desktop ignores it.
 *
 * [height] fixes the surface's height; `null` fills whatever height the parent gives instead, which
 * is what a full-screen map wants (see [RouteBasemap]).
 */
@Composable
expect fun OfflineBasemap(
    modifier: Modifier = Modifier,
    height: Dp? = 200.dp,
    seed: Int = 0,
    center: BasemapCoord? = null,
    regionBundlePath: String? = null,
    streamSource: RegionStreamSource? = null,
    content: @Composable BoxScope.() -> Unit,
)

/**
 * The one place a basemap surface decides its own size: always as wide as the parent allows, and
 * either a fixed [height] or — when that is `null` — as tall as the parent allows too. Shared by the
 * live and schematic renders on both platforms so a full-screen map cannot end up sized one way on
 * Android and another on desktop.
 */
internal fun Modifier.basemapSurfaceSize(height: Dp?): Modifier =
    fillMaxWidth().let { if (height == null) it.fillMaxHeight() else it.height(height) }

/** Whose map data a render draws under [BasemapChrome] — which decides the credit the chip shows. */
internal enum class BasemapData {
    /** The schematic sketch: no third-party data at all ("Offline · MapLibre"). */
    Schematic,

    /** The live MapLibre path on Android and iOS: OpenStreetMap-derived vector tiles. */
    OpenStreetMap,

    /** The desktop's elected Apple Maps snapshot (`AppleMapsSnapshot`). */
    AppleMaps,
}

/**
 * The data the surrounding render draws: [BasemapData.Schematic] unless a render says otherwise.
 *
 * ⚠⚠ The ODbL requires the "© OpenStreetMap contributors" credit wherever the data is shown
 * (`docs/oss-licences.md` §3), and [BasemapChrome] is the only attribution a Tenshu map has — both
 * MapLibre renders hide MapLibre's own attribution button. So a live render MUST run its `content`
 * through [OsmDataContent]; a new one that calls `content()` directly ships OSM tiles uncredited.
 * ⚠ The desktop Apple Maps snapshot runs its `content` through [AppleMapsDataContent]: there the
 * chip credits Apple Maps and links its legal notices, and must never claim MapLibre or OSM.
 */
internal val LocalBasemapData = staticCompositionLocalOf { BasemapData.Schematic }

/** Runs a live render's chrome [content] with [LocalBasemapData] = OpenStreetMap. */
@Composable
internal fun BoxScope.OsmDataContent(content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalBasemapData provides BasemapData.OpenStreetMap) { content() }
}

/** Runs the Apple Maps snapshot's chrome [content] with [LocalBasemapData] = AppleMaps. */
@Composable
internal fun BoxScope.AppleMapsDataContent(content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalBasemapData provides BasemapData.AppleMaps) { content() }
}

/**
 * Apple's map-data acknowledgements — the page MapKit's own "Legal" link opens (the OS frameworks
 * carry this URL; checked 2026-10-24). It names Apple's data providers, OpenStreetMap included,
 * which a link to Apple's Maps terms of use would not.
 */
internal const val APPLE_MAPS_LEGAL_URL = "https://gspe21-ssl.ls.apple.com/html/attribution.html"

/**
 * The standard basemap chrome overlaid on both the live and schematic renders: a single centre
 * pin, the attribution chip, and a scale-bar label. Kept in one place so the two platform renders
 * stay pixel-identical.
 *
 * The chip reads "Offline · © OpenStreetMap contributors" over live tiles (the ODbL credit, see
 * [LocalBasemapData]), "© Apple Maps · Legal" over the desktop's Apple Maps snapshot (a tap opens
 * Apple's map-data acknowledgements, as MapKit's own Legal link does), and "Offline · MapLibre" over the schematic sketch, which draws no
 * third-party data at all.
 */
@Composable
fun BoxScope.BasemapChrome(showPin: Boolean = true) {
    if (showPin) {
        Icon(
            imageVector = BasemapIcons.pin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center).size(36.dp).offset(y = (-14).dp),
        )
    }

    val data = LocalBasemapData.current
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = Modifier.align(Alignment.BottomStart).padding(MaterialTheme.spacing.s)
            .then(
                if (data == BasemapData.AppleMaps) {
                    Modifier.clickable { runCatching { uriHandler.openUri(APPLE_MAPS_LEGAL_URL) } }
                } else Modifier,
            ),
        shape = RoundedCornerShape(MaterialTheme.spacing.s),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.s, vertical = MaterialTheme.spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(BasemapIcons.layers, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
            val attribution = when (data) {
                BasemapData.OpenStreetMap -> Res.string.basemap_osm_attribution
                BasemapData.AppleMaps -> Res.string.basemap_apple_attribution
                BasemapData.Schematic -> Res.string.basemap_offline_attribution
            }
            Text(stringResource(attribution), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Text(
        text = stringResource(Res.string.basemap_scale_200m),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.align(Alignment.BottomEnd).padding(MaterialTheme.spacing.s),
    )
}

private object BasemapIcons {
    val pin: ImageVector get() = Icons.Outlined.Place
    val layers: ImageVector get() = Icons.Outlined.Layers
}

/**
 * The schematic offline basemap (Ph7a — port of the design handoff's `maps-shared.jsx`): a
 * jittered/skewed street grid + block fills, an occasional river or park, and offline attribution
 * via [content]. Desktop's `actual`, and the Android `actual`'s fallback while tiles copy in / on
 * load failure.
 *
 * The one renderer that paints real Compose colours instead of emitting a style, so it converts the
 * shared `MapPalette` at the boundary ([forCanvas]).
 */
@Composable
internal fun SchematicBasemap(
    modifier: Modifier,
    height: Dp?,
    seed: Int,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = mapPalette(dark = isSystemInDarkTheme()).forCanvas()
    val shape = RoundedCornerShape(MaterialTheme.spacing.m)

    Box(
        modifier = modifier
            .basemapSurfaceSize(height)
            .clip(shape)
            .drawBehind { drawSchematicBasemap(seed, palette) },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Deterministic LCG matching the handoff's `rng(seed)` — a stable layout per `seed`. */
internal class Lcg(seed: Int) {
    private var s: Long = ((seed.toLong() * 9301 + 49297) % 233280).let { if (it == 0L) 1L else it }
    fun next(): Float {
        s = (s * 9301 + 49297) % 233280
        return (s.toDouble() / 233280.0).toFloat()
    }
}

/**
 * Draws the schematic basemap (port of `maps-shared.jsx` `MapSnippet`): a jittered, slightly
 * skewed street grid with block fills, an occasional diagonal river (`seed % 3 == 0`) or park
 * (`seed % 2 == 0`), road casings under thinner roads, with two arterials drawn wider.
 */
private fun DrawScope.drawSchematicBasemap(seed: Int, pal: SchematicPalette) {
    val w = size.width
    val h = size.height
    val r = Lcg(seed)

    // Land base.
    drawRect(pal.land)

    // Vertical + horizontal street coordinates, jittered.
    val vx = mutableListOf<Float>()
    var x = -20f
    while (x < w + 20f) { vx.add(x + (r.next() - 0.5f) * 26f); x += 46f + r.next() * 34f }
    val hy = mutableListOf<Float>()
    var y = -20f
    while (y < h + 20f) { hy.add(y + (r.next() - 0.5f) * 22f); y += 44f + r.next() * 30f }

    val skew = (r.next() - 0.5f) * 0.28f
    fun tfx(px: Float, py: Float) = px + (py - h / 2f) * skew

    val artV = if (vx.size > 2) 1 + floor(r.next() * (vx.size - 2)).toInt() else -1
    val artH = if (hy.size > 2) 1 + floor(r.next() * (hy.size - 2)).toInt() else -1

    val hasWater = seed % 3 == 0
    val hasPark = !hasWater && seed % 2 == 0
    val parkX = r.next() * w * 0.5f
    val parkY = r.next() * h * 0.4f + h * 0.1f
    val parkW = 90f + r.next() * 60f
    val parkH = 70f + r.next() * 50f

    if (hasPark) {
        drawRoundRect(
            color = pal.park,
            topLeft = Offset(parkX, parkY),
            size = Size(parkW, parkH),
            cornerRadius = CornerRadius(16f, 16f),
        )
    }
    if (hasWater) {
        val river = Path().apply {
            moveTo(-20f, h * 0.7f)
            quadraticTo(w * 0.4f, h * 0.5f, w + 20f, h * 0.85f)
            lineTo(w + 20f, h + 20f)
            lineTo(-20f, h + 20f)
            close()
        }
        drawPath(river, pal.water)
    }

    // Block fills between the grid.
    for (j in 0 until hy.size - 1) {
        for (i in 0 until vx.size - 1) {
            val bw = (vx[i + 1] - vx[i]) - 10f
            val bh = (hy[j + 1] - hy[j]) - 10f
            if (bw < 8f || bh < 8f) continue
            drawRoundRect(
                color = pal.block,
                topLeft = Offset(tfx(vx[i] + 5f, hy[j] + 5f), hy[j] + 5f),
                size = Size(bw, bh),
                alpha = 0.9f,
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }

    // Road casings (wider, darker) then roads (thinner, lighter); two arterials drawn wider.
    fun vLine(xx: Float, color: Color, stroke: Float) =
        drawLine(color, Offset(tfx(xx, 0f), 0f), Offset(tfx(xx, h), h), strokeWidth = stroke)
    fun hLine(yy: Float, color: Color, stroke: Float) =
        drawLine(color, Offset(0f, yy), Offset(w, yy), strokeWidth = stroke)

    vx.forEachIndexed { i, xx -> vLine(xx, pal.roadCasing, if (i == artV) 9f else 5f) }
    hy.forEachIndexed { i, yy -> hLine(yy, pal.roadCasing, if (i == artH) 9f else 5f) }
    vx.forEachIndexed { i, xx -> vLine(xx, pal.road, if (i == artV) 6f else 2.6f) }
    hy.forEachIndexed { i, yy -> hLine(yy, pal.road, if (i == artH) 6f else 2.6f) }
}
