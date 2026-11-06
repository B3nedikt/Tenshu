@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.MutableLibraryWatch
import kotlin.concurrent.AtomicInt
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.PhotoPreview
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.data.PhotosRepositoryDecorator
import app.tenshu.photos.model.Photo
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSLock
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import platform.posix.CLOCK_THREAD_CPUTIME_ID
import platform.posix.clock_gettime
import platform.posix.timespec
import platform.Foundation.NSString
import platform.Foundation.create
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.drawAtPoint
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill
import org.tenshu.core.paging.seekablePagingConfig

/**
 * The `-photosFixture` render probe's library (`photos-ios.md` §8.3 step 6) — **DEV_HARNESS only**;
 * no shipping path calls [PhotosSurface.installScreenshotFixture].
 *
 * It exists because a simulator realm has no photographs and `simctl` cannot tap through
 * onboarding: without it the iPad capture is either the wall or an empty grid, neither of which says
 * anything about the layout. The feed, paging and headers are [MockPhotosRepository]'s — which pages
 * exactly as the real one does — so the probe exercises the shipping views over a real paging path;
 * only the bytes are drawn here, because the mock's are empty.
 *
 * ⚠ ~1 500 photographs in bursty days (3–25 a day, 25 min apart) over ~4 months across a year
 * boundary: enough that Day, Month and Year each span several screens — a re-anchor can only be
 * seen when the target is not already on the first screen — and a tablet row fills. Each tile is
 * drawn with its own library index (#57), so a screenshot shows WHICH photograph is on top.
 */
internal object PhotosScreenshotFixture {

    /** 1 500, or `-photosLibrarySize <n>` — a long library with a small loaded window (§8.20). */
    private val COUNT: Int = NSProcessInfo.processInfo.arguments.map { it.toString() }.let { args ->
        args.getOrNull(args.indexOf("-photosLibrarySize") + 1)?.takeIf { "-photosLibrarySize" in args }?.toIntOrNull()
    } ?: 1_500
    private const val WITHIN_DAY_MS = 25L * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** 2025-01-20 21:00 UTC — the newest photograph; the rest run back into autumn 2024. */
    private const val NEWEST_MS = 1_737_406_800_000L

    /**
     * [motionClip] turns every seventh photograph (#5, #12, …) into a video holding that clip — the
     * `-photosMotion` probe. `null` keeps the plain fixture.
     */
    fun repository(motionClip: ByteArray? = null): PhotosRepository {
        val photos = ArrayList<Photo>(COUNT)
        var day = 0
        while (photos.size < COUNT) {
            val inDay = 3 + (day * 5) % 23
            for (k in 0 until inDay) {
                if (photos.size == COUNT) break
                val i = photos.size
                photos += Photo(
                    path = "/Backup/iPhone/All Photos/IMG_${10_000 + i}.jpg",
                    capturedAtMs = NEWEST_MS - day * DAY_MS - k * WITHIN_DAY_MS,
                    isVideo = motionClip != null && i % 7 == 5,
                    contentId = "fixture-$i",
                    contentHash = duplicateHash(i),
                    // Alternate landscape and portrait, so the viewer's letterboxing shows too.
                    pixelWidth = if (i % 3 == 0) 3024 else 4032,
                    pixelHeight = if (i % 3 == 0) 4032 else 3024,
                )
            }
            day++
        }
        val clips = if (motionClip == null) emptyMap() else photos.filter { it.isVideo }.associate { it.path to motionClip }
        // ⚠ Watching from the start, as route 1 is once the Library is up: the fixture says "changed"
        // itself, after each of its writes and each arrival (`photos-ios.md` §8.22.3).
        val watch = MutableLibraryWatch(watching = true)
        val arriving = if (NSProcessInfo.processInfo.arguments.contains("-photosArrivals")) Arrivals(photos, watch) else null
        arrivals = arriving
        // ⚠⚠ Outermost first: a call is NOTED and HELD on the thread that made it — that is the whole
        // measurement — and only then crosses onto the fixture's one lane. The other way round the
        // lane would take the call off the caller before anything could see where it came from.
        return Held(Confined(watch, FixtureRepository(
            arriving,
            MockPhotosRepository(photos = photos, motionClips = clips).apply {
                this.watch = watch
                // Tags another device's model wrote and sync delivered (AI-R) — search finds them, and
                // the Collections tab gets its Things row.
                DOGS.forEach { label(photos[it].path, "dog") }
                label(photos[BEACH].path, "beach")
            },
            family = photos.take(FAMILY_SIZE),
            trashed = photos.subList(TRASHED_FROM, TRASHED_FROM + TRASHED_SIZE),
        )))
    }

    // ── where a call ran (`photos-ios.md` §8.22.2) ──────────────────────────────────────────────────

    /**
     * `-photosHeldReads <ms>`: every read and write costs that much **on the thread that calls it**
     * before it answers — and, flag or no flag, each one made ON THE MAIN THREAD is counted
     * ([callsOnMain]).
     *
     * ⚠⚠ This is what the mock could not show. It answers from memory, so a screen that ran its
     * reads on the main thread cost nothing here and seconds on a phone, where the route-1 runtime's
     * reads are store queries on the caller's thread. ⚠ CPU burned, not a sleep: the probe reads the
     * main thread's CPU time, which a sleeping thread does not spend.
     * ⚠ The tile, hero and clip reads are counted but not held: they were never the caller's, and a
     * grid asks for hundreds.
     */
    private class Held(inner: PhotosRepository) : PhotosRepositoryDecorator(inner) {
        override suspend fun <T> call(name: String, block: suspend () -> T): T {
            arrived(name)
            return block()
        }

        /**
         * ⚠ A stream is a call too, made where it is COLLECTED: without this the hero's read would go
         * through uncounted, and "none" could not say otherwise.
         */
        override fun <T> stream(name: String, flow: Flow<T>): Flow<T> = flow.onStart { arrived(name) }
    }

    private val UNHELD = setOf("preview", "viewerPreview", "ensureViewerRung", "motionClip")

    private val heldReadMs: Int = NSProcessInfo.processInfo.arguments.map { it.toString() }.let { args ->
        args.getOrNull(args.indexOf("-photosHeldReads") + 1)?.takeIf { "-photosHeldReads" in args }?.toIntOrNull()
    } ?: 0

    private val onMainLock = NSLock()
    private val onMain = ArrayList<String>()

    private val walkCount = AtomicInt(0)

    /** How many walks of the library the fixture has served since launch — `allPhotosRead` calls. */
    fun walks(): Int = walkCount.value

    /** A repository call arriving, on whatever thread is making it. */
    private fun arrived(name: String) {
        if (name == "allPhotosRead") walkCount.incrementAndGet()
        if (NSThread.isMainThread) {
            onMainLock.lock()
            onMain += name
            onMainLock.unlock()
        }
        if (heldReadMs <= 0 || name in UNHELD) return
        val until = threadCpuNanos() + heldReadMs * 1_000_000L
        var spin = 0L
        while (threadCpuNanos() < until) spin++
    }

    /**
     * Every fixture call made on the main thread since launch, by name, in order — what
     * `ReadsOffMainUITests` asserts is empty. ⚠ A count, not a time: it does not move with the load
     * on the machine, and one call is enough to fail.
     */
    fun callsOnMain(): List<String> {
        onMainLock.lock()
        try {
            return onMain.toList()
        } finally {
            onMainLock.unlock()
        }
    }

    private fun threadCpuNanos(): Long = memScoped {
        val time = alloc<timespec>()
        clock_gettime(CLOCK_THREAD_CPUTIME_ID.toUInt(), time.ptr)
        time.tv_sec * 1_000_000_000L + time.tv_nsec
    }

    /**
     * The fixture's state on ONE lane. ⚠⚠ The mock under it is plain maps and lists, and `seed()` is a
     * check-then-write: it is safe only with one call at a time. The shell's repository makes its calls
     * from a pool (`OffCallerPhotosRepository`), so the fixture confines itself — never more than one
     * call running; a call that suspends (the slow edits, the slow search) lets the next through, as
     * it would on the main thread.
     * ⚠ The byte reads are left where they are called: they draw, and touch no state.
     */
    private class Confined(private val watch: MutableLibraryWatch, inner: PhotosRepository) : PhotosRepositoryDecorator(inner) {
        private val lane = Dispatchers.Default.limitedParallelism(1)

        /** ⚠ AFTER the write, as a store's own signal is: a reader that began before it must not be called clean. */
        override suspend fun <T> write(name: String, block: suspend () -> T): T =
            call(name, block).also { watch.changed() }

        override suspend fun <T> call(name: String, block: suspend () -> T): T =
            if (name in UNHELD) block() else withContext(lane) { block() }

        /** The one stream is the hero's bytes: drawn, no state — left where it is collected. */
        override fun <T> stream(name: String, flow: Flow<T>): Flow<T> = flow
    }

    /** The `-photosArrivals` library, when that flag is set — what [PhotosSurface.fixtureArrive] adds to. */
    var arrivals: Arrivals? = null
        private set

    /**
     * `-photosArrivals`: a library that GROWS at its head while the Library is open — a sync, as the
     * grid sees one (`photos-ios.md` §8.20). Each [arrive] adds photographs newer than every other
     * (20 min apart, so the tenth opens a new day) and invalidates the pager, which is what a
     * realm change does to the real one: a new generation, loaded around where the reader is, with
     * placeholders for the rest.
     *
     * ⚠ A positional source, not the keyset one — the mock's whole list is in memory. It shares the
     * real feed's `seekablePagingConfig`, so the window, the placeholders and the jump are Paging's
     * own; what it cannot show is the real read's latency.
     * ⚠ An arrival is drawn `#-1`, `#-2`, …: the tile's number is its path's, and these are newer than #0.
     */
    class Arrivals(initial: List<Photo>, private val watch: MutableLibraryWatch) {
        private var all = initial
        private var arrived = 0
        private var source: PagingSource<Int, Photo>? = null

        fun arrive(count: Int) {
            val added = List(count) {
                arrived++
                Photo(
                    path = "/Backup/iPhone/All Photos/IMG_${10_000 - arrived}.jpg",
                    capturedAtMs = NEWEST_MS + arrived * 20L * 60 * 1000,
                    isVideo = false,
                    contentId = "fixture-arrival-$arrived",
                    pixelWidth = 4032,
                    pixelHeight = 3024,
                )
            }
            all = added.reversed() + all
            source?.invalidate()
            watch.changed()
        }

        fun paging(pageSize: Int, readContext: CoroutineContext): Flow<PagingData<Photo>> =
            Pager(seekablePagingConfig(pageSize = pageSize)) { Source(all, readContext).also { source = it } }.flow

        private class Source(
            private val photos: List<Photo>,
            private val readContext: CoroutineContext,
        ) : PagingSource<Int, Photo>() {
            override val jumpingSupported: Boolean get() = true

            override fun getRefreshKey(state: PagingState<Int, Photo>): Int? =
                state.anchorPosition?.let { (it - state.config.initialLoadSize / 2).coerceAtLeast(0) }

            /** ⚠ In [readContext], and held there — a page is a read like any other (§8.22.2). */
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Photo> = withContext(readContext) {
                arrived("feedPaging.load")
                page(params)
            }

            private fun page(params: LoadParams<Int>): LoadResult<Int, Photo> {
                val key = params.key ?: 0
                val from = if (params is LoadParams.Prepend) (key - params.loadSize).coerceAtLeast(0) else key
                val to = (if (params is LoadParams.Prepend) key else key + params.loadSize).coerceAtMost(photos.size)
                return LoadResult.Page(
                    data = photos.subList(from.coerceAtMost(to), to),
                    prevKey = from.takeIf { it > 0 },
                    nextKey = to.takeIf { it < photos.size },
                    itemsBefore = from.coerceAtMost(to),
                    itemsAfter = photos.size - to,
                )
            }
        }
    }

    /**
     * Three sets of "identical" photographs, for Duplicates (`photos-ios.md` §8.18): **100–102** (three
     * copies), **200–201**, and **302–303** — where 303, a fourth photograph, REFUSES both the trash
     * and the permanent delete, so a keep-only of 302 is the refusal the screen must say.
     * ⚠ The tiles still draw their own numbers: the fixture's bytes say WHICH copy is which.
     */
    private fun duplicateHash(i: Int): String = when (i) {
        100, 101, 102 -> "fixture-duplicate-a"
        200, 201 -> "fixture-duplicate-b"
        302, 303 -> "fixture-duplicate-c"
        else -> ""
    }

    /** The photographs tagged "dog", and the one tagged "beach" — see `FixtureRepository`'s search. */
    private val DOGS = listOf(10, 11, 12)
    private const val BEACH = 20

    /** The one query `-photosSlowSearch` slows. */
    private const val SLOW_QUERY = "1004"

    /** How many photographs the "Family" album holds — the newest ones, #0 to #5. */
    private const val FAMILY_SIZE = 6

    /**
     * The trash holds eight photographs, 1490 to 1497 (`photos-ios.md` §8.17) — so the Trash screen
     * has something in it on launch, and two of them (1491, 1495) REFUSE restore and delete: every
     * fourth photograph refuses every trash write here, as it refuses trashing.
     */
    private const val TRASHED_FROM = 1_490
    private const val TRASHED_SIZE = 8

    /**
     * ⚠ Two albums, and a trash that fails for every fourth photograph (#3, #7, …): the selection's
     * partial-failure report — the case it exists for — cannot be shown by a mock where every write
     * succeeds (photos-ios.md §8.9).
     *
     * ⚠ The albums differ on purpose (§8.16): **"Family"** is a real album in the mock — six
     * photographs, and rename, delete and remove all work on it — while **"Holiday"** is a fixed row
     * the mock does not know, so it reads empty and REFUSES every edit: the refusal alerts are shown
     * by the same fixture the successes are.
     */
    private class FixtureRepository(
        private val arrivals: Arrivals?,
        private val mock: MockPhotosRepository,
        private val family: List<Photo>,
        private val trashed: List<Photo>,
    ) : PhotosRepository by mock {
        private var seeded = false

        override fun feedPaging(pageSize: Int, readContext: CoroutineContext): Flow<PagingData<Photo>> =
            arrivals?.paging(pageSize, readContext) ?: mock.feedPaging(pageSize, readContext)

        /**
         * `-photosSlowEdits`: an album's rename, delete and removal land 6 s late — later than a user's
         * Back, so the Collections tab's return refresh reads BEFORE the edit.
         * ⚠ 6 s, not 3: XCUITest's Back-and-settle can outlast 3 s, and the test's precondition (the
         * card still showing the old name) then fails — as for the viewer rung (§8.13).
         */
        private val slowEdits = NSProcessInfo.processInfo.arguments.contains("-photosSlowEdits")

        private suspend fun <T> edit(write: suspend () -> T): T {
            seed()
            if (slowEdits) delay(6_000)
            return write()
        }

        /** Files "Family" into the mock on the first album call — the mock's writes are suspend. */
        private suspend fun seed() {
            if (seeded) return
            seeded = true
            val id = mock.createAlbum("Family") ?: return
            family.forEach { mock.addToAlbum(id, it) }
            trashed.forEach { mock.trash(it) }
        }

        // ── search (`photos-ios.md` §8.19) ──────────────────────────────────────────────────────────
        //
        // By NAME every photograph is `IMG_1xxxx.jpg`: "IMG_10042" finds #42 alone, "1004" finds
        // #40–#49. By TAG, #10–#12 are "dog" and #20 is "beach" (planted in `repository()` — synced
        // `MediaLabel` rows, which iOS reads with no model: AI-R). "giraffe" finds nothing. A trashed
        // photograph (1490–1497) is found by neither.
        //
        // ⚠ Overridden, not inherited: `by mock` forwards the interface's DEFAULT `…Read` methods to
        // the mock too, so the flags below would never be reached through `searchByName`.

        /** `-photosSearchFails`: the FIRST name search could not look; asking again works. */
        private var searchFailuresLeft =
            if (NSProcessInfo.processInfo.arguments.contains("-photosSearchFails")) 1 else 0

        /**
         * `-photosSlowSearch`: the name search for exactly [SLOW_QUERY] answers 6 s late — after a
         * newer, longer query typed meanwhile has been answered. An older answer published over a
         * newer one would then be on screen for a UI test to see.
         */
        private val slowSearch = NSProcessInfo.processInfo.arguments.contains("-photosSlowSearch")

        override suspend fun searchByNameRead(query: String): List<Photo>? {
            seed()
            if (searchFailuresLeft > 0) {
                searchFailuresLeft--
                return null
            }
            if (slowSearch && query == SLOW_QUERY) delay(6_000)
            return mock.searchByName(query)
        }

        override suspend fun searchByLabelRead(query: String): List<Pair<Photo, String>>? {
            seed()
            return mock.searchByLabel(query)
        }

        /** Every fourth photograph (#3, #7, …) refuses every trash write — see the class KDoc. */
        private fun refuses(contentId: String) = contentId.removePrefix("fixture-").toIntOrNull()?.rem(4) == 3

        override suspend fun trashed() = seed().let { mock.trashed() }

        override suspend fun restore(contentId: String) = edit { !refuses(contentId) && mock.restore(contentId) }

        override suspend fun deleteForever(contentId: String) =
            edit { !refuses(contentId) && mock.deleteForever(contentId) }

        /** The mock's albums — Family, and any "New album" made — plus the fixed, refusing Holiday. */
        override suspend fun albums(): AlbumsResult {
            seed()
            val created = (mock.albums() as? AlbumsResult.Albums)?.albums.orEmpty()
            return AlbumsResult.Albums(
                created + AlbumSummary("fixture-album-1", "Holiday", createdAtMs = -1, photoCount = 0, coverPath = null),
            )
        }

        override suspend fun albumPhotos(albumId: String): PhotoPageResult {
            seed()
            return mock.albumPhotos(albumId)
        }

        override suspend fun albumsFor(contentId: String) = seed().let { mock.albumsFor(contentId) }

        override suspend fun createAlbum(name: String): String? = seed().let { mock.createAlbum(name) }

        override suspend fun renameAlbum(albumId: String, name: String) = edit { mock.renameAlbum(albumId, name) }

        override suspend fun deleteAlbum(albumId: String) = edit { mock.deleteAlbum(albumId) }

        override suspend fun addToAlbum(albumId: String, photo: Photo) = seed().let { mock.addToAlbum(albumId, photo) }

        override suspend fun removeFromAlbum(albumId: String, contentId: String) =
            edit { mock.removeFromAlbum(albumId, contentId) }

        /** ⚠ Slow under `-photosSlowEdits` too (§8.18): Duplicates' keep-only is this write. */
        override suspend fun trash(photo: Photo): Boolean = edit { !refuses(photo) && mock.trash(photo) }

        /** Duplicates' permanent delete — refused for every fourth photograph, as the trash is. */
        override suspend fun deletePermanently(photo: Photo): Boolean =
            edit { !refuses(photo) && mock.deletePermanently(photo) }

        /** ⚠ Seeded first: the trashed photographs are not duplicates' to count. */
        override suspend fun duplicates() = seed().let { mock.duplicates() }

        private fun refuses(photo: Photo) =
            photo.path.substringAfterLast("IMG_").substringBefore('.').toIntOrNull()?.minus(10_000)?.rem(4) == 3

        override suspend fun preview(path: String, width: Int, height: Int): PhotoPreview? =
            PhotoPreview(render(path, maxOf(width, height).coerceIn(64, 512)))

        /**
         * ⚠ Two rungs, like the real read: the local preview at once, then a sharper one after a pull
         * — here 6 s later, well after a UI test's pinch lands. A viewer that treated the upgrade as a
         * new photograph (and reset the zoom) is caught by `ViewerGestureUITests` only because this
         * emits twice, and only because the second rung comes AFTER the pinch: at 2.5 s it can land
         * before XCUITest has finished pinching, and the test passes against the bug.
         */
        override fun viewerPreview(path: String, requestPx: Int, mayMint: Flow<Unit>): Flow<PhotoPreview> =
            flow {
                emit(PhotoPreview(render(path, (requestPx / 2).coerceIn(256, 800))))
                delay(6_000)
                emit(PhotoPreview(render(path, requestPx.coerceIn(256, 1600))))
            }
    }

    /** A JPEG whose colour is a function of the path, labelled with the photograph's index. */
    private fun render(path: String, edge: Int): ByteArray {
        val hue = ((path.hashCode().toLong() and 0xffff) % 360) / 360.0
        val index = path.substringAfterLast("IMG_").substringBefore('.').toIntOrNull()?.minus(10_000)
        val width = edge.toDouble()
        val height = edge * 0.75
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { setScale(1.0) }
        val image = UIGraphicsImageRenderer(size = CGSizeMake(width, height), format = format).imageWithActions {
            UIColor.colorWithHue(hue, saturation = 0.55, brightness = 0.80, alpha = 1.0).setFill()
            UIRectFill(CGRectMake(0.0, 0.0, width, height))
            if (index != null) {
                val attributes = mapOf<Any?, Any?>(
                    NSFontAttributeName to UIFont.boldSystemFontOfSize(height * 0.34),
                    NSForegroundColorAttributeName to UIColor.whiteColor,
                )
                NSString.create(string = "#$index").drawAtPoint(CGPointMake(width * 0.08, height * 0.28), withAttributes = attributes)
            }
        }
        val data = UIImageJPEGRepresentation(image, 0.8) ?: return ByteArray(0)
        return data.bytes?.readBytes(data.length.toInt()) ?: ByteArray(0)
    }
}
