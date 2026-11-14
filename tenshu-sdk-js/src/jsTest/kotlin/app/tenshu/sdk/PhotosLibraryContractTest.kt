package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.resolve
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.getOrNull
import org.tenshu.file.client.fileService
import org.tenshu.photo.PhotoFlags
import org.tenshu.photo.addToAlbum
import org.tenshu.photo.client.photoService
import org.tenshu.photo.contentId
import org.tenshu.photo.hidden
import org.tenshu.photo.setPhotoFlags
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Boundary-contract tests (`docs/testing.md` §9.3) for `Photos` (web-app-suite Wave 2, Photos run A),
 * against the **real** in-process runtime with seeded file rows.
 *
 * ⚠ The jsTest suite shares one `JsRuntime`, and the feed is the whole realm. So each test:
 *  - seeds its photographs in its own folder;
 *  - gives them capture times in its own random far-future window, which nothing else in the realm
 *    can sit inside;
 *  - asserts only on rows at or above that window's floor.
 *
 * Capture times are set through `uploadFile`'s `sourceModifiedAt`, which seeds `File.CreatedAt`.
 */
class PhotosLibraryContractTest : SdkRuntimeTest() {

    private fun stamp() = "${Date.now().toLong()}-${(0..999_999).random()}"

    /** A window start no other test (or real row) shares: somewhere in the 2100s–2400s. */
    private fun window(): Long = 4_102_444_800_000L + (0L..10_000_000_000_000L).random() / 1000 * 1000

    private suspend fun folder(): String {
        JsRuntime.awaitReady()
        val path = "/photos-test-${stamp()}"
        val made = JsRuntime.tenshuClient.fileService.defaultFileSystem.createFolder(path)
        assertTrue(made is TResult.Success<*>, "createFolder($path) failed: $made")
        return path
    }

    /** Uploads distinct bytes as an image captured at [capturedAt]; returns its path. */
    private suspend fun seed(folder: String, name: String, capturedAt: Long): String {
        val bytes = "photo $name ${stamp()}".encodeToByteArray()
        val r = JsRuntime.tenshuClient.fileService.defaultFileSystem
            .uploadFile(folder, name, bytes, sourceModifiedAt = capturedAt)
        assertTrue(r is TResult.Success<*>, "upload of $name failed: $r")
        return "$folder/$name"
    }

    private suspend fun ok(promise: kotlin.js.Promise<dynamic>, what: String): dynamic {
        val result = promise.await()
        assertEquals(true, result.ok, "$what must succeed; got ${JSON.stringify(result)}")
        return result
    }

    private suspend fun failureKind(promise: kotlin.js.Promise<dynamic>, what: String): String {
        val result = promise.await()
        assertEquals(false, result.ok, "$what must fail; got ${JSON.stringify(result)}")
        return result.error.kind as String
    }

    /**
     * Pages from [cursor] until a page reaches below [floor] or the feed ends, keeping only the rows
     * at or above [floor]: one list of paths per page.
     */
    private suspend fun pagesDownTo(cursor: String?, pageSize: Int, floor: Long): Pair<List<List<String>>, String?> {
        val pages = mutableListOf<List<String>>()
        var at = cursor
        while (true) {
            val page = ok(Photos.feedPage(at, pageSize), "feedPage")
            val photos = page.photos.unsafeCast<Array<dynamic>>()
            pages += photos.filter { (it.capturedAtMs as Double) >= floor }.map { it.path as String }
            val next = page.nextCursor as String?
            if (next == null || photos.any { (it.capturedAtMs as Double) < floor }) return pages to next
            at = next
        }
    }

    /** A cursor whose page starts with every row AT [ms] — a window top, not a served row. */
    private fun top(ms: Long): String = FeedCursor(ms, null).encode()

    private suspend fun feedPaths(top: Long, floor: Long, pageSize: Int = 3): List<String> =
        pagesDownTo(top(top), pageSize, floor).first.flatten()

    private suspend fun pagePaths(page: dynamic): List<String> =
        page.photos.unsafeCast<Array<dynamic>>().map { it.path as String }

    private suspend fun photoAt(path: String): dynamic = ok(Photos.photo(path), "photo($path)").photo

    // ⚠⚠ Never compare a SUSPEND call's `dynamic` result inline (`assertTrue(photoAt(x) != null)`):
    // Kotlin/JS miscompiles the dynamic comparison across the suspension point, the call never runs,
    // and the test dies in the coroutine machinery (`CoroutinesInternalError`) with no trace of why.
    // Bind the result to a `val` first. Found the hard way on this class.

    // ── the feed ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun theFeedIsNewestFirst_andKeepsEveryPhotographOfATie_withTheFullRowShape() = runTest {
        val dir = folder()
        val t = window()
        val newest = seed(dir, "a.jpg", t + 3)
        // Seeded out of path order: the tie run must come back by path, not by arrival.
        val tied = listOf(seed(dir, "d.jpg", t + 2), seed(dir, "b.jpg", t + 2), seed(dir, "c.jpg", t + 2))
        val oldest = seed(dir, "e.jpg", t + 1)

        val paths = feedPaths(top = t + 3, floor = t + 1, pageSize = 2)
        assertEquals(5, paths.size, "every seeded photograph, once; got $paths")
        assertEquals(newest, paths.first())
        assertEquals(oldest, paths.last())
        assertEquals(tied.sorted(), paths.subList(1, 4), "the tie run is between them, whole, by path")

        val row = photoAt(newest)
        assertEquals(newest, row.path)
        assertEquals("a.jpg", row.name)
        assertEquals((t + 3).toDouble(), row.capturedAtMs)
        assertEquals(false, row.isVideo)
        assertTrue((row.contentId as String).isNotEmpty(), "an upload is content-addressed")
        assertEquals(null, row.durationMs, "a still has no duration: null, not 0")
    }

    @Test
    fun pagingAcrossABoundaryInsideATie_givesDisjointCompletePages() = runTest {
        val dir = folder()
        val t = window()
        // Five photographs on ONE millisecond with pages of two: every boundary is inside the tie,
        // and the second page's cursor sits on the millisecond its whole page shares.
        val seeded = (1..5).map { seed(dir, "tie$it.jpg", t) }.toSet()
        val (pages, _) = pagesDownTo(top(t), pageSize = 2, floor = t)
        val all = pages.flatten()
        assertEquals(seeded, all.toSet(), "complete")
        assertEquals(all.size, all.toSet().size, "disjoint: no row served twice; pages $pages")
        assertTrue(pages.take(2).all { it.size == 2 }, "full pages while rows remain; pages $pages")
    }

    @Test
    fun aCursorStaysValidWhilePhotographsArrive() = runTest {
        val dir = folder()
        val t = window()
        val first = listOf(seed(dir, "p1.jpg", t + 50), seed(dir, "p2.jpg", t + 40))
        val rest = listOf(seed(dir, "p3.jpg", t + 30), seed(dir, "p4.jpg", t + 20))

        val head = ok(Photos.feedPage(top(t + 50), 2), "first page")
        assertEquals(first, head.photos.unsafeCast<Array<dynamic>>().map { it.path as String })
        val cursor = head.nextCursor as String

        // A sync lands one photograph newer than the cursor and one in the unread part.
        val newer = seed(dir, "new-top.jpg", t + 45)
        val older = seed(dir, "new-old.jpg", t + 25)

        val after = pagesDownTo(cursor, pageSize = 2, floor = t + 20).first.flatten()
        assertEquals(listOf(rest[0], older, rest[1]), after, "the unread part, in order, with the arrival")
        assertTrue(after.none { it in first }, "no row of the first page is served again")
        assertTrue(newer !in after, "a row newer than the cursor belongs to the head, not to later pages")
    }

    @Test
    fun aRowArrivingAtTheBoundaryMillisecond_neitherRepeatsNorSkipsARow() = runTest {
        val dir = folder()
        val t = window()
        val ties = (1..4).map { seed(dir, "tie$it.jpg", t) }
        val older = seed(dir, "older.jpg", t - 1)

        val first = ok(Photos.feedPage(top(t), 2), "first page")
        assertEquals(ties.take(2), pagePaths(first), "the tie run by path")
        val cursor = first.nextCursor as String

        // Two arrivals ON the boundary millisecond: one sorting before the last served row (it
        // belongs to the head) and one after it (it belongs to the unread part).
        val before = seed(dir, "tie0.jpg", t)
        val after = seed(dir, "tie2x.jpg", t)

        val rest = pagesDownTo(cursor, pageSize = 2, floor = t - 1).first.flatten()
        assertEquals(listOf(after, ties[2], ties[3], older), rest, "no row served twice, none skipped")
        assertTrue(before !in rest, "a tied row ordered before the cursor belongs to the head")
    }

    @Test
    fun trashingAnAlreadyServedTiedRow_skipsNoRow() = runTest {
        val dir = folder()
        val t = window()
        val ties = (1..4).map { seed(dir, "tie$it.jpg", t) }

        val first = ok(Photos.feedPage(top(t), 2), "first page")
        assertEquals(ties.take(2), pagePaths(first))
        ok(Photos.trash(ties[0]), "trash a served row")

        val rest = pagesDownTo(first.nextCursor as String, pageSize = 2, floor = t).first.flatten()
        assertEquals(ties.drop(2), rest, "every unread row of the tie, once")
    }

    @Test
    fun theHeadPage_fromANullCursor_isTheNewestRows_andItsCursorReachesTheRest() = runTest {
        val dir = folder()
        // ⚠ Above every other test's window (and every real row), so this test owns the head. Kept
        // under 2^53 so the capture time survives the trip through a JS number.
        val h = 8_000_000_000_000_000L + (0L..999_999L).random() * 1000
        val newest = seed(dir, "h1.jpg", h + 2)
        val tiedB = seed(dir, "h2b.jpg", h + 1)
        val tiedA = seed(dir, "h2a.jpg", h + 1)
        val below = seed(dir, "h3.jpg", h)

        val head = ok(Photos.feedPage(null, 2), "the head page")
        assertEquals(listOf(newest, tiedA), pagePaths(head), "the newest rows, ties by path")
        val emptyToken = ok(Photos.feedPage("", 2), "the head page from ''")
        assertEquals(pagePaths(head), pagePaths(emptyToken), "'' is the head too")

        val next = ok(Photos.feedPage(head.nextCursor as String, 2), "the page after the head")
        assertEquals(listOf(tiedB, below), pagePaths(next), "the head's cursor continues inside the tie")
    }

    @Test
    fun captureTimes_coverTheWholeFeed_newestFirst() = runTest {
        val dir = folder()
        val t = window()
        seed(dir, "x.jpg", t + 2)
        seed(dir, "y.jpg", t + 2)
        seed(dir, "z.jpg", t + 1)
        val times = ok(Photos.captureTimes(), "captureTimes").times.unsafeCast<Array<Double>>().toList()
        assertEquals(listOf((t + 2).toDouble(), (t + 2).toDouble(), (t + 1).toDouble()), times.filter { it >= t && it <= t + 2 })
        assertEquals(times.sortedDescending(), times, "ordered by the chain, newest first")
    }

    // L5 (web-apps-layout.md): the Videos collection is the same walk with one more filter — same
    // order, same cursor — and its times must map its pages index for index.
    @Test
    fun videosKind_holdsOnlyVideos_inFeedOrder_andItsTimesMatchItsPages() = runTest {
        val dir = folder()
        val t = window()
        seed(dir, "a.jpg", t + 4)
        val clip = seed(dir, "b.mp4", t + 3)
        seed(dir, "c.jpg", t + 2)
        val older = seed(dir, "d.mov", t + 1)
        val page = ok(Photos.feedPage(top(t + 4), 10, "videos"), "videos page")
        val inWindow = page.photos.unsafeCast<Array<dynamic>>().filter { (it.capturedAtMs as Double) >= t }
        assertEquals(listOf(clip, older), inWindow.map { it.path as String }, "only the videos, newest first")
        assertTrue(inWindow.all { it.isVideo as Boolean }, "every row is a video")

        val times = ok(Photos.captureTimes("videos"), "videos captureTimes").times.unsafeCast<Array<Double>>().toList()
        assertEquals(listOf((t + 3).toDouble(), (t + 1).toDouble()), times.filter { it >= t && it <= t + 4 })

        // The default is still the whole library (photos AND videos).
        val all = ok(Photos.feedPage(top(t + 4), 10), "library page").photos.unsafeCast<Array<dynamic>>()
        assertEquals(4, all.count { (it.capturedAtMs as Double) >= t })
    }

    @Test
    fun anUnknownFeedKindIsATypedFailure_neverEverything() = runTest {
        assertEquals("error_photos_bad_kind", failureKind(Photos.feedPage(null, 10, "images"), "bad kind"))
        assertEquals("error_photos_bad_kind", failureKind(Photos.captureTimes("images"), "bad kind times"))
    }

    @Test
    fun aMalformedCursorOrPageSizeIsATypedFailure() = runTest {
        assertEquals("error_photos_bad_cursor", failureKind(Photos.feedPage("nonsense", 10), "bad cursor"))
        assertEquals("error_photos_bad_page_size", failureKind(Photos.feedPage(null, 0), "page 0"))
        assertEquals("error_photos_bad_page_size", failureKind(Photos.feedPage(null, 501), "page 501"))
    }

    // ── albums ────────────────────────────────────────────────────────────────────────────────

    private suspend fun album(albumId: String): dynamic =
        ok(Photos.albums(), "albums").albums.unsafeCast<Array<dynamic>>().firstOrNull { it.albumId == albumId }

    private suspend fun albumPaths(albumId: String): List<String> =
        ok(Photos.albumPhotos(albumId), "albumPhotos").photos.unsafeCast<Array<dynamic>>().map { it.path as String }

    @Test
    fun anAlbumIsCreatedFilledRenamedEmptiedAndDeleted() = runTest {
        val dir = folder()
        val t = window()
        val a = seed(dir, "a.jpg", t + 1)
        val b = seed(dir, "b.jpg", t + 2)
        val name = "Holiday ${stamp()}"

        val albumId = ok(Photos.createAlbum(name), "createAlbum").albumId as String
        val created = assertNotNull(album(albumId), "a created album is listed")
        assertEquals(name, created.name)
        assertEquals(0, created.photoCount)
        assertEquals(null, created.coverPath, "an empty album has no cover: null")
        assertTrue((created.createdAtMs as Double) > 1_000_000_000_000.0, "stamped from the wall clock, not 1970")

        ok(Photos.addToAlbum(albumId, a), "add a")
        ok(Photos.addToAlbum(albumId, b), "add b")
        ok(Photos.addToAlbum(albumId, a), "re-add a") // not a reorder
        assertEquals(listOf(a, b), albumPaths(albumId), "the user's order: the order added")
        assertEquals(2, album(albumId).photoCount)
        assertEquals(a, album(albumId).coverPath, "no chosen cover: the first entry")
        val aId = photoAt(a).contentId as String
        assertEquals(listOf(albumId), ok(Photos.albumsFor(aId), "albumsFor").albums.unsafeCast<Array<dynamic>>().map { it.albumId as String })

        ok(Photos.renameAlbum(albumId, "Renamed"), "rename")
        assertEquals("Renamed", album(albumId).name)
        assertEquals(listOf(a, b), albumPaths(albumId), "a rename keeps the entries")

        assertEquals(true, ok(Photos.removeFromAlbum(albumId, aId), "remove a").changed)
        assertEquals(false, ok(Photos.removeFromAlbum(albumId, aId), "remove a again").changed, "a no-op is visible")
        assertEquals(listOf(b), albumPaths(albumId))

        ok(Photos.deleteAlbum(albumId), "delete")
        assertEquals(null, album(albumId), "a deleted album leaves the list")
        assertEquals("error_photos_unknown_album", failureKind(Photos.albumPhotos(albumId), "photos of a deleted album"))
        val kept: Any? = photoAt(b)
        assertNotNull(kept, "deleting an album touches no photograph")
    }

    @Test
    fun observeAlbumsPushesARename_notOnlyMembershipChanges() = runTest {
        val albumId = ok(Photos.createAlbum("Before ${stamp()}"), "createAlbum").albumId as String
        val seen = mutableListOf<String>()
        val subscribed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val renamed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stop = Photos.observeAlbums({ result ->
            val row = (result.albums as Array<dynamic>?)?.firstOrNull { it.albumId == albumId }
            val name = row?.name as String?
            if (name != null) seen += name
            if (name != null && name.startsWith("Before")) subscribed.complete(Unit)
            if (name == "After") renamed.complete(Unit)
        }, { message -> renamed.completeExceptionally(AssertionError("stream ended: $message")) })
        try {
            // ⚠ Real time, not virtual: the push crosses a JS dispatcher the test scheduler cannot advance.
            // ⚠⚠ Rename only AFTER the first emission, or that first snapshot already carries the new
            // name and the test passes with no push at all.
            val pushed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    subscribed.await()
                    ok(Photos.renameAlbum(albumId, "After"), "rename")
                    renamed.await()
                    true
                }
            }
            assertEquals(true, pushed, "a rename must reach an observeAlbums subscriber; saw $seen")
        } finally {
            stop()
        }
    }

    // ── favourites ────────────────────────────────────────────────────────────────────────────

    private suspend fun favouritePaths(): List<String> =
        ok(Photos.favourites(), "favourites").photos.unsafeCast<Array<dynamic>>().map { it.path as String }

    @Test
    fun aFavouriteTogglesOnAndOff() = runTest {
        val dir = folder()
        val path = seed(dir, "fav.jpg", window())
        val id = photoAt(path).contentId as String

        assertEquals(false, ok(Photos.isFavourite(id), "isFavourite").favourite)
        ok(Photos.setFavourite(id, true), "favourite")
        assertEquals(true, ok(Photos.isFavourite(id), "isFavourite").favourite)
        assertTrue(path in favouritePaths())

        ok(Photos.setFavourite(id, false), "unfavourite")
        assertEquals(false, ok(Photos.isFavourite(id), "isFavourite").favourite)
        assertTrue(path !in favouritePaths())
    }

    @Test
    fun aFavouriteWritePassesHiddenThrough_neverUnhides() = runTest {
        val dir = folder()
        val id = photoAt(seed(dir, "hidden.jpg", window())).contentId as String
        val service = JsRuntime.tenshuClient.photoService
        assertTrue(service.setPhotoFlags(id, favourite = false, hidden = true) is TResult.Success<*>)

        ok(Photos.setFavourite(id, true), "favourite a hidden photo")

        val row = service.photoFlags
            .select { fields(PhotoFlags.Field.CONTENT_ID, PhotoFlags.Field.FAVOURITE, PhotoFlags.Field.HIDDEN) }
            .getAll().getOrNull()?.toList()?.firstOrNull { it.contentId.get() == id }
        assertEquals(true, assertNotNull(row).hidden.get(), "the hidden flag survives a favourite")
    }

    // ── trash ─────────────────────────────────────────────────────────────────────────────────

    private suspend fun trashedIds(): List<String> =
        ok(Photos.trashed(), "trashed").items.unsafeCast<Array<dynamic>>().map { it.contentId as String }

    @Test
    fun aTrashedPhotographLeavesTheFeed_andRestoreBringsItBack() = runTest {
        val dir = folder()
        val t = window()
        val keep = seed(dir, "keep.jpg", t + 2)
        val gone = seed(dir, "gone.jpg", t + 1)
        val id = photoAt(gone).contentId as String

        ok(Photos.trash(gone), "trash")
        assertEquals(listOf(keep), feedPaths(top = t + 2, floor = t + 1), "the trash is outside the feed")
        assertEquals(null, photoAt(gone), "nothing at the original path")
        val item = ok(Photos.trashed(), "trashed").items.unsafeCast<Array<dynamic>>().first { it.contentId == id }
        assertEquals(gone, item.originalPath)
        assertEquals("/Trash/$id-gone.jpg", item.photo.path)

        ok(Photos.restore(id), "restore")
        assertEquals(listOf(keep, gone), feedPaths(top = t + 2, floor = t + 1), "back in the feed, in its place")
        assertTrue(id !in trashedIds(), "the record is cleared")
    }

    @Test
    fun deleteForeverRemovesTheFileAndTheRecord() = runTest {
        val dir = folder()
        val path = seed(dir, "bye.jpg", window())
        val id = photoAt(path).contentId as String
        ok(Photos.trash(path), "trash")

        ok(Photos.deleteForever(id), "deleteForever")
        assertTrue(id !in trashedIds())
        assertEquals(null, photoAt("/Trash/$id-bye.jpg"), "the trashed file is gone")
        assertEquals("error_photos_not_in_trash", failureKind(Photos.deleteForever(id), "twice"))
    }

    @Test
    fun aFailedDeleteForever_keepsTheRecord_soTheFileStaysListedAndARetryWorks() = runTest {
        val dir = folder()
        val path = seed(dir, "stuck.jpg", window())
        val id = photoAt(path).contentId as String
        ok(Photos.trash(path), "trash")
        val trashPath = "/Trash/$id-stuck.jpg"

        val real = Photos.deleteFile
        Photos.deleteFile = {
            TResult.Failure.create<Data?>(JsRuntime.tContext, GenericError.create(JsRuntime.tContext, "store refused"))
        }
        try {
            assertEquals("error_generic", failureKind(Photos.deleteForever(id), "a delete the store refuses"))
        } finally {
            Photos.deleteFile = real
        }
        assertTrue(id in trashedIds(), "the record survives a failed delete, so the trash still lists it")
        val stillThere: Any? = photoAt(trashPath)
        assertNotNull(stillThere, "the file was not deleted")

        ok(Photos.deleteForever(id), "the retry")
        assertTrue(id !in trashedIds())
    }

    @Test
    fun aTrashedPathCannotBeTrashedAgainOrFiled_andRestoreStillGoesHome() = runTest {
        val dir = folder()
        val path = seed(dir, "twice.jpg", window())
        val id = photoAt(path).contentId as String
        ok(Photos.trash(path), "trash")
        val trashPath = "/Trash/$id-twice.jpg"

        assertEquals("error_photos_not_a_photo", failureKind(Photos.trash(trashPath), "re-trash"))
        val albumId = ok(Photos.createAlbum("Guard ${stamp()}"), "createAlbum").albumId as String
        assertEquals("error_photos_not_a_photo", failureKind(Photos.addToAlbum(albumId, trashPath), "file a trashed path"))

        val item = ok(Photos.trashed(), "trashed").items.unsafeCast<Array<dynamic>>().first { it.contentId == id }
        assertEquals(path, item.originalPath, "the record still knows the way home")
        ok(Photos.restore(id), "restore")
        val home: Any? = photoAt(path)
        assertNotNull(home, "restored to the original path")
    }

    @Test
    fun aNonMediaFileIsNeverAPhotograph_inTheFeedAlbumsOrFavourites() = runTest {
        val dir = folder()
        val t = window()
        val photo = seed(dir, "real.jpg", t + 3)
        val txt = seed(dir, "notes.txt", t + 2)
        val pdf = seed(dir, "scan.pdf", t + 1)

        assertEquals(listOf(photo), feedPaths(top = t + 3, floor = t + 1), "the feed is media only")
        assertEquals(null, photoAt(txt), "a .txt is no photograph")
        assertEquals("error_photos_not_a_photo", failureKind(Photos.trash(txt), "trash a .txt"))
        val albumId = ok(Photos.createAlbum("Docs ${stamp()}"), "createAlbum").albumId as String
        assertEquals("error_photos_not_a_photo", failureKind(Photos.addToAlbum(albumId, pdf), "file a .pdf"))

        // Filed and favourited behind the surface's back (another client, or a row from before the
        // guard): still never shown.
        val fs = JsRuntime.tenshuClient.fileService.defaultFileSystem
        val pdfId = assertNotNull(
            fs.root.files.navigateTo(dir).search<File> { this.path equalTo pdf }
                .select { fields(File.Field.PATH); relations(File.ContentId) }
                .resolve().getOrNull()?.get(File.ContentId)?.get()?.takeIf { it.isNotEmpty() },
            "the .pdf upload is content-addressed",
        )
        val service = JsRuntime.tenshuClient.photoService
        assertTrue(service.addToAlbum(albumId, pdfId, pdf) is TResult.Success<*>)
        assertTrue(service.setPhotoFlags(pdfId, favourite = true, hidden = false) is TResult.Success<*>)
        val filed = albumPaths(albumId)
        assertEquals(emptyList<String>(), filed, "an album shows no .pdf")
        val favourites = favouritePaths()
        assertTrue(pdf !in favourites, "favourites show no .pdf")
    }

    @Test
    fun aVideoCarriesItsDuration_andAFavouritedVideoIsAFavourite() = runTest {
        val dir = folder()
        val t = window()
        val bytes = mp4(durationUnits = 4_200, timescale = 1_000)
        val r = JsRuntime.tenshuClient.fileService.defaultFileSystem.uploadFile(dir, "clip.mp4", bytes, sourceModifiedAt = t)
        assertTrue(r is TResult.Success<*>, "upload of clip.mp4 failed: $r")
        val path = "$dir/clip.mp4"

        assertEquals(listOf(path), feedPaths(top = t, floor = t), "a video is in the feed")
        val row = photoAt(path)
        assertEquals(true, row.isVideo)
        assertEquals(4_200.0, row.durationMs, "the container's duration, in ms")
        val details = ok(Photos.details(path), "details").details
        assertEquals(4_200.0, details.durationMs)

        // ⚠ Divergence from native (images only), on purpose: `photos-app.md` §42.6.
        ok(Photos.setFavourite(row.contentId as String, true), "favourite the video")
        val favourites = favouritePaths()
        assertTrue(path in favourites, "a favourited video appears in favourites")
    }

    /** The smallest MP4 the metadata parser reads a duration from: `ftyp`, then `moov` > `mvhd` v0. */
    private fun mp4(durationUnits: Int, timescale: Int): ByteArray {
        val out = mutableListOf<Byte>()
        fun be32(v: Int) { for (shift in intArrayOf(24, 16, 8, 0)) out += ((v ushr shift) and 0xFF).toByte() }
        fun ascii(text: String) { for (c in text) out += c.code.toByte() }
        fun zeros(n: Int) { repeat(n) { out += 0 } }
        be32(16); ascii("ftyp"); ascii("isom"); be32(0)
        val mvhdSize = 8 + 4 + 8 + 4 + 4 + 80
        be32(8 + mvhdSize); ascii("moov")
        be32(mvhdSize); ascii("mvhd")
        zeros(4)          // version 0 + flags
        zeros(8)          // creation + modification
        be32(timescale)
        be32(durationUnits)
        zeros(80)         // rate, volume, matrix, predefined, next_track_ID
        return out.toByteArray()
    }

    // ── not found ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aMissingIdIsNullForAReadAndATypedFailureForAnOp() = runTest {
        val missing = "/no-such-folder-${stamp()}/x.jpg"
        // Reads of one thing: an answer, `ok` with null — for a missing folder AND a missing file.
        assertEquals(null, photoAt(missing))
        assertEquals(null, photoAt("${folder()}/absent.jpg"))
        val details = ok(Photos.details(missing), "details")
        assertTrue(details.details === null, "details: exactly null")
        val clip = ok(Photos.motionClip(missing), "motionClip")
        assertTrue(clip.bytes === null, "motionClip: exactly null")

        // Ops on a target that does not exist: a typed failure, never a silent success.
        val unknown = "album-0-nope-${stamp()}"
        assertEquals("error_photos_unknown_album", failureKind(Photos.albumPhotos(unknown), "albumPhotos"))
        assertEquals("error_photos_unknown_album", failureKind(Photos.renameAlbum(unknown, "x"), "renameAlbum"))
        assertEquals("error_photos_unknown_album", failureKind(Photos.deleteAlbum(unknown), "deleteAlbum"))
        assertEquals("error_photos_unknown_album", failureKind(Photos.addToAlbum(unknown, missing), "addToAlbum"))
        val real = ok(Photos.createAlbum("Real ${stamp()}"), "createAlbum").albumId as String
        assertEquals("error_photos_not_found", failureKind(Photos.addToAlbum(real, missing), "add a missing photo"))
        assertEquals("error_photos_not_found", failureKind(Photos.trash(missing), "trash a missing photo"))
        assertEquals("error_photos_not_in_trash", failureKind(Photos.restore("nope-${stamp()}"), "restore"))
        assertEquals("error_photos_no_content_id", failureKind(Photos.setFavourite("", true), "favourite no id"))
    }

    @Test
    fun detailsCarryTheStoredFields() = runTest {
        val dir = folder()
        val t = window()
        val path = seed(dir, "d.jpg", t)
        val details = assertNotNull(ok(Photos.details(path), "details").details)
        assertEquals(path, details.path)
        assertEquals(t.toDouble(), details.capturedAtMs)
        assertTrue((details.sizeBytes as Double) > 0.0, "the upload's size")
    }
}
