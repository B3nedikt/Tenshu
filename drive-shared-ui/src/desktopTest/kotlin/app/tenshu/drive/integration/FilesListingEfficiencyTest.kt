package app.tenshu.drive.integration

import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FileSortKey
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.boundedSnapshot
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The efficiency CONTRACT of the files-listing hot paths, pinned as
 * deterministic store-read counts (never wall-clock — that flakes in CI).
 * The correctness of each mechanism is covered elsewhere
 * ([EvaluateCallstackWindowFusionTest], `SearchByKeyFastPathTest`,
 * `SearchByKeyIntegrationTest`, [FilesPagingIntegrationTest]); what nothing
 * else pins is that the *real Drive chains* keep hitting those fast paths.
 * Each of these regressions would keep every correctness test green while
 * silently reverting a folder open to O(folder size) per interaction:
 *
 *  - an op slipped ahead of `drop/take` in the paging chain → window fusion
 *    stops firing → every page load deserialises the whole folder;
 *  - `navigateTo` reverted to a single deep search → the membership probe
 *    misses → per-row `getPreview` scans the tree again;
 *  - `Folder.Fields.FILES` (or an inline `File.Preview`) re-added to a
 *    select → folder rows materialise + ship their whole subtree again.
 *
 * The proxy's deserialisation work is proportional to [ObjectStore.getObject]
 * calls (every entity/list-entry read is at least one), so a counting store
 * injected into the integration Koin harness measures exactly the work the
 * round bounded. Bounds sit well above the measured values (noted per
 * test) and well below the O(folder) regression level they exist to
 * catch.
 */
class FilesListingEfficiencyTest {

    private class CountingObjectStore(
        private val delegate: ObjectStore = InMemoryObjectStore(),
    ) : ObjectStore by delegate {
        val getObjectCalls = AtomicInteger(0)

        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            getObjectCalls.incrementAndGet()
            return delegate.getObject(dataIdHash)
        }

        fun reset() = getObjectCalls.set(0)
        val count: Int get() = getObjectCalls.get()
    }

    private lateinit var filesRepository: FilesRepository
    private val store = CountingObjectStore()

    /** Folder size — big enough that O(page) and O(folder) are far apart. */
    private val folderSize = 150

    /**
     * Measured 2026-09-15, in-memory store, at 5 windows: **2123** for the default Name sort and
     * **2149 / 2149 / 2151** for the explicit date sort. Before the `thenBy`-run fold
     * (`drive-sorted-folder-paging.md` §3 B6) the same scroll cost **4263** — that is what the
     * unfused shape looks like, and what the bound must reject. 22 × folderSize = 3300 clears a
     * 7-window healthy run (≈ 2 650) by ~24 % and sits ~23 % under the 5-window regression.
     */
    private val DEFAULT_SCROLL_MEASURED = 2123
    private val SORTED_SCROLL_MEASURED = 2149
    private val SORTED_SCROLL_BOUND = folderSize * 22

    private lateinit var appKoin: AppIntegrationKoin

    @Before
    fun setup(): Unit = runBlocking {
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.test", objectStore = store)
        filesRepository = appKoin.get()

        filesRepository.createFolder("/big")
        repeat(folderSize) { i ->
            val name = "f%03d.txt".format(i)
            filesRepository.uploadFile(
                uriString = "mock://$name",
                parentPath = "/big",
                fileName = name
            )
        }
        // One previewable file for the per-row preview phase.
        filesRepository.uploadFile(
            uriString = "mock://photo.jpg?w=8&h=8",
            parentPath = "/big",
            fileName = "photo.jpg"
        )
    }

    @After
    fun tearDown() {
        appKoin.stop()
    }

    /**
     * Open + scroll across 4 page windows in one paging collection, on the listing's **default**
     * order — Name ascending (D6), since SP-6 retired natural order. Legit cost = ONE projected
     * observer pass over the folder + ONE fused thin pass per loaded window + the window's own
     * 20 projected rows. A fusion regression re-reads and re-sorts the whole folder per page
     * load on top of that.
     *
     * ⚠⚠ **This number rose ~3× on 2026-09-15 and that was the accepted price of SP-6, not a
     * regression.** It measured 683–699 at 5 windows when the default listing was *natural
     * (store) order*: no sort, and a change observer over the bare receiver, which diffs `File`
     * composites by `path` alone. D5 retired that mode because an order the user never chose
     * reads as a bug, and the cost is what a real listing needs — the observer now evaluates the
     * row projection so an in-place overwrite signals at all (`drive-sorted-folder-paging.md`
     * §3 B5), and each page cuts its window under a sort. On the device this is 1.1 s to first
     * paint at 618 rows, flat in folder size, against 2.8 s before (§10.7).
     */
    @Test
    fun openAndScroll_isOnePassPlusPageWindows() = runBlocking {
        store.reset()

        // ⚠ Bounded (see [boundedSnapshot]): this test is a recorded member of the rotating
        // flake population in `docs/tech-debt.md`, so the failure it is most likely to hit
        // next is one where the listing never settles — which unbounded reads as a stalled
        // gate rather than as this assertion's own loud message.
        val snapshot: List<FileModel> = filesRepository.getFilesPaged("/big").boundedSnapshot {
            scrollTo(79)
        }

        assertTrue(snapshot.size >= 80, "expected at least 4 windows, got ${snapshot.size} rows")
        assertEquals(snapshot.size, snapshot.map { it.path }.toSet().size, "no row served twice")
        val calls = store.count
        // Measured 2026-09-15: 2123 at 5 windows (the explicit date sort below is 2149 — the
        // same shape with a value-property key instead of the name transform).
        //
        // ⚠⚠ "Loaded" is not "returned", and that is what made this the gate's most
        // frequent flake for two days. `snapshot.size` is always 100 (5 windows), but
        // paging's prefetch cascade legitimately loads **5 or 7** windows before the
        // counter is read — never 6 — and which one you get is a race with `asSnapshot`
        // returning. Both are correct behaviour, so the bound must clear the whole
        // 7-window mode, not just the 5-window one.
        //
        // ⚠ Do not "fix" a failure here by nudging the bound to just clear whatever count you
        // saw — that is how the pre-SP-6 bound ended up *inside* the 7-window distribution
        // (893 passed while 907 failed, from equally healthy runs). Re-measure instead, and if
        // the number moved without a listing change, suspect drift.
        assertTrue(
            calls < SORTED_SCROLL_BOUND,
            "open + 4-window scroll cost $calls store reads for a $folderSize-file folder — " +
                "expected ~one projected observer pass + one fused thin pass per loaded window " +
                "(healthy ≈ $DEFAULT_SCROLL_MEASURED at 5 windows). A count well above that " +
                "means a sort key fell off the fused path and every page re-sorts the fully " +
                "materialised folder"
        )
    }

    /**
     * The SORTED listing, same open + 4-window scroll (`drive-sorted-folder-paging.md` SP-3).
     * Legit cost = the observer's initial evaluation of the *projected* chain (~4 reads/row —
     * member + per-relation resolution, the price B5 pays for seeing overwrites) + ONE thin
     * pass over the folder per loaded window (the ordered-window fusion: every row read once,
     * only the window's rows projected). The regression this exists to catch is a key falling
     * off the fused path — a derived property, an op with params, a composite key — which
     * re-reads every row a second time as an entity per window and interprets the selector per
     * row: ≥ 2 full passes per window on top.
     */
    @Test
    fun sortedOpenAndScroll_isOneFusedPassPerWindow() = runBlocking {
        store.reset()

        val snapshot: List<FileModel> = filesRepository
            .getFilesPaged("/big", FileSort(FileSortKey.MODIFIED, descending = true))
            .boundedSnapshot { scrollTo(79) }

        assertTrue(snapshot.size >= 80, "expected at least 4 windows, got ${snapshot.size} rows")
        assertEquals(snapshot.size, snapshot.map { it.path }.toSet().size, "no row served twice")
        val calls = store.count
        // Budget (see [SORTED_SCROLL_BOUND]): the projected observer pass (~5 reads × folderSize)
        // + per loaded window one thin pass (folderSize + the array entry) + 20 projected rows
        // × ~5 reads — ≈ 250 per window, 5 or 7 windows (the prefetch race the natural test
        // documents). The unfused regression re-reads every row as an entity and sorts three
        // times per window instead, ≈ +420 per window.
        assertTrue(
            calls < SORTED_SCROLL_BOUND,
            "sorted open + 4-window scroll cost $calls store reads for a $folderSize-file " +
                "folder — expected ~one projected observer pass + one thin pass per loaded " +
                "window (healthy ≈ $SORTED_SCROLL_MEASURED at 5 windows). A count well above " +
                "that means a sort key fell off the fused path (`classifySortKey` refused it) " +
                "and every page re-sorts the fully materialised folder"
        )
    }

    /**
     * One per-row preview fetch (the `HorizontalFileItem` loader path):
     * `navigateTo(parent)` + `search { path }` + preview resolution must all
     * be point reads — a regression to the scan fallback (deep search /
     * membership miss) walks the tree and shows up as O(folder) reads.
     */
    @Test
    fun perRowPreview_isPointReads() = runBlocking {
        store.reset()

        val preview = filesRepository.getPreview("/big/photo.jpg", width = 100, height = 100)

        assertNotNull(preview, "preview must resolve for the uploaded image")
        val calls = store.count
        // Measured ~11.
        assertTrue(
            calls < 60,
            "one getPreview cost $calls store reads — expected a handful of point reads; " +
                "a count near $folderSize means the by-key search fell back to scanning " +
                "(deep navigateTo search or membership-probe regression)"
        )
    }

    /**
     * Details of the folder itself: the parent listing's folder row carries
     * its child count via `Folder.FileCount` (a member-hash read). A
     * regression that re-projects `Folder.Fields.FILES` (or otherwise
     * materialises children for the count) reads all [folderSize] children
     * for one row.
     */
    @Test
    fun folderDetails_doesNotMaterialiseChildren() = runBlocking {
        store.reset()

        val details = filesRepository.fileDetails("/big").first()

        assertNotNull(details, "details must resolve for /big")
        // folderSize .txt files + photo.jpg
        assertEquals("${folderSize + 1} files", (details.file as? FileModel.Folder)?.subLine)
        val calls = store.count
        // Measured ~5.
        assertTrue(
            calls < 60,
            "folder details cost $calls store reads — expected point reads + a member-hash " +
                "count; a count near $folderSize means the folder row materialised its " +
                "children again (FILES field back in a select?)"
        )
    }
}
