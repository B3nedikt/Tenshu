package app.tenshu.photos.ui.screens.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.tenshu.photos.model.Photo
import app.tenshu.photos.data.heroPreviewRequestPx
import app.tenshu.photos.ui.system.HideSystemBars
import app.tenshu.ui.components.LightSystemBarIcons
import app.tenshu.ui.image.decodePreviewBitmap
import app.tenshu.ui.input.AppShortcuts
import app.tenshu.ui.input.KeyboardShortcut
import app.tenshu.ui.input.AppKeyboardShortcutHost
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take

/**
 * The full-screen viewer (`photos-app.md` S3) — **a pager over the list the grid showed**,
 * not a screen showing one photograph.
 *
 * ⚠⚠ [photos] is the *same* list the caller's grid is rendering, and [initialIndex] the row that
 * was tapped. That is the whole design: a viewer that held one photo and fetched "the next one"
 * would need its own idea of what comes next, and it would disagree with the grid the moment the
 * user came from a search, an album or a smart section rather than the feed.
 *
 * ⚠⚠ **Endless is the caller's paging, not the viewer's.** When the pager comes within
 * [PREFETCH_PAGES] of the end of [photos] it calls [onLoadMore] — the same callback the feed's grid
 * fires on scroll — and the list it is paging over grows underneath it. A viewer with its own read
 * would be a second cursor over the realm, and the two would drift.
 *
 * ⚠⚠ **Gesture priority, decided once (D4).** Four things want this one surface, and a
 * single rule orders them — *while zoomed in, the drag belongs to the pan*:
 *
 * | Gesture | Owner |
 * |---|---|
 * | Two fingers | the zoom, always — a pinch is never a page swipe |
 * | One finger, magnified | the pan |
 * | One finger down, at fit-scale | **drag to dismiss** |
 * | One finger sideways, at fit-scale | the pager |
 *
 * So the dismiss and the pager are unreachable while magnified, and both return the moment the
 * photograph is back at fit-scale. ⚠ Deciding these one at a time is how a viewer ends up with a
 * zoom that cannot be panned downward — and §9.3b is what it looks like when one gesture changes
 * and an unrelated one breaks.
 *
 * ⚠⚠ **Upward scrolls into About.** The page is one tall column — the photograph at viewport
 * height, its details below — so dragging up does not "open a sheet", it scrolls the surface the
 * photograph is part of (§11). Downward at the top of that scroll dismisses; downward anywhere
 * else scrolls back to the photograph.
 *
 * ⚠ Still not here: video playback (§5 of `drive-content-preview.md`).
 */
@Composable
fun PhotoViewerScreen(
    photos: List<Photo>,
    initialIndex: Int,
    state: PhotoViewerUiState,
    loadHero: HeroLoader,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The pager moved. ⚠ Drives the info sheet, which must follow the page or lie about it. */
    onPhotoShown: (Photo) -> Unit = {},
    /**
     * Ask the owner for the next page of its list. ⚠ `null` for a list that is already whole — an
     * album, a search result — so the viewer cannot ask a screen for pages it has no way to serve.
     */
    onLoadMore: (() -> Unit)? = null,
    /**
     * Moves the photo on screen to the trash. ⚠ Never deletes it: the irreversible action lives
     * behind a confirmation on the Trash screen, and this app has exactly one place that can
     * destroy a photograph.
     */
    onTrash: ((Photo) -> Unit)? = null,
    /** Opens and closes the info sheet. ⚠ `null` where nothing can read the metadata (previews). */
    onToggleInfo: (() -> Unit)? = null,
    /** A tap on the photograph — toggles the chrome and the OS bars together (§3.1b). */
    onToggleChrome: () -> Unit = {},
    /**
     * The panel was opened or closed **by scrolling** rather than by ⋯ → About (§11).
     *
     * ⚠ Must be idempotent on the receiving side: it reports the state, it does not toggle it.
     */
    onInfoOpenChanged: (Boolean) -> Unit = {},
    /**
     * Favourite / un-favourite the photo on screen. ⚠ The star renders **disabled** rather than
     * absent when [PhotoViewerUiState.canFavourite] is false, so the affordance never lies about
     * what it will do.
     */
    onToggleFavourite: () -> Unit = {},
    /** Adds the photo on screen to an album. ⚠ `null` where nothing can receive it. */
    onAddToAlbum: ((Photo) -> Unit)? = null,
    /**
     * Opens the editor on the photo on screen (`photos-editing.md` ED-1). ⚠ `null` where nothing can
     * edit; offered for stills only — a video's edit is a trim, which this editor is not.
     */
    onEdit: ((Photo) -> Unit)? = null,
    /**
     * *Revert to original* (ED-3) on an edited photo — the caller confirms first. Offered in ⋯ only
     * when the photo on screen [Photo.isEdited]; `null` where nothing can revert.
     */
    onRevertEdit: ((Photo) -> Unit)? = null,
    /** The date shown in the bar's title, e.g. "9 Apr". Supplied by the shell (`PhotoDates`), so a
     * render test can pin it — the `groupIntoDays` precedent. */
    formatDate: (Long) -> String = { "" },
    /** The time shown in the subtitle, e.g. "21:26". */
    formatTime: (Long) -> String = { "" },
    /** The About sheet's header, e.g. "Thu, 9 Apr 2026 · 21:26". Supplied by the shell for the same
     * reason as the other two. */
    formatCapturedAt: (Long) -> String = { "" },
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)),
        // ⚠ A lambda, not a captured count: the feed's list grows while the viewer is open, and a
        // page count read once at first composition is what would make the swipe stop at the end
        // of whatever had loaded when the user tapped.
        pageCount = { photos.size },
    )
    val current = photos.getOrNull(pagerState.currentPage)

    // ⚠ The viewer is black in either theme, so the bars' icons must be light in both.
    LightSystemBarIcons()

    // ⚠ Hoisted because the PAGER needs it: a magnified photograph owns every drag, so the pager
    // must stop consuming horizontal ones. Only the page on screen ever receives a gesture, so the
    // single flag cannot be contended.
    var zoomed by remember { mutableStateOf(false) }
    // ⚠⚠ Cleared on a page change. Without it a photograph zoomed, then swiped away from once it
    // was back at fit-scale, could leave the flag set and silently lock the pager on the next page.
    LaunchedEffect(pagerState.currentPage) { zoomed = false }

    LaunchedEffect(pagerState, photos) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> photos.getOrNull(page)?.let(onPhotoShown) }
    }

    if (onLoadMore != null) {
        // ⚠ The feed grid's recipe verbatim: `snapshotFlow` + `distinctUntilChanged` fires once per
        // *crossing* of the threshold rather than once per settled page, and the owning view model
        // guards against concurrent pages anyway.
        LaunchedEffect(pagerState, photos.size) {
            snapshotFlow { pagerState.currentPage >= photos.size - PREFETCH_PAGES }
                .distinctUntilChanged()
                .filter { it }
                .collect { onLoadMore() }
        }
    }

    // ⚠⚠ Driven off the SAME flag as the chrome below. Two flags and `safeDrawing` starts
    // describing bars that are not on screen — see `PhotoViewerUiState.chromeVisible`.
    HideSystemBars(hidden = !state.chromeVisible)

    val keyScope = rememberCoroutineScope()
    // ⚠ Pages by the pager's own animation, so a key press and a swipe land the same way — and not
    // while magnified, the rule the drag already follows (D4).
    fun pageBy(delta: Int) {
        if (zoomed) return
        val target = (pagerState.currentPage + delta).coerceIn(0, (photos.size - 1).coerceAtLeast(0))
        keyScope.launch { pagerState.animateScrollToPage(target) }
    }
    val trashCurrent = { current?.let { onTrash?.invoke(it) }; Unit }

    // ⚠⚠ A host that takes focus, not bare `keyboardShortcuts`: a viewer is images and icon buttons,
    // nothing in it takes focus, and shortcuts on an unfocused subtree never fire (`photos-app.md`
    // §33). ⚠ Delete AND Backspace: the key labelled "delete" on a Mac sends Backspace.
    AppKeyboardShortcutHost(
        KeyboardShortcut(Key.DirectionLeft, repeats = true) { pageBy(-1) },
        KeyboardShortcut(Key.DirectionRight, repeats = true) { pageBy(1) },
        // ⚠ Esc closes the details before it closes the viewer, as the back gesture does.
        AppShortcuts.dismiss { if (state.infoOpen && onToggleInfo != null) onToggleInfo() else onBack() },
        AppShortcuts.deleteSelection(trashCurrent),
        KeyboardShortcut(Key.Backspace, onTriggered = trashCurrent),
        modifier = modifier.fillMaxSize().background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            // ⚠⚠ One page either side, composed ahead. This is the difference between "swipe, then
            // wait for a spinner" and the swipe feeling instantaneous: the neighbour's read is
            // already in flight before the finger goes down. It is also the memory bound — three
            // decoded heroes, not one per photo swiped past.
            beyondViewportPageCount = 1,
            key = { index -> photos[index].path },
            // ⚠⚠ **Not locked by an open panel, and that is the point of §11.** An overlay
            // describing one photograph above a pager showing another would put every field on the
            // wrong picture, and would need `userScrollEnabled = !state.infoOpen`. The details
            // belong to the page, so paging swaps photograph and details together and that
            // staleness is unrepresentable. Google Photos pages with its panel open for the same
            // reason.
            //
            // ⚠ Still locked while magnified: D4, the drag belongs to the pan.
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize().testTag(PHOTO_PAGER_TAG),
        ) { page ->
            PhotoPage(
                photo = photos[page],
                loadHero = loadHero,
                onZoomChanged = { pageZoomed ->
                    // ⚠ Only the page actually on screen may drive the lock. A neighbour is
                    // composed ahead (`beyondViewportPageCount`) and reports its own reset state;
                    // letting that through would unlock the pager mid-pinch.
                    if (page == pagerState.currentPage) zoomed = pageZoomed
                },
                onDismiss = onBack,
                // ⚠⚠ Only the page on screen is given its details. A neighbour is composed ahead,
                // and the view model holds the CURRENT photograph's metadata — handing that to a
                // neighbour would render page N's details under page N+1's picture, which is the
                // exact staleness the old pager lock existed to prevent. Null here means the
                // neighbour is simply a photograph until you land on it, at which point
                // `onPhotoShown` loads its own.
                infoOpen = state.infoOpen,
                onInfoOpenChanged = onInfoOpenChanged,
                isCurrent = page == pagerState.currentPage,
                // ⚠ `settledPage`, not `currentPage`: the former changes only once the pager stops,
                // which is what makes a dwell a dwell (X-4).
                isSettled = page == pagerState.settledPage,
                about = if (page == pagerState.currentPage) {
                    {
                        // ⚠ The content is decided in the shared `AboutSheetContent`; the iOS sheet
                        // draws the same one.
                        AboutSheet(AboutSheetContent.of(state, formatCapturedAt))
                    }
                } else {
                    null
                },
                // ⚠ `indication = null` and no interaction ripple: the pager keeps the drag, and a
                // ripple across the whole screen reads as a rendering fault rather than feedback.
                //
                // ⚠ `indication = null`: a ripple across the whole screen reads as a rendering
                // fault rather than feedback.
                //
                // ⚠⚠ No §9.3b guard (`enabled = !state.infoOpen`): the panel is not a thing
                // floating over the picture that a stray drag could dismiss behind; it is the same
                // surface, and a drag on it scrolls rather than clicks.
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleChrome,
                ),
            )
        }

        // ⚠⚠ The controls live in their own inset layer, **the photograph does not**. An overlay
        // at `Alignment.TopStart` of a full-bleed `Box` puts the back arrow *underneath the status
        // bar*: on a real phone a tap at its apparent centre goes to system UI and the viewer
        // simply does not respond. The image stays edge-to-edge — that is the
        // point of a viewer — and only the things you can press move inside the safe area.
        //
        // ⚠ The whole layer fades together, the info sheet included: §3.1b's immersive mode means
        // *all* UI goes, and a sheet left floating over a bare photograph would be the one thing
        // that did not.
        AnimatedVisibility(
            visible = state.chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // ⚠⚠ **The scrim `ViewerTopBar`'s legibility relies on.** Its contents are white;
                // without a scrim they are white-on-white over a bright photograph, and §11's
                // scroll brings the brightest part of a picture up under the bar.
                //
                // ⚠ Full-bleed, deliberately OUTSIDE `windowInsetsPadding`: the photograph runs
                // edge to edge and under the status bar, so a scrim clipped to the safe area would
                // leave the system clock and icons sitting on bare picture — the very place the
                // problem is worst.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(SCRIM_HEIGHT)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                            ),
                        ),
                )

                // ⚠ The bottom bar's scrim, for the same reason as the top one.
                if (!state.infoOpen) Box(
                    Modifier
                        .fillMaxWidth()
                        .height(SCRIM_HEIGHT)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                            ),
                        ),
                )

                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    ViewerTopBar(
                        state = state,
                        current = current,
                        onBack = onBack,
                        onToggleFavourite = onToggleFavourite,
                        onToggleInfo = onToggleInfo,
                        onAddToAlbum = onAddToAlbum,
                        onTrash = onTrash,
                        onRevertEdit = onRevertEdit,
                        formatDate = formatDate,
                        formatTime = formatTime,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    // ⚠ Hidden while the About panel is open: the panel is the page scrolled up
                    // (§11), and a bar over it would cover the details it is showing.
                    if (!state.infoOpen) {
                        ViewerBottomBar(
                            state = state,
                            current = current,
                            onEdit = onEdit,
                            onAddToAlbum = onAddToAlbum,
                            onTrash = onTrash,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The viewer's bar: where you are, and what you can do to the photograph you are looking at.
 *
 * ⚠ Not a `TopAppBar`. Material's bar paints a surface behind itself, and over a photograph that is
 * a grey band across the top of the image — this one is transparent by construction and legible
 * because its contents are white with a scrim behind them.
 */
@Composable
private fun ViewerTopBar(
    state: PhotoViewerUiState,
    current: Photo?,
    onBack: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleInfo: (() -> Unit)?,
    onAddToAlbum: ((Photo) -> Unit)?,
    onTrash: ((Photo) -> Unit)?,
    onRevertEdit: ((Photo) -> Unit)?,
    formatDate: (Long) -> String,
    formatTime: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatDate(state.photo.capturedAtMs),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            // ⚠⚠ The place half is omitted, never guessed. Most photographs carry no coordinate,
            // and one taken somewhere the user never dwelled belongs to no named `Place` even so —
            // `photos-app.md` §13.2. A subtitle that fell back to a country, a timezone or an
            // "Unknown location" would be asserting something no row says.
            val time = formatTime(state.photo.capturedAtMs)
            val place = state.place?.displayName
            val subtitle = listOfNotNull(time.ifBlank { null }, place).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }

        // ⚠⚠ Disabled rather than hidden when the row carries no `contentId`. A star that vanishes
        // on some photographs reads as a bug; one that is visibly unavailable is a statement.
        // ⚠ Enabled only once the flag is KNOWN, and not while a write runs — see
        // `PhotoViewerViewModel.toggleFavourite` and `PhotoViewerUiState.writing`.
        IconButton(
            onClick = onToggleFavourite,
            enabled = state.canFavourite && state.favourite != null && !state.writing,
        ) {
            val on = state.favourite == true
            Icon(
                imageVector = if (on) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (on) "Remove from favourites" else "Add to favourites",
                tint = if (state.canFavourite) Color.White else Color.White.copy(alpha = 0.38f),
            )
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "More",
                    tint = Color.White,
                )
            }
            // ⚠⚠ Every item here does something. `control-cross-app-open.md` §5g.2's rule — a
            // line with no receiving route is worse than none — is why *Share* and *Open in Maps*
            // are absent rather than present and inert.
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                onToggleInfo?.let { toggle ->
                    DropdownMenuItem(
                        text = { Text(if (state.infoOpen) "Hide about" else "About") },
                        onClick = { menuOpen = false; toggle() },
                        leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    )
                }
                // ED-3: only on an edited photo, so the item always does something (§5g.2).
                // ⚠ The view model's photo first: it is what a revert just updated, while [current]
                // comes from the list the pager was handed.
                val edited = (state.photo.takeIf { current == null || it.path == current.path } ?: current)
                    ?.takeIf { it.isEdited }
                if (onRevertEdit != null && edited != null) {
                    DropdownMenuItem(
                        text = { Text("Revert to original") },
                        enabled = !state.writing,
                        onClick = { menuOpen = false; onRevertEdit(edited) },
                        modifier = Modifier.testTag(VIEWER_REVERT_TAG),
                    )
                }
                // ⚠ Add to album and Move to trash moved to the bottom bar (ED-D7); the menu keeps
                // only what has no bar slot.
            }
        }
    }
}

/**
 * The viewer's bottom bar — **Edit · Add to · Bin** (`photos-editing.md` ED-D7, Google's §1.1 bar).
 *
 * ⚠⚠ **No Share slot** until Share exists: a button that does nothing is the line-with-no-route rule
 * (`control-cross-app-open.md` §5g.2). Share joins first in line when it ships.
 *
 * ⚠ Add to and Bin are **disabled**, not hidden, for a photo with no `contentId` (the album and trash
 * ops refuse it) — the star's rule: a control that vanishes on some photographs reads as a bug.
 */
@Composable
private fun ViewerBottomBar(
    state: PhotoViewerUiState,
    current: Photo?,
    onEdit: ((Photo) -> Unit)?,
    onAddToAlbum: ((Photo) -> Unit)?,
    onTrash: ((Photo) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        onEdit?.let { edit ->
            BarAction(
                label = "Edit",
                icon = Icons.Outlined.Tune,
                enabled = current != null && !current.isVideo && !state.writing,
                testTag = VIEWER_EDIT_TAG,
            ) { current?.let(edit) }
        }
        onAddToAlbum?.let { add ->
            BarAction(
                label = "Add to",
                icon = Icons.Outlined.LibraryAdd,
                enabled = state.canFavourite && !state.writing,
            ) { current?.let(add) }
        }
        onTrash?.let { trash ->
            BarAction(
                label = "Bin",
                icon = Icons.Outlined.Delete,
                enabled = state.canFavourite && !state.writing,
            ) { current?.let(trash) }
        }
    }
}

@Composable
private fun BarAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.38f)
    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}

/** The bottom bar's Edit button. */
const val VIEWER_EDIT_TAG = "viewer-edit"
const val VIEWER_REVERT_TAG = "viewer-revert-edit"

/**
 * One photograph, full bleed.
 *
 * ⚠⚠ **The page loads and holds its own bytes, keyed on the path** — `PhotoTile`'s rule, and here
 * it is load-bearing rather than tidy: the pager keeps a window of pages composed and discards the
 * rest, so this is what makes the number of decoded heroes alive a function of the window and not
 * of how far the user has swiped.
 *
 * ⚠ It asks for a large *preview* at the size it is drawn (§34), not `File.Data`: on a device that
 * holds no bytes the original is a content-plane fetch of megabytes, while the preview path already
 * falls back to the synced thumbnail classes. ⚠ The cost is honest — a device without the original
 * is served the 1024 px hero rung however large the request, so a retina viewer there is still an
 * upscale until a larger rung exists (`drive-synced-thumbnails.md` §17.4).
 */
@Composable
private fun PhotoPage(
    photo: Photo,
    loadHero: HeroLoader,
    modifier: Modifier = Modifier,
    /** Reports whether this page is magnified — the pager locks its drag while it is. */
    onZoomChanged: (Boolean) -> Unit = {},
    /** Fired when a downward drag with the panel closed passes [ViewerGestures.DISMISS_FRACTION]. */
    onDismiss: () -> Unit = {},
    /**
     * This photograph's details, shown **below** it (§11).
     *
     * ⚠⚠ `null` for any page that is not the one on screen. The view model holds one photograph's
     * metadata — the current one — so giving it to a neighbour composed ahead would put page N's
     * details under page N+1's picture.
     */
    about: (@Composable () -> Unit)? = null,
    /** Whether the panel should be showing. Drives the reveal; also driven *by* it. */
    infoOpen: Boolean = false,
    /** Reports the panel opening or closing because the user dragged rather than tapped ⋯. */
    onInfoOpenChanged: (Boolean) -> Unit = {},
    /** Whether this is the page on screen. ⚠ Only it may report the panel's state (§11.6). */
    isCurrent: Boolean = true,
    /**
     * Whether the pager has **settled** on this page — not merely composed it (X-4, §4.6).
     *
     * ⚠⚠ The distinction is the whole gate. `beyondViewportPageCount = 1` composes a neighbour on
     * each side and every composed page starts its loader at once, so a dwell timed from composition
     * would download **two unseen originals** (6–18 MB each) every time one photograph is looked at.
     */
    isSettled: Boolean = false,
) {
    // ⚠ Keyed on the path, so each page keeps its own magnification while it is composed and a
    // recycled page starts at fit-scale rather than inheriting the last photograph's zoom.
    var scale by remember(photo.path) { mutableFloatStateOf(1f) }
    var offset by remember(photo.path) { mutableStateOf(Offset.Zero) }
    val zoomed = scale > 1f
    LaunchedEffect(zoomed) { onZoomChanged(zoomed) }

    // ⚠ `Animatable`s rather than plain state: each has two writers with different needs — the
    // finger sets it directly (`snapTo`), the release settles it (`animateTo`). Mixing a direct
    // write with `animateFloatAsState` gives a value that fights itself mid-drag.
    val dismissY = remember(photo.path) { Animatable(0f) }
    val reveal = remember(photo.path) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val panelScroll = rememberScrollState(0)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportPx = constraints.maxHeight.toFloat()
        val widthPx = constraints.maxWidth.toFloat()

        // ⚠⚠ The size this photograph is DRAWN at, in physical pixels (`heroPreviewRequestPx`,
        // `photos-app.md` §34) — a constant 1024 px is a ~3× upscale on a retina panel. ⚠ Read off
        // the constraints, not `onSizeChanged`: the key is right on the first frame, so the page
        // loads once, not once at a guess and again at the real size (§16's trap).
        val requestPx = heroPreviewRequestPx(
            viewportWidthPx = constraints.maxWidth,
            viewportHeightPx = constraints.maxHeight,
            pixelWidth = photo.pixelWidth,
            pixelHeight = photo.pixelHeight,
        )

        // ⚠⚠ The page loads and holds its own bytes, keyed on the path — see the KDoc — and on the
        // request, so a window grown past a step asks again. ⚠ The picture already on screen stays
        // there while the larger one loads: `produceState` keeps its value across a key change, and
        // a spinner over a photograph the user was looking at would read as the photograph vanishing.
        // Only the SAME photograph's bitmap is kept, never a recycled page's.
        // ⚠⚠ `collect`, not one await: the loader may answer twice for one key — the local class
        // first, the named rung once it arrives (§4.5). Each emission replaces the picture in place,
        // which is the upgrade the user sees; a page that only ever gets one emission is unchanged.
        // ⚠⚠ **The licence is *observed*, never captured** (D-18, X-4). `produceState` below is
        // keyed on the path and the size and must not also be keyed on settling — that would restart
        // the whole load every time the pager stops. But a neighbour is composed *before* the swipe,
        // with `isSettled = false`, so a licence built from the captured parameter freezes that
        // `false` and the page never mints once it is actually reached: mint-on-**open** rather than
        // mint-on-view, for every page but the first. Reading it through `snapshotFlow` means the
        // producer that started early still sees settling when it happens.
        // ⚠⚠ **The clock runs beside the page, not inside the loader's subscription.** D-18 says "a
        // second of being looked at", and the loader does not subscribe until it has been to the
        // relay and back twice (§4.5 steps 1–2) — so timing the wait from subscription measures
        // `time-to-reach-step-3 + DWELL_MS`, which on a slow network is several seconds and on a
        // fast one is the dwell. That errs safe (never *earlier* than a second) but it is not the
        // stated policy, and it makes the wait depend on the network rather than on the user. A
        // `LaunchedEffect` beside the page starts it the moment the page settles, whoever is or is
        // not listening.
        // ⚠ Keyed on settling, so swiping away part-way through cancels the pending wait rather
        // than letting it fire late; latched once earned, so a loader that finishes its reads after
        // the user has been staring for five seconds is not made to wait another one.
        // ⚠ ED-3: everything here reads [Photo.previewPath] — an edited photo is drawn by its
        // rendition, or not at all (`photos-editing.md` §12.4); never its original as a fallback.
        var dwelt by remember(photo.previewPath, requestPx) { mutableStateOf(false) }
        LaunchedEffect(photo.previewPath, requestPx, isSettled) {
            if (!isSettled) return@LaunchedEffect
            delay(DWELL_MS)
            dwelt = true
        }
        // ⚠⚠ `take(1)` is the difference between a licence and a subscription that never ends.
        // Without it this flow never completes, so `mayMint.firstOrNull()` in a collector that is
        // *not* going to settle suspends for ever and the `Absent` branch below it is unreachable —
        // the page spins instead of saying the photograph is not here. One emission, then done.
        val mayMint = remember(photo.previewPath, requestPx) {
            snapshotFlow { dwelt }.filter { it }.take(1).map { }
        }

        val hero by produceState<HeroState>(HeroState.Loading, photo.previewPath, requestPx) {
            val shown = (value as? HeroState.Ready)?.takeIf { it.path == photo.previewPath }
            if (shown == null) value = HeroState.Loading
            var drew = false
            // ⚠⚠ **The dwell, handed to the loader rather than run beside it** (D-18, X-4). A page
            // that is composed but not settled never emits, so a neighbour the pager prepared ahead
            // spends nothing; the settled page emits once, `DWELL_MS` after it settles, and leaving
            // cancels this whole producer. ⚠ The result arrives as another emission of the same
            // flow, so it goes through the same comparison and the same state as every other answer
            // — the alternative, a second state drawn over the top, lets a mint replace a
            // *sharper* render on a byte holder and leaves one that succeeded never shown at all.
            loadHero(photo.previewPath, requestPx, mayMint).collect { bytes ->
                // ⚠⚠ Decoded at the size the page DRAWS, never the rung's own 2048 px (§4.5).
                // A 2048 px class is 16 MiB square at `ARGB_8888` and the pager keeps three
                // pages composed; at a ~1440 px phone viewport this is the difference between
                // ~24 and ~48 MiB of live bitmaps. ⚠ It never decodes *below* `requestPx` —
                // `decodeSampleSize` stops one step short and scales the remainder exactly.
                bytes.decodePreviewBitmap(maxEdge = requestPx)?.let { bitmap ->
                    drew = true
                    value = HeroState.Ready(photo.previewPath, bitmap)
                }
            }
            // Nothing decodable at all: keep this photograph's own last picture if it had one, as a
            // spinner over a photograph the user was looking at reads as the photograph vanishing.
            if (!drew) value = shown ?: HeroState.Absent
        }

        // How far the panel may rise. ⚠ A fraction of the SCREEN, not of the content: the panel's
        // resting place has to be the same for every photograph, because the photograph is
        // positioned relative to *it* (below).
        val maxReveal = if (about == null) 0f else viewportPx * (1f - PANEL_TOP_FRACTION)

        // The photograph's displayed height under `ContentScale.Fit`, and the letterbox margin
        // above and below it.
        val heroHeightPx = (hero as? HeroState.Ready)?.bitmap?.let { bmp ->
            minOf(viewportPx, widthPx * bmp.height.toFloat() / bmp.width.toFloat())
        } ?: viewportPx
        val letterbox = (viewportPx - heroHeightPx) / 2f

        // ⚠⚠ **The rule §11.9 is built on: the photograph's bottom edge meets the panel's top
        // edge, and the photograph never moves DOWN.** `min(0f, …)` is the whole of it.
        //
        // While the panel is rising through the black margin below a letterboxed photograph
        // (`reveal <= letterbox`) the photograph does not move at all — the panel is eating space
        // that was never picture. Only once the panel reaches the picture's bottom does the
        // picture start travelling with it.
        //
        // ⚠ This is what makes one rule serve both aspect ratios. A landscape photograph has a
        // large `letterbox`, so it barely moves and stays whole; a portrait one has almost none,
        // so it travels the full distance and its top is clipped. Measured against Google Photos:
        // predicted −1193 px for a portrait photograph, observed −1195.
        val heroShift = minOf(0f, letterbox - reveal.value)

        // ⚠⚠ **The panel drags the panel.** Without this the only draggable surface is the
        // photograph, so a panel that fills three quarters of the screen cannot be pulled shut
        // from anywhere you would naturally put your thumb.
        //
        // ⚠ Google's panel closes on a downward drag too.
        //
        // The two directions are not symmetrical, which is why this is a nested-scroll connection
        // rather than one more branch in the gesture loop:
        //
        //   dragging UP    → *pre*-scroll: raise the panel first, and only once it is fully up
        //                    does its content begin to scroll
        //   dragging DOWN  → *post*-scroll: the content scrolls back to its top first, and only
        //                    what it could not use lowers the panel
        //
        // ⚠ That ordering is the whole feel of it. Reversed, the panel would collapse under you
        // while you were still reading half way down a list of EXIF rows.
        val panelNested = remember(maxReveal) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0f || reveal.value >= maxReveal) return Offset.Zero
                    val next = (reveal.value - available.y).coerceAtMost(maxReveal)
                    val used = reveal.value - next
                    scope.launch { reveal.snapTo(next) }
                    return Offset(0f, used)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (available.y <= 0f || reveal.value <= 0f) return Offset.Zero
                    val next = (reveal.value - available.y).coerceAtLeast(0f)
                    val used = reveal.value - next
                    scope.launch { reveal.snapTo(next) }
                    return Offset(0f, used)
                }

                // ⚠ The detent, for gestures that end on the panel. The loop on the photograph
                // settles its own; this is the same rule for the other surface, and without it a
                // drag released half way leaves the panel stuck there.
                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    val settled = if (reveal.value > maxReveal / 2f) maxReveal else 0f
                    if (reveal.value != settled) reveal.animateTo(settled)
                    return Velocity.Zero
                }
            }
        }

        PanelRevealBinding(
            reveal = reveal,
            maxReveal = maxReveal,
            infoOpen = infoOpen,
            onInfoOpenChanged = onInfoOpenChanged,
            isCurrent = isCurrent,
        )

        Box(Modifier.fillMaxSize()) {
            when (val shown = hero) {
                HeroState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is HeroState.Ready -> Image(
                    bitmap = shown.bitmap,
                    contentDescription = photo.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // ⚠ The shrink is the dismiss's feedback. The viewer's ground is opaque
                            // black with nothing composed behind it, so fading it out would reveal
                            // the window rather than the grid — the picture pulling away from the
                            // frame is the honest way to say "let go and this closes".
                            // The shared arithmetic (`ViewerGestures`) — the iOS viewer shrinks by it too.
                            val shrink = ViewerGestures.dismissScale(dismissY.value, size.height)
                            scaleX = scale * shrink
                            scaleY = scale * shrink
                            translationX = offset.x
                            translationY = offset.y + dismissY.value + heroShift
                        }
                        // ⚠⚠ **A hand-written loop rather than `detectTransformGestures`, and the
                        // reason is consumption.** The stock detector consumes a single-finger pan
                        // as soon as it passes slop, which at fit-scale would steal every
                        // horizontal drag from the pager and stop the swipe working at all. This
                        // one consumes ONLY when it should own the gesture:
                        //
                        //   two fingers            → always ours (a pinch is never a page swipe)
                        //   one finger, zoomed in  → ours, it is a pan (D4)
                        //   one finger, vertical   → ours: it moves the panel, and past the bottom
                        //                            of its travel it becomes the dismiss
                        //   one finger, sideways   → NOT consumed, so the pager sees it
                        //
                        // ⚠ Getting this wrong is not hypothetical: an unconsumed drag can register
                        // as a *click* and dismiss the whole chrome (§9.3b). Who consumes what is
                        // the entire arbitration.
                        .pointerInput(photo.path, maxReveal) {
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                // ⚠⚠ Latched, decided at most ONCE per gesture. An owner that can
                                // change under the finger is the bug D4 exists to prevent.
                                var claim = Claim.Undecided
                                var travel = Offset.Zero
                                do {
                                    val event = awaitPointerEvent()
                                    val pan = event.calculatePan()
                                    if (event.changes.count { it.pressed } > 1 || scale > 1f) {
                                        claim = Claim.Transform
                                    }
                                    if (claim == Claim.Undecided) {
                                        travel += pan
                                        // ⚠ The axis lock, and it is why adding these gestures did
                                        // not cost the pager its swipe: a mostly-sideways drag is
                                        // never claimed, so it stays unconsumed and the pager
                                        // still sees it.
                                        if (abs(travel.y) > slop && abs(travel.y) > abs(travel.x)) {
                                            claim = Claim.Vertical
                                        }
                                    }
                                    when (claim) {
                                        Claim.Transform -> {
                                            val next = ViewerGestures.clampZoom(scale * event.calculateZoom())
                                            offset = if (next > 1f) offset + pan else Offset.Zero
                                            scale = next
                                        }

                                        Claim.Vertical -> {
                                            // The drag closes the panel before it dismisses: what
                                            // the reveal cannot absorb falls through to the
                                            // dismiss, so one continuous downward drag walks the
                                            // photograph back and then out.
                                            val next = (reveal.value - pan.y)
                                                .coerceIn(0f, maxReveal)
                                            val absorbed = reveal.value - next
                                            scope.launch { reveal.snapTo(next) }
                                            val leftover = pan.y - absorbed
                                            if (next == 0f && leftover > 0f) {
                                                scope.launch {
                                                    dismissY.snapTo(
                                                        (dismissY.value + leftover)
                                                            .coerceAtLeast(0f),
                                                    )
                                                }
                                            }
                                        }

                                        Claim.Undecided -> Unit
                                    }
                                    if (claim != Claim.Undecided) {
                                        event.changes.forEach {
                                            if (it.positionChanged()) it.consume()
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                if (claim == Claim.Vertical) {
                                    // ⚠ Read once, after the finger is up: both values are still
                                    // being written by the coroutines above, and the decision is
                                    // about where the photograph actually IS.
                                    if (ViewerGestures.dismisses(dismissY.value, size.height.toFloat())) {
                                        onDismiss()
                                    } else {
                                        scope.launch { dismissY.animateTo(0f) }
                                        // ⚠ The detent. Without it a half-open panel simply stays
                                        // half open, which reads as the screen having got stuck.
                                        val settled =
                                            if (reveal.value > maxReveal / 2f) maxReveal else 0f
                                        scope.launch { reveal.animateTo(settled) }
                                    }
                                }
                            }
                        },
                )

                // ⚠ Not an error: a device that holds neither the bytes nor a thumbnail for this
                // file has nothing to show *yet*, and saying so beats an empty black rectangle.
                HeroState.Absent -> Text(
                    text = "This photo hasn't been downloaded to this device yet.",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }

            // ⚠⚠ **A layer positioned by `reveal`, not an element in a scrolling column.** The
            // column version could not work: with the photograph centred in a viewport-tall box,
            // the black margin below it is INSIDE that box, so the distance between the picture's
            // bottom and the panel's top is fixed at `(viewport − heroHeight) / 2` and no scroll
            // offset can close it. A landscape photograph therefore opened onto a screen of black.
            // The two have to move at different rates, which means two offsets, not one scroll.
            about?.let { panel ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, (viewportPx - reveal.value).roundToInt()) }
                        .background(Color.Black)
                        // ⚠ Order matters: `nestedScroll` must wrap the scroll it is arbitrating
                        // for, so it sits BEFORE `verticalScroll` in the chain.
                        //
                        // ⚠⚠ **The scroll below currently never scrolls.** Even a full-EXIF camera
                        // capture's details fit the space the panel is given, so `maxValue` is 0,
                        // the child consumes nothing and every drag falls through to the
                        // connection — which is exactly why dragging the panel closes it. The
                        // `onPreScroll` half is therefore unreachable until something makes this
                        // content taller: a map, a caption, a shorter screen, or a
                        // lower anchor. Whoever adds that owns checking the ordering (§11.10).
                        .nestedScroll(panelNested)
                        .verticalScroll(panelScroll),
                ) { panel() }
            }
        }
    }
}


/**
 * Keeps the panel's *state* and how far it has been pulled up agreeing, in both directions.
 *
 * ⚠⚠ **Two-way, and that is why the view model needed `setInfoOpen` rather than `toggleInfo`.**
 * ⋯ → About sets the flag and this raises the panel; a drag raises the panel and this sets the
 * flag. A toggle on the reporting side would close a panel the user had just dragged open.
 *
 * ⚠ Both directions are idempotent — the flow is `distinctUntilChanged`, the setter returns early
 * when nothing changed — which is what stops the pair oscillating.
 */
@Composable
private fun PanelRevealBinding(
    reveal: Animatable<Float, *>,
    maxReveal: Float,
    infoOpen: Boolean,
    onInfoOpenChanged: (Boolean) -> Unit,
    isCurrent: Boolean,
) {
    // Flag → reveal. ⚠ Keyed on `maxReveal` too: it is 0 until the page has been measured and
    // until this page has been given its details, so the first run would otherwise animate to
    // nothing and stop.
    LaunchedEffect(infoOpen, maxReveal) {
        if (maxReveal <= 0f) return@LaunchedEffect
        val target = if (infoOpen) maxReveal else 0f
        if (reveal.value != target) reveal.animateTo(target)
    }

    // Reveal → flag. ⚠ A fraction of the way, not "any movement at all": a few pixels of drag is
    // an unfinished gesture, and reporting it would flip the ⋯ label and arm Back for a panel the
    // user has not actually opened.
    //
    // ⚠⚠ **Only the page on screen may report.** Without this gate, paging with the panel open
    // closes it — and the culprit is the page being LEFT, not the one arriving: as a page stops
    // being current it loses its `about`, `maxReveal` drops to 0 and its predicate flips
    // `true → false` — a perfectly genuine later emission, which `drop(1)` cannot touch and
    // `distinctUntilChanged` is happy to pass on.
    LaunchedEffect(reveal, isCurrent) {
        if (!isCurrent) return@LaunchedEffect
        snapshotFlow { maxReveal > 0f && reveal.value > maxReveal * INFO_REPORTED }
            .distinctUntilChanged()
            // ⚠ Still right for the incoming page, which mounts at 0 and would otherwise report a
            // close it never performed.
            .drop(1)
            .collect(onInfoOpenChanged)
    }
}

/**
 * ⚠ Three states, not a nullable bitmap: "still loading" and "this device has no bytes for it" are
 * different answers, and collapsing them is what makes a slow read look like a missing photograph.
 */
private sealed interface HeroState {
    data object Loading : HeroState
    /** ⚠ Carries its path, so a page never keeps another photograph's picture across a reload. */
    data class Ready(val path: String, val bitmap: ImageBitmap) : HeroState
    data object Absent : HeroState
}

/**
 * **About** — everything known about one photograph (`photos-viewer-about-screen.md` §3.2).
 *
 * ⚠⚠ **Part of the page, not a screen.** The simplifying choice: no navigation destination, no
 * back-stack entry, no origin-tracking, and the pager keeps its page (§11).
 *
 * ⚠ Every row is conditional on its value. The one always shown is the path, because it is the only
 * thing the viewer knows without a read — and while the reads are in flight the sheet shows what it
 * has rather than a spinner, which would make opening the panel feel like loading a screen.
 */
@Composable
private fun AboutSheet(
    content: AboutSheetContent,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            // ⚠⚠ **No `verticalScroll` and no height cap here, and both removals are
            // load-bearing.** The scroll belongs to the *caller* — `PhotoPage` wraps this in the
            // layer it positions by `reveal` and puts the scroll there (§11.9). A second one
            // inside would be a nested vertical scroll, which Compose answers with "measured with
            // an infinity maximum height constraints" and a crash, not a layout quirk. No height
            // cap either: the photograph is above the panel, not behind it.
            //
            // ⚠ Opaque, not translucent: sitting on its own, translucency just lets a bright
            // photograph bleed through the text.
            .background(Color.Black)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // The drag handle. ⚠ Not decorative: dragging up from the photograph scrolls into
        // this panel, and this is the affordance that says so (§11).
        Box(
            Modifier
                .padding(bottom = 12.dp)
                .align(Alignment.CenterHorizontally)
                .background(Color.White.copy(alpha = 0.32f), RoundedCornerShape(2.dp))
                .size(width = 32.dp, height = 4.dp),
        )

        Text(
            text = content.title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Text(
            text = content.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.62f),
        )

        // ⚠ Omitted entirely when it is in none — an empty "Albums" heading is a section that
        // exists to say nothing.
        if (content.albums.isNotEmpty()) {
            SheetHeading("Albums")
            content.albums.forEach { album ->
                Text(
                    text = album.line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }

        // ⚠⚠ Shown only where a **named** `Place` claims the coordinate (D2) — see `AboutSheetContent`.
        content.place?.let {
            SheetHeading("Place")
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }

        SheetHeading("Details")
        content.details.forEach { InfoRow(it.label, it.value, it.suffix) }

        PhotographicRows(content.settings)
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White.copy(alpha = 0.62f),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * How the photograph was taken — the rows that come from the file's own bytes.
 *
 * ⚠⚠ Four states, and only one of them is rows. `null` is *still reading* and shows nothing rather
 * than a spinner, so the sheet does not appear to be loading a second time. `NotOnDevice` says so in
 * one line — it is a **fact about this device**, not a failure, and not something to hide: a panel
 * that silently omits the settings for half a library reads as a parser that does not work.
 *
 * ⚠ Every row is still conditional on its own value. A stripped photograph reaches `Read` and has
 * almost nothing in it, which is the same statement the other rows already make.
 */
@Composable
private fun PhotographicRows(settings: AboutSettings) {
    when (settings) {
        AboutSettings.Reading, AboutSettings.Silent -> Unit

        is AboutSettings.NotOnDevice -> {
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = settings.message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.62f),
            )
        }

        is AboutSettings.Rows -> {
            if (settings.rows.isEmpty()) return
            Spacer(Modifier.padding(top = 6.dp))
            settings.rows.forEach { InfoRow(it.label, it.value, it.suffix) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, suffix: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.62f),
            modifier = Modifier.weight(0.32f),
        )
        Text(
            text = if (suffix != null) "$value  ($suffix)" else value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.weight(0.68f),
        )
    }
}

/** The pager, for UI tests that need to swipe it — the `pdfPager` precedent. */
const val PHOTO_PAGER_TAG = "photoPager"

/**
 * Loads a photograph's bytes for the viewer at `requestPx` — a size from `heroPreviewRequestPx`, the
 * long edge the photograph is drawn at, stepped.
 *
 * ⚠⚠ **A stream, because one page may be drawn twice** (`drive-xxlarge-thumbnail-rung.md` §4.5):
 * the best local answer arrives at once so the page is never blank longer than it must be, and the
 * named rung replaces it when the pull lands. An emission is always a picture to draw — the flow
 * emits nothing at all rather than a null, and never emits bytes identical to the ones before them.
 */
typealias HeroLoader = (path: String, requestPx: Int, mayMint: Flow<Unit>) -> Flow<ByteArray>

/**
 * How long a photograph must be **settled** on screen before this device will spend a download on
 * sharpening it (D-18). ⚠ A swipe past is not a look. `internal` so `ViewerDwellMintTest` steps
 * its clock by this rather than by a guess.
 */
internal const val DWELL_MS = 1_000L

/**
 * Who owns the gesture in progress. ⚠⚠ Latched once and never revised, which is the point: an owner
 * that can change while the finger is still down is precisely the failure D4 was written against.
 */
private enum class Claim { Undecided, Transform, Vertical }

/** How far the top scrim reaches. ⚠ Enough to cover the status bar as well as the viewer's own
 * bar — the photograph is behind both. */
private val SCRIM_HEIGHT = 168.dp

/**
 * Where the panel's top edge comes to rest, as a fraction of the screen height from the top.
 *
 * ⚠⚠ A fraction of the SCREEN, not of the content, and that is load-bearing: the photograph is
 * positioned relative to the panel's top edge, so that edge has to land in the same place for every
 * picture. Measured off Google Photos, whose panel settles at ~31% for both a landscape and a
 * portrait photograph.
 */
private const val PANEL_TOP_FRACTION = 0.31f

/** How far the user must scroll before the panel counts as open. */
private const val INFO_REPORTED = 0.25f

/** How close to the end of the list the pager starts asking for the next page — two photos, so a
 * fast swipe meets loaded rows rather than the end of the list. */
private const val PREFETCH_PAGES = 2
