package app.tenshu.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tenshu.basemap.MapPalette
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.feedback.AppLoadingSize
import kotlinx.coroutines.delay

/**
 * Where a live basemap's style is in loading — the renderer's `StyleLoadState` reduced to what the
 * overlay needs. Each MapLibre actual maps its own state onto this, since `maplibre-compose` is not
 * a `commonMain` dependency (desktop draws the schematic).
 */
internal enum class BasemapLoadPhase { Loading, Loaded, Failed }

/** How long a style may take before the overlay appears, so a fast load never flashes a spinner. */
internal const val BASEMAP_LOADING_DELAY_MS = 150L

/**
 * Whether [BasemapLoadingOverlay] is visible: only while the style is still loading **and** the
 * [BASEMAP_LOADING_DELAY_MS] grace period has run. A failed style never shows it — the actual's own
 * failure handling (the schematic fallback on Android) is what the user sees instead.
 */
internal fun shouldShowBasemapLoading(phase: BasemapLoadPhase, delayElapsed: Boolean): Boolean =
    phase == BasemapLoadPhase.Loading && delayElapsed

/**
 * A small centred spinner over the basemap's empty colour ([MapPalette.land], the style's
 * `background` layer), shown while a live map's style loads. Place it in the map's `Box` after
 * `MaplibreMap` and before the caller's `content()`, so it covers the blank map but not the chrome.
 *
 * Appears only after [BASEMAP_LOADING_DELAY_MS] of loading, and fades out once [phase] leaves
 * [BasemapLoadPhase.Loading]; the decision is [shouldShowBasemapLoading]. The grace period restarts
 * whenever the style begins loading again (e.g. a theme switch rebuilds it).
 */
@Composable
internal fun BoxScope.BasemapLoadingOverlay(phase: BasemapLoadPhase, palette: MapPalette) {
    var delayElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        delayElapsed = false
        if (phase == BasemapLoadPhase.Loading) {
            delay(BASEMAP_LOADING_DELAY_MS)
            delayElapsed = true
        }
    }
    val background = remember(palette) { palette.land.toComposeColor() }
    AnimatedVisibility(
        visible = shouldShowBasemapLoading(phase, delayElapsed),
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier.background(background),
            contentAlignment = Alignment.Center,
        ) {
            AppLoadingIndicator(size = AppLoadingSize.Inline)
        }
    }
}
