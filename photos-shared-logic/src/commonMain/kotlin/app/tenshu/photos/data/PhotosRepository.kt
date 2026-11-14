package app.tenshu.photos.data

import app.tenshu.reachability.UnreachableReason
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoDetails
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.SemanticSearchStatus
import app.tenshu.photos.model.PhotoPlace
import org.tenshu.core.file.MediaMetadata

/**
 * Everything the Photos app reads and writes (`photos-app.md`).
 *
 * Bound behind the interface so the mock stays a one-line swap for previews, render tests and a
 * shell with no Control running — the `ContactsRepository` precedent (contacts-app.md D8).
 */
interface PhotosRepository {

    /**
     * Whether Control can currently serve reads — `null` until the first outcome (`ADR 0025`).
     * Photos has no store of its own: every read goes over IPC, so an unreachable Control folds the
     * feed to empty, and this is what lets the shell say *why* rather than showing a library that
     * looks deleted.
     *
     * ⚠ Defaults to a permanent `true` so the mock and previews keep compiling and never show a
     * banner they cannot substantiate.
     */
    fun controlReachable(): Flow<Boolean?> = flowOf(true)

    /**
     * *Why* Control is unavailable — `null` while it is fine or not yet known. [controlReachable]
     * decides **whether** the banner shows; this decides what it says, because "not installed",
     * "asleep" and "refusing on a schema version" have three different fixes and only one of them
     * is waiting.
     */
    fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<UnreachableReason?> = app.tenshu.reachability.NO_UNREACHABLE_REASON

    /**
     * The user asked to check again ("Try again" on the checking gate's retry wall): re-probe Control
     * and re-read the realm now rather than at the next poll. A no-op without an engine.
     */
    fun recheckControl() = Unit

    /**
     * One **keyset** page of the flat feed: the newest [pageSize] images captured strictly before
     * [beforeMs], newest first.
     *
     * ⚠ Keyset, not offset, and the difference is not stylistic: the realm gains photos while the
     * user scrolls (the backup drain is running), and an offset page would then serve a row twice
     * or skip one. A cursor on capture time cannot.
     *
     * [beforeMs] `Long.MAX_VALUE` asks for the first page.
     *
     * ⚠⚠ **[skip] is what makes a tie safe, and it is not an optimisation.** The window is
     * INCLUSIVE of [beforeMs], because an exclusive one would silently drop every photograph
     * sharing the boundary millisecond — a burst, or a bulk import, simply vanishing from the feed.
     * Inclusive alone re-serves the boundary row instead, and when a whole page shares one
     * millisecond the cursor cannot advance past it at all. [skip] is how many rows at exactly
     * [beforeMs] the caller already holds, so the reader resumes *after* them.
     */
    suspend fun feedPage(beforeMs: Long, skip: Int = 0, pageSize: Int): PhotoPageResult

    /**
     * The feed as a `PagingData` stream — the **screen's** read (`core-keyset-paging.md` K-4).
     *
     * ⚠⚠ This replaces the hand-rolled pager that [feedPage] existed to drive, not [feedPage]
     * itself: the Places grouping and the duplicate scan still page the library themselves and
     * still call it. What moves here is only the thing that had a *scroll position* — which is the
     * half that was paying for the workaround (no `cachedIn`, a bespoke cursor state machine, and a
     * merge-on-resume Paging's invalidation does for free).
     *
     * ⚠ The keyset cursor lives in `:core-paging`, so the tie rules [feedPage] documents are not
     * this app's to get right. They are the same rules — the window is inclusive and the
     * skip counts the boundary's ties — enforced by `KeysetPagingSourceTest` rather than by
     * `PhotosFeedViewModel` remembering to.
     *
     * ⚠⚠ A read failure arrives as `LoadState.Error` carrying a [FeedReadException], **not** as an
     * empty page. Paging has no third state, and an unreachable proxy rendered as "no photos yet"
     * is the exact failure [PhotoPageResult.Unreachable] was introduced to prevent.
     *
     * ⚠⚠ [readContext] is where each page's read runs — and the count beside it, and the mapping of
     * its rows (`photos-ios.md` §8.22.2). Empty keeps them where Paging calls `load`, which is the
     * collector's scope: right over IPC, where the read suspends, and the main thread on iOS, where it
     * is a store query — a walk of the library per page, while the user scrolls. ⚠ Only the READ
     * moves. Paging's own bookkeeping stays where it was, so which page is asked for, and the order
     * pages are placed in, cannot change with it. ⚠ A parameter of the read rather than a wrapper
     * around it because the pages are loaded by Paging, inside the source — nothing outside can reach
     * them. An implementation whose pages are already in memory ignores it.
     */
    fun feedPaging(pageSize: Int = 60, readContext: CoroutineContext = EmptyCoroutineContext): Flow<PagingData<Photo>>

    /**
     * Every capture time in the feed, newest first — the scrubber's density map.
     *
     * ⚠⚠ **The whole feed, not a page**, and that is the point: the year pills claim to say where
     * 2021 is *in the library*, so a map built from the pages that happen to be loaded would be a
     * lie that moves as the user scrolls. `photos-feed-scrubber.md` §5 filed this as blocked on the
     * shared content index (~55 s cold); it is not — the same window the feed pages from answers it
     * in one read, because a capture time is the only column the map needs.
     *
     * ⚠ `Long`, not `Photo`: projecting the row would ship a path, a content id and a category per
     * photograph for a number nothing else uses. See `TenshuPhotosRepository.feedCaptureTimes`.
     *
     * ⚠ `null` on failure, never `emptyList()` — an empty map draws no pills, which is also what a
     * library with no photographs draws, and the two must not be confused by the caller.
     */
    suspend fun feedCaptureTimes(): List<Long>?

    /**
     * Whether anything the Collections tab reads has changed, without reading it
     * (`photos-ios.md` §8.22.3) — or `null` when this repository cannot tell, which every caller
     * must treat as *changed*.
     *
     * ⚠⚠ `null` is the default and the safe answer: a caller that cannot tell re-reads, as it always
     * did. A repository that returns a watch promises the opposite direction only — see [LibraryWatch].
     */
    fun libraryWatch(): LibraryWatch? = null

    /**
     * Preview bytes for one photo at roughly [width]×[height], or `null` when this device holds
     * neither the original nor a synced thumbnail for it. See [PhotoPreview.standIn] for an answer
     * smaller than the request.
     */
    suspend fun preview(path: String, width: Int, height: Int): PhotoPreview?

    /**
     * The **viewer's** read of one photograph, as a stream: the best local answer first, then the
     * named rung once it lands (`drive-xxlarge-thumbnail-rung.md` §4.5).
     *
     * ⚠⚠ Not [preview] with a flow around it. Two things differ, and both matter only for the one
     * photograph on screen: it reads `File.ViewerPreview`, so a device holding the original renders
     * it rather than taking a rung stretched up to the request (D-17); and it names `XXLARGE`, which
     * is ~0.5 MB and must never be asked for by a tile or a feed row (§4.4).
     *
     * Emits **at most three times** — the local class, the pulled rung, and what a view mint
     * produced — and never the same bytes twice. A collector redraws on each emission.
     *
     * ⚠⚠ **[PhotoPreview.standIn] is decided before the first read and is not revised.** When the
     * named rung turns out to be local already, the single emission carries `standIn = true` while
     * being the best answer there is. Inert for the viewer, which reads only the bytes — but
     * `PreviewCache` keys *caching* on that flag, so a caller that cached this stream would refuse
     * to remember the right answer. Fix the flag before caching this.
     */
    fun viewerPreview(
        path: String,
        requestPx: Int,
        /**
         * Emits once the page has been looked at long enough to spend a download on it (D-18) —
         * ⚠⚠ the **only** thing that licenses mint-on-view, and a flow that never emits mints
         * nothing. The wait lives with the caller because only it knows what "settled" means.
         */
        mayMint: Flow<Unit> = emptyFlow(),
    ): Flow<PhotoPreview>

    /**
     * §4.5's step 3 — **make this photograph a large rung, here, now**
     * (`drive-xxlarge-thumbnail-rung.md` §4.6). For a photograph no device ever minted one for,
     * or whose byte holder is gone: the proxy pulls it if it exists and otherwise downloads the
     * original and renders it.
     *
     * ⚠⚠ **Expensive, and gated in the proxy rather than here.** It may download 6–18 MB. Quota,
     * a metered network and an earlier decline all refuse it *there*, so a caller cannot spend those
     * bytes by forgetting a check — and [userRequested] is the only override, for a zoom or an
     * explicit "sharpen", never for an ordinary dwell.
     *
     * ⚠ `null` means "not now": every refusal looks the same on purpose, because none of them is
     * something a viewer can act on. The picture already on screen stays.
     */
    suspend fun ensureViewerRung(path: String, userRequested: Boolean = false): ByteArray?

    /**
     * The `MOTION` class for the video at [path] — a ~3 s, 256 px, silent H.264/MP4 loop
     * (`photos-video-support.md` §4 / M3–M5) — or `null` when this device has neither minted nor
     * fetched one.
     *
     * ⚠⚠ **`null` is a wait, not an answer.** The class is `Syncable(OnDemand)`, so on a device that
     * did not upload the video it is absent until this very read pulls it, and a video minted before
     * M4 or over the cap never has one. The rotation (`MotionRotation`) therefore never remembers a
     * `null`; it skips the tile this cycle and asks again on the next.
     *
     * ⚠ Its own read, deliberately outside [preview]'s `File.Preview` ladder and outside the bulk
     * thumbnail pull: a clip is not a bitmap, and pulling one for every video scrolled past would
     * fetch ~90 KB per tile for a feature whose whole point is one clip in flight (§5.3).
     */
    suspend fun motionClip(path: String): ByteArray?

    // ── albums ───────────────────────────────────────────────────────────────────────────────
    //
    // ⚠ Reads return the same [PhotoPageResult] failure cases as the feed, for the same reason:
    // an unreachable proxy must never reach a screen as "you have no albums".

    /** Every album, newest first, each with its cover photo and count. */
    suspend fun albums(): AlbumsResult

    /**
     * One album's photos, in the user's order.
     *
     * ⚠ Entries whose file cannot be found at all are **omitted**, not rendered as gaps: a photo
     * deleted from Drive leaves an entry behind, and an album is a view of what exists.
     */
    suspend fun albumPhotos(albumId: String): PhotoPageResult

    suspend fun createAlbum(name: String): String?

    suspend fun renameAlbum(albumId: String, name: String): Boolean

    suspend fun deleteAlbum(albumId: String): Boolean

    suspend fun addToAlbum(albumId: String, photo: Photo): Boolean

    suspend fun removeFromAlbum(albumId: String, contentId: String): Boolean

    /**
     * The albums one photograph belongs to (the viewer's About sheet).
     *
     * ⚠ The reverse of [albumPhotos], and it needs **no new operation**: `AlbumEntry` rows are
     * already read whole and filtered client-side by album id, so filtering the same read by
     * content id instead is the same cost. That is why this arrived without a schema move.
     *
     * ⚠⚠ Empty for a photograph with no `contentId` — a file predating content addressing cannot be
     * *in* an album, because membership is keyed on that id (`photos-app.md` §11.2). Empty here is
     * therefore "in no album", which is also the honest answer for a file that could never be in one.
     */
    suspend fun albumsFor(contentId: String): List<AlbumSummary>

    /** Every favourited photo, newest first — the one smart album `PhotoService`'s flags answer. */
    suspend fun favourites(): PhotoPageResult

    suspend fun isFavourite(contentId: String): Boolean

    suspend fun setFavourite(contentId: String, favourite: Boolean): Boolean

    /**
     * Every **named** place the user has been.
     *
     * ⚠⚠ These come from `:place-service`, which **Timeline** populates — Photos records none of
     * its own. A user who has never run Timeline has no places, so the Places section is legitimately
     * empty for them, and a photo taken somewhere they never dwelled belongs to no place even if it
     * carries a coordinate. `photos-app.md` §13.2 records why Photos cannot geocode one itself yet.
     */
    suspend fun places(): List<PhotoPlace>

    /**
     * [places], but `null` when the read FAILED rather than an empty list (`photos-app.md` §36):
     * a section that loads, fails and retries on its own must not show a failed read as a
     * library with no places. ⚠ Defaults to [places] so a fake that cannot fail needs nothing.
     */
    suspend fun placesRead(): List<PhotoPlace>? = places()

    // ── trash ────────────────────────────────────────────────────────────────────────────────

    /**
     * Moves a photo to the realm's trash, remembering where it came from.
     *
     * ⚠ A **move**, not a delete: the bytes, the `contentId` and the file's history stay put, so
     * restoring is another move rather than a re-upload, and the backup drain — which tracks the
     * source asset, not the realm copy — is undisturbed.
     */
    suspend fun trash(photo: Photo): Boolean

    /** Puts a trashed photo back where it was. */
    suspend fun restore(contentId: String): Boolean

    /**
     * Deletes a trashed photo for good.
     *
     * ⚠⚠ Irreversible, and the only call in this app that removes a photograph. See
     * `photos-app.md` §15.3 for what it does *not* do — it does not make the drain re-upload the
     * original from the phone's gallery, which the design doc originally claimed it would.
     */
    suspend fun deleteForever(contentId: String): Boolean

    /** What is in the trash, most-recently-trashed first. */
    suspend fun trashed(): TrashResult

    // ── duplicates ───────────────────────────────────────────────────────────────────────────

    /**
     * Groups of files holding **byte-identical** content, for the Duplicates review surface
     * (`photos-app.md` PH-D2, S4).
     *
     * ⚠⚠ A review surface, not a filter: the feed keeps showing every copy, and nothing here is
     * ever acted on without a tap. "Silently hidden" and "lost" are indistinguishable to a user,
     * which is the whole argument PH-D2 records.
     *
     * ⚠ One walk of the realm per call — see `ContentIndex`. The engine has no property index, so
     * this is the app's third library-scaling read and, unlike the other two, it is one the user
     * asked for by opening the screen.
     */
    suspend fun duplicates(): DuplicatesResult

    /**
     * Deletes a photo at its current path, without a trip through the trash.
     *
     * ⚠⚠ Irreversible. The second call in this app that destroys a photograph, and it exists for
     * the case PH-D4 names by name: a user clearing duplicates who is low on quota and wants the
     * bytes gone *now*, for whom "move to trash" frees nothing. Every caller reaches it through a
     * checkbox the user has to tick.
     */
    suspend fun deletePermanently(photo: Photo): Boolean

    /**
     * Whether the library holds, NOW, a file at [path] with exactly [contentHash] — the question
     * Duplicates asks of the copy it is about to KEEP before it removes any other
     * (`photos-ios.md` §8.18).
     *
     * ⚠⚠ `true` only on positive evidence from the store. Not there, moved to the trash (its path is
     * `/Trash/…` then), overwritten with other bytes, or a read that failed: all `false` — the caller
     * removes nothing on any of them, so "could not look" must never read as "it is there".
     *
     * ⚠ One row, by path: never a realm walk.
     */
    suspend fun holdsContent(path: String, contentHash: String): Boolean

    // ── metadata ─────────────────────────────────────────────────────────────────────────────

    /**
     * What one photo says about itself, for the viewer's info sheet (`photos-app.md` S3).
     *
     * ⚠ One row, read when the sheet opens. Deliberately not part of the feed's projection: six
     * more relations per page would be paid on every scroll and read on almost none of them.
     *
     * `null` when the photo is not there or the read failed — the sheet then shows what it already
     * had (path and name) rather than an error, because a metadata read failing is not a reason to
     * interrupt someone looking at a photograph.
     */
    suspend fun details(path: String): PhotoDetails?

    /**
     * The full photographic settings, parsed **on this device** from the file's own bytes
     * (`photos-viewer-about-screen.md` D1).
     *
     * ⚠⚠ **Gated on [org.tenshu.core.file.system.hasLocalData], and that gate is the whole design.**
     * There is no ranged read — `GetData` returns a whole file — so parsing a photograph whose bytes
     * are not here would pull megabytes over the content plane to reach a couple of hundred bytes of
     * EXIF. The probe is O(1) and reads no payload, so asking first costs nothing and refusing is
     * honest: [ExifReadResult.NotOnDevice] is a *state to render*, not an error.
     *
     * ⚠ Nothing here is stored. The values are read at upload into six scalars and there is no
     * backfill (`photos-app.md` §17.4), which is exactly why this reads the bytes instead.
     */
    suspend fun photographicSettings(path: String): ExifReadResult

    // ── editing (`photos-editing.md` ED-1) ───────────────────────────────────────────────────

    /**
     * The photograph's **original** bytes, for the editor — fetched over the content plane when
     * this device does not hold them. `null` when they cannot be had (not synced yet, reclaimed,
     * the proxy unreachable).
     *
     * ⚠⚠ The original, never a thumbnail rung: Android/JVM rungs are raw sensor order with no tag,
     * so an edit drawn from one saves sideways (`photos-creation-features.md` §9.1).
     *
     * ⚠ Defaults to `null` so a repository that cannot edit says so; the editor renders that as
     * "not available on this device" rather than spinning.
     */
    suspend fun originalBytes(path: String): ByteArray? = null

    /**
     * Upload [rendered] (an upright JPEG) as a **new** file in [original]'s folder, and answer the
     * copy as a [Photo] — `null` when it was not written.
     *
     * ⚠⚠ **Never an overwrite** (ED-D1): an overwrite re-mints `contentId`, clearing the favourite and
     * breaking album entries (`photos-editing.md` §3.1). An implementation only ever creates a name
     * that does not exist yet.
     *
     * ⚠ The copy carries [original]'s capture time (`SOURCE_MODIFIED_AT`) so it sits beside it in the
     * feed, and its location (`CAPTURE_LAT_E7`/`LNG_E7`), because the re-encoded bytes carry no
     * EXIF (`photos-creation-features.md` §9.2) — so camera, lens and EXIF date are gone, by design.
     */
    suspend fun saveEditedCopy(original: Photo, rendered: ByteArray): Photo? = null

    /**
     * The stored non-destructive edit of the photo with [contentId] (`photos-editing.md` §12), for
     * re-opening the editor — `null` when it has none. ⚠⚠ **Throws when the read failed**, never
     * `null`: an editor that took a failed read for "no edit" would let a save replace an edit the
     * user never saw.
     *
     * ⚠ Read by the editor only. Nothing renders [StoredEdit.recipe] for display (ED-D2).
     */
    suspend fun photoEdit(contentId: String): StoredEdit? = null

    /**
     * *Save* (ED-3, ED-D11): record [recipe] as [original]'s edit, with [rendered] (an upright JPEG of
     * it) as the rendition every device shows, and answer the photo as it now reads — its own path
     * and `contentId`, with [Photo.renditionPath] set. `null` when nothing was saved, and then the
     * photo's previous edit (or none) is what stands.
     *
     * ⚠⚠ The original is never written. Its `contentId` — what albums, flags and trash key on — is
     * the row's key, which is the point (§3.1).
     */
    suspend fun saveEdit(original: Photo, recipe: app.tenshu.photos.edit.EditRecipe, rendered: ByteArray): Photo? = null

    /**
     * *Revert*: forget [photo]'s edit and delete its rendition. Answers the photo as it now reads
     * (no rendition), or `null` when the edit could not be forgotten.
     */
    suspend fun revertEdit(photo: Photo): Photo? = null

    // ── search (S2) ──────────────────────────────────────────────────────────────────────────

    /**
     * Photos whose **name** contains [query], case-insensitively (`photos-app.md` S2, source 1).
     *
     * ⚠ The one source that is always there. Sources 2 and 3 need a model on the device, and the
     * production backend registry has none — so the screen must read as complete with this alone,
     * which is why it is a separate call rather than a flag on a combined one.
     */
    suspend fun searchByName(query: String): List<Photo>

    /**
     * Photos a model has tagged with a label containing [query] (S2, source 2) — paired with the
     * label that matched, because *"why is this here?"* is the question a tag-based hit raises.
     *
     * Empty when nothing has ever been indexed, which is every device today.
     */
    suspend fun searchByLabel(query: String): List<Pair<Photo, String>>

    /**
     * [searchByName], but `null` when the library could not be searched — never an empty list
     * (`photos-ios.md` §8.19): a walk that failed found nothing because it looked at nothing, and the
     * Search screen must not say "No photos match" over it. ⚠ Defaults to [searchByName] so a fake that
     * cannot fail needs nothing — [allPhotosRead]'s shape.
     */
    suspend fun searchByNameRead(query: String): List<Photo>? = searchByName(query)

    /** [searchByLabel], but `null` when the label rows could not be read — see [searchByNameRead]. */
    suspend fun searchByLabelRead(query: String): List<Pair<Photo, String>>? = searchByLabel(query)

    /**
     * Photos that *look like* [query] (S2, source 3), best first.
     *
     * ⚠⚠ Returns a status, not just a list, and the caller must render the two apart.
     * `photosLike` throws `SemanticSearchUnavailable` rather than returning nothing when the
     * capability is off, no model is installed, or nothing is indexed — and showing that as
     * "nothing matched" is the exact mistake `mia-query-layer.md` §14.1 names.
     *
     * ⚠ A per-call scan over every indexed vector by its own KDoc's admission. The caller owes
     * debouncing; this does none.
     */
    suspend fun searchSemantic(query: String, limit: Int): Pair<List<Photo>, SemanticSearchStatus>

    // ── the shared index (§20) ───────────────────────────────────────────────────────────────

    /**
     * Every photo in the realm, newest first, off the **shared** index (`photos-app.md` §20).
     *
     * ⚠⚠ Not by paging the whole feed: a page is its own `traverse()` with its own sort, so walking
     * the library by calling [feedPage] until it runs dry is **one full-realm walk per page**, not
     * one per screen — on a 20 000-photo library, ~334 of them per read.
     *
     * ⚠ Empty when the index could not be read. Callers that must tell "empty" from "could not
     * look" use their own read; this one is for the screens whose answer is the same either way.
     */
    suspend fun allPhotos(): List<Photo>

    /**
     * [allPhotos], but `null` when the library could not be read — never an empty or PARTIAL list
     * (`photos-app.md` §36): Collections must not publish years, places and people from
     * a walk that stopped half way. ⚠ Defaults to [allPhotos] so a fake that cannot fail needs nothing.
     */
    suspend fun allPhotosRead(): List<Photo>? = allPhotos()

    /**
     * Every label any model has attached to any file in the realm (`inference-service.md` §12).
     *
     * ⚠⚠ **Empty on every device today, and that is the honest answer rather than a bug.** The
     * proxy binds `InferenceBackendRegistry.Empty` (§17.4), so no pass has ever written a
     * `MediaLabel` anywhere — the rows arrive with a real backend, and the Things
     * section appears on its own when they do.
     *
     * ⚠ Returns the rows, not groups: the Collections tab already holds every photo from one
     * shared-index read, so the join and the grouping are arithmetic it can do without a second
     * walk ([groupPhotosByLabel]). A repository that grouped would have to read the library again.
     *
     * ⚠ Unlike `searchSemantic`, an empty answer here needs no status. An absent **index** is a
     * real "nothing has been labelled"; only an absent **model** is a refusal, and nothing on this
     * path asks a model anything.
     */
    suspend fun allLabels(): List<PhotoLabel>

    /** [allLabels], but `null` when the read FAILED — see [placesRead]. */
    suspend fun allLabelsRead(): List<PhotoLabel>? = allLabels()

    /**
     * The faces this device groups together, largest group first.
     *
     * ⚠⚠ Returns a status like [searchSemantic] and **unlike** [allLabels], and the difference is
     * the point. An absent *index* genuinely means "nothing has been labelled", so Things needs no
     * status; here the same empty list can mean "your library has no recognisable faces" OR "this
     * build has no calibrated threshold" OR "People is switched off" — three answers a caller must
     * not collapse, which is why `faceGroups` throws rather than returning nothing.
     *
     * ⚠ Returns paths, not photos: the Collections tab already holds every photo from one shared
     * index read, so resolving them is arithmetic it can do without a second walk — exactly the
     * argument [allLabels] makes.
     *
     * @param limit the maximum number of **groups**; all of a returned group's photographs are
     *   always included, so a caller never sees a partial person.
     */
    suspend fun faceGroups(limit: Int): Pair<List<FaceGroup>, PeopleStatus>

    /**
     * The `contentId` of every edited photo — the edit index's keys. `null` when it cannot be known.
     *
     * ⚠⚠ For a reader holding covers it did NOT map just now — the Collections snapshot, written
     * before the edit may have existed (`photos-editing.md` §12.13): a cover it names may be an
     * original that is now edited, and must not be drawn. A reader holding mapped [Photo]s reads
     * [Photo.isEdited]. ⚠ Cheap by construction — the index is a short list, no library walk — since
     * the snapshot exists for exactly the start when Control is slow.
     *
     * ⚠ Defaults to `null`, the fail-closed answer: a repository that cannot say draws no such cover.
     */
    suspend fun editedContentIds(): Set<String>? = null

    /**
     * Record that [filePaths] show a person called [name].
     *
     * ⚠⚠ **Takes the photographs, not a group id.** A group id is rebuilt per call and an index
     * pass can renumber it, so naming by id could attach the name to a different person entirely —
     * silently, and in synced data. The user named what they were looking at, so that is what
     * travels.
     *
     * ⚠⚠ **This is the only write in the whole People feature, and the only part of it that syncs.**
     * Templates and groups are device-local (IN-D5); a name is an ordinary `MediaLabel` and reaches
     * every device the user owns. Threat model A40.
     *
     * ⚠ Additive: naming a second person on a photograph leaves the first alone, and re-applying a
     * name folds rather than duplicating. Correcting a spelling therefore ADDS a row — a person-wide
     * rename is a separate operation and does not exist yet.
     *
     * @return what happened. ⚠⚠ A [NamingOutcome], not a boolean: a caller has to tell
     *   "switch it on" from "try again", and a single `false` forced one sentence to serve both.
     */
    suspend fun nameFaceGroup(filePaths: List<String>, name: String): NamingOutcome

    /**
     * Every "this face is not X" answered on this device (IN-6 F6c name suggestions) — what keeps
     * an answered *"Is this X?"* from coming back.
     *
     * ⚠⚠ `null` when it could not be read, and the caller then asks NOTHING: an empty answer read
     * as "nothing was rejected" would bring every answered question back. ⚠ Defaults to `null` —
     * a repository that cannot say offers no suggestion.
     */
    suspend fun faceSuggestionRejections(): List<app.tenshu.photos.model.FaceRejection>? = null

    /**
     * Record "these faces are not [name]" — the "No" to a suggestion. **Device-local**: it writes
     * no realm data and never syncs (`photos-people-and-pets.md` §6a F6c.9 D2).
     *
     * @return whether it was recorded. ⚠ Defaults to `false` — nothing recorded is never "saved".
     */
    suspend fun rejectNameSuggestion(name: String, faces: List<app.tenshu.photos.model.GroupFace>): Boolean = false
}

/**
 * One set of files whose bytes are identical, oldest first — so the copy most likely to be the
 * original leads, and "keep the first one" is a defensible default rather than an arbitrary one.
 */
/**
 * A photo's stored edit, as the editor re-opens it (`photos-editing.md` §12).
 *
 * ⚠ [recipe] is `null` when the row's recipe is one this build cannot read — a newer editor's
 * (ED-D9). The photo still shows its rendition; only re-editing is blocked.
 */
data class StoredEdit(
    val recipe: app.tenshu.photos.edit.EditRecipe?,
    val renditionPath: String,
    val redacted: Boolean,
)

data class DuplicateGroup(
    val contentHash: String,
    val photos: List<Photo>,
    /** Size of one copy, in bytes; `0` when the row carried none. */
    val sizeBytes: Long,
) {
    /** What deleting every copy but one would free. */
    val reclaimableBytes: Long get() = sizeBytes * (photos.size - 1).coerceAtLeast(0)
}

sealed interface DuplicatesResult {
    data class Groups(
        val groups: List<DuplicateGroup>,
        /** How many files the walk covered — the honest denominator for "we found N". */
        val filesScanned: Int,
    ) : DuplicatesResult

    data object Unreachable : DuplicatesResult
    data class Failed(val message: String) : DuplicatesResult
}

/**
 * What a device could say about how a photograph was taken.
 *
 * ⚠⚠ Four cases, and [NotOnDevice] is the one that earns the type. It is not a failure and must
 * never render as one: the file is fine, the realm is fine, and the bytes are simply somewhere else
 * — which is a thing the user can act on (fetch it) and a thing this app can say plainly. Folding it
 * into an error is the "not-connected is a failure, never an empty success" mistake pointed the
 * other way.
 */
sealed interface ExifReadResult {

    /** Parsed. ⚠ [media] may still be largely empty — a stripped photo genuinely says little. */
    data class Read(val media: MediaMetadata) : ExifReadResult

    /**
     * This device does not hold the original.
     *
     * ⚠ Says nothing about whether the realm does. The photograph is not missing; the bytes are
     * elsewhere, and fetching them is a choice the user gets to make rather than one a details
     * panel makes for them on mobile data.
     */
    data object NotOnDevice : ExifReadResult

    /** The proxy could not be reached — the same third case every other read here carries. */
    data object Unreachable : ExifReadResult

    /** The read or the parse failed. [message] is diagnostic, not user-facing copy. */
    data class Failed(val message: String) : ExifReadResult
}

/** One photo waiting in the trash, as the Trash screen shows it. */
data class TrashedItem(
    val contentId: String,
    val photo: Photo,
    val originalPath: String,
    val trashedAtMs: Long,
)

/** The trash read, with the same three cases as [PhotoPageResult] and for the same reason. */
sealed interface TrashResult {
    data class Items(val items: List<TrashedItem>) : TrashResult
    data object Unreachable : TrashResult
    data class Failed(val message: String) : TrashResult
}

/** One album as the Collections grid shows it. */
data class AlbumSummary(
    val albumId: String,
    val name: String,
    val createdAtMs: Long,
    val photoCount: Int,
    /** The cover's file path, or `null` when the album is empty or its cover cannot be resolved. */
    val coverPath: String?,
    /** The cover's stored pixel size, for its card's thumbnail class; see `Photo.pixelWidth`. */
    val coverPixelWidth: Int? = null,
    val coverPixelHeight: Int? = null,
    /** The cover photo's `contentId` — what a snapshot of this card checks against the edits (`photos-editing.md` §12.13). */
    val coverContentId: String? = null,
)

/** The album-list read, with the same three cases as [PhotoPageResult] and for the same reason. */
sealed interface AlbumsResult {
    data class Albums(val albums: List<AlbumSummary>) : AlbumsResult
    data object Unreachable : AlbumsResult
    data class Failed(val message: String) : AlbumsResult
}

/**
 * The result of a page read.
 *
 * ⚠⚠ Three cases, not two, and the third is the point: [Unreachable] is **not** an empty page.
 * A feed that renders "no photos yet" when the Control proxy simply is not running has told the
 * user their library is gone — the failure `ios-control-notifications-and-shares.md` names as
 * "not-connected is a failure, never an empty success", and the same reason `photosLike` throws
 * rather than returning an empty list.
 */
sealed interface PhotoPageResult {

    data class Page(
        val photos: List<Photo>,
        /**
         * `false` once a short page arrives: a page smaller than asked for is the last one, so the
         * feed stops paging instead of spending a round trip per scroll to learn nothing.
         */
        val hasMore: Boolean,
    ) : PhotoPageResult

    /** The proxy could not be reached. The shell says so and offers a retry. */
    data object Unreachable : PhotoPageResult

    /** The read failed for a reason worth showing. [message] is diagnostic, not user-facing copy. */
    data class Failed(val message: String) : PhotoPageResult
}

/**
 * A feed read that failed, carried through Paging's `LoadState.Error`.
 *
 * ⚠⚠ Exists because Paging's page result is `Page | Error` and [PhotoPageResult] is
 * `Page | Unreachable | Failed` — three states into two. The distinction cannot be dropped: "Control
 * is not running" and "your library is empty" look identical on screen and only one of them is
 * true, which is the whole reason [PhotoPageResult.Unreachable] exists. So it rides the exception
 * instead, and the screen reads [unreachable] off `LoadState.Error.error`.
 *
 * ⚠ Classified from the client's connection state, **never** by matching the error's text — a
 * proxy that is not connected answers with a `GenericError` whose message happens to say so, and
 * pinning behaviour to a diagnostic string is how a rename becomes a production-only regression.
 */
class FeedReadException(
    val unreachable: Boolean,
    val detail: String,
) : Exception(detail)

/**
 * What [PhotosRepository.preview] answered.
 *
 * ⚠⚠ [standIn] is `true` when the bytes are a smaller thumbnail class standing in for one the
 * request could use but this device could not get — the hero rung not minted yet (a backfill still
 * working through an old library) or not fetchable right now (`drive-synced-thumbnails.md` §16).
 * It is the right thing to *draw*, and the wrong thing to *remember*: like a `null`, it is a wait,
 * so a cache that kept it would hold a tile blurry after the better class arrived.
 */
class PhotoPreview(val bytes: ByteArray, val standIn: Boolean = false)
