@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.tenshu.sdk.ios

import app.tenshu.control.ui.screens.models.ModelsRepository
import app.tenshu.control.ui.screens.models.ModelsViewModel
import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.NotConnectedPhotosRepository
import app.tenshu.photos.data.OffCallerPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.data.heroPreviewRequestPx
import app.tenshu.photos.data.tilePreviewRequestPx
import app.tenshu.photos.model.MotionTurn
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.SemanticSearchStatus
import app.tenshu.photos.time.PhotoDates
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailViewModel
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.collections.CurrentRealm
import app.tenshu.photos.ui.screens.collections.iosCollectionsSnapshotStore
import app.tenshu.photos.ui.screens.collections.purge
import kotlinx.coroutines.launch
import org.tenshu.core.common.epc.RealmKeyMaterialBridge
import app.tenshu.photos.ui.screens.duplicates.DuplicatesViewModel
import app.tenshu.photos.ui.screens.search.DescriptionSearch
import app.tenshu.photos.ui.screens.search.ModelsSwitchHome
import app.tenshu.photos.ui.screens.search.PhotoSearchViewModel
import app.tenshu.photos.ui.screens.trash.TrashViewModel
import app.tenshu.photos.ui.screens.feed.PhotosFeedBinding
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import app.tenshu.photos.ui.screens.viewer.AboutSheetContent
import app.tenshu.photos.ui.screens.viewer.PhotoViewerUiState
import app.tenshu.photos.ui.screens.viewer.PhotoViewerViewModel
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad

/**
 * Wires the **real** Photos view-model layer (`:photos-shared-logic`) to the SwiftUI `photos-ios`
 * app (`photos-ios.md` PIO-1). The Photos twin of [CalendarSurface] / [ContactsSurface].
 *
 * Photos' Koin DI lives in `:photos-shared-ui` (Compose, not on iOS), so this object is the minimal
 * hand-wiring the SwiftUI client needs instead of `photosModules()`.
 *
 * The runtime default is the honest [NotConnectedPhotosRepository] (commonMain, tested): an
 * unprovisioned app says it cannot reach the library. ⚠⚠ Never a mock behind the gate — a photo
 * app showing photographs that are not yours, or none as though yours were gone, is the worst lie
 * this platform can tell (`photos-app.md` §10.3; parity doc D4).
 *
 * ⚠ Reachability rides on the repository (`PhotosRepository.controlReachable`), not beside it as in
 * Calendar, because Photos' Compose shells already read it there.
 *
 * ⚠ Only the factories the Swift shell calls are here — a factory nothing calls is "built and
 * unreachable". PIO-1 draws the feed and the viewer; each later phase adds its own.
 */
object PhotosSurface {

    /**
     * Where repository calls, and the view models' passes over a whole library, run.
     * ⚠ Declared FIRST: [repository]'s initialiser reads it, and an object's properties initialise in order.
     */
    private val WORK = Dispatchers.Default

    /**
     * ⚠⚠ **Always [offMain]** — set through [install], never assigned (`photos-ios.md` §8.22.2).
     * Every view model and every Swift call below reaches the library through this one field, so
     * this is the one place that decides no repository call runs on the thread that makes it. On
     * route 1 a call is a store query on the caller's thread, and the caller is the main thread.
     */
    private var repository: PhotosRepository = offMain(NotConnectedPhotosRepository())

    private fun offMain(inner: PhotosRepository): PhotosRepository = OffCallerPhotosRepository(inner, WORK)

    private fun install(inner: PhotosRepository) {
        repository = offMain(inner)
    }

    /** Install the repository subsequently-built view models bind. Called by [PhotosLocalRuntime]. */
    fun installRepository(photosRepository: PhotosRepository) = install(photosRepository)

    /**
     * Whether this phone's preflight found it unsuitable; `null` while no backend is bound in the
     * installed runtime (the build flag is off, or a static gate refused). Set once per install by
     * [PhotosLocalRuntime], from what its assembly bound: it is what decides [search]'s route.
     */
    private var onDeviceAiUnsuitable: (() -> Boolean)? = null

    internal fun installOnDeviceAi(unsuitable: (() -> Boolean)?) {
        onDeviceAiUnsuitable = unsuitable
    }

    /**
     * What the Models screen is built over, as the installed runtime provides it; with none
     * installed, why this phone is offered nothing, or *not connected*. ⚠ Never a fake, and
     * never an empty list.
     */
    private var modelsRepository: () -> ModelsRepository = { PhotosLocalRuntime.modelsWithNoRuntime() }

    private val modelsInstallCount = MutableStateFlow(0)

    /**
     * Counts every change of what the Models screens are built over: an install, a teardown.
     * ⚠⚠ A screen that is open across one holds a view model over the repository that was
     * replaced, whose *Try again* can never work: it compares this with
     * [PhotosModelsBinding.install] and builds its view model again when they differ.
     */
    val modelsInstalls: StateFlow<Int> get() = modelsInstallCount

    internal fun installModels(repository: () -> ModelsRepository) {
        modelsRepository = repository
        modelsInstallCount.update { it + 1 }
    }

    /** Restore the honest not-connected surface — the teardown / fallback target. */
    fun installNotConnected() {
        install(NotConnectedPhotosRepository())
        onDeviceAiUnsuitable = null
        installModels { PhotosLocalRuntime.modelsWithNoRuntime() }
    }

    /**
     * Settings → **Models** (`ios-on-device-inference.md` §14.18): the shared view model, over
     * this runtime's repository. Built fresh per call; SwiftUI owns it and **closes** it when
     * its screen goes. What it draws is `photosModelsScreen(state, device, onWifi)`.
     *
     * ⚠⚠ A view model built before a teardown keeps the retired repository and shows *not
     * connected*; the next one is built over the next install's ([modelsInstalls]).
     */
    fun models(): PhotosModelsBinding = binding(forTheModelsScreen = true)

    /**
     * ⚠⚠ The count is read BEFORE the repository, and [installModels] writes them the other way
     * round: an install between the two reads then leaves a binding of an earlier install over
     * the later repository, which builds once more than it had to. Read the other way round it
     * would be the later install's count over the repository that was replaced, and never
     * built again.
     */
    private fun binding(forTheModelsScreen: Boolean): PhotosModelsBinding {
        val install: Int = modelsInstallCount.value
        return PhotosModelsBinding(ModelsViewModel(modelsRepository(), forTheModelsScreen), install)
    }

    /** The shell's noun for itself, as the Models screens' sentences take it: *iPhone*, *iPad*. */
    fun deviceNoun(): String = if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) "iPad" else "iPhone"

    /**
     * Settings → Models → **one feature**: the instance that shows the consents and nothing of
     * the models. What it draws is `photosFeatureScreen(state, capability, device)`.
     */
    fun modelsFeature(): PhotosModelsBinding = binding(forTheModelsScreen = false)

    /**
     * `-photosModels <state>`: the Models screens over [PhotosModelsFixture], in the state named.
     * **DEV_HARNESS only**; no shipping path reaches it, and nothing is installed for a name it
     * does not know.
     *
     * @return the fixture's own controls, or `null` for a state it does not know.
     */
    fun installModelsFixture(state: String): PhotosModelsProbe? {
        val fixture: PhotosModelsFixture = PhotosModelsFixture.of(state) ?: return null
        installModels { fixture.repository }
        return fixture.probe
    }

    /**
     * The `-photosFixture` render probe's library ([PhotosScreenshotFixture]). **DEV_HARNESS render
     * probes only** — the `ContactsSurface.installMock` precedent; no shipping path reaches it.
     */
    fun installScreenshotFixture() = install(PhotosScreenshotFixture.repository())

    /**
     * The fixture's calls that were made ON THE MAIN THREAD since launch, by name — empty when none
     * was (`photos-ios.md` §8.22.2). **DEV_HARNESS only**: what `ReadsOffMainUITests` reads.
     */
    fun fixtureCallsOnMain(): String = PhotosScreenshotFixture.callsOnMain().joinToString(" ")

    /**
     * How many walks of the library the fixture has served since launch (`photos-ios.md` §8.22.3).
     * **DEV_HARNESS only**: what `CollectionsWalkUITests` counts.
     */
    fun fixtureWalks(): Int = PhotosScreenshotFixture.walks()

    /**
     * The same fixture with every seventh photograph a VIDEO whose `MOTION` clip is [clip] — the
     * `-photosMotion` probe (PIO-2). **DEV_HARNESS only.** The clip comes from Swift because
     * encoding an MP4 is AVFoundation's job, not the fixture's.
     */
    fun installScreenshotFixtureWithMotion(clip: NSData) {
        install(PhotosScreenshotFixture.repository(motionClip = clip.toByteArray()))
    }

    /**
     * `-photosArrivals` (with `-photosFixture`): [count] photographs arrive at the head of the
     * fixture's library, as a sync delivers them. **DEV_HARNESS only**; nothing without the flag.
     */
    fun fixtureArrive(count: Int) {
        PhotosScreenshotFixture.arrivals?.arrive(count)
    }

    /**
     * A turn's clip as `NSData`, for the grid's player (PIO-2). ⚠ One copy per turn; a
     * `KotlinByteArray` read element by element from Swift would cost a bridge call per byte.
     */
    fun motionClipData(turn: MotionTurn): NSData = turn.clip.toNSData()

    /**
     * Whether Control can be reached. ⚠ `null` means *not yet known*: the shell shows neither the
     * wall nor the library while it waits, because "still asking" is not "not connected".
     */
    fun controlReachable(): Flow<Boolean?> = repository.controlReachable()

    /**
     * The feed, bound for SwiftUI: view model + presenter + layout (see [PhotosFeedBinding]).
     * Built fresh per call — SwiftUI owns its lifetime through `@StateObject`.
     *
     * ⚠⚠ The zone functions are [PhotoDates]'s, the same ones Android passes — never the view
     * model's UTC-ish defaults, which file a New Year's Eve photograph under the wrong year.
     */
    fun feed(): PhotosFeedBinding = PhotosFeedBinding(
        // ⚠ [WORK]: the scrubber's year index is a pass over every capture time in the library.
        viewModel = PhotosFeedViewModel(repository, PhotoDates::sectionStart, PhotoDates::yearOf, workDispatcher = WORK),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )

    /**
     * Every album, for the selection's *Add to Album* sheet (PIO-2).
     *
     * ⚠⚠ The three cases stay apart: `Unreachable` is **not** "no albums" — a sheet that said "you
     * have no albums" because Control was down would tell the user their albums are gone
     * (`PhotosRepository`'s read contract, and the reason this is not a bare list).
     */
    suspend fun albums(): AlbumsResult = repository.albums()

    /**
     * What the viewer's About sheet says about the photograph in [state] (PIO-3) — the shared
     * `AboutSheetContent`, with the SAME capture-time formatter Compose passes (`PhotoDates::capturedAt`),
     * so the two apps title one photograph the same way.
     */
    fun aboutSheet(state: PhotoViewerUiState): AboutSheetContent =
        AboutSheetContent.of(state, PhotoDates::capturedAt)

    /**
     * The Collections tab's view model (PIO-4) — with the calendar-aware `yearOf` Compose injects, so a
     * photograph taken on New Year's Eve lands in the same Year card on both platforms.
     */
    fun collections(): CollectionsViewModel = CollectionsViewModel(
        repository,
        PhotoDates::yearOf,
        // §36 step 2: the last tab drawn, from the container's Caches — keyed by the realm the
        // route-1 hand-off names. ⚠ That read is the shared Keychain's, offline and synchronous, so
        // it answers when the runtime is slow; every identity teardown (reset, sign-out, revocation:
        // Control's `clearDriveSession`) clears the material, and `null` purges the snapshot.
        snapshotStore = iosCollectionsSnapshotStore(),
        currentRealm = CurrentRealm { RealmKeyMaterialBridge.read()?.ownerId },
        // ⚠⚠ Never the caller's context here (`photos-ios.md` §8.22): the route-1 repository's reads
        // are store queries on the thread that calls them, and the caller is the main thread.
        workDispatcher = WORK,
    )

    /**
     * Drop the Collections snapshot (§36 step 2) — called by [PhotosLocalRuntime.deprovision], the
     * route-1 teardown, so a torn-down identity leaves no cards behind even if Photos never reaches
     * the Collections tab again.
     *
     * ⚠⚠ Effective when it RETURNS: `tearDown()` deletes the file at once, on this
     * thread, without waiting on the store's fence — the Swift caller (`adopt()`) is synchronous on the
     * main thread, and a teardown that only *scheduled* a purge could be suspended or killed while it
     * waited behind a write, leaving the file for a re-pairing into the same realm to draw. A write the
     * live Collections view model already has in flight re-checks the store's clear count after it
     * writes and deletes what it wrote. The fenced [purge] is launched as well, as a second sweep.
     * ⚠ The store is the process singleton and is NOT reset: the next identity uses it as it is.
     */
    fun clearCollectionsSnapshot() {
        val store = iosCollectionsSnapshotStore()
        store.tearDown()
        snapshotPurgeScope.launch { store.purge() }
    }

    private val snapshotPurgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The Trash (PIO-4). [onChanged] runs whenever a restore or delete happened — ⚠ including
     * after the screen has gone (`TrashViewModel.onChanged`).
     */
    fun trash(onChanged: () -> Unit): TrashViewModel = TrashViewModel(repository, onChanged = onChanged)

    /**
     * Duplicates (PIO-4). [onChanged] runs whenever a keep-only wrote something — ⚠ including
     * after a NO or an unanswered write, which may have happened (`DuplicatesViewModel.onChanged`).
     */
    fun duplicates(onChanged: () -> Unit): DuplicatesViewModel = DuplicatesViewModel(repository, onChanged = onChanged)

    /**
     * The Search tab (PIO-4, `photos-ios.md` §8.19). [device] is the shell's noun for itself
     * ("iPhone", "iPad") — the one word of the screen Kotlin cannot know.
     *
     * ⚠⚠ [DescriptionSearch.NotBuilt] wherever no backend is bound — every build with the on-device AI
     * flag off, and every phone a static gate refused (`ios-on-device-inference.md` §3.2). Search by
     * description is then never asked for and reads as "isn't available on this iPhone yet" — never as
     * "no results", and never with the proxy's consent sentence, which names a switch in Control's
     * Settings → Models that Control-on-iOS does not have. Names and synced tags (AI-R) need no index.
     *
     * ⚠⚠ [DescriptionSearch.AskThisDevice] where a backend IS bound. That is decided once, at
     * assembly, and this route never changes afterwards: what changes is the proxy's answer. Its
     * `noBackend` reads "there isn't a model yet" until one is ready — and "isn't available on this
     * iPhone yet" once the preflight found the phone unsuitable, the one case that line would lie in.
     * ⚠ The `noConsent` line names **Photos'** Settings → Models there ([ModelsSwitchHome.THIS_APP]):
     * Control-on-iOS has no such switch.
     */
    fun search(device: String): PhotoSearchViewModel {
        val unsuitable: (() -> Boolean) = onDeviceAiUnsuitable
            ?: return PhotoSearchViewModel(repository, descriptionSearch = DescriptionSearch.NotBuilt(device))
        return PhotoSearchViewModel(
            repository,
            descriptionSearch = DescriptionSearch.AskThisDevice,
            onNoBackend = { if (unsuitable()) SemanticSearchStatus.NotBuiltHere(device) else null },
            // ⚠ The proxy that answers is this app's own, and so is the switch its refusal names.
            modelsSwitch = ModelsSwitchHome.THIS_APP,
        )
    }

    /**
     * An album's view model — one album's grid; [albumId] `AlbumDetailViewModel.FAVOURITES` is the
     * Favourites pseudo-album. [onEditLanded] runs whenever one of its edits is known to have landed —
     * ⚠ including after the screen has gone (`AlbumDetailViewModel.onEditLanded`).
     */
    fun albumDetail(albumId: String, title: String, onEditLanded: () -> Unit): AlbumDetailViewModel =
        AlbumDetailViewModel(repository, albumId, title, onEditLanded = onEditLanded)

    /** The viewer's view model, seeded with the photograph tapped. The pager reports the rest. */
    fun viewerViewModel(photo: Photo): PhotoViewerViewModel = PhotoViewerViewModel(repository, photo)

    /**
     * A grid tile's bytes, for a tile [tileEdgePx] device pixels on its long edge.
     *
     * ⚠ The rung is chosen by the shared [tilePreviewRequestPx], never in Swift — the same answer
     * Android's tile gets for the same tile. `nil` = draw the placeholder (loading, or no rung yet).
     * Crosses as `NSData` so Swift gets `Foundation.Data` (the `DrivePreviewSurface` precedent).
     */
    suspend fun tilePreview(path: String, tileEdgePx: Int, pixelWidth: Int, pixelHeight: Int): NSData? =
        withContext(Dispatchers.Default) {
            val px = tilePreviewRequestPx(tileEdgePx, pixelWidth.takeIf { it > 0 }, pixelHeight.takeIf { it > 0 })
            runCatching { repository.preview(path, px, px) }.getOrNull()?.bytes?.toNSData()
        }

    /**
     * The viewer's hero bytes for one page, as a stream: it emits the best rung present now, then
     * better ones as they arrive (`PhotosRepository.viewerPreview`, at most three emissions).
     *
     * ⚠⚠ Per page, owned by the page — never held by [PhotoViewerViewModel] (`photos-app.md`
     * §21.3): a pager has a window, a view model does not, and bytes in the view model make memory a
     * function of how far the user swiped.
     */
    fun heroPreview(
        path: String,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        pixelWidth: Int,
        pixelHeight: Int,
    ): Flow<NSData> {
        val px = heroPreviewRequestPx(
            viewportWidthPx, viewportHeightPx, pixelWidth.takeIf { it > 0 }, pixelHeight.takeIf { it > 0 },
        )
        return repository.viewerPreview(path, px).map { it.bytes.toNSData() }
    }

    /** "17 December 2024" / "December 2024" / "2024" — the shared header text (PIO-0). */
    fun sectionLabel(binding: PhotosFeedBinding, sectionStartMs: Long): String =
        PhotoDates.sectionLabel(binding.viewModel.density.value, sectionStartMs)
}

private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}

private fun NSData.toByteArray(): ByteArray = bytes?.readBytes(length.toInt()) ?: ByteArray(0)
