package app.tenshu.photos.ui

import app.tenshu.photos.data.controlCatchingUp
import app.tenshu.ui.feedback.SyncActivityCapsule
import app.tenshu.ui.feedback.rememberSyncActivity
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import app.tenshu.photos.data.controlPreviewWakes
import app.tenshu.photos.ui.components.LocalPreviewWakes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.SharingStarted
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.layout.CompactNavStyle
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.tenshu.ui.layout.LocalWindowWidthSizeClass
import app.tenshu.ui.layout.WindowWidthSizeClass
import app.tenshu.ui.layout.LocalNavBarClearance
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ScaffoldDefaults
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.data.syncConnectivity
import app.tenshu.reachability.SuiteSyncProblem
import app.tenshu.ui.feedback.SuiteSyncBanner
import app.tenshu.ui.feedback.rememberSuiteSyncProblem
import org.tenshu.core.common.TenshuClient
import app.tenshu.photos.model.Photo
import app.tenshu.photos.settings.GridMotionSettings
import app.tenshu.photos.settings.PhotosPreferences
import app.tenshu.photos.ui.components.isMotionPlaybackSupported
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailScreen
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailUiState
import app.tenshu.photos.ui.screens.collections.personPage
import app.tenshu.photos.ui.screens.albumdetail.NameSuggestionPrompt
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
import app.tenshu.photos.ui.navigation.photosNavItems
import app.tenshu.photos.ui.navigation.selectTop
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
 * ⚠ Two tabs — Photos · Collections (CR-D8, `photos-creation-features.md` §8; the Create tab is
 * deferred). Settings is the ⚙ on the feed's top bar; it manages what this device backs up. It **manages** rather than
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
    fun selectTop(destination: Destination.TopLevel) = backStack.selectTop(destination)
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
    /** The photograph the editor is open on, and the copy it saved (ED-1) — see `Destination.Editor`. */
    var editingPhoto: Photo? by remember { mutableStateOf(null) }
    // ED-3: photos whose edit was just saved or reverted, by path — laid over every list the viewer
    // pages until that list carries the same row. ⚠⚠ The live feed catches up only on its reload, and
    // the pager re-reports its list's row on every change, so without this a viewer over the feed
    // went on drawing the photo as it was before the save — for a redaction, the original.
    var editOverlay by remember { mutableStateOf<Map<String, Photo>>(emptyMap()) }
    // ED-3: the photo whose edit the user asked to revert, and the viewer that asked.
    var revertingPhoto by remember { mutableStateOf<Pair<Photo, PhotoViewerViewModel>?>(null) }
    var editedPhotos: List<Photo> by remember { mutableStateOf(emptyList()) }
    var pendingAlbumFor: Photo? by remember { mutableStateOf(null) }
    var creatingAlbum by remember { mutableStateOf(false) }
    /** The album write was refused — said, never swallowed (iOS's *Couldn't Create the Album*). */
    var createAlbumRefused by remember { mutableStateOf(false) }

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

    val navItems = photosNavItems(current) { destination ->
        selectTop(destination)
        if (destination == Destination.Collections) collectionsViewModel.refresh()
    }

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
    // ⚠ One strip for the whole app (`suite-sync-status.md` SS-1), worked out here once so the
    // offline delay does not restart on every tab change.
    val tenshuClient: TenshuClient = koinInject()
    val connectivity = remember(tenshuClient) { syncConnectivity(tenshuClient) }
    // ⚠⚠ A `State`, never the bare value: the tabs read it inside their nav entries, and Android's
    // `NavDisplay` keeps an entry's content lambda from the composition that created it
    // (`KmpNavHost.kt`). Captured by value, a feed first composed in the ~0.3 s before the Binder
    // connects kept "Can't reach Tenshu Control" for good, over photos that were loading fine —
    // and a feed composed while healthy never showed a later outage. `TabStripFollowsTheVerdictTest`.
    val syncProblemState: State<SuiteSyncProblem> =
        rememberUpdatedState(rememberSuiteSyncProblem(unreachableReason, storageState, connectivity))
    val syncProblem by syncProblemState

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
    // ⚠ SS-3 (`suite-sync-status.md`): sync coming back, or a catch-up ending, wakes every grey
    // tile at once instead of at its backoff's next step (up to a minute). Shared once here so
    // a screen of tiles is one subscription to Control, not one each.
    val wakeScope = rememberCoroutineScope()
    val previewWakes = remember(tenshuClient) {
        controlPreviewWakes(tenshuClient)
            // Defensive: these reads turn failures into values today, and a wake is never worth a crash.
            .catch { }
            .runningFold(0) { count, _ -> count + 1 }
            // Eagerly: a wake while no tile happens to be waiting (all mid-ask) must still count.
            .stateIn(wakeScope, SharingStarted.Eagerly, 0)
    }
    // SS-2: "Syncing…" while Control pulls, read through its heartbeat.
    val catchingUp = remember(tenshuClient) { controlCatchingUp(tenshuClient) }
    val syncActivity = rememberSyncActivity(catchingUp)
    CompositionLocalProvider(LocalPreviewWakes provides previewWakes) {
        AdaptiveNavScaffold(
            navItems = navItems,
            // ⚠ The viewer is full-bleed: nav chrome over a photograph is the one place this app must
            // not put a bar. Everything that is not a tab hides it.
            showNav = current is Destination.TopLevel,
            // Two tabs: a docked 80dp bar spends a strip of photographs on two buttons.
            compactNavStyle = CompactNavStyle.Floating,
            modifier = modifier,
            // Over the tabs only: a problem strip, or the full-bleed viewer, hides it.
            overlay = {
                SyncActivityCapsule(
                    activity = syncActivity,
                    hidden = syncProblem != SuiteSyncProblem.None || current !is Destination.TopLevel,
                )
            },
        ) {
            KmpNavHost(
                backStack = backStack,
                onBack = { pop() },
                entryProvider = kmpEntryProvider {
                    entry<Destination.Feed> {
                        // ⚠ Enter-always on a phone: the header slides away as the feed scrolls down
                        // and returns on the first scroll up. Wider windows keep it pinned — the room
                        // is not short there, and every wheel tick would move it.
                        // ⚠⚠ Never while selecting: the selection bar holds Add-to-album and Trash,
                        // so it is pinned, and the header is put back as selection starts.
                        val compact = LocalWindowWidthSizeClass.current == WindowWidthSizeClass.Compact
                        val selecting = feedSelection.isNotEmpty()
                        val headerMayHide by rememberUpdatedState(compact && !selecting)
                        // ⚠ One lambda for the entry's life: the behaviour is remembered against it,
                        // so a fresh lambda per recomposition would rebuild the scroll connection.
                        val headerCanScroll = remember { { headerMayHide } }
                        val headerScroll = TopAppBarDefaults.enterAlwaysScrollBehavior(canScroll = headerCanScroll)
                        val feedGridState = rememberLazyGridState()
                        // Back while selecting clears the selection, as Esc does on desktop. Without
                        // it the feed — a root tab — has no handler and Back leaves the app.
                        BackHandler(enabled = selecting) { feedViewModel.clearSelection() }
                        // Also a window widened past compact while the header was away.
                        LaunchedEffect(headerMayHide) { if (!headerMayHide) headerScroll.state.heightOffset = 0f }
                        // ⚠ A jump to the top that no finger made — the scrubber, or new photographs
                        // landing above a feed at rest — must not leave the top of the feed headerless.
                        LaunchedEffect(feedGridState) {
                            snapshotFlow {
                                feedGridState.firstVisibleItemIndex == 0 &&
                                    feedGridState.firstVisibleItemScrollOffset == 0
                            }.collect { atTop -> if (atTop) headerScroll.state.heightOffset = 0f }
                        }
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
                            syncProblem = syncProblemState,
                            // The sync banners ride below the header and stay: only the header hides.
                            modifier = Modifier.nestedScroll(headerScroll.nestedScrollConnection),
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
                                PhotosFeedTopAppBar(
                                    density = feedDensity,
                                    onCycleDensity = { feedViewModel.setDensity(feedDensity.next()) },
                                    onSearch = { push(Destination.Search) },
                                    onSettings = { push(Destination.Settings) },
                                    scrollBehavior = headerScroll,
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
                                gridState = feedGridState,
                            )
                        }
                        }
                    }

                    entry<Destination.Settings> {
                        val settingsViewModel: SettingsViewModel = koinViewModel()
                        val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                        // ⚠ Pushed, not a tab (CR-D8): it carries its own Back, which the bar used to make
                        // unnecessary — on desktop there is no system Back to fall back on.
                        TabScaffold(
                            syncProblem = syncProblemState,
                            topBar = { PushedTopAppBar(title = "Settings", onBack = { pop() }) },
                        ) { padding ->
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
                        TabScaffold(syncProblem = syncProblemState) { padding ->
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
                        // ⚠⚠ Who is a person, whether naming is offered, and whose verdict a dialog is:
                        // the shared `personPage` (the SwiftUI person page reads the same answer). It
                        // asks the STATE, never the id's spelling, matches the RESOLVED album through its
                        // aliases, and is absent on a snapshot card, whose photographs are not known yet.
                        val personPage = collectionsState.personPage(key.smartId, page)
                        val photos = album?.photos.orEmpty()
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
                                // Who the "With…" chip can add — keyed on the ROUTE (`personPage`).
                                person = personPage.header,
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
                            // ⚠ `null` for a place, a year or a Thing, so the action is absent rather
                            // than present and inert (`PersonPage.nameId`).
                            onName = personPage.nameId?.let { id ->
                                { typed: String -> collectionsViewModel.nameGroup(id, typed) }
                            },
                            // ⚠⚠ Only for the person it belongs to, matched through every address
                            // (`PersonPage.nameFailure`); a multi-chunk naming outlives its screen.
                            nameFailure = personPage.nameFailure,
                            // ⚠ The interrupted NAME, so "Finish naming" retries the right person.
                            unfinishedName = personPage.unfinishedName,
                            onDismissNameRefusal = collectionsViewModel::dismissNameRefusal,
                            // IN-6 F6c: answered on the page's resolved id (`PersonPage.nameId`).
                            suggestion = personPage.nameId?.let { id ->
                                NameSuggestionPrompt.of(
                                    suggestedName = personPage.suggestedName,
                                    unconfirmedCount = personPage.unconfirmedCount,
                                    name = album?.title.orEmpty(),
                                    failed = personPage.suggestionRejectFailed,
                                    onYes = { collectionsViewModel.acceptSuggestion(id) },
                                    onNo = { collectionsViewModel.rejectSuggestion(id) },
                                )
                            },
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

                    entry<Destination.Editor> { key ->
                        val photo = editingPhoto?.takeIf { it.path == key.path }
                        LaunchedEffect(photo) { if (photo == null) pop() }
                        if (photo != null) {
                            val editorViewModel: app.tenshu.photos.edit.PhotoEditorViewModel =
                                koinViewModel(key = "editor:" + photo.path) { parametersOf(photo) }
                            app.tenshu.photos.ui.screens.editor.PhotoEditorRoute(
                                viewModel = editorViewModel,
                                onClose = { pop() },
                                onSaved = { saved, savedAs ->
                                    pop()
                                    when (savedAs) {
                                        // ED-3: the same photograph, now drawn by its edit — back to its
                                        // viewer, with every list that holds it patched so the page
                                        // redraws from the rendition at once.
                                        app.tenshu.photos.edit.SavedAs.EDIT -> {
                                            viewerPhotos = viewerPhotos.withPhoto(saved)
                                            editedPhotos = editedPhotos.withPhoto(saved)
                                            editOverlay = editOverlay + (saved.path to saved)
                                        }
                                        // ⚠ Close the editor and open the copy — the original's viewer
                                        // stays underneath, so Back returns to the photograph edited.
                                        app.tenshu.photos.edit.SavedAs.COPY -> {
                                            editedPhotos = listOf(saved)
                                            push(Destination.Viewer(PhotoCollection.Edited, 0))
                                        }
                                    }
                                    // A copy is a new row; an edit re-maps the photo's own.
                                    feedItems.refresh()
                                },
                            )
                        }
                    }

                    entry<Destination.Viewer> { key ->
                        // ⚠⚠ The **live** feed list for `Feed`, not a copy taken when the photo was
                        // tapped. That is what makes the swipe endless: the shared presenter loads
                        // another page, this list grows with it, and the next photograph is simply
                        // there. `Fixed` is a snapshot precisely because those lists are whole already.
                        val listed = when (key.collection) {
                            PhotoCollection.Feed -> feedPhotos
                            PhotoCollection.Fixed -> viewerPhotos
                            PhotoCollection.Edited -> editedPhotos
                        }
                        // ED-3: a just-saved or just-reverted photo wins until the list carries it too;
                        // an entry the list has caught up with is dropped, so a later change (a peer's
                        // edit) is the list's to show.
                        val photos = if (editOverlay.isEmpty()) listed else listed.map { editOverlay[it.path] ?: it }
                        LaunchedEffect(listed, editOverlay) {
                            // ⚠ By the rendition alone: the list's row may differ in fields the save never read.
                            val caughtUp = listed
                                .filter { row -> editOverlay[row.path]?.let { it.renditionPath == row.renditionPath } == true }
                                .map { it.path }
                            if (caughtUp.isNotEmpty()) editOverlay = editOverlay - caughtUp.toSet()
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
                                // ED-1: the editor, from the bottom bar (ED-D7).
                                onEdit = { photo ->
                                    editingPhoto = photo
                                    push(Destination.Editor(photo.path))
                                },
                                // ED-3: asks first — the edit's recipe goes with it.
                                onRevertEdit = { photo -> revertingPhoto = photo to viewerViewModel },
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
    }

    if (creatingAlbum) {
        NameAlbumDialog(
            onDismiss = { creatingAlbum = false },
            onConfirm = { name ->
                collectionsViewModel.createAlbum(name) { made -> if (!made) createAlbumRefused = true }
                creatingAlbum = false
            },
        )
    }

    revertingPhoto?.let { (photo, viewer) ->
        AlertDialog(
            onDismissRequest = { revertingPhoto = null },
            title = { Text("Revert to original?") },
            text = { Text("Your edits to this photo will be removed. This can’t be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        revertingPhoto = null
                        viewer.revertEdit { reverted ->
                            if (reverted != null) {
                                viewerPhotos = viewerPhotos.withPhoto(reverted)
                                editedPhotos = editedPhotos.withPhoto(reverted)
                                editOverlay = editOverlay + (reverted.path to reverted)
                                feedItems.refresh()
                            }
                        }
                    },
                    modifier = Modifier.testTag(REVERT_CONFIRM_TAG),
                ) { Text("Revert") }
            },
            dismissButton = { TextButton(onClick = { revertingPhoto = null }) { Text("Cancel") } },
        )
    }

    if (createAlbumRefused) {
        AlertDialog(
            onDismissRequest = { createAlbumRefused = false },
            title = { Text("Couldn’t create the album") },
            text = { Text("Tenshu couldn’t save the album right now. Try again in a moment.") },
            confirmButton = { TextButton(onClick = { createAlbumRefused = false }) { Text("OK") } },
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

/**
 * The feed's own header: the density control, Search, and — since CR-D8 — the ⚙ that opens Settings,
 * where iOS has always had it (`photos-ios/Sources/FeedSelection.swift`, `library-settings`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotosFeedTopAppBar(
    density: FeedDensity,
    onCycleDensity: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = { Text("Photos") },
        scrollBehavior = scrollBehavior,
        actions = {
            // ⚠⚠ One button that CYCLES rather than three that pick. Three segments cost the width
            // the title needs on a phone, and the reference idiom — pinch — is not built: it wants
            // the vertical drag the grid owns, so it is a gesture-priority decision rather than a
            // `Modifier`, the same reason S3 still lists pinch-zoom as unbuilt (§21).
            IconButton(onClick = onCycleDensity, modifier = Modifier.testTag(FEED_DENSITY_TAG)) {
                Icon(
                    if (density.features) Icons.Outlined.AutoAwesomeMosaic else Icons.Outlined.GridView,
                    // ⚠ Names the CURRENT grouping, not the next one: a control that describes what
                    // it will become reads as a statement about what is on screen.
                    contentDescription = density.description,
                )
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, contentDescription = "Search")
            }
            IconButton(onClick = onSettings, modifier = Modifier.testTag(FEED_SETTINGS_TAG)) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
            }
        },
    )
}

/** The app bar of a pushed screen that has no bar of its own: a title and Back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushedTopAppBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag(PUSHED_BACK_TAG)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

/** Test handles for the feed's ⚙ and a pushed screen's Back (CR-D8). */
const val FEED_SETTINGS_TAG = "photosFeedSettings"
const val PUSHED_BACK_TAG = "photosPushedBack"

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
internal fun TabScaffold(
    /** ⚠⚠ A `State`, read here inside the entry — see `syncProblemState` in [App] for why. */
    syncProblem: State<SuiteSyncProblem>,
    modifier: Modifier = Modifier,
    topBar: @Composable (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    // ⚠ Under the floating nav pill the tab runs to the bottom edge and its list pads by
    // `LocalNavBarClearance` instead, which already counts the navigation-bar inset.
    val floatingNav = LocalNavBarClearance.current > 0.dp
    Scaffold(
        modifier = modifier,
        contentWindowInsets = if (floatingNav) {
            ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        topBar = {
            Column {
                topBar?.invoke()
                // ⚠⚠ One strip, the most important (`suite-sync-status.md` SS-1): Control's state
                // first, then this device's sync health, then storage. Two stacked bars push the
                // app's own header off the top of the screen, and the lower fact is rarely
                // actionable while the upper one is true.
                SuiteSyncBanner(
                    problem = syncProblem.value,
                    controlBanner = { ControlBanner(it) },
                    storageBanner = { RealmStorageBannerHost(it) },
                )
            }
        },
        content = content,
    )
}

/** [this] with the row for [photo]'s path replaced by [photo] — an edit saved or reverted (ED-3). */
private fun List<Photo>.withPhoto(photo: Photo): List<Photo> =
    map { if (it.path == photo.path) photo else it }

/** The confirm button of *Revert to original* (ED-3). */
const val REVERT_CONFIRM_TAG = "viewer-revert-confirm"
