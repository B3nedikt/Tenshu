package app.tenshu.timeline.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.model.Photo
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth

/**
 * Shared chrome for the detail screens (§1.2–§1.4): a top app bar with a back chevron, then
 * a vertically-scrolling body. v1 uses a solid `AppTopAppBar` above the hero; the mock's
 * translucent-blur-over-hero bar is deferred (a scroll-aware refinement — noted in §1.2).
 *
 * The body is capped at the shared reading width on expanded windows and centred, so a detail
 * does not become a 1600 px-wide column of 16sp text on a desktop monitor. That cap covers the
 * hero too, deliberately: it is the one element whose *pixels* are finite (the widest stored rung
 * is ~1024 px), so stretching it edge-to-edge is exactly where a preview stops looking like a
 * photograph.
 *
 * [onBack] is nullable because Settings is a nav-chrome destination rather than a pushed one —
 * it sits alone at the bottom of the back stack, where a chevron would have nothing to pop to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineDetailScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { AppTopAppBar(title = title, onBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            // ⚠ `appContentWidth()` FIRST — see `ContentWidthOrderTest`: after a `fillMaxSize()`
            // the cap is silently a no-op, because the fill has already fixed the width.
            Column(
                Modifier
                    .appContentWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        }
    }
}

/**
 * A full-bleed hero band (§1.2–§1.4) with an optional overlay pill.
 *
 * [image] is the decoded photo when the screen has one (`timeline-photos.md` T-P1/T-P2); without it
 * the band renders the gradient it always has.
 *
 * ⚠ **This is the one photo surface allowed to spend the hero rung.** A hero is a single photo on a
 * screen the user deliberately opened, which is exactly the shape that can afford
 * `PhotoByteLoaders.hero`; a feed row is not, and must never pass it. Without this, a hero on a
 * device that does not hold the file is drawn from the 256 px `LARGE` class — a 3–5×
 * upscale, and the whole reason this parameter exists.
 */
@Composable
fun DetailHero(
    photo: Photo,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    overlayPill: String? = null,
    image: ImageBitmap? = null,
) {
    Box(modifier.fillMaxWidth().height(height)) {
        PhotoFrame(
            photo,
            Modifier.fillMaxSize(),
            cornerRadius = 0.dp,
            image = image,
            heroFidelity = true,
        )
        if (overlayPill != null) {
            OverlayPill(overlayPill, Modifier.align(Alignment.TopStart).padding(16.dp))
        }
    }
}

/** A footer affordance row ("All photos · N →", "See all N visits →"). */
@Composable
fun FooterAffordance(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(16.dp),
    )
}
