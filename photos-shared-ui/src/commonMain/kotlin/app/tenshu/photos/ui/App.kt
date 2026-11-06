package app.tenshu.photos.ui

import app.tenshu.photos.shared.resources.Res
import app.tenshu.photos.shared.resources.photos_control_fenced_update_app
import app.tenshu.photos.shared.resources.photos_control_fenced_update_both
import app.tenshu.photos.shared.resources.photos_control_fenced_update_control
import app.tenshu.photos.shared.resources.photos_control_fenced_update_unknown
import app.tenshu.photos.shared.resources.photos_control_get_action
import app.tenshu.photos.shared.resources.photos_control_not_installed
import app.tenshu.photos.shared.resources.photos_control_not_installed_other_channel
import app.tenshu.photos.shared.resources.photos_control_not_installed_unpublished
import app.tenshu.photos.shared.resources.photos_control_not_set_up
import app.tenshu.photos.shared.resources.photos_control_open_action
import app.tenshu.photos.shared.resources.photos_control_skew_update_app
import app.tenshu.photos.shared.resources.photos_control_skew_update_both
import app.tenshu.photos.shared.resources.photos_control_skew_update_control
import app.tenshu.photos.shared.resources.photos_control_unreachable
import app.tenshu.photos.shared.resources.photos_control_wall_title
import app.tenshu.photos.shared.resources.photos_update_action
import org.jetbrains.compose.resources.stringResource
import app.tenshu.ui.legal.OpenSourceLicencesScreen
import app.tenshu.ui.feedback.appControlCheckingLabel
import app.tenshu.photos.time.PhotoDates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import app.tenshu.photos.ui.components.RealmStorageBannerHost
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import app.tenshu.ui.feedback.ControlBannerAction
import app.tenshu.ui.feedback.AppControlUnavailableWall
import app.tenshu.ui.feedback.AppControlCheckingGate
import app.tenshu.ui.feedback.ErrorBanner
import app.tenshu.ui.feedback.controlBannerIcon
import app.tenshu.ui.feedback.rememberControlBannerAction
import app.tenshu.ui.storage.ControlInstallRoute
import app.tenshu.ui.storage.rememberControlInstallRoute
import app.tenshu.photos.ui.components.rememberRealmStorageState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import app.tenshu.photos.ui.screens.feed.BulkOutcome
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.GridView
import app.tenshu.photos.ui.screens.feed.FeedDensity
import androidx.compose.runtime.getValue
import app.tenshu.photos.ui.components.PreviewCache
import app.tenshu.ui.input.AppShortcuts
import app.tenshu.ui.input.KeyboardShortcut
import app.tenshu.ui.input.AppKeyboardShortcutHost
import androidx.compose.ui.input.key.Key
import app.tenshu.photos.ui.components.PreviewLoader
import androidx.compose.foundation.layout.PaddingValues
import org.tenshu.storage.common.RealmStorageState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import app.tenshu.photos.ui.navigation.Destination
import app.tenshu.photos.ui.navigation.PhotoCollection
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.settings.GridMotionSettings
import app.tenshu.photos.settings.PhotosPreferences
import app.tenshu.photos.ui.components.isMotionPlaybackSupported
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailScreen
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailUiState
import app.tenshu.photos.ui.screens.albumdetail.PersonHeader
import app.tenshu.photos.ui.screens.albumdetail.companionsFor
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailViewModel
import app.tenshu.photos.ui.screens.collections.CollectionSectionScreen
import app.tenshu.photos.ui.screens.collections.CollectionsScreen
import app.tenshu.photos.ui.screens.collections.CollectionsUtility
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.collections.SmartAlbum
import app.tenshu.photos.ui.screens.feed.FeedError
import app.tenshu.photos.ui.screens.duplicates.DuplicatesScreen
import app.tenshu.photos.ui.screens.duplicates.DuplicatesViewModel
import app.tenshu.photos.ui.screens.feed.PhotosFeedScreen
import app.tenshu.photos.ui.screens.search.PhotoSearchScreen
import app.tenshu.photos.ui.screens.search.PhotoSearchViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import app.tenshu.photos.model.FeedItem
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import app.tenshu.photos.ui.screens.settings.SettingsScreen
import app.tenshu.photos.ui.screens.settings.SettingsViewModel
import app.tenshu.photos.ui.screens.trash.TrashScreen
import app.tenshu.photos.ui.screens.trash.TrashViewModel
import app.tenshu.photos.ui.screens.viewer.HeroLoader
import app.tenshu.photos.ui.screens.viewer.PhotoViewerScreen
import app.tenshu.photos.ui.screens.viewer.PhotoViewerViewModel
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The Control-unavailable strip (`ADR 0025`).
 *
 * ⚠⚠ Without it, Photos renders an **empty feed with no explanation** when Control is gone — a
 * library that looks deleted.
 *
 * ⚠ Gated on the REASON, not `controlReachable == false`: a fenced device answers every read, so it
 * stays "reachable" while receiving nothing, and `controlReachable == false` never fires for it.
 *
 * Copy from `composeResources` (ADR 0016): the Control banner and walls are
 * the first of this app's strings in the catalogue — the rest of the app's literals follow in the
 * i18n rollout.
 */
@Composable
private fun ControlBanner(reason: UnreachableReason?) {
    if (reason == null) return
    val installRoute = rememberControlInstallRoute()
    val action = rememberControlBannerAction(reason, installRoute)
    ErrorBanner(
        text = when (val r = reason) {
            // ⚠⚠ The app to name is frequently NOT Photos: on Android and desktop one Control-owned
            // proxy serves every app, so a version disagreement is usually Control's to fix, and
            // telling the user to update Photos leaves them stuck after doing exactly that.
            is UnreachableReason.Skew -> when (r.update) {
                UpdateTarget.CONTROL ->
                    stringResource(Res.string.photos_control_skew_update_control)
                UpdateTarget.THIS_APP ->
                    stringResource(Res.string.photos_control_skew_update_app)
                UpdateTarget.BOTH ->
                    stringResource(Res.string.photos_control_skew_update_both)
            }
            is UnreachableReason.Fenced -> when (r.update) {
                UpdateTarget.CONTROL ->
                    stringResource(Res.string.photos_control_fenced_update_control)
                UpdateTarget.THIS_APP ->
                    stringResource(Res.string.photos_control_fenced_update_app)
                UpdateTarget.BOTH ->
                    stringResource(Res.string.photos_control_fenced_update_both)
                // ⚠ No host declared itself. Name nothing rather than guess wrong on a platform.
                null ->
                    stringResource(Res.string.photos_control_fenced_update_unknown)
            }
            // ⚠ Normally behind the wall (ADR 0025 §Decision 8); worded anyway, never as an outage.
            // Never reaches a banner — the root shows AppControlCheckingGate first (ADR 0025 §8.6).
            UnreachableReason.Checking -> appControlCheckingLabel()
            UnreachableReason.NotSetUp -> stringResource(Res.string.photos_control_not_set_up)
            UnreachableReason.NotInstalled -> when (installRoute) {
                is ControlInstallRoute.Store ->
                    stringResource(Res.string.photos_control_not_installed)
                ControlInstallRoute.NotPublishedYet ->
                    stringResource(Res.string.photos_control_not_installed_unpublished)
                ControlInstallRoute.SameChannelAsThisApp ->
                    stringResource(Res.string.photos_control_not_installed_other_channel)
            }
            UnreachableReason.Transport, null ->
                stringResource(Res.string.photos_control_unreachable)
        },
        icon = controlBannerIcon(reason),
        actionLabel = action?.let {
            when (it.kind) {
                ControlBannerAction.Kind.OPEN_CONTROL -> stringResource(Res.string.photos_control_open_action)
                ControlBannerAction.Kind.GET_CONTROL -> stringResource(Res.string.photos_control_get_action)
                ControlBannerAction.Kind.UPDATE -> stringResource(Res.string.photos_update_action)
            }
        },
        onAction = action?.onClick,
    )
}

/**
 * The Photos app shell (`photos-app.md`) — the feed, collections, and the viewer both open into.
 *
 * ⚠ Three tabs — the Settings tab manages what this device backs up. It **manages** rather than
 * configures: Photos does not own the backup drain, and the screen says so rather than offering a
 * picker that would write a row nothing reads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(modifier: Modifier = Modifier) {
    // ⚠⚠ A real back stack, not a remembered destination. `KmpNavHost` gives each entry its own
    // `SaveableStateHolder` slot and its own `ViewModelStore`, which is what makes a scrolled feed
    // come back where it was.
    val backStack = remember { mutableStateListOf<Destination>(Destination.Feed) }
    fun pop() { if (backStack.size > 1) backStack.removeLastOrNull() }
    fun push(destination: Destination) { backStack.add(destination) }
    fun selectTop(destination: Destination.TopLevel) {
        if (backStack.lastOrNull() != destination) {
            backStack.clear()
            backStack.add(destination)
        }
    }
    val current = backStack.last()

    /**
     * The rows the viewer pages over when it was opened from something other than the feed.
     *
     * ⚠⚠ Held by the SHELL, which outlives every nav entry. The alternative — naming the source in
     * the route and having the viewer fetch from whichever view model owns it — fails because those
     * view models live in *other* entries with their own `ViewModelStore`s; reaching across would
     * quietly construct a second one and re-read. Captured at the tap, which keeps a `List<Photo>`
     * out of the navigation route.
     */
    var viewerPhotos: List<Photo> by remember { mutableStateOf(emptyList()) }
    var pendingAlbumFor: Photo? by remember { mutableStateOf(null) }
    var creatingAlbum by remember { mutableStateOf(false) }

    val repository: PhotosRepository = koinInject()
    // ⚠ Collected once and passed down: the banner says *why*, and the feed uses the same answer to
    // decide whether a "Try again" can honestly be offered. Two collectors would be two sources of
    // truth for one fact, and whichever disagreed would be invisible.
    val unreachableReason by repository.controlUnreachableReason().collectAsState()
    val feedViewModel: PhotosFeedViewModel = koinViewModel()
    val trashViewModel: TrashViewModel = koinViewModel()
    val collectionsViewModel: CollectionsViewModel = koinViewModel()
    // ⚠⚠ **One presenter, hoisted above the nav host**, and it has to be one rather than two.
    // The viewer swipes through the *feed's* photographs and extends them by reading past what is
    // loaded (below) — so a presenter of its own would start at the first page and could not
    // address a photograph the grid had scrolled to. Sharing this one keeps the two showing the
    // same loaded window.
    val feedItems = feedViewModel.items.collectAsLazyPagingItems()
    // ⚠ Collected beside the rows rather than inside the feed screen: the density map is read once
    // per view model and the screen is re-entered on every navigation, so subscribing here keeps
    // one subscription alive across the round trip instead of restarting it.
    val feedYearMarks by feedViewModel.yearMarks.collectAsStateWithLifecycle()
    val feedSelection by feedViewModel.selected.collectAsStateWithLifecycle()
    // ⚠ The bar's actions wait while one runs; the single-flight guard itself is the view model's.
    val feedActing by feedViewModel.acting.collectAsStateWithLifecycle()
    val feedDensity by feedViewModel.density.collectAsStateWithLifecycle()
    // The feed's rotating player (`photos-video-support.md` §5) is driven from HERE — the one
    // place that sees the Settings row, the lifecycle and the platform at once. The rotation itself
    // is feed state (§5.2) and lives in the view model.
    val gridMotion: GridMotionSettings = koinInject()
    val playVideosInGrid by remember(gridMotion) { gridMotion.observePlayVideosInGrid() }
        .collectAsState(initial = PhotosPreferences.PlayVideosInGrid.default)
    // ⚠⚠ Gated on the platform as well as the switch: a platform with no tile player must never be
    // handed a turn, or the rotation waits forever on a tile that cannot finish (Windows/Linux).
    LaunchedEffect(playVideosInGrid) {
        feedViewModel.motion.setEnabled(playVideosInGrid && isMotionPlaybackSupported())
    }
    // Q1: a backgrounded app decoding video is a battery bug. Paused with the lifecycle, resumed
    // with it — the rotation picks up where it was, in document order.
    LifecycleResumeEffect(Unit) {
        feedViewModel.motion.setPaused(false)
        onPauseOrDispose { feedViewModel.motion.setPaused(true) }
    }
    val motionTurn by feedViewModel.motion.turn.collectAsStateWithLifecycle()
    // ⚠ The album picker is reused for one photograph and for many: `pendingAlbumFor` stays the
    // single-photo path (the viewer still uses it), and this is the selection's.
    var pickingAlbumForSelection by remember { mutableStateOf(false) }
    // ⚠⚠ The result of the last bulk action, held until the user dismisses it. A destructive action
    // that quietly does nothing is worse than one that fails loudly (`TrashViewModel`), and in bulk
    // the loud part has to survive the action finishing.
    var lastBulk: BulkOutcome? by remember { mutableStateOf(null) }

    // ⚠ The photographs alone, without the day headers that share the presenter's index space.
    // `Destination.Viewer.index` addresses THIS list, as it always has.
    val feedPhotos = feedItems.itemSnapshotList.items
        .filterIsInstance<FeedItem.Tile>()
        .map { it.photo }
    val collectionsState by collectionsViewModel.state.collectAsStateWithLifecycle()

    // ⚠ Selecting a tab CLEARS the stack rather than pushing: tabs are peers, and pushing them
    // would make Back walk a history of tab switches instead of leaving the app.
    val navItems = listOf(
        AdaptiveNavItem(
            key = Destination.Feed,
            icon = Icons.Default.PhotoLibrary,
            label = "Photos",
            selected = current is Destination.Feed,
            onClick = { selectTop(Destination.Feed) },
        ),
        AdaptiveNavItem(
            key = Destination.Settings,
            icon = Icons.Outlined.Settings,
            label = "Settings",
            selected = current is Destination.Settings,
            onClick = { selectTop(Destination.Settings) },
        ),
        AdaptiveNavItem(
            key = Destination.Collections,
            icon = Icons.Outlined.Collections,
            label = "Collections",
            selected = current is Destination.Collections,
            onClick = {
                selectTop(Destination.Collections)
                collectionsViewModel.refresh()
            },
        ),
    )

    // ⚠⚠ **No resume refresh, deliberately.** A photograph taken while the app was backgrounded
    // appears because the chain's own change signal invalidates the pager and the reload
    // re-anchors on the cursor. Merging a fresh head page into the held tail — and the
    // misordering rule that needs for a drain working backwards through a camera roll — is
    // `:core-paging`'s problem, not this app's.

    // ⚠⚠ The grid remembers the bytes it has already fetched. `PhotoTile` keys its
    // `produceState` on the path, so without this a tile scrolled away and back re-runs its loader
    // — 17 reads for 10 photographs across one scroll round trip, each a `navigateTo` + `search`
    // over IPC.
    // ⚠ `remember` and not a field: one cache per composition of the app, dropped with it.
    val previewCache = remember { PreviewCache() }
    // ⚠ Keyed on the size as well as the path: the same photograph is a small tile in the year view
    // and a large one in the day view, and a cache keyed on the path alone would keep serving
    // whichever was loaded first.
    val loadPreview: PreviewLoader = { path, requestPx ->
        previewCache.bytes(previewCacheKey(path, requestPx)) { repository.preview(path, requestPx, requestPx) }
    }
    // ⚠ A separate lambda rather than the tile loader: the viewer's page is not a tile — it is not
    // cropped, not cached, and asks for at least the hero rung, at the size it is drawn (§34).
    // ⚠⚠ And a different *read*: `viewerPreview` prefers the original over a rung stretched to the
    // request on a device that holds it, and names `XXLARGE`, neither of which a tile may do (§4.4).
    val loadHero: HeroLoader = { path, requestPx, mayMint ->
        repository.viewerPreview(path, requestPx, mayMint).map { it.bytes }
    }
    val storageState = rememberRealmStorageState()

    // ⚠⚠ **The wall replaces the app, it does not sit on top of it** (ADR 0025 §Decision 1,
    // amended). Without Control there is no realm, so the feed renders "No photos yet. Photos
    // backed up from your devices appear here." — a library that looks deleted, above a banner
    // saying why. One screen, said once.
    //
    // ⚠ `NotInstalled` and `NotSetUp` only: a transport outage is transient and self-recovering,
    // and a wall that slams down on a three-second blip is worse than any banner.
    // ⚠⚠ **Installed, answering, and no realm** (ADR 0025 §Decision 8). Nothing is unreachable —
    // the proxy serves every read, empty — so without this the shell rendered its empty states
    // over a library that does not exist. Permanent until the user acts, so it walls like
    // `NotInstalled`; the offer is to *open* Control, never to get it.
    // ⚠⚠ ADR 0025 §8.6: Control is answering, and whether this device has a
    // realm is not known yet. Non-interactive — no shell, nothing that writes — until the realm
    // read answers (normally ~1 s); blank for 300 ms, a spinner, a retry wall past 20 s.
    if (unreachableReason == UnreachableReason.Checking) {
        AppControlCheckingGate(onRetry = repository::recheckControl)
        return
    }
    if (unreachableReason == UnreachableReason.NotSetUp) {
        val wallAction = rememberControlBannerAction(unreachableReason)
        AppControlUnavailableWall(
            title = stringResource(Res.string.photos_control_wall_title),
            body = stringResource(Res.string.photos_control_not_set_up),
            icon = controlBannerIcon(unreachableReason),
            actionLabel = wallAction?.let { stringResource(Res.string.photos_control_open_action) },
            onAction = wallAction?.onClick,
        )
        return
    }
    if (unreachableReason == UnreachableReason.NotInstalled) {
        val wallRoute = rememberControlInstallRoute()
        val wallAction = rememberControlBannerAction(unreachableReason, wallRoute)
        AppControlUnavailableWall(
            title = stringResource(Res.string.photos_control_wall_title),
            body = when (wallRoute) {
                is ControlInstallRoute.Store ->
                    stringResource(Res.string.photos_control_not_installed)
                ControlInstallRoute.NotPublishedYet ->
                    stringResource(Res.string.photos_control_not_installed_unpublished)
                ControlInstallRoute.SameChannelAsThisApp ->
                    stringResource(Res.string.photos_control_not_installed_other_channel)
            },
            actionLabel = wallAction?.let { stringResource(Res.string.photos_control_get_action) },
            onAction = wallAction?.onClick,
        )
        return
    }


    // ⚠⚠ **What keeps a scrolled feed where the user left it.** `KmpNavHost` keeps a
    // `rememberSaveableStateHolder` AND a `ViewModelStore` per key, so a screen that leaves
    // composition keeps every `rememberSaveable` — including the one inside
    // `rememberLazyGridState` — and a detail screen's view model dies on pop instead of living as
    // long as the shell (`core-paging-scroll-restoration.md` §1).
    AdaptiveNavScaffold(
        navItems = navItems,
        // ⚠ The viewer is full-bleed: nav chrome over a photograph is the one place this app must
        // not put a bar. Everything that is not a tab hides it.
        showNav = current is Destination.TopLevel,
        modifier = modifier,
    ) {
        KmpNavHost(
            backStack = backStack,
            onBack = { pop() },
            entryProvider = kmpEntryProvider {
                entry<Destination.Feed> {
                    val trashSelection = {
                        if (feedSelection.isNotEmpty()) feedViewModel.trashSelected { lastBulk = it }
                    }
                    // ⚠ Around the whole tab, its top bar included: a click on the selection bar's
                    // buttons moves focus there, and shortcuts watching only the grid would go deaf.
                    // ⚠ Delete AND Backspace: the key labelled "delete" on a Mac sends Backspace.
                    // Trash is recoverable, which is why the key needs no confirmation.
                    AppKeyboardShortcutHost(
                        AppShortcuts.dismiss { if (feedSelection.isNotEmpty()) feedViewModel.clearSelection() },
                        AppShortcuts.deleteSelection(trashSelection),
                        KeyboardShortcut(Key.Backspace, onTriggered = trashSelection),
                        AppShortcuts.focusSearch { push(Destination.Search) },
                    ) {
                    TabScaffold(
                        unreachableReason = unreachableReason,
                        storageState = storageState,
                        topBar = {
                            // ⚠⚠ The selection bar REPLACES the feed's header rather than stacking
                            // above it. Two bars push the grid down and, on a phone, the second one
                            // costs a row of photographs — the same reason the storage banner is
                            // suppressed while Control is unreachable.
                            if (feedSelection.isNotEmpty()) {
                                PhotosSelectionTopAppBar(
                                    selectedCount = feedSelection.size,
                                    acting = feedActing,
                                    onClear = feedViewModel::clearSelection,
                                    onAddToAlbum = { pickingAlbumForSelection = true },
                                    onTrash = {
                                        feedViewModel.trashSelected { lastBulk = it }
                                    },
                                )
                            } else
                            TopAppBar(
                                title = { Text("Photos") },
                                actions = {
                                    // ⚠⚠ One button that CYCLES rather than three that pick.
                                    // Three segments cost the width the title needs on a phone,
                                    // and the reference idiom — pinch — is not built: it wants the
                                    // vertical drag the grid owns, so it is a gesture-priority
                                    // decision rather than a `Modifier`, the same reason S3 still
                                    // lists pinch-zoom as unbuilt (§21).
                                    IconButton(
                                        onClick = {
                                            feedViewModel.setDensity(feedDensity.next())
                                        },
                                        modifier = Modifier.testTag(FEED_DENSITY_TAG),
                                    ) {
                                        Icon(
                                            if (feedDensity.features) Icons.Outlined.AutoAwesomeMosaic
                                            else Icons.Outlined.GridView,
                                            // ⚠ Names the CURRENT grouping, not the next one: a
                                            // control that describes what it will become reads as
                                            // a statement about what is on screen.
                                            contentDescription = feedDensity.description,
                                        )
                                    }
                                    IconButton(onClick = { push(Destination.Search) }) {
                                        Icon(Icons.Outlined.Search, contentDescription = "Search")
                                    }
                                },
                            )
                        },
                    ) { padding ->
                        PhotosFeedScreen(
                            items = feedItems,
                            loadPreview = loadPreview,
                            onPhotoClick = { photo ->
                                push(
                                    Destination.Viewer(
                                        collection = PhotoCollection.Feed,
                                        index = feedPhotos.indexOfPath(photo),
                                    ),
                                )
                            },
                            onAddToAlbum = { pendingAlbumFor = it },
                            onRetry = feedItems::refresh,
                            reason = unreachableReason,
                            modifier = Modifier.padding(padding),
                            sectionLabel = PhotoDates::sectionLabel,
                            density = feedDensity,
                            yearMarks = feedYearMarks,
                            selected = feedSelection.keys,
                            onToggleSelection = feedViewModel::toggleSelection,
                            motion = motionTurn,
                            onVisibleVideos = feedViewModel.motion::setVisible,
                            onMotionFinished = feedViewModel.motion::turnFinished,
                        )
                    }
                    }
                }

                entry<Destination.Settings> {
                    val settingsViewModel: SettingsViewModel = koinViewModel()
                    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                    TabScaffold(unreachableReason = unreachableReason, storageState = storageState) { padding ->
                        SettingsScreen(
                            state = settingsState,
                            onToggle = settingsViewModel::setEnabled,
                            onRetry = settingsViewModel::refresh,
                            onOpenTrash = { push(Destination.Trash) },
                            onSkipExistingContent = settingsViewModel::setSkipExistingContent,
                            onBucketSelected = settingsViewModel::setBucketSelected,
                            onPlayVideosInGrid = settingsViewModel::setPlayVideosInGrid,
                            onOpenLicences = { push(Destination.Licences) },
                            modifier = Modifier.padding(padding),
                        )
                    }
                }

                entry<Destination.Collections> {
                    TabScaffold(unreachableReason = unreachableReason, storageState = storageState) { padding ->
                        CollectionsScreen(
                            state = collectionsState,
                            loadPreview = loadPreview,
                            onOpenAlbum = { id, title -> push(Destination.Album(id, title)) },
                            onOpenSmartAlbum = { push(Destination.Smart(it.id)) },
                            onOpenSection = { push(Destination.Section(it)) },
                            onOpenDuplicates = { push(Destination.Duplicates) },
                            onOpenUtility = { utility ->
                                when (utility) {
                                    CollectionsUtility.Kind.TRASH -> push(Destination.Trash)
                                }
                            },
                            onOpenFavourites = {
                                push(Destination.Album(AlbumDetailViewModel.FAVOURITES, "Favourites"))
                            },
                            onCreateAlbum = { creatingAlbum = true },
                            onRetry = collectionsViewModel::retry,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }

                entry<Destination.Section> { key ->
                    CollectionSectionScreen(
                        section = key.section,
                        state = collectionsState,
                        loadPreview = loadPreview,
                        onOpenAlbum = { id, title -> push(Destination.Album(id, title)) },
                        onOpenSmartAlbum = { push(Destination.Smart(it.id)) },
                        onCreateAlbum = { creatingAlbum = true },
                        onBack = { pop() },
                        onRetry = collectionsViewModel::retry,
                    )
                }

                entry<Destination.Search> {
                    val searchViewModel: PhotoSearchViewModel = koinViewModel()
                    val searchState by searchViewModel.state.collectAsStateWithLifecycle()
                    PhotoSearchScreen(
                        state = searchState,
                        loadPreview = loadPreview,
                        onQueryChanged = searchViewModel::onQueryChanged,
                        // ⚠ The hits **in the order the list shows them**, so swiping in the viewer
                        // walks the results the user is looking at rather than the library behind.
                        onPhotoClick = { photo ->
                            val results = searchState.photos
                            viewerPhotos = results
                            push(
                                Destination.Viewer(
                                    collection = PhotoCollection.Fixed,
                                    index = results.indexOfPath(photo),
                                ),
                            )
                        },
                        onBack = { pop() },
                        modifier = Modifier,
                        onRetry = searchViewModel::retry,
                        // ⚠ Back from the viewer: a photograph trashed there must not still be a
                        // result here (`photos-ios.md` §8.19).
                        onReturn = searchViewModel::refresh,
                    )
                }

                entry<Destination.Duplicates> {
                    // ⚠ A keep-only trashes or deletes photographs — Collections re-reads its counts
                    // and years (`photos-ios.md` §8.18; the Trash route's rule, §8.17 note 1).
                    val duplicatesViewModel: DuplicatesViewModel =
                        koinViewModel { parametersOf({ collectionsViewModel.refresh() }) }
                    val duplicatesState by duplicatesViewModel.state.collectAsStateWithLifecycle()
                    // ⚠⚠ No leaving while a write runs: popping this entry clears its ViewModelStore
                    // and cancels the view model's scope — the remaining copies of a keep-only would
                    // silently never be tried, and its report and `onChanged` never arrive. The system
                    // Back is consumed; the screen's own arrow is disabled (`DuplicatesScreen`).
                    BackHandler(enabled = !duplicatesState.canLeave) {}
                    DuplicatesScreen(
                        state = duplicatesState,
                        loadPreview = loadPreview,
                        onKeepOnly = duplicatesViewModel::keepOnly,
                        onSetDeletePermanently = duplicatesViewModel::setDeletePermanently,
                        onRetry = duplicatesViewModel::retry,
                        onDismissReport = duplicatesViewModel::dismissReport,
                        onBack = { if (duplicatesState.canLeave) pop() },
                        modifier = Modifier,
                    )
                }

                // Photos draws no map and shows no third-party data: libraries only.
                entry<Destination.Licences> {
                    OpenSourceLicencesScreen(onBack = { pop() })
                }

                entry<Destination.Trash> {
                    // ⚠ A restore or delete re-reads Collections — whichever route opened the Trash.
                    val trashScreenViewModel: TrashViewModel =
                        koinViewModel { parametersOf({ collectionsViewModel.refresh() }) }
                    val trashState by trashScreenViewModel.state.collectAsStateWithLifecycle()
                    // ⚠⚠ No leaving while a write runs: popping this entry clears its
                    // ViewModelStore and cancels the view model's scope, so a write that then lands
                    // never reaches `onChanged` and Collections stays stale. The system Back is
                    // consumed; the screen's own arrow is disabled (`TrashScreen`). iOS needs neither:
                    // its view model is not cleared with the screen.
                    BackHandler(enabled = trashState.writing) {}
                    TrashScreen(
                        state = trashState,
                        loadPreview = loadPreview,
                        onRestore = trashScreenViewModel::restore,
                        onDeleteForever = trashScreenViewModel::deleteForever,
                        onEmptyTrash = trashScreenViewModel::emptyTrash,
                        onDismissReport = trashScreenViewModel::dismissReport,
                        onBack = { if (!trashState.writing) pop() },
                        modifier = Modifier,
                    )
                }

                entry<Destination.Smart> { key ->
                    // ⚠⚠ Looked up, not carried. The route holds an id; the photographs come from
                    // `CollectionsViewModel`, which computed every derived section from ONE feed read
                    // Nothing is re-queried and nothing walks the realm twice.
                    // ⚠⚠ Aliases too. A person can absorb several clusters, and naming a
                    // second one re-keys their card — so the id this route holds may be an
                    // alias rather than the primary. Matching on `id` alone blanks the screen at
                    // the moment the naming succeeds.
                    // ⚠⚠ The last resolution is kept for THIS route, because a person's
                    // cluster aliases can vanish under an open screen: withdraw consent after a
                    // naming chunk has saved and `faceGroups` returns nothing, so the named entry
                    // keeps its name and loses every `person:<groupId>` alias — including the one
                    // the user is standing on. The last good answer shows their photographs rather
                    // than a blank grid.
                    var lastResolved by remember(key.smartId) { mutableStateOf<SmartAlbum?>(null) }
                    // ⚠⚠ §36 step 2: a card drawn from the on-device snapshot can be tapped before
                    // the live refresh lands, and it has no photographs yet. `smartPage` says
                    // loading (or failed) for it — never an empty grid that reads as "no photos" —
                    // and this page fills in when the live state arrives, since the route is an id.
                    val page = collectionsState.smartPage(key.smartId, lastResolved)
                    val album = page.album
                    // ⚠ Only a LIVE resolution is kept: a snapshot card holds no photographs.
                    LaunchedEffect(album) { if (album != null && album.snapshotCard == null) lastResolved = album }
                    val photosKnown = !page.loading && !page.failed
                    // ⚠ Every id this person answers to. Built once so the three consumers below
                    // cannot drift apart into comparing a primary id against a list of aliases.
                    val personAddresses = remember(key.smartId, album) {
                        setOfNotNull(key.smartId, album?.id) + album?.alternateIds.orEmpty()
                    }
                    val photos = album?.photos.orEmpty()
                    // ⚠ Asked of the `people` list, for `onName`'s reason below: the
                    // state knows which sections are people; an id's spelling does not.
                    val isPerson = album != null && collectionsState.people.any { it.id == album.id }
                    AlbumDetailScreen(
                        state = AlbumDetailUiState(
                            title = album?.title.orEmpty(),
                            photos = photos,
                            // §36 step 2: a snapshot card before its live read, or a route nothing
                            // answers to while reads are in flight — loading, never "no photos".
                            loading = page.loading,
                            error = if (page.failed) FeedError.Failed("section read failed") else null,
                            // ⚠ Not editable: a place or a year has no membership to edit.
                            // Removing a photo from "2026" is not a thing a user can mean.
                            editable = false,
                            person = if (isPerson && album != null) {
                                PersonHeader(
                                    cover = album.coverFace,
                                    // Who the "With…" chip can add. ⚠ Keyed on the
                                    // ROUTE, so the selection survives a naming that re-keys
                                    // this person and never carries over to another one.
                                    companions = companionsFor(album, collectionsState.people),
                                    key = key.smartId,
                                )
                            } else {
                                null
                            },
                        ),
                        // ⚠ The viewer pages through the list the user is LOOKING at — the
                        // "With…" intersection when one is chosen.
                        onPhotoInListClick = { photo, visible ->
                            viewerPhotos = visible
                            push(
                                Destination.Viewer(
                                    collection = PhotoCollection.Fixed,
                                    index = visible.indexOfPath(photo),
                                ),
                            )
                        },
                        loadPreview = loadPreview,
                        onPhotoClick = { photo ->
                            viewerPhotos = photos
                            push(
                                Destination.Viewer(
                                    collection = PhotoCollection.Fixed,
                                    index = photos.indexOfPath(photo),
                                ),
                            )
                        },
                        onRemove = {},
                        onBack = { pop() },
                        modifier = Modifier,
                        // ⚠⚠ Asked of the STATE, never of the id's spelling: `people` is
                        // the list that knows, and matching on a `"person:"` prefix would make a
                        // naming button appear on any future section that happened to be spelled
                        // that way. ⚠ `null` for a place, a year or a Thing, so the action is
                        // absent rather than present and inert.
                        // ⚠⚠ Asked of the RESOLVED album, not of the raw route id. After a
                        // first naming the route still holds `person:<groupId>` while the person's
                        // primary id is `person:name:<name>` — the lookup above resolves that via
                        // the aliases, but an exact test here answers "not a person" and the
                        // "Add another person" action vanishes the moment it is promised.
                        // ⚠ Not on a snapshot card: naming reads the photographs, which it has none of yet.
                        onName = album
                            ?.takeIf { photosKnown }
                            ?.takeIf { resolved -> collectionsState.people.any { it.id == resolved.id } }
                            ?.let { resolved ->
                                { typed: String -> collectionsViewModel.nameGroup(resolved.id, typed) }
                            },
                        // ⚠⚠ Only for the person it belongs to — a multi-chunk naming outlives
                        // the screen that started it, so a global verdict would surface its dialog
                        // on whatever is open by then. ⚠⚠ Matched against EVERY address this
                        // person answers to: `nameGroup` is called with the resolved primary
                        // id, so a refusal records THAT, while the route holds an alias and
                        // `alternateIds` holds the aliases — comparing one to the other matches
                        // nothing, and the dialog would never appear on an alias route.
                        nameFailure = collectionsState.nameFailure
                            ?.takeIf { _ ->
                                collectionsState.nameFailureFor in personAddresses
                            },
                        // ⚠⚠ Compared to THIS route's id. A global flag would offer
                        // "Finish naming" on whichever person happened to be open.
                        // ⚠ The interrupted NAME, so "Finish naming" retries the right person
                        // rather than the one the card happens to be titled after.
                        unfinishedName = collectionsState.namingIncompleteFor
                            .firstOrNull { it.smartId in personAddresses }
                            ?.name,
                        onDismissNameRefusal = collectionsViewModel::dismissNameRefusal,
                    )
                }

                entry<Destination.Album> { key ->
                    val detailViewModel: AlbumDetailViewModel =
                        koinViewModel(key = key.albumId) { parametersOf(key.albumId, key.title) }
                    val detailState by detailViewModel.state.collectAsStateWithLifecycle()
                    // ⚠⚠ No leaving while an edit runs: a reopened album is a NEW view
                    // model whose own lock is free, and a delete there could run while this edit's
                    // rename — an upsert — can still land and re-create the album. The Trash's rule.
                    BackHandler(enabled = detailState.writing) {}
                    AlbumDetailScreen(
                        state = detailState,
                        loadPreview = loadPreview,
                        onPhotoClick = { photo ->
                            viewerPhotos = detailState.photos
                            push(
                                Destination.Viewer(
                                    collection = PhotoCollection.Fixed,
                                    index = detailState.photos.indexOfPath(photo),
                                ),
                            )
                        },
                        onRemove = detailViewModel::remove,
                        onBack = { pop() },
                        modifier = Modifier,
                    )
                }

                entry<Destination.Viewer> { key ->
                    // ⚠⚠ The **live** feed list for `Feed`, not a copy taken when the photo was
                    // tapped. That is what makes the swipe endless: the shared presenter loads
                    // another page, this list grows with it, and the next photograph is simply
                    // there. `Fixed` is a snapshot precisely because those lists are whole already.
                    val photos = when (key.collection) {
                        PhotoCollection.Feed -> feedPhotos
                        PhotoCollection.Fixed -> viewerPhotos
                    }
                    // ⚠ A list can empty underneath the viewer — the last photo in an album
                    // trashed, or a refresh that found nothing. Pop rather than render a pager
                    // with no pages.
                    LaunchedEffect(photos.isEmpty()) {
                        if (photos.isEmpty()) pop()
                    }

                    if (photos.isNotEmpty()) {
                        val index = key.index.coerceIn(0, photos.lastIndex)
                        val viewerViewModel: PhotoViewerViewModel =
                            koinViewModel(key = photos[index].path) { parametersOf(photos[index]) }
                        val viewerState by viewerViewModel.state.collectAsStateWithLifecycle()

                        // ⚠⚠ The About sheet consumes Back before the nav host does — a nested
                        // `BackHandler`, so the innermost enabled one wins. Without it, Back on an
                        // open sheet leaves the viewer outright: the sheet closes, the photograph
                        // closes, and the user is back on the grid in one press, having asked to
                        // dismiss a panel.
                        BackHandler(enabled = viewerState.infoOpen) { viewerViewModel.toggleInfo() }
                        PhotoViewerScreen(
                            photos = photos,
                            initialIndex = index,
                            state = viewerState,
                            loadHero = loadHero,
                            onPhotoShown = viewerViewModel::onPhotoShown,
                            onToggleChrome = viewerViewModel::toggleChrome,
                            onToggleFavourite = viewerViewModel::toggleFavourite,
                            // ⚠ The same dialog the feed's long-press opens — one route to filing a
                            // photo, not a second one that could disagree with it.
                            onAddToAlbum = { pendingAlbumFor = it },
                            formatDate = PhotoDates::shortDate,
                            formatTime = PhotoDates::timeOfDay,
                            formatCapturedAt = PhotoDates::capturedAt,
                            // ⚠ Only the feed can serve another page; an album and a search result
                            // are already whole, and offering them a callback would be a promise
                            // nothing keeps.
                            // ⚠⚠ Asking for the last loaded row IS the request for another
                            // page: `LazyPagingItems.get` sends Paging an access hint, and a hint
                            // at the end of the loaded window is what triggers the append. There is
                            // no `loadMore` to call, and the feed screen has no "near the end"
                            // listener for the same reason.
                            onLoadMore = {
                                if (feedItems.itemCount > 0) feedItems[feedItems.itemCount - 1]
                            }.takeIf { key.collection == PhotoCollection.Feed },
                            onToggleInfo = viewerViewModel::toggleInfo,
                            // ⚠ The setter, not the toggle: this reports what the scroll DID, and a
                            // toggle here would close a panel the user had just dragged open (§11).
                            onInfoOpenChanged = viewerViewModel::setInfoOpen,
                            onBack = { pop() },
                            // ⚠ Trash, not delete: the photo moves to `/Trash` and can be restored.
                            // The irreversible one lives behind a confirmation on the Trash screen.
                            // ⚠⚠ The pop waits for the answer. Popping at once would close the
                            // viewer on a trash that failed and leave the photograph in the grid,
                            // which hides a broken trash entirely. Staying put says "that did not
                            // happen" without claiming to know why.
                            onTrash = { photo ->
                                trashViewModel.trash(photo) { trashed ->
                                    if (trashed) {
                                        pop()
                                        // ⚠ The explicit reload stays. Trashing MOVES the file to
                                        // `/Trash`, which the feed's window excludes — so the row
                                        // must go, and coming back to a grid that still shows it is
                                        // the one thing worse than a flicker. The chain's own change
                                        // signal would do this too, after its debounce; this is the
                                        // case where the user is watching.
                                        feedItems.refresh()
                                    }
                                }
                            },
                            modifier = Modifier,
                        )
                    }
                }
            },
        )
    }

    if (creatingAlbum) {
        NameAlbumDialog(
            onDismiss = { creatingAlbum = false },
            onConfirm = { name ->
                collectionsViewModel.createAlbum(name)
                creatingAlbum = false
            },
        )
    }

    if (pickingAlbumForSelection) {
        AddToAlbumDialog(
            albums = collectionsState.albums,
            onDismiss = { pickingAlbumForSelection = false },
            onPick = { albumId ->
                feedViewModel.addSelectedToAlbum(albumId) { lastBulk = it }
                pickingAlbumForSelection = false
            },
        )
    }

    // ⚠⚠ Reported, always — including the all-succeeded case, because a bulk action with no
    // feedback at all leaves the user counting tiles to find out whether it worked.
    lastBulk?.let { outcome ->
        BulkOutcomeDialog(outcome = outcome, onDismiss = { lastBulk = null })
    }

    pendingAlbumFor?.let { photo ->
        AddToAlbumDialog(
            albums = collectionsState.albums,
            onDismiss = { pendingAlbumFor = null },
            onPick = { albumId ->
                collectionsViewModel.addPhoto(albumId, photo)
                pendingAlbumFor = null
            },
        )
    }
}

/**
 * The feed's selection bar (S1).
 *
 * ⚠ Patterned on Drive's `SelectionTopAppBar` rather than importing it: that one lives in
 * `drive-shared-ui`, which Photos does not depend on, and lifting it to `:ui-core` would be a
 * refactor of Drive's chrome dressed as a Photos change.
 *
 * ⚠⚠ **No share action, and its absence is deliberate.** S1 asks for "share / delete /
 * add-to-album"; sharing a photograph out is `photos-app.md` PH-G11, an open gap with no
 * implementation behind it. A button that cannot work is worse than one that is not there — the
 * lesson ADR 0025 records one layer down, where a *Try again* sat under a banner saying Control was
 * not installed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotosSelectionTopAppBar(
    selectedCount: Int,
    /** A bulk action is running — its buttons wait rather than offer one the view model would drop. */
    acting: Boolean,
    onClear: () -> Unit,
    onAddToAlbum: () -> Unit,
    onTrash: () -> Unit,
) {
    TopAppBar(
        title = { Text("$selectedCount selected") },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "Clear selection")
            }
        },
        actions = {
            IconButton(onClick = onAddToAlbum, enabled = !acting, modifier = Modifier.testTag(SELECTION_ADD_TAG)) {
                Icon(Icons.Default.Add, contentDescription = "Add to album")
            }
            IconButton(onClick = onTrash, enabled = !acting, modifier = Modifier.testTag(SELECTION_TRASH_TAG)) {
                Icon(Icons.Default.Delete, contentDescription = "Move to trash")
            }
        },
        modifier = Modifier.testTag(SELECTION_BAR_TAG),
    )
}

/**
 * What a bulk action did, said out loud.
 *
 * ⚠⚠ The partial case is the one this exists for. "7 moved, 3 could not" is a sentence a single
 * boolean cannot carry, and `TrashViewModel` records what happens when the app pretends otherwise:
 * a trash that fails everywhere while the viewer closes as though it had worked.
 */
@Composable
private fun BulkOutcomeDialog(outcome: BulkOutcome, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // ⚠ The copy is `BulkOutcome`'s, shared with the iOS shell.
        title = { Text(outcome.title) },
        text = { Text(outcome.message, modifier = Modifier.testTag(BULK_OUTCOME_TEXT_TAG)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        modifier = Modifier.testTag(BULK_OUTCOME_TAG),
    )
}

/** Test handle for the feed's density control (S1). */
const val FEED_DENSITY_TAG = "feedDensity"

/** Test handles for the feed's multi-select chrome (S1). */
const val SELECTION_BAR_TAG = "photosSelectionBar"
const val SELECTION_ADD_TAG = "photosSelectionAdd"
const val SELECTION_TRASH_TAG = "photosSelectionTrash"
const val BULK_OUTCOME_TAG = "photosBulkOutcome"
const val BULK_OUTCOME_TEXT_TAG = "photosBulkOutcomeText"

@Composable
private fun NameAlbumDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New album") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * ⚠ Lists the albums that exist and nothing else. Creating one from here would need this dialog to
 * own a name field *and* a list, and the two gestures belong on the Collections tab where the user can
 * see what they already have.
 */
@Composable
private fun AddToAlbumDialog(
    albums: List<app.tenshu.photos.data.AlbumSummary>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to album") },
        text = {
            Column {
                if (albums.isEmpty()) {
                    // ⚠ "Collections", not "Albums": the sentence must name the tab as the nav
                    // labels it, and no test asserts app copy against the nav's own labels.
                    Text("You haven't made an album yet. Make one on the Collections tab.")
                } else {
                    albums.forEach { album ->
                        TextButton(onClick = { onPick(album.albumId) }) { Text(album.name) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One entry per photograph *per request size* — see the loader. */
internal fun previewCacheKey(path: String, requestPx: Int): String = "$requestPx:$path"

/**
 * Where [photo] sits in this list, for the viewer's opening page.
 *
 * ⚠ By **path**, not by `indexOf`: a `Photo` is a value, and two reads of the same file can differ
 * in a field the grid never showed (`contentHash` is projected by one screen and not another), so
 * structural equality would silently answer -1 and open the viewer on the wrong photograph.
 * ⚠ A miss coerces to the first row rather than failing — the pager clamps it either way.
 */
private fun List<Photo>.indexOfPath(photo: Photo): Int =
    indexOfFirst { it.path == photo.path }.coerceAtLeast(0)

/**
 * The chrome every top-level tab shares: the banners, and an optional app bar above them.
 *
 * ⚠⚠ **The banners ride the `topBar` slot rather than wrapping the content**, so `Scaffold` owns the
 * status-bar inset AND grows the content padding by the banner's height on its own. Hand-rolling
 * that puts the banner under the clock. They sit BELOW the app bar: the bar is
 * where you are, the banner is what is wrong.
 *
 * ⚠ Each tab is its own nav entry, so the chrome they share has to be a composable they each call
 * rather than a branch they sit inside.
 * ⚠⚠ The nav bar is NOT here — `AdaptiveNavScaffold` owns it, and it is a rail rather than a bar
 * on anything wider than compact.
 */
@Composable
private fun TabScaffold(
    unreachableReason: UnreachableReason?,
    storageState: RealmStorageState,
    modifier: Modifier = Modifier,
    topBar: @Composable (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                topBar?.invoke()
                // ⚠ Above the storage banner, not instead of it: "Control is gone" and "the realm
                // cannot sync" are different facts and a user can be owed both.
                ControlBanner(unreachableReason)
                // ⚠⚠ Suppressed while Control is unreachable: both facts are real, but the storage
                // one is not actionable while the first is true — its *Manage storage* button opens
                // a Control that is not answering — and two stacked bars push the app's own
                // header off the top of the screen.
                if (unreachableReason == null) RealmStorageBannerHost(storageState)
            }
        },
        content = content,
    )
}
