package app.tenshu.photos.ui.screens.feed

import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.waitingWillFix
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.interaction.DragInteraction
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import app.tenshu.photos.data.FeedReadException
import app.tenshu.photos.model.FeedItem
import app.tenshu.photos.model.MotionTurn
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.components.PhotoTile
import app.tenshu.photos.ui.components.PreviewLoader

/**
 * The Home feed (`photos-app.md` S1): every photo in the realm, newest first, sectioned by day.
 *
 * Stateless in the Compose sense — it renders [items] and reports intent.
 *
 * ⚠⚠ **The three empty-looking states are not one state**, and telling them apart is this screen's
 * main job. A feed with no rows is *loading*, *broken*, or *genuinely empty*, and rendering the last
 * one when the truth is the second tells a user their library is gone. Paging gives all three
 * through `loadState.refresh`, so the `when` below reads them in that order — error before empty,
 * always.
 */
@Composable
fun PhotosFeedScreen(
    items: LazyPagingItems<FeedItem>,
    loadPreview: PreviewLoader,
    onPhotoClick: (Photo) -> Unit,
    /**
     * Long-press on a tile. ⚠ Offered only for a photo that *can* be filed — one with a
     * `contentId` — because a file predating content addressing has none and the op refuses it.
     * Advertising an action that always fails is worse than not offering it.
     */
    onAddToAlbum: (Photo) -> Unit,
    onRetry: () -> Unit,
    /**
     * *Why* Control is unavailable, when it is (`ADR 0025`). ⚠ Not used to decide **whether** to
     * show an error — [state] owns that — only whether a **Try again** can honestly be offered.
     *
     * ⚠ `null` (nothing known) keeps the retry, which is the honest default.
     */
    reason: UnreachableReason? = null,
    modifier: Modifier = Modifier,
    /**
     * ⚠ Takes the density: the same instant reads "17 December 2024", "December 2024" or "2024"
     * depending on how the feed is grouped, and the header, the sticky overlay and the scrubber's
     * bubble must all say the same thing.
     */
    sectionLabel: (FeedDensity, Long) -> String,
    /**
     * The scrubber's year pills, or empty for a screen that has none.
     *
     * ⚠ Defaulted, so every existing caller and every preview keeps working with the thumb it had.
     */
    yearMarks: List<FeedYearMark> = emptyList(),
    /**
     * How coarsely the feed is grouped (S1). ⚠ It decides the column count as well as the header
     * text — `FeedDensity.columns` owns both so they cannot drift — and whether some photographs are
     * drawn over 2×2 cells (§32). ⚠ Defaulted to what the app opens on, so a test that names no
     * density exercises the path users see.
     */
    density: FeedDensity = FeedDensity.Mosaic,
    /**
     * The paths currently selected (S1). ⚠ Empty means *not selecting* — there is no separate
     * mode flag, because two sources of truth for "are we selecting" is how a screen ends up with
     * a selection bar over an empty selection.
     */
    selected: Set<String> = emptySet(),
    /** ⚠ Given the whole [Photo]: the actions need the content id, not just the path. */
    onToggleSelection: (Photo) -> Unit = {},
    /**
     * ⚠ Hoisted so a test can place the viewport precisely — `scrollToItem(index, scrollOffset)`
     * is the only way to put a header *partially* off the top, which is the case §29.2 was about
     * and which no swipe can hit deterministically.
     *
     * ⚠⚠ Its indices address LINES (`FeedLayout`), not paged items: a header is a line, and a row
     * of three photographs is one.
     */
    gridState: LazyGridState = rememberLazyGridState(),
    /**
     * The rotation's current turn (`photos-video-support.md` §5), handed to the one tile
     * whose path it names. ⚠ Defaulted, so every existing caller and preview keeps a static grid.
     */
    motion: MotionTurn? = null,
    /**
     * The video tiles on screen, in document order, every time that set changes — and an empty
     * list when this screen leaves composition. The rotation's only view of the grid.
     */
    onVisibleVideos: (List<String>) -> Unit = {},
    /** A tile finished its turn — the rotation's clock. */
    onMotionFinished: (path: String) -> Unit = {},
) {
    val refresh = items.loadState.refresh
    // ⚠ `itemCount`, not a snapshot of the rows: with a paged list "is there anything on screen"
    // is a property of the presenter, and it is the only thing that distinguishes a first load
    // from a reload behind rows the user is already reading.
    val empty = items.itemCount == 0

    when {
        refresh is LoadState.Loading && empty ->
            Centered(modifier) { CircularProgressIndicator() }

        // ⚠⚠ Error before empty, always. A feed that renders "No photos yet" when the proxy is
        // simply not running has told the user their library is gone.
        refresh is LoadState.Error && empty ->
            Centered(modifier) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            // ⚠⚠ It does not say "Control just isn't running": that is **false**
                            // when Control is not installed. The specific cause is named once, in
                            // the shell's banner above, and repeating a guess underneath it is the
                            // two-claims-on-one-screen failure `waitingWillFix` exists for.
                            // ⚠⚠ Read off the exception, never off its message text. The
                            // repository classifies from the client's connection state and hands
                            // the answer over as a flag; matching a diagnostic string here is how
                            // a rename becomes a production-only regression.
                            (refresh.error as? FeedReadException)?.unreachable == true ->
                                "Can't reach Tenshu Control.\nYour photos are safe — they just can't load right now."

                            else -> "Something went wrong loading your photos."
                        },
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    // ⚠⚠ A retry is offered only where retrying can succeed. Under a banner that
                    // says Control is not installed, "Try again" is a button that cannot work —
                    // ADR 0025's own defect one layer down. `null` reason keeps the button: absent
                    // a known cause, the failure really is the kind that clears itself.
                    if (waitingWillFix(reason)) {
                        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                            Text("Try again")
                        }
                    }
                }
            }

        // ⚠ Genuinely empty only once a refresh has *succeeded*. `NotLoading` after an error is
        // not this branch, because the error arm above already caught it.
        refresh is LoadState.NotLoading && empty ->
            Centered(modifier) {
                Text(
                    // Says where photos come from, because in this app they arrive from the
                    // backup rather than from anything the user can do on this screen.
                    text = "No photos yet.\nPhotos backed up from your devices appear here.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

        // ⚠⚠ Constraints, because the column count depends on how wide the GRID is (§33): measured
        // here it already excludes the navigation rail, a split-screen neighbour and a window's
        // frame. ⚠ Not `onSizeChanged`: the first frame would cut the lines at a phone's count, and a
        // scroll position restored into that cut names a different photograph than it was saved on.
        else -> BoxWithConstraints(modifier.fillMaxSize()) {
            val columns = density.columnsAt(maxWidth.value - 2 * CELL_SPACING.value)
            // ⚠⚠ Previews are NOT decoded while the thumb is held. Measured on Google Photos: the
            // grid is grey placeholders mid-scrub and populates when it settles. Decoding hundreds
            // of thumbnails as the finger moves is what makes a fast-scroll feel slow — the one
            // thing it exists not to be.
            //
            // ⚠⚠ Passed as a FLAG, never by swapping `loadPreview` for one that returns null: that
            // leaves tiles permanently blank — `PhotoTile`'s `produceState` is keyed on the path,
            // so swapping the lambda back never re-runs it.
            var scrubbing by remember { mutableStateOf(false) }

            // ⚠⚠ **The grid's items are LINES, not photographs** (`FeedLayout`, §32). A photograph
            // drawn over 2×2 cells is a row span, which no lazy grid in Compose has, so each item is
            // a whole line that places its own cells — and every density is drawn this way, so there
            // is one path through this screen rather than one per density.
            //
            // ⚠ `derivedStateOf` over the snapshot, so the layout is recut when a page lands and not
            // on every scroll frame. ⚠ Linear in the LOADED window only: the placeholders either side
            // of it are arithmetic, however large the library.
            val layoutState = remember(items, density, columns) {
                derivedStateOf {
                    val snapshot = items.itemSnapshotList
                    FeedLayout.of(
                        placeholdersBefore = snapshot.placeholdersBefore,
                        items = snapshot.items,
                        placeholdersAfter = snapshot.placeholdersAfter,
                        density = density,
                        columns = columns,
                    )
                }
            }

            // ⚠⚠ **A density change keeps the photograph at the top of the viewport there.** The
            // grid state holds a LINE index, and a line is three, five or seven
            // photographs — so left alone, line 100 is photograph ~300 in the day view and ~500 in
            // the month view. The only coordinate the densities share is a photograph's position in
            // the library (`FeedLayout.photoAt`).
            //
            // ⚠ A column change is the same problem — a window dragged wider re-cuts every line — and
            // takes the same anchor, minus the wait: the stream is not rebuilt, so the photograph is
            // usually already loaded and one scroll settles it.
            //
            // ⚠ Read in COMPOSITION, off the layout the grid was last measured against: by the time
            // any effect runs, the grid may already have re-measured the new lines and moved its
            // index. ⚠ Without read observation, so the screen does not recompose on every scroll.
            val anchor = remember { DensityAnchor() }
            if (anchor.density != null && (anchor.density != density || anchor.columns != columns)) {
                Snapshot.withoutReadObservation {
                    val top = gridState.firstVisibleItemIndex
                    val shown = anchor.layout
                    // ⚠ How far into the top line the viewport starts: past half a feature, the
                    // photograph leading what is visible is its LOWER small one, the line's first
                    // being off the top by then.
                    val height = gridState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == top }?.size?.height ?: 0
                    val scrolledPast =
                        if (height > 0) gridState.firstVisibleItemScrollOffset.toFloat() / height else 0f
                    anchor.photo = shown?.photoAt(top, scrolledPast)
                    // ⚠ A header and the row under it share one position; remember which was on top.
                    anchor.onHeader = shown != null && top < shown.lineCount && shown[top] is FeedLine.Header
                    anchor.snapshot = items.itemSnapshotList
                    anchor.rebuildsStream = anchor.density != density
                }
            }
            SideEffect {
                anchor.density = density
                anchor.columns = columns
                anchor.layout = layoutState.value
            }
            LaunchedEffect(density, columns) {
                val photo = anchor.photo ?: return@LaunchedEffect
                val onHeader = anchor.onHeader
                val rebuildsStream = anchor.rebuildsStream
                anchor.photo = null
                val changedOver = anchor.snapshot
                // True once the photograph is in the loaded window — the line is exact from then on.
                suspend fun scrollToAnchor(): Boolean {
                    val layout = layoutState.value
                    if (layout.lineCount > 0) gridState.scrollToItem(layout.lineOfPhoto(photo, header = onHeader))
                    return layout.isLoaded(photo)
                }
                suspend fun nextSnapshot(after: Any?) {
                    snapshotFlow { items.itemSnapshotList }.first { it !== after }
                }
                coroutineScope {
                    val job = launch {
                        // At once, over the rows still on screen — then again as the rebuilt
                        // stream lands, because its first page is the TOP of the library and the
                        // photograph is usually a placeholder in it. Asking for that line is what
                        // makes Paging jump there; the page it jumps to is the one that settles it.
                        // ⚠⚠ Only a density change rebuilds the stream. After a column change there
                        // may be no next snapshot at all, and waiting for one would leave this job
                        // alive to yank the list whenever a page next lands.
                        if (scrollToAnchor() && !rebuildsStream) return@launch
                        nextSnapshot(changedOver)
                        repeat(MAX_REANCHOR_PASSES) {
                            if (scrollToAnchor()) return@launch
                            nextSnapshot(items.itemSnapshotList)
                        }
                    }
                    anchor.job = job
                    // ⚠ The user taking the list back ends it: a re-anchor that yanked a scroll
                    // the user had already started would be worse than losing the place.
                    val drags = launch {
                        gridState.interactionSource.interactions.first { it is DragInteraction.Start }
                        job.cancel()
                    }
                    job.join()
                    drags.cancel()
                }
            }

            // Which video tiles the rotation may use — document order, and NONE while the thumb
            // is held (Q2: the same carrier `previewsEnabled` uses, because nobody reads a moving
            // tile under a scrub). ⚠ Read off the layout, which only peeks: deciding whether a row is
            // a video must not tell Paging it was displayed. ⚠ `derivedStateOf`, because
            // `layoutInfo` changes every scroll frame and the set of visible videos changes once a
            // row. The identity of the list is what `LaunchedEffect` keys on, so a scroll that
            // keeps the same videos on screen reports nothing.
            val visibleVideos: List<String> by remember(layoutState) {
                derivedStateOf {
                    val layout = layoutState.value
                    if (scrubbing) {
                        emptyList()
                    } else {
                        val info = gridState.layoutInfo
                        info.visibleItemsInfo.flatMap { item ->
                            if (item.index >= layout.lineCount) return@flatMap emptyList()
                            val line = layout[item.index]
                            (line.start until line.start + line.size).mapNotNull { index ->
                                // ⚠⚠ A feature is two rows tall, so the LINE being on screen does
                                // not put its small photographs there: with only its top half
                                // showing, the lower one can be wholly below the viewport, and a
                                // turn spent on it plays a video nobody can see.
                                // The halves are the rows to within the gap between them.
                                val row = (line as? FeedLine.Feature)?.rowOf(index)
                                if (row != null) {
                                    val half = item.size.height / 2
                                    val top = item.offset.y + row * half
                                    if (top >= info.viewportEndOffset || top + half <= info.viewportStartOffset) {
                                        return@mapNotNull null
                                    }
                                }
                                (layout.itemAt(index) as? FeedItem.Tile)
                                    ?.photo
                                    ?.takeIf { it.isVideo }
                                    ?.path
                            }
                        }
                    }
                }
            }
            LaunchedEffect(visibleVideos) { onVisibleVideos(visibleVideos) }
            // Leaving the screen — the viewer, another tab — takes every tile off screen, and the
            // rotation must hear that rather than keep a turn alive on a grid nobody can see.
            DisposableEffect(Unit) {
                onDispose { onVisibleVideos(emptyList()) }
            }

            @Composable
            fun FeedCell(layout: FeedLayout, index: Int) {
                when (val item = layout.itemAt(index)) {
                    is FeedItem.Tile -> PhotoTile(
                        photo = item.photo,
                        loadPreview = loadPreview,
                        previewsEnabled = !scrubbing,
                        selected = item.photo.path in selected,
                        // ⚠ Each tile keeps only its own turn; the others see null and stay still.
                        motion = motion?.takeIf { it.path == item.photo.path },
                        onMotionFinished = { onMotionFinished(item.photo.path) },
                        // ⚠⚠ While a selection is running a tap TOGGLES rather than opening
                        // the viewer. Opening a photograph mid-selection loses the selection
                        // behind a full-screen page, which is the one thing the gesture must
                        // not do.
                        onClick = {
                            if (selected.isEmpty()) onPhotoClick(item.photo)
                            else onToggleSelection(item.photo)
                        },
                        // ⚠⚠ Long-press BEGINS a selection. Add-to-album lives in the selection
                        // bar, where it also works for more than one photograph. S1 asks for
                        // exactly this.
                        onLongClick = { onToggleSelection(item.photo) },
                    )

                    // ⚠⚠ A placeholder must hold the SAME space a tile would, or the line
                    // collapses and the scrubber — which reads positions off the layout — points
                    // at the wrong rows. `FeedLineCells` sizes both, so this only has to paint.
                    else -> Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }

            Box(Modifier.fillMaxSize()) {
            // ⚠ Captured once per pass, so the count, the keys and the lines below all describe the
            // same cut of the list — a key read against a newer layout than the count could name a
            // line that does not exist.
            val layout = layoutState.value

            // ⚠⚠ **Resting at the very top, the grid follows the top.** A lazy grid keeps its first
            // visible item by KEY, so a photograph arriving at the head — a sync, a backup — pushes
            // the old first line down and the grid follows it: the arrival is drawn just above the
            // viewport and the feed looks as if it had not updated until someone scrolls up. The
            // request holds index 0 for the one remeasure that applies the new lines.
            // ⚠ Only at rest at the top. Anywhere else the key rule is the right one — it is what
            // keeps the user's place while photographs land above them — and a list somebody is
            // holding, scrubbing or re-anchoring is theirs. iOS has the same rule (`photos-ios.md`
            // §8.20). ⚠ In composition, for the reason the anchor above is: an effect runs after
            // the grid has already re-measured and moved.
            val head = remember { FeedHead() }
            val headKey = if (layout.lineCount > 0) layout.key(0) else null
            if (head.key != null && headKey != null && headKey != head.key) {
                Snapshot.withoutReadObservation {
                    val restingAtTop = gridState.firstVisibleItemIndex == 0 &&
                        gridState.firstVisibleItemScrollOffset == 0 &&
                        !gridState.isScrollInProgress
                    val reanchoring = anchor.photo != null || anchor.job?.isActive == true
                    if (restingAtTop && !scrubbing && !reanchoring) gridState.requestScrollToItem(0)
                }
            }
            SideEffect { head.key = headKey }

            LazyVerticalGrid(
                // ⚠ One column, because each item is a whole line. Still a grid rather than a
                // `LazyColumn`: the scrubber and the saved scroll position are a `LazyGridState`.
                columns = GridCells.Fixed(1),
                state = gridState,
                modifier = Modifier.fillMaxSize().testTag(FEED_GRID_TAG),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(CELL_SPACING),
                verticalArrangement = Arrangement.spacedBy(CELL_SPACING),
            ) {
                // ⚠⚠ Still one flat, paged index space underneath. A line is a range of it, so
                // the item at index n stays renderable without knowing the rows after it — which is
                // why the day grouping is a separator rule upstream (`PhotosFeedViewModel.items`)
                // and the lines are cut from the snapshot rather than from a grouped list.
                items(
                    count = layout.lineCount,
                    key = { line -> layout.key(line) },
                    contentType = { line ->
                        when (layout[line]) {
                            is FeedLine.Header -> 0
                            is FeedLine.Row -> 1
                            is FeedLine.Feature -> 2
                        }
                    },
                ) { lineIndex ->
                    val line = layout[lineIndex]
                    // ⚠⚠ `get`, not `peek`, for every index the line draws: this is what tells
                    // Paging the rows are on screen, and the layout above deliberately only peeks.
                    for (index in line.start until line.start + line.size) {
                        if (index < items.itemCount) items[index]
                    }
                    when (line) {
                        is FeedLine.Header -> (layout.itemAt(line.start) as? FeedItem.DayHeader)?.let {
                            Text(
                                text = sectionLabel(density, it.dayStartMs),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                            )
                        }

                        is FeedLine.Row -> FeedLineCells(columns = layout.columns, spacing = CELL_SPACING) {
                            for (index in line.start until line.start + line.size) FeedCell(layout, index)
                        }

                        is FeedLine.Feature -> FeedLineCells(
                            columns = layout.columns,
                            spacing = CELL_SPACING,
                            feature = line,
                        ) {
                            for (index in line.start until line.start + line.size) FeedCell(layout, index)
                        }
                    }
                }

                if (items.loadState.append is LoadState.Loading) {
                    item(key = "loading-more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            // ⚠⚠ **A sticky header, built as an overlay rather than with `stickyHeader`.** The
            // grid's `stickyHeader` does exist in this Compose, and it is unusable here: it wants
            // each section declared as its own lazy item, and this list is FLAT and PAGED — the
            // section boundaries below the loaded window are placeholders, so they cannot be
            // declared at all. Reading the top row instead works at any scroll position and needs
            // nothing the list does not already know.
            //
            // ⚠ Shown only when the top row's own header is not FULLY on screen, which is what
            // makes it read as sticky rather than as a duplicate: while a section's header is
            // wholly visible it *is* the header and the overlay stays out of its way.
            //
            // ⚠⚠ **The offset, not just the index** (§29.2). Suppressing whenever the top item is
            // a header leaves a window where that header is half scrolled past the top — clipped,
            // unreadable, and with nothing taking over. `offset` is what separates "the header is
            // up there" from "the header is on its way out".
            //
            // ⚠ `derivedStateOf`: `layoutInfo` changes every scroll frame, and reading it straight
            // in composition would recompose this screen — the grid included — sixty times a
            // second. The label changes once a section.
            val stickyLabel: String? by remember(layoutState) {
                derivedStateOf {
                    // ⚠⚠ `firstVisibleItemScrollOffset`, NOT `layoutInfo.visibleItemsInfo.first()`.
                    // The latter is the first item in the visible LINE RANGE, which can be a whole
                    // row above the viewport — measured at index 1, offset −340, while the top item
                    // was index 2. The pair below is the documented "top item, and how far it is
                    // scrolled past the top", which is exactly the question.
                    val layout = layoutState.value
                    val top = gridState.firstVisibleItemIndex
                    val headerFullyOnScreen = gridState.firstVisibleItemScrollOffset == 0 &&
                        top < layout.lineCount && layout[top] is FeedLine.Header
                    if (headerFullyOnScreen) null else layout.labelAt(top, density, sectionLabel)
                }
            }
            val label = stickyLabel
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .testTag(FEED_STICKY_HEADER_TAG),
                )
            }

            // ⚠ Inside the same `Box` as the grid and aligned to its end edge, so it overlays
            // the rows rather than taking a column of its own — the reference apps float it.
            FeedScrubber(
                gridState = gridState,
                // ⚠ Read off the layout, which only peeks: resolving the date must not tell Paging
                // the row was displayed, or dragging the thumb would drive prefetching on its own.
                // ⚠⚠ `null` for an unloaded row is deliberate and the bubble honours it — see
                // `FeedScrubber.dateAt`. ⚠ The index is a LINE's, since the grid's items are lines.
                dateAt = { line -> layoutState.value.labelAt(line, density, sectionLabel) },
                // ⚠⚠ In photographs, the coordinate the year pills are placed in — see the scrubber.
                progressAt = { line, within -> layoutState.value.progressAt(line, within) },
                indexAtProgress = { progress -> layoutState.value.lineAtProgress(progress) },
                yearMarks = yearMarks,
                onScrubbingChange = {
                    scrubbing = it
                    // ⚠ A scrub is the user taking the list back too, and it moves by `scrollToItem`
                    // rather than by a drag the re-anchor could hear.
                    if (it) anchor.job?.cancel()
                },
            )
            }

            // ⚠⚠ There is no "near the end" listener, and its absence is the point: Paging
            // prefetches from the index the presenter is asked for, so a listener, threshold and
            // duplicate-call guard would only reimplement it.
        }
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().padding(32.dp), Alignment.Center) { content() }
}

/**
 * What a density or column change re-anchors on, carried across compositions without being state —
 * nothing here should recompose anything.
 */
private class DensityAnchor {
    /** The density, column count and layout of the last composition that committed. */
    var density: FeedDensity? = null
    var columns: Int? = null
    var layout: FeedLayout? = null

    /** Whether the change being anchored rebuilt the paged stream — a density change does, columns do not. */
    var rebuildsStream: Boolean = false

    /** The top photograph's library position at the moment the density changed, until consumed. */
    var photo: Int? = null

    /** Whether the line on top was that photograph's section header rather than its row. */
    var onHeader: Boolean = false

    /** The snapshot the change was made over — the rebuilt stream's first page is any other. */
    var snapshot: Any? = null

    var job: Job? = null
}

/** The key of the grid's first line as last committed — what "a line arrived above it" is read against. */
private class FeedHead {
    var key: String? = null
}

/**
 * How many pages a re-anchor waits through before giving up: the rebuilt stream's first page, the
 * jump it causes, and one spare for a page landing in between.
 */
private const val MAX_REANCHOR_PASSES = 3

/** The gap between cells and around the grid — one value, so the lines and the rows agree. */
private val CELL_SPACING = 4.dp

/** Test handle for the feed grid itself — the scrubber has its own. */
const val FEED_GRID_TAG = "photosFeedGrid"


/** Test handle for the sticky section header (S1) — absent while a section's own header shows. */
const val FEED_STICKY_HEADER_TAG = "feedStickyHeader"
