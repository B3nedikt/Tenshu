package app.tenshu.drive.integration.utils

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.IdlingResource
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.RepositoryResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.awt.EventQueue
import java.awt.Toolkit

/**
 * Makes Compose's own `waitForIdle()` wait for the Drive repository, so a UI test can assert
 * straight after an action instead of polling the screen against a real-time budget.
 *
 * ⚠⚠ **Why Compose cannot see this work by itself.** Desktop's idle check covers pending
 * layout, the recomposer, the composition's own dispatcher, snapshot changes and registered
 * idling resources — nothing else. In these tests the state a test asserts is produced on two
 * threads it does not cover:
 *
 * 1. **The in-process proxy** (`Dispatchers.Default`): an upload, a folder create, a delete,
 *    and the listing's re-emission after each.
 * 2. **The Swing EDT** (`Dispatchers.Main`): `viewModelScope` (where `AddFileActionsViewModel`
 *    turns an upload's `AlreadyExists` into the conflict dialog), `cachedIn(viewModelScope)` on
 *    the listing, and paging-compose's presenter, which hops to `Dispatchers.Main` on desktop.
 *
 * So `waitForIdle()` used to return while an upload was still in flight, and every assertion
 * had to be a `waitUntil` against a real-time ceiling. That was the C-category flake
 * (`docs/testing-flakes.md`): under load the ceiling, not the code, decided the result.
 *
 * **What this resource is busy on**, each one an event rather than a duration:
 * - a [FilesRepository] call through [IdlingFilesRepository] that has not returned;
 * - a live listing that has not emitted yet, or that owes an emission reflecting a write that
 *   returned successfully (an uploaded or created name present, a deleted one absent);
 * - the EDT: every poll runs an `invokeAndWait` barrier, so whatever the EDT had queued — the
 *   view model's reaction to a returned call, the presenter's hop — has run, and then requires
 *   the EDT's queue to be empty;
 * - one more poll after anything changed, because Compose reads snapshot changes *before* it
 *   polls this resource; the extra round lets it see what the barrier just let land.
 *
 * ⚠ **Not covered: work on `Dispatchers.Default` that is not a repository call.** The detail
 * screen decodes its bitmap there (`ImagePreviewViewModel`), so the preview tag is still a
 * condition wait. A new real-threaded hop between a repository call and the screen needs either
 * a hook here or a condition wait. Without one the assertion races, and the test does not say so.
 *
 * ⚠ A listing for a folder nobody is collecting is not awaited: a write into such a folder
 * registers no expectation, so the next listing's *first* emission is what the test waits on.
 */
@OptIn(ExperimentalTestApi::class)
class RepositoryIdling : IdlingResource {

    private class Listing(val folderPath: String) {
        var latest: List<FileModel>? = null
        val owed = mutableListOf<Expectation>()
    }

    private class Expectation(val what: String, val satisfiedBy: (List<FileModel>) -> Boolean)

    private val lock = Any()
    private val inFlight = mutableListOf<String>()
    private val listings = mutableSetOf<Listing>()

    /** Bumped by every start, return and emission; idle needs a whole poll without one. */
    private var changes = 0L
    private var changesAtLastPoll = -1L
    private var busyPolls = 0

    /**
     * Called with the running count each time a poll finds this resource busy. A test counts
     * polls, never milliseconds, to hold work open across many of Compose's idle checks.
     */
    @Volatile
    var onBusyPoll: (Int) -> Unit = {}

    override val isIdleNow: Boolean
        get() {
            val before = synchronized(lock) { changes }
            // The barrier: everything the EDT had queued before this line has now run.
            if (!EventQueue.isDispatchThread()) EventQueue.invokeAndWait {}
            val edtQuiet = Toolkit.getDefaultToolkit().systemEventQueue.peekEvent() == null
            synchronized(lock) {
                val quiet = edtQuiet &&
                    inFlight.isEmpty() &&
                    listings.all { it.latest != null && it.owed.isEmpty() } &&
                    changes == before &&
                    changesAtLastPoll == changes
                changesAtLastPoll = changes
                if (!quiet) busyPolls++
                quiet to busyPolls
            }.let { (quiet, polls) ->
                if (!quiet) onBusyPoll(polls)
                return quiet
            }
        }

    override fun getDiagnosticMessageIfBusy(): String = synchronized(lock) {
        buildString {
            append("in flight: ").append(inFlight)
            listings.forEach { l ->
                append("; listing ").append(l.folderPath)
                if (l.latest == null) append(" (no emission yet)")
                l.owed.forEach { append(" owes ").append(it.what) }
            }
        }
    }

    internal suspend fun <T> call(what: String, block: suspend () -> T): T {
        synchronized(lock) { inFlight += what; changes++ }
        try {
            return block()
        } finally {
            synchronized(lock) { inFlight -= what; changes++ }
        }
    }

    /**
     * Every live listing of [folderPath] owes an emission that satisfies [satisfiedBy], unless its
     * latest one already does: the proxy may notify the listing before the write's call returns.
     */
    internal fun expect(folderPath: String, what: String, satisfiedBy: (List<FileModel>) -> Boolean) =
        synchronized(lock) {
            listings.filter { it.folderPath == folderPath }.forEach { l ->
                if (l.latest?.let(satisfiedBy) != true) l.owed += Expectation(what, satisfiedBy)
            }
            changes++
        }

    internal fun listing(folderPath: String, upstream: suspend () -> Flow<List<FileModel>>): Flow<List<FileModel>> =
        flow {
            val listing = Listing(folderPath)
            synchronized(lock) { listings += listing; changes++ }
            try {
                emitAll(upstream().map { files ->
                    synchronized(lock) {
                        listing.latest = files
                        listing.owed.removeAll { it.satisfiedBy(files) }
                        changes++
                    }
                    files
                })
            } finally {
                synchronized(lock) { listings -= listing; changes++ }
            }
        }
}

/**
 * The real [FilesRepository] with the paged listing replaced by a static [PagingData] wrap of
 * the real reactive [FilesRepository.getFiles], and every call the UI tests drive reported to
 * [idling].
 *
 * Why the listing is static: the production `getFilesPaged` uses the live `TenshuPagingSource`,
 * whose infinite reactive observer never lets a `runComposeUiTest` reach idle; a second such
 * test in the same JVM then GC-thrashes into a hang. Wrapping `getFiles` keeps reactivity (an
 * upload re-emits, so a new page) without that observer. Real paging (windowing) is covered by
 * `FilesPagingIntegrationTest` via `asSnapshot`.
 *
 * ⚠⚠ The [LoadStates] are stated because without them this fake MISREPRESENTS the empty folder,
 * the one case `PagedFilesList` takes the most care over. The deprecated single-argument
 * `PagingData.from(list)` pins refresh in `LoadState.Loading` forever, and that composable
 * branches three ways on `refresh` when `itemCount == 0`: Loading → spinner, Error → "couldn't
 * read it", else → "No files here yet". Pinned, an empty folder spins forever and the empty
 * state is unreachable. `FilesScreenIntegrationTest.testInitialEmptyList` asserts it, and that
 * assertion fails against the single-argument form.
 *
 * ⚠ It is NOT an idle hazard, though the spinner makes that look likely: the indicator is an
 * indeterminate `CircularProgressIndicator`, an infinite animation, and those are a classic
 * reason a `runComposeUiTest` never reaches idle. Measured: the suite passes either way.
 *
 * [beforeUploadReturns] runs after the real upload and before its result is handed back; a test
 * uses it to hold an upload open (`FilesScreenIntegrationTest.testASlowUploadIsAwaitedNotTimed`).
 */
class IdlingFilesRepository(
    private val delegate: FilesRepository,
    private val idling: RepositoryIdling,
    @Volatile var beforeUploadReturns: suspend () -> Unit = {},
) : FilesRepository by delegate {

    override fun getFilesPaged(folderPath: String, sort: FileSort): Flow<PagingData<FileModel>> =
        idling.listing(folderPath) { delegate.getFiles(folderPath) }
            .map { PagingData.from(it, WHOLE_LISTING_LOAD_STATES) }

    override suspend fun uploadFile(uriString: String, parentPath: String, fileName: String): RepositoryResult<Unit> =
        idling.call("uploadFile $parentPath $fileName") {
            delegate.uploadFile(uriString, parentPath, fileName).also { result ->
                if (result is RepositoryResult.Success) {
                    idling.expect(parentPath, "$fileName present") { files -> files.any { it.name == fileName } }
                }
                beforeUploadReturns()
            }
        }

    override suspend fun createFolder(path: String): RepositoryResult<Unit> =
        idling.call("createFolder $path") {
            delegate.createFolder(path).also { result ->
                if (result is RepositoryResult.Success) {
                    val name = path.substringAfterLast('/')
                    idling.expect(parentOf(path), "$name present") { files -> files.any { it.name == name } }
                }
            }
        }

    override suspend fun deleteFile(path: String): RepositoryResult<Unit> =
        idling.call("deleteFile $path") {
            delegate.deleteFile(path).also { result ->
                if (result is RepositoryResult.Success) {
                    val name = path.substringAfterLast('/')
                    idling.expect(parentOf(path), "$name absent") { files -> files.none { it.name == name } }
                }
            }
        }

    override suspend fun getRemoteFile(path: String): RepositoryResult<ByteArray> =
        idling.call("getRemoteFile $path") { delegate.getRemoteFile(path) }

    override suspend fun getPreview(path: String, width: Int, height: Int): ByteArray? =
        idling.call("getPreview $path") { delegate.getPreview(path, width, height) }

    private fun parentOf(path: String): String = path.substringBeforeLast('/').ifEmpty { FileModel.Root.path }
}

/** One page, refresh done, neither end can append — the truth for a whole-listing wrap. */
private val WHOLE_LISTING_LOAD_STATES = LoadStates(
    refresh = LoadState.NotLoading(endOfPaginationReached = true),
    prepend = LoadState.NotLoading(endOfPaginationReached = true),
    append = LoadState.NotLoading(endOfPaginationReached = true),
)
