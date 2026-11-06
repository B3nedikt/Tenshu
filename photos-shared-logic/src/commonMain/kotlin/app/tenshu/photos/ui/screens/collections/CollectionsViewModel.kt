package app.tenshu.photos.ui.screens.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.LibraryWatch
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.FaceCover
import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.peopleFrom
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.InferenceCapability
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.groupPhotosByLabel
import app.tenshu.photos.model.groupPhotosByPlace
import app.tenshu.photos.ui.screens.feed.FeedError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/**
 * One derived section of the Collections tab — a query, never a stored row (`photos-app.md` S4).
 *
 * ⚠ [photos] is carried rather than re-queried on open: the whole point of the one-pass design
 * (`photos-app.md` §13) is that the Places and Years sections are computed from a feed read that
 * already happened, so opening one must not walk the realm again.
 */
data class SmartAlbum(
    val id: String,
    val title: String,
    val subtitle: String,
    val photos: List<Photo>,
    /**
     * Other ids that address this same section — empty for every section but People.
     *
     * ⚠⚠ `Destination.Smart` holds an id, and a refresh can re-key a person: naming a second
     * cluster folds it into an existing name, so the id the open screen was addressed by stops
     * existing at the moment the naming succeeds. Carrying the aliases is what keeps that screen
     * alive.
     */
    val alternateIds: List<String> = emptyList(),
    /** People only: the header's face. `null` for every other section. */
    val coverFace: FaceCover? = null,
    /**
     * Non-null only on a card drawn from the on-device snapshot (§36 step 2) whose live read has not
     * replaced it: what the card shows in place of its photographs, which are NOT known — [photos] is
     * empty for that reason, not because the section is. ⚠⚠ Its page must show loading, never "no
     * photos" ([CollectionsUiState.smartPage]).
     */
    val snapshotCard: SnapshotCard? = null,
) {
    val coverPath: String? get() = coverFace?.path ?: snapshotCard?.coverPath ?: photos.firstOrNull()?.path

    /** How many photographs the card counts — the snapshot's count until the live list arrives. */
    val photoCount: Int get() = snapshotCard?.photoCount ?: photos.size

    /** The cover's stored pixel size, for its tile's rung — `null` when unknown. */
    val coverPixelWidth: Int? get() = if (snapshotCard != null) snapshotCard.coverPixelWidth else coverPhoto?.pixelWidth
    val coverPixelHeight: Int? get() = if (snapshotCard != null) snapshotCard.coverPixelHeight else coverPhoto?.pixelHeight
    private val coverPhoto: Photo? get() = coverPath?.let { path -> photos.firstOrNull { it.path == path } }
}

/**
 * A naming that stopped part way and has not been completed since.
 *
 * ⚠⚠ **Both halves are the key.** The same card can launch two namings — "Add another
 * person" reopens the moment the dialog closes, so Bob and Carol can be in flight from Anna's card
 * at once — and a map keyed by card alone lets one partial result overwrite the other, after
 * which a success for either removes the entry and the still-broken name loses its retry for good.
 */
data class UnfinishedNaming(val smartId: String, val name: String)

/**
 * Where one Collections section's read stands (`photos-app.md` §36).
 *
 * ⚠ Per section, because the tab does not wait for all of its reads: a section is drawn when its
 * own inputs arrive, and a read that fails or hangs fails only the section that needed it.
 */
enum class SectionLoad { LOADING, LOADED, FAILED }

data class CollectionsUiState(
    val albums: List<AlbumSummary> = emptyList(),
    val favouriteCount: Int = 0,
    /** Named places with photos, most-photographed first. Empty for a user with no Timeline. */
    val places: List<SmartAlbum> = emptyList(),
    /** One row per year that has photos, newest first. */
    val years: List<SmartAlbum> = emptyList(),
    val videos: SmartAlbum? = null,
    /**
     * What a model recognised — "Dog", "Beach", "Receipt" (`inference-service.md` §12).
     *
     * ⚠⚠ **Empty on every device today, and the tab must read as complete without it.** No device
     * has an inference backend (the proxy binds `InferenceBackendRegistry.Empty`, §17.4), so no
     * `MediaLabel` row exists anywhere and this list stays empty until a backend writes one. That
     * is why the section is *absent* rather than greyed out: a row that does nothing until some
     * future release is advertising, not a feature.
     */
    val things: List<SmartAlbum> = emptyList(),
    /**
     * The faces this device groups together, largest group first and **unnamed** — a name is
     * [peopleStatus]'s sibling problem and arrives through the naming flow (`nameGroup`).
     *
     * ⚠⚠ Empty on every device today for TWO reasons that look identical here: no backend can
     * detect a face, and no calibrated threshold has been recorded. [peopleStatus] is what tells
     * them apart, and the screen says which instead of showing an empty section.
     */
    val people: List<SmartAlbum> = emptyList(),
    /**
     * ⚠⚠ Why this is not merely `people.isEmpty()`: a library with no recognisable faces and a
     * build with no threshold both produce an empty list, and only one of them is a defect. The
     * Collections screen turns it into one line under the People header (`peopleStatusLine`).
     */
    val peopleStatus: PeopleStatus = PeopleStatus.Unavailable(FaceGroupsUnavailable.Reasons.NO_BACKEND),
    /**
     * Why a name the user typed did NOT save — `null` when nothing has failed.
     *
     * ⚠⚠ It has its own field rather than folding into [error] or [peopleStatus], because
     * the failure it reports is invisible otherwise: the sheet closes, the grid refreshes, and the
     * person is simply still unnamed. A user reads that as "it did not take my tap", tries again,
     * and has nothing to report.
     *
     * ⚠⚠ It carries the REASON rather than a flag, because "switch People on" and "try
     * again" are different instructions and only one of them is ever right. ⚠ Cleared by the
     * next [refresh], so it never latches.
     */
    val nameFailure: NamingOutcome? = null,
    /**
     * Which person [nameFailure] belongs to, by their [SmartAlbum.id].
     *
     * ⚠⚠ A naming of many photographs outlives the screen that started it. Without this the
     * verdict is global, and its dialog appears on whatever the user has opened by the time it
     * lands — an unrelated person, a year, a place — and sits there until dismissed.
     */
    val nameFailureFor: String? = null,
    /**
     * The person whose naming stopped part way and has not been completed since, by their
     * [SmartAlbum.id] — `null` when none has.
     *
     * ⚠⚠ **Separate from [nameFailure], and it has to outlive the dialog.** Once a person is
     * named their card carries a name, which hides the naming action — that action exists for
     * first namings. The dialog then says "try again" and dismissing it would remove the only way
     * to, leaving them permanently half synced.
     *
     * ⚠⚠ **Card AND name, because neither alone is enough.** A flag is global: dismiss the
     * warning, open a different already-named person, and they would offer "Finish naming", and
     * completing that would clear it for whoever needed it. A set of cards fixes that and loses the
     * name — which matters, because "Add another person" means the interrupted write can be for
     * someone the card is NOT titled after, so a retry using the card's title rewrites the wrong
     * person and reports success. And a map from card to name still holds only one, so two namings
     * launched from the same card overwrite each other.
     */
    val namingIncompleteFor: Set<UnfinishedNaming> = emptySet(),
    val loading: Boolean = false,
    val error: FeedError? = null,
    /**
     * The library walk's state — what [years] and [videos] wait for (§36). ⚠ Defaults to LOADED so
     * a state built by hand (every test, a preview) reads as complete.
     */
    val libraryLoad: SectionLoad = SectionLoad.LOADED,
    /**
     * Albums' and favourites' read. ⚠ A failure here is local to the Albums row like any other
     * section's: only an unreachable Control speaks for the whole tab, through [error].
     */
    val albumsLoad: SectionLoad = SectionLoad.LOADED,
    /** The favourites read — its own, so a failure is not passed off as the old count. */
    val favouritesLoad: SectionLoad = SectionLoad.LOADED,
    /** Whether a favourites read has ever answered — see [favouritesLoad]; `true` for a hand-built state. */
    val favouritesConfirmed: Boolean = true,
    /** [places]' read, which also waits for the library walk. */
    val placesLoad: SectionLoad = SectionLoad.LOADED,
    /** [things]' read — labels, joined against the library walk. */
    val thingsLoad: SectionLoad = SectionLoad.LOADED,
    /** [people]'s read — `faceGroups`, joined against the labels and the library walk. */
    val peopleLoad: SectionLoad = SectionLoad.LOADED,
) {
    /**
     * The derived sections the tab groups under *Places & times* — facts off the file, not claims
     * a model made about the picture.
     */
    val placesAndTimes: List<SmartAlbum> get() = listOfNotNull(videos) + places + years

    /**
     * Every derived section, in the order the tab shows them.
     *
     * ⚠ [people] then [things] once either is non-empty: they are the sections a user cannot get
     * any other way. Places and years are re-derivable by scrolling; "every photo of a dog" is not.
     *
     * ⚠⚠ `Destination.Smart` resolves a route against THIS list (`App.kt`), so a section omitted
     * here still renders and then opens an empty screen. Adding a derived section means adding it
     * in both places or in neither.
     */
    val smartAlbums: List<SmartAlbum> get() = people + things + placesAndTimes

    /**
     * Whether anything is on screen to keep — the test for replacing the tab with a full-screen
     * spinner or error. ⚠⚠ Not `albums.isNotEmpty()`: a user with no albums (most users) would
     * lose their loaded years and people to a spinner on every refresh, and to a full-screen error
     * on any failure, so the retry banner over cached content could never show.
     */
    val hasContent: Boolean
        get() = albums.isNotEmpty() || favouriteCount > 0 || smartAlbums.isNotEmpty()

    /**
     * The section a route addressed by [id] — matched on its id OR any alias: naming a
     * second cluster re-keys a person's card, so the id an open screen holds may now be an alias.
     * Matching on `id` alone blanks the screen at the moment the naming succeeded. ⚠ Shared so the
     * iOS shell resolves a route the way Compose's `Destination.Smart` does.
     */
    fun smartAlbumFor(id: String): SmartAlbum? =
        smartAlbums.firstOrNull { it.id == id || id in it.alternateIds }

    /**
     * What a section's own page shows for the route [smartId] (§36 step 2) — [smartAlbumFor], plus
     * whether its photographs are known yet. ⚠⚠ A card drawn from the on-device snapshot has none, and
     * its page must say loading (or failed), never "no photos". Shared so iOS's grid answers the way
     * Compose's `Destination.Smart` does. [lastResolved] is the route's last LIVE resolution.
     */
    fun smartPage(smartId: String, lastResolved: SmartAlbum? = null): SmartPage = resolveSmartPage(smartId, lastResolved)

    /**
     * People in their *View all* order: NAMED first, then the unnamed clusters — each half keeping
     * the largest-group-first order it came in (a stable sort). ⚠ The raw [people] order lets
     * unnamed clusters bury the names the user made or synced. Shared so the iOS grid keeps
     * Compose's order.
     */
    val peopleNamedFirst: List<SmartAlbum> get() = people.sortedBy { it.title.isBlank() }

    /**
     * ⚠ Empty only after a read *succeeded* — the rule the feed's own state machine states. ⚠⚠ Every
     * section's read, not just the tab's [error]: a failed library walk is not an empty library, and
     * saying "Nothing here yet" beside "Couldn't load your library" calls a failure a fact.
     */
    val isEmpty: Boolean
        get() = isEmpty(ignorePeople = false)

    /** Every section's read has answered — the walk that produced this state read the whole tab. */
    val everySectionLoaded: Boolean
        get() = listOf(albumsLoad, favouritesLoad, libraryLoad, placesLoad, thingsLoad, peopleLoad).all { it == SectionLoad.LOADED }

    /**
     * [isEmpty], optionally not counting People's read — ⚠ for a platform that cannot group faces,
     * where a People failure is hidden and must not also hide "Nothing here yet".
     */
    fun isEmpty(ignorePeople: Boolean): Boolean =
        albums.isEmpty() && favouriteCount == 0 && smartAlbums.isEmpty() &&
            !loading && error == null &&
            listOfNotNull(albumsLoad, favouritesLoad, libraryLoad, placesLoad, thingsLoad, peopleLoad.takeUnless { ignorePeople })
                .all { it == SectionLoad.LOADED }
}

/**
 * The Collections tab (`photos-app.md` S4).
 *
 * ⚠⚠ **Collections is the tab; an album is one section inside it.** Google Photos and Apple Photos
 * both settled on this split, and it is the one that matches what is here:
 * [CollectionsUiState.albums] is the list the user built by hand, and the places, years, videos and
 * things sections are things Photos noticed.
 *
 * ⚠ Smart albums are **queries, not rows**: every derived section is computed from the feed each
 * refresh. Storing them would mean maintaining them, and the only thing a query cannot express — a
 * list the user chose by hand — is what `Album` is for.
 */
class CollectionsViewModel(
    private val repository: PhotosRepository,
    /**
     * Which year an instant falls in. ⚠ Injected for the reason `groupIntoDays` takes its
     * day-start function: the timezone question is answered by the shell (`PhotoDates::yearOf`)
     * and the arithmetic stays testable.
     */
    private val yearOf: (Long) -> Int = { epochMs -> (epochMs / YEAR_MS + 1970).toInt() },
    /**
     * How long one refresh may take before it is an error. ⚠ Generous on purpose: the refresh walks
     * the WHOLE library (`allPhotos`), measured at 8 s for 1,345 photographs on a phone, and a timeout
     * that fires on a large healthy library would turn a slow tab into a broken one. It exists to end
     * a read that will never answer, not to hurry one that will.
     */
    private val refreshTimeoutMs: Long = REFRESH_TIMEOUT_MS,
    /**
     * When set, the FIRST refresh waits for the feed's first rows instead of running in `init`
     * (`photos-app.md` §38, fix 2) — `null` (tests, previews, the iOS shell) refreshes at once.
     *
     * ⚠⚠ The Compose shells create this view model at the App level, so an `init` refresh would run
     * on every launch while the user looks at the *Photos* tab, and its six reads — the library
     * walk among them — would share Control with the feed's first page. Deferred, not dropped: the
     * album sheet the feed offers reads [CollectionsUiState.albums], so the refresh still runs on
     * every launch, the moment the rows land. ⚠ If the Collections tab is opened first, its own
     * [refresh] is the first and this one is skipped — never a second walk.
     */
    private val startAfter: app.tenshu.photos.ui.screens.feed.FeedFirstPage? = null,
    /**
     * The last tab drawn, kept on this device (§36 step 2) — `null` keeps none (tests, previews).
     * ⚠ Read and written off the main thread, on [snapshotDispatcher]; both are best-effort.
     */
    private val snapshotStore: CollectionsSnapshotStore? = null,
    /**
     * Which realm the device is in — the snapshot's key (§36 step 2). ⚠⚠ Without it there is no
     * snapshot at all: a cache that cannot tell realms apart would draw the last realm's people.
     */
    private val currentRealm: CurrentRealm? = null,
    private val snapshotDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Where a refresh's reads and its regrouping run — `null` keeps them in the caller's context
     * (tests, previews, and the Compose shells, whose reads are IPC and suspend).
     *
     * ⚠⚠ **The iOS shell must pass a background dispatcher** (`photos-ios.md` §8.22). There the
     * repository is the in-process route-1 runtime: a read is a store query on the thread that
     * calls it, and `viewModelScope` is `Main.immediate` — so with `null` all six reads and the
     * grouping of the whole library run on the main thread, one after another, and a return to the
     * Collections tab holds it for 1.65 s on a phone. ⚠ State is still published from the view
     * model's own scope: only values cross back.
     */
    private val workDispatcher: CoroutineDispatcher? = null,
) : ViewModel() {

    private val snapshots: CollectionsSnapshotStore? = snapshotStore?.takeIf { currentRealm != null }

    /** ⚠ Declared before `init`, like every field its coroutines read. */
    private val work: CoroutineContext = workDispatcher ?: EmptyCoroutineContext

    /** A section's cards, computed from the library where the reads ran — never in a state update. */
    private suspend fun <T> derived(block: () -> T): T =
        if (workDispatcher == null) block() else withContext(workDispatcher) { block() }

    // ⚠ No favourites read has answered yet — so the tile says "Loading…", never a "0 photos" nobody confirmed.
    // ⚠ With a snapshot every section starts LOADING: nothing on screen is current until its own read
    // lands, however late the first refresh starts (§36 step 2).
    private val _state = MutableStateFlow(
        CollectionsUiState(favouritesConfirmed = false).let { if (snapshots != null) it.allLoading() else it },
    )
    val state: StateFlow<CollectionsUiState> = _state.asStateFlow()

    private var inFlight = false

    /** The realm the snapshot was read or last written under — a different answer later drops it. */
    private var snapshotRealm: String? = null

    /** An identity teardown arrived while running — see [onTeardown]. Never reset. */
    private var tornDown = false

    /**
     * The realm became unreadable to this session without a teardown — a late lock.
     * The snapshot's cards are off and stay off; the file is kept. ⚠ Declared BEFORE `init`, like every
     * field init's coroutines read: on Kotlin/Native `viewModelScope` is `Main.immediate`.
     */
    private var snapshotHidden = false

    /** Whether a realm answer has been recorded yet — the first one is a baseline, not a change. */
    private var realmSeen = false

    /**
     * The refresh generation at which the realm last changed under this view model, until a refresh
     * started after it has read every section afresh — see [onRealmChanged]. `null` = no transition.
     */
    private var realmChangedAt: Int? = null

    // ── walking only when something changed (`photos-ios.md` §8.22.3) ───────────────────────────────
    // ⚠ All declared BEFORE `init`, like every field its coroutines read.

    /** The repository's word on whether the library changed — `null`: it cannot tell, so every return walks. */
    private val watch: LibraryWatch? = repository.libraryWatch()

    /** The surfaces drawing this state right now — see [setShown]. */
    private val shownOn = mutableSetOf<String>()

    /** The shell said something changed ([changed]) and no walk has started since. */
    private var toldChanged = false

    /**
     * The watch's revision when the last walk that read EVERY section began — what "clean" means: the
     * same revision now, with the watch still watching, is the same library. `null`: nothing on
     * screen is known to be current.
     */
    private var cleanAt: Long? = null

    /**
     * The watch's revision when the LATEST walk began, whether or not it read everything — `null`
     * when the watch was not watching then. What "changed since the last walk" is measured against.
     */
    private var walkedFromLatest: Long? = null

    /** A change to walk for, while the tab is shown. Conflated: any number of changes is one walk. */
    private val changeTicks = Channel<Unit>(Channel.CONFLATED)

    init {
        // §36 step 2: the last tab drawn, as soon as the realm is known and the file is read —
        // independent of WHEN the first refresh runs. ⚠⚠ Drawn only into sections no read has LOADED
        // and that are empty (a FAILED one keeps FAILED), and only before a second refresh starts (the
        // `generation` guard), so it never overwrites a live answer and is never drawn as current.
        snapshots?.let { store ->
            viewModelScope.launch {
                val realm = readRealm() ?: return@launch // unknown: draw nothing, drop nothing
                observeRealm(realm) // no realm on this device: the tracker drops the file
                val ownerId = realm.ownerId ?: return@launch
                val snapshot = offMain(store) { _, _ -> store.read(ownerId) }
                // ⚠ Drawn only for the realm the tracker holds NOW, with no transition pending.
                val drawable = generation <= 1 && !tornDown && !snapshotHidden &&
                    snapshotRealm == ownerId && realmChangedAt == null
                if (snapshot != null && drawable) _state.update { it.withSnapshot(snapshot) }
            }
            // ⚠⚠ For the whole life of the view model, not one sample: a revocation
            // reaches Control only after its first refused relay call, long after the realm answered.
            currentRealm?.let { realm ->
                viewModelScope.launch {
                    try {
                        realm.teardowns().collect { onTeardown() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        println("CollectionsViewModel -> teardown watch ended: $e")
                    }
                }
                viewModelScope.launch {
                    try {
                        realm.invalidations().collect { onUnreadable() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        println("CollectionsViewModel -> lock watch ended: $e")
                    }
                }
                // ⚠⚠ The realm watcher: realm changes are found HERE and after every
                // refresh — never only by a refresh that succeeded — and all go through [observeRealm].
                viewModelScope.launch {
                    try {
                        realm.changes().collect { readRealm()?.let { answer -> observeRealm(answer) } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        println("CollectionsViewModel -> realm watch ended: $e")
                    }
                }
            }
        }
        watch?.let { watch ->
            // A change while the tab is on screen is walked for; one while it is not waits for the
            // return ([refreshIfChanged]) — walking a library nobody is looking at is the cost this exists to end.
            viewModelScope.launch { watch.revision.drop(1).collect { if (shownOn.isNotEmpty()) changeTicks.trySend(Unit) } }
            viewModelScope.launch { walkForChanges() }
        }
        // §38: only the FIRST live refresh waits for the feed's first rows; the snapshot above is
        // applied at construction regardless (§36 step 2).
        val gate = startAfter
        if (gate == null) {
            refresh()
        } else {
            viewModelScope.launch {
                gate.await()
                // ⚠ `generation`, not `inFlight`: a refresh the tab already ran — finished or not —
                // is the first one, and this would only repeat it.
                if (generation == 0) refresh()
            }
        }
    }

    /** A realm read's answer — `ownerId == null` is "no realm", which is not the same as unknown. */
    private class RealmAnswer(val ownerId: String?)

    /**
     * The device's realm, or `null` when it cannot be told (a failed or unanswered read). ⚠ Bounded
     * like every read here, and abandoned rather than awaited — see [bounded].
     */
    private suspend fun readRealm(): RealmAnswer? {
        val provider = currentRealm ?: return null
        val job = viewModelScope.async { RealmAnswer(provider.ownerId()?.takeIf { it.isNotBlank() }) }
        return try {
            withTimeoutOrNull(refreshTimeoutMs) { job.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("CollectionsViewModel -> realm unknown, snapshot neither drawn nor dropped: $e")
            null
        } finally {
            if (job.isActive) job.cancel()
        }
    }

    /**
     * File work for the snapshot, off the main thread; a failure is no answer, never a crash.
     *
     * ⚠⚠ **One at a time, in order, across the whole process**: every read, write and
     * delete holds the STORE's [CollectionsSnapshotStore.fence] — the one a teardown outside this view
     * model ([purge], iOS `deprovision()`) takes too — so a delete always runs after a write that started
     * before it. [work] is told, read AFTER the fence is held, whether this view model has seen a
     * teardown and how many purges the store has run, so a write that waited behind a delete never
     * writes.
     */
    private suspend fun <T> offMain(store: CollectionsSnapshotStore, work: (tornDown: Boolean, clears: Int) -> T?): T? = store.fence.withLock {
        val gone = tornDown
        val clears = store.clears
        try {
            withContext(snapshotDispatcher) { work(gone, clears) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("CollectionsViewModel -> snapshot file work failed, ignored: $e")
            null
        }
    }

    /**
     * The user's *Try again*. ⚠⚠ Not [refresh]: that one is dropped while a refresh is in
     * flight, and a section can fail fast while a sibling's read is still inside its budget — so a tap
     * would do nothing for up to [refreshTimeoutMs]. This abandons the refresh in flight (its reads are
     * already abandonable — see [bounded]) and starts a new one at once.
     */
    fun retry() {
        // ⚠ A rerun queued while the old refresh ran is superseded by this one, which starts after
        // it — left set, the retry would end by walking the whole library a second time.
        rerunRequested = false
        current?.cancel()
        inFlight = false
        refresh()
    }

    private var current: kotlinx.coroutines.Job? = null

    /**
     * Which refresh is in charge. ⚠⚠ A counter taken BEFORE the launch, not the launched `Job`:
     * on Kotlin/Native `viewModelScope` is `Main.immediate`, so a refresh whose reads
     * answer without suspending runs to its `finally` before `launch` has even returned its job —
     * comparing against [current] there would leave [inFlight] set for good.
     */
    private var generation = 0

    /** A refresh was asked for while one ran — see [refresh]. */
    private var rerunRequested = false

    fun refresh() {
        // ⚠⚠ Asked again while one runs: run once more AFTER it, never drop it. A refresh already
        // walking the library captured its albums before a mutation landed; dropping the mutation's
        // own refresh here would let the older walk publish the pre-mutation state. One queued
        // rerun covers any number of requests made meanwhile.
        if (inFlight) {
            rerunRequested = true
            return
        }
        inFlight = true
        val mine = ++generation
        // ⚠ Taken BEFORE any read starts: a change that lands during the walk moves the revision past
        // this, and the walk's answer is then not called clean. `null` while the watch cannot vouch.
        val walkedFrom = watch?.takeIf { it.watching.value }?.revision?.value
        cleanAt = null
        walkedFromLatest = walkedFrom
        toldChanged = false
        // ⚠ Every section starts LOADING; what is already on screen stays there until its own read
        // replaces it, so a refresh over content never blanks the tab.
        _state.update {
            it.copy(
                loading = true,
                albumsLoad = SectionLoad.LOADING,
                // ⚠ LOADING only while no count has ever been confirmed: a retained count
                // stays LOADED until the new answer, so the tile shows "0 photos" only after a read said 0.
                favouritesLoad = if (it.favouritesLoad == SectionLoad.LOADED && it.favouritesConfirmed) SectionLoad.LOADED else SectionLoad.LOADING,
                libraryLoad = SectionLoad.LOADING,
                placesLoad = SectionLoad.LOADING,
                thingsLoad = SectionLoad.LOADING,
                peopleLoad = SectionLoad.LOADING,
            )
        }
        current = viewModelScope.launch {
            val timings = ReadTimings(readsOffCaller = workDispatcher != null)
            try {
                loadSections(timings)
            } finally {
                // ⚠⚠ Only the refresh in charge may clear the flags: a [retry] cancels the
                // old one and starts the next at once, and the cancelled one's `finally` can run after the
                // new one has set `loading` — clearing it would let a full-screen state replace the new
                // placeholders.
                if (generation == mine) {
                    inFlight = false
                    _state.update { it.copy(loading = false) }
                    // ⚠ Kept in the product (§36): this line is what showed the reads take ~0.45 s on an
                    // idle Control, and it is how the next slow tab gets diagnosed.
                    println("Collections -> ${timings.report()}")
                    // §36 step 2: keep what this refresh drew for the next cold start — not when it was
                    // cancelled (a retry, or the screen going away).
                    if (isActive) saveSnapshot(mine)
                    // ⚠ Clean only when EVERY section was read: a failed one is retried by the next return.
                    if (isActive && walkedFrom != null && _state.value.everySectionLoaded) cleanAt = walkedFrom
                    if (rerunRequested) {
                        rerunRequested = false
                        refresh()
                    } else if (shownOn.isNotEmpty() && aWalkIsOwed()) {
                        // Something changed while this walk ran, and the tab is on screen.
                        // ⚠⚠ A CHANGE, not "not clean": a walk that failed is not clean either, and
                        // walking for that would retry a failing read twice a second for as long as
                        // the tab stayed up. A failure waits for the user — Try Again, or a return.
                        changeTicks.trySend(Unit)
                    }
                }
            }
        }
    }

    private fun watching(): Boolean = watch?.watching?.value == true

    /**
     * Whether a walk nobody asked for is owed: this app said it changed something, or the watch saw
     * a change. ⚠⚠ [toldChanged] stands on its own: it is only noted because the
     * watch is expected to deliver the walk, and a watch that stops before it does — an observer
     * ending inside the settle wait — must not drop the app's own edit. The revision, by contrast,
     * means nothing once the watch is not watching.
     */
    private fun aWalkIsOwed(): Boolean = toldChanged || (watching() && changedSinceTheLatestWalk())

    /**
     * Whether anything changed since the latest walk BEGAN. ⚠⚠ Not [needsWalk]: a walk that failed
     * leaves the tab unvouched-for, but nothing has changed, and walking for that would retry a
     * failing read for as long as the tab stayed up. Only this may start a walk nobody asked for.
     */
    private fun changedSinceTheLatestWalk(): Boolean =
        toldChanged || walkedFromLatest != watch?.revision?.value

    /** Whether what is on screen may be out of date. ⚠ `true` whenever nobody can vouch that it is not. */
    private fun needsWalk(): Boolean {
        val watch = watch ?: return true
        return !watch.watching.value || toldChanged || cleanAt != watch.revision.value
    }

    /**
     * [surface] began or stopped drawing this state — the tab, or one of the grids pushed over it.
     * While any does, a change is walked for as it arrives ([walkForChanges]); while none does, it
     * waits for [refreshIfChanged]. ⚠ A set of names, not a count: an `onAppear` delivered twice must
     * not need two `onDisappear`s.
     *
     * ⚠⚠ Coming back on screen after a change is itself a reason to walk: a grid that only says it is
     * shown again (the smart album's, under a viewer that trashed a photo from it) is asked about by
     * nothing else, and the change was noted while nobody was looking. It goes through the burst
     * wait like any change, so a surface that also calls [refreshIfChanged] walks once, not twice.
     */
    fun setShown(surface: String, shown: Boolean) {
        val wasShown = shownOn.isNotEmpty()
        if (shown) shownOn += surface else shownOn -= surface
        if (shown && !wasShown && aWalkIsOwed()) changeTicks.trySend(Unit)
    }

    /**
     * The tab came back on screen (`photos-ios.md` §8.22.3). Walks the library only if something it
     * draws may have changed since the last walk that read all of it.
     *
     * ⚠⚠ "May have": with no [watch], or one that is not watching, this IS [refresh] — every return
     * walks. A stale tab that never updates is worse than a slow
     * one, so the skip needs a positive answer, never the absence of a negative one.
     */
    fun refreshIfChanged() {
        if (!watching()) {
            refresh()
            return
        }
        // A walk is running: its end looks again, and walks once more if a change overtook it.
        if (inFlight) return
        if (needsWalk()) {
            refresh()
        } else {
            // ⚠ Kept in the product, like the read timings: from the phone's console this line is how
            // "the tab did not update" is told from "nothing had changed".
            println("Collections -> walk=skipped (clean)")
        }
    }

    /**
     * Something this app did changed what the tab draws — an album edit, a restore, a keep-only.
     *
     * ⚠ With a watch, the write's own signal is on its way too; walking at once would start before
     * it lands, be overtaken by it, and walk a second time. So the change is noted, and the one walk
     * runs when the signals have settled (shown) or on the return (not shown). Without one it is
     * [refresh].
     */
    fun changed() {
        if (!watching()) {
            refresh()
            return
        }
        toldChanged = true
        if (shownOn.isNotEmpty()) changeTicks.trySend(Unit)
    }

    /**
     * One walk per BURST of changes, while the tab is shown: waits until [CHANGE_SETTLE_MS] pass with
     * no further change — but never more than [CHANGE_MAX_WAIT_MS], or a long sync would show nothing
     * until it ended — and never beside a walk already running.
     */
    private suspend fun walkForChanges() {
        for (tick in changeTicks) {
            // Each further change restarts the wait; the outer bound ends it regardless.
            withTimeoutOrNull(CHANGE_MAX_WAIT_MS) {
                while (true) withTimeoutOrNull(CHANGE_SETTLE_MS) { changeTicks.receive() } ?: break
            }
            current?.join()
            // ⚠ A tick can be about a change the latest walk already began after — it is delivered
            // late, and the walk it asked for has happened.
            if (shownOn.isNotEmpty() && aWalkIsOwed() && !inFlight) refresh()
        }
    }

    /**
     * Keeps the tab as now drawn for the next cold start (§36 step 2), written off the main thread.
     *
     * ⚠ Runs after EVERY refresh — one that failed everywhere included, because its realm check is
     * what notices a change the watcher missed. It never handles a change itself: the
     * check feeds [observeRealm], and the write passes the gates in [writeSnapshot] or not at all.
     * ⚠⚠ Cards only: [toSnapshot] never reads a photo list.
     */
    private fun saveSnapshot(refreshGeneration: Int) {
        val store = snapshots ?: return
        val state = _state.value
        val loads = listOf(state.albumsLoad, state.favouritesLoad, state.libraryLoad, state.placesLoad, state.thingsLoad, state.peopleLoad)
        // ⚠ Everything is captured NOW, in refresh order: the ticket orders this save against every
        // other, and the clear count dates it against any purge — whatever its realm read does later.
        val ticket = store.issueTicket()
        val clearsAtCapture = store.clears
        val snapshot = state.toSnapshot().takeIf { loads.any { it == SectionLoad.LOADED } }
        val cleanRead = loads.all { it == SectionLoad.LOADED }
        viewModelScope.launch {
            val realm = readRealm() ?: return@launch // unknown: keep what is on disk
            observeRealm(realm)
            // ⚠ The transition latch ends only on a clean read by a refresh STARTED after the change.
            realmChangedAt?.let { changedAt -> if (refreshGeneration > changedAt && cleanRead) realmChangedAt = null }
            if (snapshot != null) writeSnapshot(store, realm.ownerId, snapshot, ticket, clearsAtCapture)
        }
    }

    /**
     * ⚠⚠ THE write invariant (`photos-app.md` §36 step 2) — every gate, in ONE critical section, under
     * the store's fence. A captured snapshot lands only if:
     * 1. its realm is the current realm (the tracker's [snapshotRealm]), and no teardown has happened;
     * 2. no clear has happened since it was captured ([CollectionsSnapshotStore.clears]);
     * 3. it is the newest capture to reach the store ([CollectionsSnapshotStore.newestWritten]);
     * 4. no realm transition is pending ([realmChangedAt]).
     * And a `tearDown()` that overtakes the write (it does not wait for the fence) makes it undo itself.
     */
    private suspend fun writeSnapshot(
        store: CollectionsSnapshotStore,
        ownerId: String?,
        snapshot: CollectionsSnapshot,
        ticket: Int,
        clearsAtCapture: Int,
    ) = store.fence.withLock {
        // ⚠ Evaluated holding the fence, on the main thread where every one of these fields changes.
        val lands = ownerId != null && ownerId == snapshotRealm && !tornDown && // 1
            store.clears == clearsAtCapture && // 2
            ticket > store.newestWritten && // 3
            realmChangedAt == null // 4
        if (!lands) return@withLock
        try {
            withContext(snapshotDispatcher) {
                val clearsAtWrite = store.clears
                store.write(ownerId!!, snapshot)
                if (store.clears != clearsAtWrite) store.clear() else store.newestWritten = ticket
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("CollectionsViewModel -> snapshot write failed, ignored: $e")
        }
    }

    /**
     * The ONE place a realm answer is recorded and a realm change detected — fed by
     * the first read, the realm watcher and every refresh's check. ⚠⚠ Any transition counts, through
     * null included: A → B, A → none (a reset) and none → B. A first answer is a baseline; a first
     * answer of "none" drops the file.
     */
    private fun observeRealm(answer: RealmAnswer) {
        val store = snapshots ?: return
        val ownerId = answer.ownerId
        if (realmSeen && snapshotRealm != ownerId) {
            onRealmChanged(store, ownerId)
            return
        }
        if (!realmSeen && ownerId == null) viewModelScope.launch { offMain(store) { _, _ -> store.clear() } }
        realmSeen = true
        snapshotRealm = ownerId
    }

    /**
     * The realm changed under a running app. ⚠⚠ Everything on screen may belong to
     * the old realm — live cards a failed section kept, snapshot cards, the favourites count, a pending
     * naming — so the realm-bound state goes back to its initial, all-LOADING shape, the file goes, and
     * the refresh in flight (whose answers may be the old realm's) is abandoned for a fresh one. Saving
     * stays latched ([realmChangedAt]) until a refresh started after this has read every section afresh.
     */
    private fun onRealmChanged(store: CollectionsSnapshotStore, ownerId: String?) {
        snapshotRealm = ownerId
        realmChangedAt = generation
        _state.update { CollectionsUiState(favouritesConfirmed = false).allLoading() }
        viewModelScope.launch { offMain(store) { _, _ -> store.clear() } }
        retry()
    }

    /**
     * A teardown learned after the snapshot was drawn — a revocation the relay reports
     * only once Control has tried it. ⚠⚠ The file goes, the snapshot's cards come off the screen, and
     * no later refresh writes one back for the life of this view model.
     */
    private fun onTeardown() {
        tornDown = true
        _state.update { it.withoutSnapshotCards() }
        snapshots?.let { store -> viewModelScope.launch { offMain(store) { _, _ -> store.clear() } } }
    }

    /**
     * The realm became unreadable to this session without a teardown — a late lock.
     * The snapshot's cards come off and are not drawn again; ⚠ the file is kept, since locking destroys
     * no identity, and no write happens while locked anyway (the realm read answers "unknown").
     */
    private fun onUnreadable() {
        snapshotHidden = true
        _state.update { it.withoutSnapshotCards() }
    }

    /**
     * Every section's read, started together and published as each lands (`photos-app.md` §36).
     *
     * ⚠⚠ **No section waits for another's read** — only for its own inputs. Albums and favourites
     * need nothing; years and videos need the library walk; places, Things and people need the walk
     * AND their own read. So the cheap rows appear at once and a slow `faceGroups` delays People
     * alone.
     */
    private suspend fun loadSections(timings: ReadTimings) = coroutineScope {
        launch { loadAlbums(timings) }

        val library = async { bounded("allPhotos", timings) { collectFeed() } }
        val placesRead = async { bounded("places", timings) { repository.placesRead() } }
        // ⚠ One more read, not one more walk: `allLabels()` reads the `MediaLabel` relation, not the
        // file library, and the grouping below joins against the walk.
        val labelsRead = async { bounded("allLabels", timings) { repository.allLabelsRead() } }
        // ⚠⚠ The status travels with it: an empty list has three possible meanings and only
        // `faceGroups`' own refusal can tell them apart.
        val facesRead = async { bounded("faceGroups", timings) { repository.faceGroups(PEOPLE_GROUP_LIMIT) } }

        // ⚠⚠ ONE walk for every derived section. The alternative — `photosNear` per place — is an
        // O(files-in-realm) scan each. Here the walk happens once and the grouping is arithmetic.
        val everything = library.await()
        if (everything == null) {
            _state.update {
                it.copy(
                    libraryLoad = SectionLoad.FAILED,
                    placesLoad = SectionLoad.FAILED,
                    thingsLoad = SectionLoad.FAILED,
                    peopleLoad = SectionLoad.FAILED,
                )
            }
            return@coroutineScope
        }
        // ⚠⚠ Every grouping below is [derived]: each is a pass over the whole library, and inside
        // `_state.update` it would run on the main thread (and again on every contended retry).
        val (years, videos) = derived {
            // ⚠ `toList().sortedByDescending`, not `toSortedMap`: the latter is JVM-only and does
            // not resolve on Kotlin/Native. `groupBy` keeps insertion order; the sort orders.
            val years = everything
                .groupBy { photo -> yearOf(photo.capturedAtMs) }
                .toList()
                .sortedByDescending { (year, _) -> year }
                .map { (year, photos) -> SmartAlbum("year:$year", year.toString(), photoCount(photos.size), photos) }
            val videos = everything.filter { photo -> photo.isVideo }
                .takeIf { videos -> videos.isNotEmpty() }
                ?.let { videos -> SmartAlbum("videos", "Videos", videoCount(videos.size), videos) }
            years to videos
        }
        _state.update { it.copy(years = years, videos = videos, libraryLoad = SectionLoad.LOADED) }

        launch {
            val places = placesRead.await()?.let { read ->
                derived {
                    groupPhotosByPlace(everything, read).map { group ->
                        SmartAlbum(
                            id = "place:${group.place.placeId}",
                            title = group.place.label,
                            subtitle = group.place.city.ifBlank { photoCount(group.photos.size) },
                            photos = group.photos,
                        )
                    }
                }
            }
            _state.update {
                if (places == null) it.copy(placesLoad = SectionLoad.FAILED)
                else it.copy(places = places, placesLoad = SectionLoad.LOADED)
            }
        }
        launch {
            val things = labelsRead.await()?.let { labels ->
                derived {
                    groupPhotosByLabel(everything, labels).map { group ->
                        SmartAlbum(
                            id = "label:${group.label.lowercase()}",
                            title = group.label,
                            subtitle = photoCount(group.photos.size),
                            photos = group.photos,
                        )
                    }
                }
            }
            _state.update {
                if (things == null) it.copy(thingsLoad = SectionLoad.FAILED)
                else it.copy(things = things, thingsLoad = SectionLoad.LOADED)
            }
        }
        launch {
            val faces = facesRead.await()
            // ⚠ People needs the labels too — they carry the names. Without them every person would
            // be drawn unnamed, which reads as "my names are gone", so a missing read fails the section.
            val labels = labelsRead.await()
            // ⚠ A generic failure arrives as `PeopleStatus.Failed` inside a non-null answer — it is a
            // failed read all the same, and gets the section's Try again. The
            // `Unavailable` reasons are answers and stay LOADED.
            if (faces == null || labels == null || faces.second is PeopleStatus.Failed) {
                _state.update { it.copy(peopleLoad = SectionLoad.FAILED) }
                return@launch
            }
            val (faceGroups, peopleStatus) = faces
            val people = derived {
                // ⚠ Built once: a `firstOrNull` per path would be O(people × photos).
                val byPath = everything.associateBy { photo -> photo.path }
                peopleFrom(
                    faceGroups = faceGroups,
                    faceLabels = labels.filter { label ->
                        label.capability == InferenceCapability.FACE_CLUSTERING.id
                    },
                ).mapNotNull { person ->
                    // ⚠ A path whose file has moved or been trashed is dropped, and a person
                    // left with nothing is dropped rather than drawn as a card opening an
                    // empty screen.
                    val photos = person.filePaths.mapNotNull { path -> byPath[path] }
                    if (photos.isEmpty()) return@mapNotNull null
                    SmartAlbum(
                        id = person.id,
                        title = person.name,
                        subtitle = photoCount(photos.size),
                        photos = photos,
                        alternateIds = person.alternateIds,
                        coverFace = person.cover,
                    )
                }
            }
            _state.update {
                it.copy(
                    people = people,
                    peopleStatus = peopleStatus,
                    // ⚠⚠ The naming fields are NOT cleared here: a refresh is how a naming
                    // failure's own state gets to the screen — see [nameFailure].
                    peopleLoad = SectionLoad.LOADED,
                )
            }
        }
    }

    /**
     * Albums and favourites — the cheap half, and the one that decides the tab-wide [error]:
     * ⚠ an unreachable Control shows here first, and it is the only failure that speaks for the whole
     * tab (every other read fails only its own section).
     */
    private suspend fun loadAlbums(timings: ReadTimings) = coroutineScope {
        // ⚠ Both started before either is awaited: a slow albums read must not hold the
        // Favourites tile at its default for the whole budget.
        val albumsRead = async { bounded("albums", timings) { repository.albums() } }
        val favouritesRead = async { bounded("favourites", timings) { repository.favourites() } }
        launch {
            val favourites = favouritesRead.await()
            _state.update {
                if (favourites is PhotoPageResult.Page) {
                    it.copy(favouriteCount = favourites.photos.size, favouritesLoad = SectionLoad.LOADED, favouritesConfirmed = true)
                } else {
                    it.copy(favouritesLoad = SectionLoad.FAILED)
                }
            }
        }
        val albums = albumsRead.await()
        _state.update {
            when (albums) {
                is AlbumsResult.Albums -> it.copy(albums = albums.albums, albumsLoad = SectionLoad.LOADED, error = null)
                // ⚠⚠ The ONE tab-wide failure: nothing else on the tab can load without Control.
                AlbumsResult.Unreachable -> it.copy(albumsLoad = SectionLoad.FAILED, error = FeedError.Unreachable)
                // ⚠ Local to the Albums row — the rest of the tab stays usable.
                is AlbumsResult.Failed, null -> it.copy(albumsLoad = SectionLoad.FAILED, error = null)
            }
        }
    }

    /**
     * One read, bounded (`tech-debt.md` *"one lost reply wedges the tab"*): `null` when it did not
     * answer within [refreshTimeoutMs] or threw.
     *
     * ⚠⚠ The timeout bounds the WAIT, never the read. A lost Android reply blocks
     * inside a Binder transaction that cancellation cannot interrupt, and the desktop transport waits
     * in `NonCancellable`; `withTimeoutOrNull` around the read itself would wait for that cancellation
     * to complete, i.e. forever. So the read runs as its own job in [viewModelScope] — deliberately
     * NOT a child of the refresh, which would then wait for it — and only `await()` is timed. A read
     * that hangs is abandoned; it returns a value rather than writing state, so a late answer changes
     * nothing.
     */
    private suspend fun <T> bounded(name: String, timings: ReadTimings, read: suspend () -> T): T? {
        val started = TimeSource.Monotonic.markNow()
        // ⚠⚠ On [work], and timed where it RUNS (`photos-ios.md` §8.22): on the main thread the six
        // reads run one after another, each answered only when the last has finished, and the line
        // below would give all six the same figure — six slow reads that are really one held
        // thread.
        val job = viewModelScope.async(work) {
            val began = TimeSource.Monotonic.markNow()
            Ran(read(), began.elapsedNow().inWholeMilliseconds)
        }
        // ⚠⚠ No catch-all: an unexpected exception from a read is a defect, and the
        // repository contract leaves containing it to the execution boundary, not to a screen that would
        // dress it up as an ordinary, retriable failure. Only the timeout becomes a `null`.
        // ⚠ Wrapped, so a read that answered `null` (a typed failure, promptly) is told apart from one
        // that never answered — the timing line must not call a 5 ms failure a timeout.
        val answer = try {
            withTimeoutOrNull(refreshTimeoutMs) { Answer(job.await()) }
        } finally {
            // ⚠ Cancelled — never joined — however the wait ended: the read is a
            // sibling of the refresh, not a child, so a retry cancelling the refresh would otherwise
            // leave it running, and repeated taps would pile reads onto an already-busy Control.
            // What cooperative work it has left stops; a Binder call it is blocked in cannot be
            // interrupted, which is why nothing waits for it.
            if (job.isActive) job.cancel()
        }
        val result = answer?.value?.value
        if (answer == null) println("CollectionsViewModel -> $name timed out after ${refreshTimeoutMs}ms")
        val answeredMs = started.elapsedNow().inWholeMilliseconds
        timings.record(name, ranMs = answer?.value?.ms ?: answeredMs, answeredMs = answeredMs, failed = result == null, timedOut = answer == null)
        return result
    }

    /** A read's answer, `null` included — what [bounded]'s timeout wraps to keep "no answer" distinct. */
    private class Answer<T>(val value: T)

    /** A read's value and how long the read itself ran. */
    private class Ran<T>(val value: T, val ms: Long)

    /**
     * Make an album called [name]. [onDone] reports whether it HAPPENED.
     *
     * ⚠⚠ The repository answers `null` when the write was refused — an unreachable proxy above all.
     * Dropped, the dialog would close, the refresh would find no such album, and the user would be
     * told nothing; the caller says so instead. ⚠ A blank name is not a
     * refusal: nothing is attempted and nothing is reported.
     */
    fun createAlbum(name: String, onDone: (Boolean) -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val created = repository.createAlbum(trimmed) != null
            changed()
            onDone(created)
        }
    }

    fun renameAlbum(albumId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.renameAlbum(albumId, trimmed)
            changed()
        }
    }

    /**
     * Every photo, by paging the feed to the end.
     *
     * ⚠⚠ The honest cost of the derived sections: this is a full walk, the same one `favourites()`
     * pays and for the same missing index. It runs once per Collections-tab refresh rather than
     * once per section, which is the trade the derived sections are built around (`photos-app.md`
     * §13) — but it is still the most expensive thing this app does, and it is written plainly here
     * rather than hidden.
     *
     * ⚠ §39: the library in bounded pages of images, each one fused walk in Control — not the
     * shared content index, whose whole-realm stream is ~6 s on a 1,401-photo phone and one
     * Binder transaction a whisker under the ~1 MB ceiling. ⚠ Still once per refresh, never once
     * per section: every derived section is arithmetic over this one list.
     */
    private suspend fun collectFeed(): List<Photo>? = repository.allPhotosRead()

    /**
     * Files a photo into an album.
     *
     * ⚠ A photo with no `contentId` cannot be filed — the repository refuses it and so does the op.
     * The UI's job is not to offer the action for such a photo in the first place; this is the
     * backstop, not the check.
     */
    fun addPhoto(albumId: String, photo: app.tenshu.photos.model.Photo) {
        viewModelScope.launch {
            repository.addToAlbum(albumId, photo)
            changed()
        }
    }

    /** ⚠ Deletes the album, never its photographs — see `DeleteAlbum`, which cannot reach them. */
    fun deleteAlbum(albumId: String) {
        viewModelScope.launch {
            repository.deleteAlbum(albumId)
            changed()
        }
    }

    /**
     * Name the people in [smartId]'s photographs.
     *
     * ⚠⚠ **The photographs are read from the state the user was looking at**, never
     * re-derived. A group id is rebuilt per call and an index pass can renumber it, so resolving it
     * again here could attach the name to a different person — silently, and in synced data.
     *
     * ⚠ A refusal is surfaced, not swallowed: see [CollectionsUiState.nameFailure].
     */
    fun nameGroup(smartId: String, name: String) {
        val typed = name.trim()
        // ⚠ A blank name is a dismissed sheet, not a failure — nothing to report.
        if (typed.isEmpty()) return
        val paths = _state.value.people.firstOrNull { it.id == smartId }?.photos?.map { it.path }
        if (paths.isNullOrEmpty()) return
        viewModelScope.launch {
            val outcome = repository.nameFaceGroup(paths, typed)
            // ⚠⚠ The verdict FIRST, then the re-read — and the re-read happens on every
            // outcome, not only on success. A person spanning several calls can be half named
            // when a later one is refused, and those rows are already durable and synced; not
            // re-reading would leave the user looking at a screen that disagrees with their realm
            // while being told nothing saved.
            _state.value = _state.value.copy(
                nameFailure = outcome.takeIf { it != NamingOutcome.Saved },
                nameFailureFor = smartId.takeIf { outcome != NamingOutcome.Saved },
                // ⚠ Set by a partial save and cleared only by one that completes — never by
                // dismissing the dialog, which would leave no way to retry. ⚠⚠ Cleared only
                // for THIS person: finishing one naming must not retire another's retry.
                namingIncompleteFor = when (outcome) {
                    // ⚠ Only THIS attempt clears — another name half written from the same card
                    // keeps its own retry.
                    NamingOutcome.Saved ->
                        _state.value.namingIncompleteFor - UnfinishedNaming(smartId, typed)
                    NamingOutcome.PartiallySaved ->
                        _state.value.namingIncompleteFor + UnfinishedNaming(smartId, typed)
                    else -> _state.value.namingIncompleteFor
                },
            )
            // ⚠ Re-read rather than patching the row in place: the name becomes a `MediaLabel`,
            // and every section derived from labels has to see it.
            changed()
        }
    }

    /**
     * Dismiss the "that name did not save" surface.
     *
     * ⚠ The flag is cleared by the next [refresh] too, so this exists for the case where the
     * user acknowledges it without triggering one — without it the message would sit there
     * until something else happened to re-read the library.
     */
    fun dismissNameRefusal() {
        if (_state.value.nameFailure != null) {
            _state.value = _state.value.copy(nameFailure = null, nameFailureFor = null)
        }
    }

    companion object {
        private const val PAGE = 200

        /** ⚠ A crude fallback only — the shells pass a real calendar-aware [yearOf]. */
        private const val YEAR_MS = 31_556_952_000L

        /** See `refreshTimeoutMs`: ~15× the phone's measured whole-library read. */
        const val REFRESH_TIMEOUT_MS = 120_000L

        /**
         * How many face groups the tab asks for.
         *
         * ⚠ A cap on **groups**, not photographs, and generous on purpose: the grid scrolls, and
         * a person missing from it is indistinguishable from a person the model never found. ⚠⚠ It
         * is not a relevance threshold — `minFaces` inside the proxy is what keeps one-off
         * strangers out, and raising this would not substitute for it.
         */
        private const val PEOPLE_GROUP_LIMIT = 200

        /** How long the library must be quiet before a change is walked for — a burst is one walk. */
        const val CHANGE_SETTLE_MS = 500L

        /** The longest a change waits for quiet — the feed's own ceiling (`INVALIDATION_MAX_WAIT_MS`). */
        const val CHANGE_MAX_WAIT_MS = 3_000L
    }
}

/**
 * The per-read durations of one refresh, for its one summary line (`photos-app.md` §36).
 *
 * ⚠⚠ Two figures per read, because they are different facts (`photos-ios.md` §8.22): how long the
 * read itself **ran**, and how long after it was asked for its answer **arrived**. They agree when a
 * read runs on its own; when the thread that must receive the answer is busy they do not, and the
 * second is then how long that thread was held. The line names the second only when it is the news.
 */
internal class ReadTimings(private val readsOffCaller: Boolean) {
    private val entries = mutableListOf<String>()

    fun record(name: String, ranMs: Long, answeredMs: Long, failed: Boolean, timedOut: Boolean) {
        val late = if (answeredMs - ranMs >= HELD_MS) "(answered after ${answeredMs}ms)" else ""
        entries += when {
            timedOut -> "$name=${answeredMs}ms(TIMED OUT)"
            failed -> "$name=${ranMs}ms(FAILED)$late"
            else -> "$name=${ranMs}ms$late"
        }
    }

    fun report(): String =
        (listOf(if (readsOffCaller) "reads=background" else "reads=caller") + entries).joinToString(" ")

    private companion object {
        /** Below this an answer is not late, it is a dispatch. */
        const val HELD_MS = 50L
    }
}
