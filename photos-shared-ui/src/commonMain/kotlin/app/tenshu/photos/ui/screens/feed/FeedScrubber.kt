package app.tenshu.photos.ui.screens.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import app.tenshu.photos.ui.screens.feed.FeedYearMark
import app.tenshu.photos.ui.screens.feed.scrubberPills
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The feed's fast-scroll thumb (`photos-feed-scrubber.md`).
 *
 * ⚠⚠ **The thumb scrubs the list the feed currently holds; the year pills describe the whole
 * library.** The feed pages a partial copy while `RealmContentIndex` holds all of it (§3), so a
 * year track marked from the loaded rows would be a lie about where 2019 is — see [yearMarks].
 *
 * ⚠ Scroll-activated, not drag-activated: appearing only on touch would make it undiscoverable.
 *
 * ⚠⚠ **It fades after [FADE_AFTER_MS] of stillness**, as Google Photos' thumb is gone five seconds
 * after scrolling stops.
 */
@Composable
fun BoxScope.FeedScrubber(
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    /**
     * The date to show for the row at `index`, or **`null` when that row is not loaded**
     * ⚠ It is asked about the row at the TOP OF THE VIEWPORT, so its answer is the
     * label of a row the user can see — never the row a seek was aiming for.
     *
     * ⚠⚠ The nullability is the whole design. With placeholders on, the track spans the library
     * but the rows do not — so through most of a fast drag the row under the thumb is a
     * placeholder and there is no date to show. The bubble goes quiet rather than holding the last
     * one: a held label is the date of a *different* photograph presented as this one's, which is
     * exactly what `photos-feed-scrubber.md` means by "the piece that most obviously lies when the
     * list is partial". Silence is the honest answer to "I do not know yet", and the jump refresh
     * fills it in within a beat.
     */
    dateAt: (index: Int) -> String? = { null },
    /**
     * One mark per year in the library, newest first, for the pills down the track.
     *
     * ⚠⚠ The whole library, not the loaded pages. A track marked from what happens to be paged in
     * would move its own pills as the user scrolls (`photos-feed-scrubber.md` §3).
     * `PhotosFeedViewModel.yearMarks` reads it once.
     *
     * ⚠ Empty is the honest default: no marks, no pills, and a bare thumb.
     */
    yearMarks: List<FeedYearMark> = emptyList(),
    /** Fired while the thumb is held. ⚠ The caller suppresses preview decoding on this. */
    onScrubbingChange: (Boolean) -> Unit = {},
    /**
     * Where the list stands on the track, from the top item's index and the fraction of that item
     * already scrolled past — or `null` for the item-count arithmetic below.
     *
     * ⚠⚠ The feed passes one, and must: [yearMarks] are placed by PHOTOGRAPHS, and the feed's items
     * are lines, which are not proportional to photographs — a header is a line to itself. A thumb
     * positioned in lines under pills positioned in photographs points at the wrong year. Given
     * with [indexAtProgress], its inverse, or not at all.
     */
    progressAt: ((index: Int, withinItem: Float) -> Float)? = null,
    /** The item a seek to `progress` of the track lands on — the inverse of [progressAt]. */
    indexAtProgress: ((progress: Float) -> Int)? = null,
) {
    // ⚠ `derivedStateOf`, not a raw read: this is recomputed on every scroll frame, and reading
    // `layoutInfo` directly in composition would recompose the whole screen with it.
    val scrollable by remember {
        derivedStateOf { gridState.canScrollForward || gridState.canScrollBackward }
    }

    var dragging by remember { mutableStateOf(false) }

    // ⚠⚠ Shown while the list is moving OR the thumb is held, then hidden after a beat of stillness
    // — the reference behaviour. ⚠ `dragging` is in the condition and not just `isScrollInProgress`:
    // a drag that has grabbed the thumb but not yet moved it scrolls nothing, and without this the
    // control would vanish under the user's own finger.
    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress, dragging, scrollable) {
        if (!scrollable) {
            awake = false
        } else if (gridState.isScrollInProgress || dragging) {
            awake = true
        } else {
            delay(FADE_AFTER_MS)
            awake = false
        }
    }

    val scope = rememberCoroutineScope()
    // ⚠⚠ Converted from the SAME `Dp` the thumb is drawn at, not a pixel constant. A hard-coded
    // `150f` matches 56.dp only at ~2.67 density — right on one phone and wrong on every other
    // screen, which is the worst kind of wrong.
    val thumbPx = with(LocalDensity.current) { THUMB_HEIGHT.toPx() }
    var trackPx by remember { mutableFloatStateOf(0f) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    // Where the thumb sits when the user is not holding it: how far through the list the grid is.
    //
    // ⚠⚠ **`firstVisibleItemIndex` ALONE makes the thumb stutter.** The index is an integer, and
    // in an N-column grid it advances by N at a time — so while a row scrolls past, the thumb does
    // not move at all, and then it jumps.
    //
    // Hence the offset WITHIN the current line: `firstVisibleItemScrollOffset` is the pixels
    // scrolled into it, so `offset / lineHeight` is the fraction of a line travelled. ⚠ Scaled by
    // how many items a line holds, because that is what the index will jump by when the line
    // completes — without the scaling the thumb still lurches by `lineItems - 1` at every
    // boundary, which is most of the stutter back again.
    // ⚠ Through `rememberUpdatedState`: the drag handler below is one long-lived coroutine, and a
    // caller's lambdas are new on every recomposition — keying the handler on them would cancel a
    // drag mid-gesture, and capturing them once would read a layout from before a density change.
    val currentProgressAt by rememberUpdatedState(progressAt)
    val currentIndexAtProgress by rememberUpdatedState(indexAtProgress)

    val scrolled by remember {
        derivedStateOf {
            currentProgressAt?.let { return@derivedStateOf customProgress(gridState, it) }
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            if (total <= 1) return@derivedStateOf 0f

            val first = info.visibleItemsInfo.firstOrNull()
            val lineHeight = first?.size?.height ?: 0
            // ⚠ Counted, not assumed to be the column count: a day header is a full-span item and
            // so is a line of one, and hard-coding the grid's columns would over-scale those.
            val lineItems = first
                ?.let { f -> info.visibleItemsInfo.count { it.row == f.row } }
                ?.coerceAtLeast(1)
                ?: 1
            val withinLine =
                if (lineHeight > 0) {
                    gridState.firstVisibleItemScrollOffset.toFloat() / lineHeight
                } else {
                    0f
                }

            ((gridState.firstVisibleItemIndex + withinLine * lineItems) / (total - 1))
                .coerceIn(0f, 1f)
        }
    }
    // ⚠⚠ While dragging, the thumb follows the FINGER rather than the grid. Reading the grid back
    // would make the thumb lag its own gesture: a seek scrolls the list, the list reports a new
    // position, and the thumb chases it a frame late. Whoever is driving owns the position.
    val progress = if (dragging) dragProgress else scrolled

    // ⚠ Only while the thumb is held. At rest the day headers in the grid already say where you
    // are, and a permanent bubble would cover the photographs it is describing.
    //
    // ⚠⚠ **The row the grid IS SHOWING, not the row the gesture aimed at.** Reading
    // `dateAt(progress * (totalItemsCount - 1))` — the same arithmetic the drag handler seeks
    // with — puts a date on screen that no visible row has, and keeps it there for as long as the
    // finger rests. The two indices are computed from the same `progress` but not from the same
    // `totalItemsCount`: the count moves underneath a held finger, because `insertSeparators`
    // adds a day header only next to LOADED rows, so every page that lands grows the list. The
    // seek uses the old count; the label would re-derive from the new one; nothing reconciles them
    // until the next drag event, and a still finger sends none.
    //
    // Reading the grid back makes the disagreement unrepresentable: the label describes the same
    // row the header above it describes, whatever the count does. ⚠ This is the opposite choice
    // from the thumb's POSITION a few lines above, and deliberately so — the thumb follows the
    // finger because the finger is driving it, and the label follows the list because the list is
    // what the user is reading.
    //
    // ⚠ Read in composition so that a page arriving recomposes it: `dateAt` peeks the paged items,
    // which is a snapshot read, so the bubble fills in by itself when the jump refresh lands.
    val bubbleLabel: String? = if (dragging) dateAt(gridState.firstVisibleItemIndex) else null

    Box(
        modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            .onSizeChanged { trackPx = it.height.toFloat() },
    ) {
        // ⚠⚠ **Only while dragging**, matching the reference and for a stronger reason than
        // matching it: the pills are a map for aiming, and a permanent column of years down the
        // right-hand edge sits on top of the photographs at all times for a control the user is
        // not touching. ⚠ Inside the same `Box` as the thumb so both measure the same track.
        if (dragging && trackPx > thumbPx) {
            // ⚠ Measured, not guessed: the gap that stops two pills overlapping is the pill's own
            // height, which is a font-size question, so it is converted from the same `Dp` the
            // label is drawn at, as the thumb's own offset is (a hard-coded `150f` matches 56.dp
            // at exactly one screen density).
            val pillPx = with(LocalDensity.current) { PILL_HEIGHT.toPx() }
            // ⚠ The shared placement (`scrubberPills`) — the iOS thumb draws the same list.
            scrubberPills(yearMarks, track = trackPx, thumb = thumbPx, pill = pillPx, thumbFraction = progress)
                .forEach { pill ->
                    Text(
                        text = pill.year.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(0, pill.top.roundToInt()) }
                            .padding(end = PILL_TRACK_INSET)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .testTag(FEED_SCRUBBER_YEAR_PILL_TAG),
                    )
                }
        }

        AnimatedVisibility(
            visible = awake,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (progress * (trackPx - thumbPx)).roundToInt()) },
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        // ⚠ Left of the thumb and vertically centred on it, so it never sits under the finger
        // that is dragging — the reference apps all place it inboard for the same reason.
        if (bubbleLabel != null) {
            Text(
                text = bubbleLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                        RoundedCornerShape(50),
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag(FEED_SCRUBBER_BUBBLE_TAG),
            )
        }
        Box(
            Modifier
                .padding(end = 6.dp)
                .size(width = 40.dp, height = THUMB_HEIGHT)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    RoundedCornerShape(percent = 50),
                )
                .testTag(FEED_SCRUBBER_TAG)
                .pointerInput(trackPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragProgress = currentProgressAt?.let { customProgress(gridState, it) }
                                ?: progressOf(gridState)
                            onScrubbingChange(true)
                        },
                        onDragEnd = { dragging = false; onScrubbingChange(false) },
                        onDragCancel = { dragging = false; onScrubbingChange(false) },
                    ) { change, dy ->
                        change.consume()
                        if (trackPx > thumbPx) {
                            dragProgress = (dragProgress + dy / (trackPx - thumbPx))
                                .coerceIn(0f, 1f)
                            val total = gridState.layoutInfo.totalItemsCount
                            if (total > 0) {
                                // ⚠⚠ `scrollToItem`, NOT `animateScrollToItem`. A scrub is a seek:
                                // animating each step makes the list chase the thumb and is
                                // precisely what makes a fast-scroll feel slow.
                                val target = currentIndexAtProgress?.invoke(dragProgress)
                                    ?: (dragProgress * (total - 1)).roundToInt()
                                scope.launch {
                                    gridState.scrollToItem(target.coerceIn(0, total - 1))
                                }
                            }
                        }
                    }
                },
        ) {
            // ⚠ Two chevrons rather than one glyph, matching the reference: the thumb has to read
            // as "drag me up or down" rather than as a position marker.
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((-10).dp),
            ) {
                Icon(Icons.Default.ArrowDropUp, contentDescription = null, tint = Color.White)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
        }
        }
        }
    }
}

/**
 * The same arithmetic as `scrolled` above, for the moment a drag starts.
 *
 * ⚠⚠ It must agree with the resting position exactly. If this returned the un-interpolated index
 * while the thumb was resting on the interpolated one, grabbing the thumb would teleport it by up
 * to a line's worth before the finger had moved — a flinch on every touch.
 */
private fun progressOf(gridState: LazyGridState): Float {
    val info = gridState.layoutInfo
    val total = info.totalItemsCount
    if (total <= 1) return 0f
    val first = info.visibleItemsInfo.firstOrNull()
    val lineHeight = first?.size?.height ?: 0
    val lineItems = first
        ?.let { f -> info.visibleItemsInfo.count { it.row == f.row } }
        ?.coerceAtLeast(1)
        ?: 1
    val withinLine =
        if (lineHeight > 0) gridState.firstVisibleItemScrollOffset.toFloat() / lineHeight else 0f
    return ((info.visibleItemsInfo.firstOrNull()?.index ?: 0) + withinLine * lineItems)
        .div(total - 1)
        .coerceIn(0f, 1f)
}

/**
 * The thumb's position through a caller's own coordinate — the one `scrolled` rests on and a drag
 * starts from, so grabbing the thumb never makes it flinch.
 *
 * ⚠ The height of the TOP item itself, looked up by index: the first entry of `visibleItemsInfo`
 * can sit above the viewport.
 */
private fun customProgress(gridState: LazyGridState, progressAt: (Int, Float) -> Float): Float {
    // ⚠ At the end of the list the thumb rests at the bottom: the top item there is rows short of
    // the last photograph, so `progressAt` alone rests the thumb above the end the grid is at, and a
    // drag to the bottom jumps up on release.
    if (!gridState.canScrollForward && gridState.canScrollBackward) return 1f
    val top = gridState.firstVisibleItemIndex
    val height = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == top }?.size?.height ?: 0
    val within = if (height > 0) gridState.firstVisibleItemScrollOffset.toFloat() / height else 0f
    return progressAt(top, within.coerceIn(0f, 1f)).coerceIn(0f, 1f)
}

/** ⚠ One source of truth: the track arithmetic converts this rather than repeating a number. */
private val THUMB_HEIGHT = 56.dp

/**
 * The pill's own height, used as the minimum gap between two of them.
 *
 * ⚠ `labelSmall` at its default line height plus the 1.dp padding either side. It is deliberately
 * a little generous: two pills exactly one height apart touch, and touching reads as a mistake.
 */
private val PILL_HEIGHT = 20.dp

/** How far the pills sit inboard of the track's edge — clear of the thumb, which is 40.dp wide. */
private val PILL_TRACK_INSET = 52.dp

/** How long the list must be still before the thumb fades. */
private const val FADE_AFTER_MS = 1_200L

const val FEED_SCRUBBER_TAG = "feedScrubber"

/** Test handle for the date bubble — present only while dragging over a LOADED row. */
const val FEED_SCRUBBER_BUBBLE_TAG = "feedScrubberBubble"

/** Test handle for the year pills — present only while the thumb is held. */
const val FEED_SCRUBBER_YEAR_PILL_TAG = "feedScrubberYearPill"
