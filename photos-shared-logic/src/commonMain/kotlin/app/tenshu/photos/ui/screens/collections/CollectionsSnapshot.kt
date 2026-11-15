@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package app.tenshu.photos.ui.screens.collections

import app.tenshu.outbox.OutboxStore
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.model.FaceCover
import app.tenshu.photos.model.FaceRegion
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.withoutEditedCovers
import org.tenshu.core.file.isEditRenditionPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.serialization.json.Json

/**
 * The last Collections tab Photos drew, kept on this device so a cold start can draw it at once and
 * refresh behind it (`photos-app.md` §36 step 2).
 *
 * ⚠⚠ **Cards, never the library.** Each section is the cards the tab draws — id, title, subtitle,
 * count, cover — and nothing a section's own page reads: no [SmartAlbum.photos]. The photo lists ARE
 * the library, and a person, place or year page must keep reading live data (see [CollectionsUiState.smartPage]).
 *
 * ⚠⚠ **Local, not through Control.** Photos has no store of its own — even its preferences are
 * read through the Control proxy — and a cache read through Control is useless exactly when Control
 * is slow, which is the case this exists for. So it lives in a file in Photos' own sandbox
 * ([JsonCollectionsSnapshotStore] over `:app-outbox`'s per-platform file store).
 *
 * ⚠ Versioned: a file of any other [version] is ignored, never read. Change the shape → bump
 * [FORMAT_VERSION]; the cost of a bump is one placeholder start.
 */
@Serializable
data class CollectionsSnapshot(
    val version: Int = FORMAT_VERSION,
    /** The realm (its `ownerId`) the cards were read from — see [CollectionsSnapshotStore]. */
    val realm: String = "",
    val albums: List<SnapshotAlbum> = emptyList(),
    val favouriteCount: Int = 0,
    val people: List<SnapshotSection> = emptyList(),
    val places: List<SnapshotSection> = emptyList(),
    val things: List<SnapshotSection> = emptyList(),
    val years: List<SnapshotSection> = emptyList(),
    val videos: SnapshotSection? = null,
    /** [PeopleStatus], spelled for the file: `available`, `failed` or `unavailable:<reason>`. */
    val peopleStatus: String? = null,
) {
    companion object {
        /** ⚠ Bump on any change to the shape above — an old file is then ignored, never mis-read. */
        const val FORMAT_VERSION = 1
    }
}

/** One album card — [AlbumSummary], field for field. */
@Serializable
data class SnapshotAlbum(
    val albumId: String,
    val name: String,
    val createdAtMs: Long,
    val photoCount: Int,
    val coverPath: String? = null,
    val coverPixelWidth: Int? = null,
    val coverPixelHeight: Int? = null,
    /** See [SnapshotSection.coverContentId]. */
    val coverContentId: String? = null,
)

/**
 * One derived section's card — what the tab draws for it, and ⚠⚠ nothing more: no photo list.
 *
 * [alternateIds] is kept so a route held by a person's alias still resolves to this card while the
 * live read is in flight; they are cluster ids, not photographs.
 */
@Serializable
data class SnapshotSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val photoCount: Int,
    val coverPath: String? = null,
    val coverPixelWidth: Int? = null,
    val coverPixelHeight: Int? = null,
    val alternateIds: List<String> = emptyList(),
    val coverFace: SnapshotFace? = null,
    /**
     * The cover photo's `contentId`, what a cold start checks against the edit index before drawing
     * the cover (`photos-editing.md` §12.13). ⚠ Absent from a file written before it existed: such a
     * cover is unknown, and is not drawn.
     */
    val coverContentId: String? = null,
)

@Serializable
data class SnapshotFace(val path: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * What a snapshot-drawn [SmartAlbum] shows in place of its photographs (§36 step 2). Non-null on a
 * [SmartAlbum] exactly when its photo list is NOT known — it came from the snapshot and its section's
 * live read has not replaced it.
 */
data class SnapshotCard(
    val photoCount: Int,
    val coverPath: String?,
    val coverPixelWidth: Int?,
    val coverPixelHeight: Int?,
    val coverContentId: String? = null,
)

/**
 * Which realm this device is in now — its `ownerId`, the realm key's fingerprint — the key the
 * snapshot is stored under (§36 step 2).
 *
 * ⚠⚠ Three answers, and the difference is the point:
 * - an id — draw and write only that realm's snapshot;
 * - `null` — this device has NO realm (reset, forgotten, not yet onboarded): the reset signal, and
 *   every snapshot is dropped;
 * - a throw — it cannot tell (Control unreachable or slow): unknown, so nothing is drawn and nothing
 *   is dropped.
 */
fun interface CurrentRealm {
    suspend fun ownerId(): String?

    /**
     * Teardowns learned AFTER [ownerId] answered — a revocation the relay reports only once Control
     * has tried it. Each emission purges the snapshot and takes its cards off the
     * screen; observed for the view model's whole life. Empty where a teardown can only be read.
     */
    fun teardowns(): Flow<Unit> = emptyFlow()

    /**
     * Moments the realm becomes unreadable to this session WITHOUT a teardown — a realm locked after
     * [ownerId] answered (a sealed Control restart). Each emission takes the snapshot's
     * cards off the screen; ⚠ the file is kept, because locking destroys no identity.
     */
    fun invalidations(): Flow<Unit> = emptyFlow()

    /**
     * Moments the realm MAY have changed — the view model asks [ownerId] again on each. ⚠ A hint,
     * not the answer: realm changes are detected by the view model's one tracker, which also asks
     * after every refresh, so a source with no stream (iOS's Keychain read) still works.
     */
    fun changes(): Flow<Unit> = emptyFlow()
}

/**
 * Where the snapshot lives — **one realm's at a time** (§36 step 2).
 *
 * ⚠⚠ Keyed by realm: [read] answers only a snapshot written under [realm], so a device that was reset,
 * re-onboarded or paired into another realm never draws the previous realm's people, places and
 * albums. [write] keeps only the realm it writes; [clear] drops every realm's — the reset signal.
 *
 * ⚠ Best-effort by contract: [read] answers `null` for a missing, unreadable or other-version file and
 * [write]/[clear] swallow their failures — a cache that can crash or block the tab is worse than none.
 */
interface CollectionsSnapshotStore {
    /**
     * ⚠⚠ THE fence for this directory — every read, write and delete of it, from every caller, runs
     * holding it. Owned by the store, not a view model, so a teardown path outside any
     * view model ([purge], iOS's `deprovision()`) is ordered against a write already in flight. The
     * store is one instance per process (a Koin `single`; iOS's `iosCollectionsSnapshotStore()`), so
     * this is process-wide.
     */
    val fence: Mutex

    /**
     * How many times [clear] or [tearDown] has run. ⚠ A write compares it three times — taken before
     * its realm check, re-read holding [fence], and re-read AFTER it has written — so a write prepared
     * before a purge never lands after it, and one a [tearDown] overtook deletes what it wrote.
     */
    val clears: Int

    /**
     * The next save ticket — monotonic for the process, issued when a save CAPTURES the tab. The
     * fence serves saves in arrival order, not capture order, so a save whose realm read answered
     * late could otherwise land over a newer one and resurrect a renamed album.
     */
    fun issueTicket(): Int

    /** The newest ticket written. ⚠ Read and set only while holding [fence]. */
    var newestWritten: Int

    fun read(realm: String): CollectionsSnapshot?
    fun write(realm: String, snapshot: CollectionsSnapshot)
    fun clear()

    /**
     * ⚠⚠ The identity teardown, effective when it RETURNS: bumps [clears] and deletes
     * every snapshot at once, on the calling thread, WITHOUT the fence — a teardown that only scheduled
     * a fenced purge could be suspended or killed while it waited behind a write, leaving the file for
     * the next cold start. A write already in flight re-checks [clears] after it writes and deletes what
     * it wrote, so the only window left is a crash between a write and that check.
     */
    fun tearDown()
}

/**
 * Drop every snapshot, ordered behind any write in flight — the teardown path's call (iOS
 * `deprovision()`), and the only way to clear from outside the view model.
 */
suspend fun CollectionsSnapshotStore.purge() = fence.withLock { clear() }

/**
 * [CollectionsSnapshotStore] over `:app-outbox`'s per-platform file store (Android/desktop
 * `FileOutboxStore`, iOS `NsFileOutboxStore`), reused rather than a new file seam: it already writes
 * atomically (temp + rename) on every platform Photos ships on. One record per realm, named for it,
 * and the realm is inside the record too, so a renamed file still cannot cross realms. The
 * single-record use is Timeline's `JsonOpenMoveJournal` precedent.
 */
class JsonCollectionsSnapshotStore(private val store: OutboxStore) : CollectionsSnapshotStore {

    private val json = Json { ignoreUnknownKeys = true }

    override val fence: Mutex = Mutex()

    // ⚠ Atomic: [tearDown] bumps it on the caller's thread while a write may be reading it on IO.
    private val clearCount = AtomicInt(0)

    override val clears: Int get() = clearCount.load()

    private val tickets = AtomicInt(0)

    override fun issueTicket(): Int = tickets.incrementAndFetch()

    override var newestWritten: Int = 0

    override fun read(realm: String): CollectionsSnapshot? = try {
        val payload = store.entries().firstOrNull { it.id == entryIdFor(realm) }?.payload
        payload?.let { json.decodeFromString(CollectionsSnapshot.serializer(), it) }
            ?.takeIf { it.version == CollectionsSnapshot.FORMAT_VERSION && it.realm == realm }
    } catch (e: Throwable) {
        // ⚠ Corrupt or from a shape this build does not read: ignored, and the next good refresh
        // overwrites it. Never rethrown — the tab then starts from placeholders.
        println("CollectionsSnapshot -> ignored an unreadable snapshot: $e")
        null
    }

    override fun write(realm: String, snapshot: CollectionsSnapshot) {
        try {
            val mine = entryIdFor(realm)
            store.append(mine, json.encodeToString(CollectionsSnapshot.serializer(), snapshot.copy(realm = realm)))
            // ⚠ One realm's snapshot at a time: any other realm's is dropped, never kept for later.
            store.entries().filter { it.id != mine }.forEach { store.remove(it.id) }
        } catch (e: Throwable) {
            println("CollectionsSnapshot -> write failed, ignored: $e")
        }
    }

    override fun clear() {
        clearCount.incrementAndFetch()
        deleteAll()
    }

    override fun tearDown() {
        // ⚠ The bump FIRST: a write that has already written sees it on its post-write check.
        clearCount.incrementAndFetch()
        deleteAll()
    }

    private fun deleteAll() {
        try {
            store.entries().forEach { store.remove(it.id) }
        } catch (e: Throwable) {
            println("CollectionsSnapshot -> clear failed, ignored: $e")
        }
    }

    companion object {
        /** The directory name each platform puts the store under, in its CACHE location. */
        const val DIRECTORY = "collections_snapshot"

        /** The record's name for [realm] — its `ownerId`, a hex fingerprint, so file-safe. */
        fun entryIdFor(realm: String): String = "collections-$realm"
    }
}

// ---- state <-> snapshot ------------------------------------------------------------------------

/**
 * The cards on screen now, as a snapshot. ⚠⚠ Reads [SmartAlbum.photoCount] / cover, never
 * [SmartAlbum.photos] — so a snapshot can only ever hold what a card shows.
 */
fun CollectionsUiState.toSnapshot(): CollectionsSnapshot = CollectionsSnapshot(
    albums = albums.map {
        SnapshotAlbum(it.albumId, it.name, it.createdAtMs, it.photoCount, it.coverPath, it.coverPixelWidth, it.coverPixelHeight, it.coverContentId)
    },
    favouriteCount = favouriteCount,
    people = people.map { it.toSnapshotSection() },
    places = places.map { it.toSnapshotSection() },
    things = things.map { it.toSnapshotSection() },
    years = years.map { it.toSnapshotSection() },
    videos = videos?.toSnapshotSection(),
    peopleStatus = when (val status = peopleStatus) {
        PeopleStatus.Available -> "available"
        PeopleStatus.Failed -> "failed"
        is PeopleStatus.Unavailable -> "unavailable:${status.reason}"
    },
)

private fun SmartAlbum.toSnapshotSection() = SnapshotSection(
    id = id,
    title = title,
    subtitle = subtitle,
    photoCount = photoCount,
    coverPath = coverPath,
    coverPixelWidth = coverPixelWidth,
    coverPixelHeight = coverPixelHeight,
    alternateIds = alternateIds,
    coverFace = coverFace?.let { SnapshotFace(it.path, it.region.left, it.region.top, it.region.right, it.region.bottom) },
    coverContentId = coverContentId,
)

private fun SnapshotSection.toSmartAlbum() = SmartAlbum(
    id = id,
    title = title,
    subtitle = subtitle,
    // ⚠⚠ Empty, and [snapshotCard] says so: this card's photographs are not known yet.
    photos = emptyList(),
    alternateIds = alternateIds,
    coverFace = coverFace?.let { FaceCover(it.path, FaceRegion(it.left, it.top, it.right, it.bottom)) },
    snapshotCard = SnapshotCard(photoCount, coverPath, coverPixelWidth, coverPixelHeight, coverContentId),
)

private fun peopleStatusFrom(spelled: String?): PeopleStatus? = when {
    spelled == null -> null
    spelled == "available" -> PeopleStatus.Available
    spelled == "failed" -> PeopleStatus.Failed
    spelled.startsWith("unavailable:") -> PeopleStatus.Unavailable(spelled.removePrefix("unavailable:"))
    else -> null
}

/** Every section LOADING — the state before any read has answered (§36 step 2). */
internal fun CollectionsUiState.allLoading(): CollectionsUiState = copy(
    albumsLoad = SectionLoad.LOADING,
    favouritesLoad = SectionLoad.LOADING,
    libraryLoad = SectionLoad.LOADING,
    placesLoad = SectionLoad.LOADING,
    thingsLoad = SectionLoad.LOADING,
    peopleLoad = SectionLoad.LOADING,
)

/**
 * This state with [snapshot]'s cards drawn into every section that is still `LOADING` with nothing in it.
 *
 * ⚠⚠ **Never drawn as current:** only a LOADING section takes the snapshot's cards, and its load state
 * is not touched — it stays LOADING until its own live read replaces them; a read that FAILS leaves
 * them on screen and the layout says "Couldn't refresh …" (stale beats blank, said as stale).
 * ⚠ A section a live read has answered — even with nothing — is never filled from the snapshot.
 */
/**
 * This snapshot with every cover taken off that may show an **edited** original — or every such
 * cover, when [editedContentIds] is `null` (not known) (`photos-editing.md` §12.13).
 *
 * ⚠⚠ The snapshot was written before the edit may have existed — on this device, or on another one
 * while Photos was closed — so a cover it names can be an original the user has since redacted, and a
 * person's face crop is cut from the original by construction. The live read never names one
 * ([withoutEditedCovers], [Photo.previewPath]); this is the same rule for the cache, applied before a
 * card is drawn from it. A card that loses its cover keeps its title and count and draws the
 * placeholder until its live read lands.
 *
 * Kept: a cover that is itself a rendition (`isEditRenditionPath`), which is the edit's own picture.
 * Dropped: a cover with no recorded `contentId` (a file from before it was recorded) — unknown.
 */
fun CollectionsSnapshot.withoutCoversFrom(editedContentIds: Set<String>?): CollectionsSnapshot {
    fun mayShowAnOriginal(path: String?, contentId: String?) =
        path != null && !isEditRenditionPath(path) &&
            (editedContentIds == null || contentId == null || contentId in editedContentIds)
    fun SnapshotSection.clean() =
        if (mayShowAnOriginal(coverPath ?: coverFace?.path, coverContentId)) {
            copy(coverPath = null, coverPixelWidth = null, coverPixelHeight = null, coverFace = null)
        } else {
            this
        }
    return copy(
        albums = albums.map {
            if (mayShowAnOriginal(it.coverPath, it.coverContentId)) it.copy(coverPath = null, coverPixelWidth = null, coverPixelHeight = null) else it
        },
        people = people.map { it.clean() },
        places = places.map { it.clean() },
        things = things.map { it.clean() },
        years = years.map { it.clean() },
        videos = videos?.clean(),
    )
}

fun CollectionsUiState.withSnapshot(snapshot: CollectionsSnapshot): CollectionsUiState {
    // ⚠ LOADING or FAILED, and empty: the realm or the file can answer after a read
    // has already failed, and an empty failed section is exactly what stale-beats-blank is for — it
    // keeps its FAILED status, so the layout says "Couldn't refresh …". ⚠⚠ Never a LOADED section,
    // even an empty one: a read answered it, and that answer is authoritative.
    fun open(load: SectionLoad, empty: Boolean) = load != SectionLoad.LOADED && empty
    val libraryOpen = open(libraryLoad, years.isEmpty() && videos == null)
    return copy(
        albums = if (open(albumsLoad, albums.isEmpty())) {
            snapshot.albums.map {
                AlbumSummary(it.albumId, it.name, it.createdAtMs, it.photoCount, it.coverPath, it.coverPixelWidth, it.coverPixelHeight, it.coverContentId)
            }
        } else {
            albums
        },
        // ⚠ Only while no read has confirmed a count; the tile shows it as the last known count.
        favouriteCount = if (favouritesLoad != SectionLoad.LOADED && !favouritesConfirmed) snapshot.favouriteCount else favouriteCount,
        people = if (open(peopleLoad, people.isEmpty())) snapshot.people.map { it.toSmartAlbum() } else people,
        places = if (open(placesLoad, places.isEmpty())) snapshot.places.map { it.toSmartAlbum() } else places,
        things = if (open(thingsLoad, things.isEmpty())) snapshot.things.map { it.toSmartAlbum() } else things,
        years = if (libraryOpen) snapshot.years.map { it.toSmartAlbum() } else years,
        videos = if (libraryOpen) snapshot.videos?.toSmartAlbum() else videos,
        peopleStatus = if (open(peopleLoad, people.isEmpty())) peopleStatusFrom(snapshot.peopleStatus) ?: peopleStatus else peopleStatus,
    )
}

/**
 * This state with every card the snapshot drew taken off — what an identity teardown learned after
 * the snapshot was drawn does to the screen (§36 step 2). Live cards stay.
 *
 * ⚠ An album summary carries no snapshot marker, so albums are dropped whenever no live albums read
 * has confirmed them, and the favourite count whenever no live read has confirmed it — under a known
 * revocation, showing less is the right side to err on.
 */
internal fun CollectionsUiState.withoutSnapshotCards(): CollectionsUiState {
    fun List<SmartAlbum>.live() = filter { it.snapshotCard == null }
    return copy(
        albums = if (albumsLoad == SectionLoad.LOADED) albums else emptyList(),
        favouriteCount = if (favouritesConfirmed) favouriteCount else 0,
        people = people.live(),
        places = places.live(),
        things = things.live(),
        years = years.live(),
        videos = videos?.takeIf { it.snapshotCard == null },
    )
}

// ---- a section's own page ----------------------------------------------------------------------

/**
 * What a derived section's page (`Destination.Smart`) shows for the route [smartId].
 *
 * @property album the section, if it resolved — possibly snapshot-only (its title, not its photos)
 * @property loading its photographs are not known yet: show the page's loading state, ⚠⚠ never an
 *   empty grid, which would say "no photos" about a person who has hundreds
 * @property failed its live read failed and nothing live is known: say so, not "no photos"
 */
data class SmartPage(val album: SmartAlbum?, val loading: Boolean, val failed: Boolean)

/**
 * Resolves a `Destination.Smart` route against this state (`photos-app.md` §36 step 2).
 *
 * ⚠⚠ A snapshot card is tappable before the live refresh lands, and its page must not claim the
 * section is empty. So a snapshot-only section — or a route that resolves to nothing while reads are
 * still in flight — is `loading`, and the page fills in when the live state arrives (the route holds
 * an id and re-resolves on every state). If its section's read FAILED, it is `failed`.
 *
 * [lastResolved] is the route's last LIVE resolution (the alias-loss rule in `App.kt`); it is used
 * only when nothing resolves now.
 */
internal fun CollectionsUiState.resolveSmartPage(smartId: String, lastResolved: SmartAlbum?): SmartPage {
    val resolved = smartAlbumFor(smartId)
    if (resolved != null && resolved.snapshotCard == null) return SmartPage(resolved, loading = false, failed = false)
    if (resolved == null && lastResolved != null && lastResolved.snapshotCard == null) {
        return SmartPage(lastResolved, loading = false, failed = false)
    }
    val load = when {
        resolved == null -> if (anySectionLoading) SectionLoad.LOADING else SectionLoad.LOADED
        people.any { it.id == resolved.id } -> peopleLoad
        // ⚠ Places and Things are joined against the walk: a failed walk fails them too.
        places.any { it.id == resolved.id } -> if (libraryLoad == SectionLoad.FAILED) SectionLoad.FAILED else placesLoad
        things.any { it.id == resolved.id } -> if (libraryLoad == SectionLoad.FAILED) SectionLoad.FAILED else thingsLoad
        else -> libraryLoad // years and videos
    }
    return SmartPage(
        album = resolved,
        loading = load == SectionLoad.LOADING,
        // ⚠ A snapshot card whose read did not answer: its photographs are unknown, not absent.
        failed = resolved != null && load != SectionLoad.LOADING,
    )
}

private val CollectionsUiState.anySectionLoading: Boolean
    get() = loading || listOf(libraryLoad, placesLoad, thingsLoad, peopleLoad).any { it == SectionLoad.LOADING }
