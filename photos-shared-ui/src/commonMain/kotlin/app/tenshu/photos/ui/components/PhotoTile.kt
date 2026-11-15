package app.tenshu.photos.ui.components

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tenshu.photos.data.tilePreviewRequestPx
import app.tenshu.photos.model.MotionTurn
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.formatDuration
import app.tenshu.ui.image.decodePreviewBitmap

/**
 * Preview bytes for the photograph at `path`, asked for at `requestPx` — a size from
 * [tilePreviewRequestPx], so it is one of two values and safe to key a cache on.
 */
typealias PreviewLoader = suspend (path: String, requestPx: Int) -> ByteArray?

/**
 * One square in the feed (`photos-app.md` S1).
 *
 * ⚠⚠ **The tile loads its own bytes, keyed on its path.** The alternative — the view model holding
 * a bitmap per row — would keep every thumbnail the user has scrolled past alive for as long as
 * the feed is open, which on a real library is hundreds of megabytes. Keying `produceState` on the
 * path means a recycled tile drops the old bitmap and starts the new load, and nothing outside the
 * composition holds a reference.
 *
 * ⚠ A tile with no bytes stays a **placeholder**, never a broken-image glyph: on a byte-less
 * device the thumbnail may simply not have arrived yet, and that is a wait, not an error.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoTile(
    photo: Photo,
    loadPreview: PreviewLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Album detail's *remove from album* gesture. ⚠ `null` where there is nothing to remove from
     * — the feed and Favourites — rather than a no-op lambda, so a tile cannot advertise a
     * long-press that does nothing.
     */
    onLongClick: (() -> Unit)? = null,
    /**
     * Whether this tile may decode its preview right now (PH-17b).
     *
     * ⚠⚠ **A flag, not a swapped `loadPreview`, and the difference was a shipped bug.** The first
     * version of the scrubber passed `{ null }` in place of the real loader while the thumb was
     * held. But this `produceState` is keyed on `photo.path` alone — so a tile composed during a
     * scrub resolved to `null`, and when the real loader came back the key had **not** changed, the
     * producer never re-ran, and the tile stayed blank for the rest of the session. Scroll down with
     * the thumb, scroll back up, and the photographs were gone.
     *
     * ⚠ As a key it also behaves better than the swap did: `produceState` keeps its current value
     * across a key change, so a tile that already holds a bitmap keeps showing it while scrubbing
     * rather than blanking, and only tiles scrolled into view during the drag are placeholders.
     */
    previewsEnabled: Boolean = true,
    /**
     * Whether this tile is part of the current multi-select (S1).
     *
     * ⚠⚠ The mark is drawn OVER the photograph and the photograph is inset, rather than the tile
     * being tinted: on a grid of pictures a colour wash reads as a property of the picture — a
     * filter, a warning — and several selected tiles in a row look like a rendering fault. Inset
     * plus a check is the reference idiom and it survives a dark photograph, which a tint does not.
     */
    selected: Boolean = false,
    /**
     * The rotation's turn, when it is *this* tile's (`photos-video-support.md` §5 / M5), else null.
     *
     * ⚠⚠ A keyed input in the [previewsEnabled] shape — the feed hands the same `MotionTurn` object
     * to every tile and each keeps only its own — never a lambda that resolves to nothing, for the
     * reason that KDoc records as a shipped bug. The player is keyed on the turn itself, so a new
     * turn on this same tile (the one-video case) is a fresh player at frame one.
     *
     * ⚠ The poster stays underneath at all times (§5.1): the player draws over it and is removed on
     * hand-over, so a tile between turns is never blank — and D4 (the clip starts on the poster's
     * frame) is what makes the seam invisible.
     */
    motion: MotionTurn? = null,
    /** The clip played through once — the rotation's clock. See `MotionTilePlayer`'s contract. */
    onMotionFinished: () -> Unit = {},
) {
    // ⚠⚠ The size this tile is DRAWN at, in physical pixels, and nothing is loaded until layout has
    // said what it is. A fixed request was the blurry grid: "256" was chosen against a tile measured
    // in dp (`tilePreviewRequestPx`). ⚠ Keyed on the request, not the raw size — two values, so a
    // resized window reloads once at the threshold rather than once per pixel.
    var tileEdgePx by remember { mutableIntStateOf(0) }
    // ⚠⚠ Computed ONCE, here, and the producer uses this copy — never the state. Under a
    // `BoxWithConstraints` (every `TenshuTheme` has one) layout reports the size before the first
    // producer has started, so a producer reading the state saw the new size under the old key and
    // loaded, and the key change then loaded the same photograph again. ⚠ The photo's shape is read
    // here too, not captured by the size callback, so a recycled tile never keeps another's.
    val requestPx = if (tileEdgePx == 0) 0 else tilePreviewRequestPx(
        tileEdgePx = tileEdgePx,
        pixelWidth = photo.pixelWidth,
        pixelHeight = photo.pixelHeight,
    )
    // ⚠ ED-3: [Photo.previewPath] — an edited photo's tile is its rendition, or a placeholder until
    // that arrives; never the original (`photos-editing.md` §12.4).
    val wakes = LocalPreviewWakes.current
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(
        initialValue = null,
        photo.previewPath,
        previewsEnabled,
        requestPx,
    ) {
        if (!previewsEnabled || requestPx == 0) return@produceState
        // ⚠⚠ **"Not arrived yet" is asked again, for as long as the tile is on screen.** A
        // photograph's row syncs before its thumbnail does, so the tile of one that has just come
        // in is composed while there are no bytes anywhere — and one ask, keyed on the path, left
        // it a placeholder until a scroll recycled the tile (`photos-app.md` §41.2). ⚠ Backing off
        // to a slow steady rate, never stopping: a photograph nobody will ever mint a thumbnail
        // for costs one read a minute while visible, and stopping is how a tile stays blank.
        // ⚠ Bytes that do not decode are an answer, and are not asked for again.
        var waitMs = PREVIEW_RETRY_FIRST_MS
        while (true) {
            // ⚠⚠ Read BEFORE the ask. A wake that lands while the ask is in flight — sync coming
            // back during a slow `GetPreview` — must still count, and a counter keeps it where a
            // one-shot signal would be lost between iterations.
            val seen = wakes.value
            val bytes = loadPreview(photo.previewPath, requestPx)
            if (bytes != null) {
                value = bytes.decodePreviewBitmap()
                break
            }
            // ⚠ SS-3: woken early when sync comes back or a catch-up ends — the moments a missing
            // thumbnail is likely to have become fetchable — and the backoff then starts over.
            // ⚠ A race of two flows, not `withTimeoutOrNull`: the timeout half of that is scheduled
            // outside the frame clock's dispatcher, so under a test clock it never fires.
            val wait = waitMs
            val woken = merge(flow { delay(wait); emit(false) }, wakes.filter { it != seen }.map { true }).first()
            waitMs = if (woken) PREVIEW_RETRY_FIRST_MS else (waitMs * 2).coerceAtMost(PREVIEW_RETRY_MAX_MS)
        }
    }

    Box(
        modifier = modifier
            .testTag(PHOTO_TILE_TAG)
            .aspectRatio(1f)
            // ⚠ Before the selection inset, so selecting a tile never changes what it asks for.
            .onSizeChanged { tileEdgePx = maxOf(it.width, it.height) }
            // ⚠ The inset is on the OUTSIDE of the clip, so the selected tile shrinks within its
            // cell and the grid's spacing does the highlighting — no extra chrome, no reflow.
            .padding(if (selected) 6.dp else 0.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        // ⚠ A handle per tile: `combinedClickable` merges the tile's
        // descendants into one node, so nothing inside it can be addressed on its own.
        // Tests reach a tile by index through this.
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                // The filename is not a description of the picture, and reading
                // "IMG_0421.jpg" aloud helps nobody — so the tile is decorative and the
                // screen reader is pointed at the date header instead.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // M5: the one player, over the poster, only while this tile holds the turn. Below the
        // selection mark and the badge, which stay readable over a moving picture too.
        if (motion != null) {
            Box(Modifier.fillMaxSize().testTag(MOTION_TILE_PLAYER_TAG)) {
                MotionTilePlayer(
                    turn = motion,
                    onFinished = onMotionFinished,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                // ⚠ Described, unlike the picture itself: the tile is decorative, but whether it
                // is selected is the one thing about it a screen reader must be able to say.
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(20.dp)
                    .testTag(PHOTO_TILE_SELECTED_TAG),
            )
        }

        if (photo.isVideo) {
            val duration = photo.durationMs?.let { formatDuration(it) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    // ⚠⚠ A scrim, and a fixed light content colour rather than a theme one. The
                    // decoration is drawn on top of a **photograph**, and a photograph does not
                    // follow the theme: `onSurfaceVariant` is dark in light theme, so the glyph
                    // used to disappear into any bright frame — a white wall, a sky, snow. The
                    // same reasoning as the selection mark above, which is an inset plus a check
                    // rather than a colour wash for exactly this reason.
                    .background(SCRIM_COLOR, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    // ⚠ Described as one node, unlike the picture. The tile is decorative because
                    // reading "IMG_0421.jpg" aloud helps nobody — but a duration is information a
                    // sighted user gets from the badge and a screen-reader user would otherwise
                    // have no route to at all.
                    .semantics {
                        contentDescription = duration
                            ?.let { "Video, $it" }
                            ?: "Video"
                    },
            ) {
                if (duration != null) {
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.labelSmall,
                        color = BADGE_CONTENT_COLOR,
                        modifier = Modifier.testTag(PHOTO_TILE_DURATION_TAG),
                    )
                }
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = BADGE_CONTENT_COLOR,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * ⚠ Fixed, not `colorScheme.scrim`: it sits over image content in both themes, so it must be the
 * same in both. 55 % black is the least that survives a white frame without dulling a dark one.
 */
private val SCRIM_COLOR = Color.Black.copy(alpha = 0.55f)

/** ⚠ Fixed for the same reason as [SCRIM_COLOR] — the scrim is always dark, so this is always light. */
private val BADGE_CONTENT_COLOR = Color.White

/** Test handle for one tile — see the merge note at its `combinedClickable`. */
const val PHOTO_TILE_TAG = "photoTile"

/** Test handle for the multi-select mark (S1) — present only on a selected tile. */
const val PHOTO_TILE_SELECTED_TAG = "photoTileSelected"

/**
 * Test handle for the duration badge — present only on a video whose duration is **known**
 * (`photos-video-support.md` M1), so its absence distinguishes a still from an unstamped video.
 */
const val PHOTO_TILE_DURATION_TAG = "photoTileDuration"

/**
 * How many times every waiting tile has been told to ask again at once (`suite-sync-status.md`
 * SS-3) — provided by the app shell over Control's rows (`controlPreviewWakes`).
 *
 * ⚠ A count, not a one-shot signal: a tile compares it before and after its own ask, so a wake that
 * lands mid-ask is not lost. The default never moves, so a tile with no provider (a test, a
 * preview) backs off exactly as before.
 */
val LocalPreviewWakes = staticCompositionLocalOf<StateFlow<Int>> { MutableStateFlow(0) }

/** How long a tile with no bytes waits before asking again — doubling from the first to the cap. */
internal const val PREVIEW_RETRY_FIRST_MS = 1_000L
internal const val PREVIEW_RETRY_MAX_MS = 60_000L
