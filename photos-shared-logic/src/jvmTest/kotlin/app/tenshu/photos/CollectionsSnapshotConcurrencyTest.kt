package app.tenshu.photos

import app.tenshu.outbox.OutboxEntry
import app.tenshu.outbox.OutboxStore
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.healthCheckedRealm
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsSnapshot
import app.tenshu.photos.ui.screens.collections.CollectionsSnapshotStore
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.collections.CurrentRealm
import app.tenshu.photos.ui.screens.collections.JsonCollectionsSnapshotStore
import app.tenshu.photos.ui.screens.collections.purge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.viewmodel.test.vmTest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ⚠⚠ The snapshot's file work on REAL threads: a write already running when a
 * revocation lands must not survive it. A single test dispatcher runs every block to completion in
 * turn, so it cannot produce the interleaving — this test holds a write open on an IO thread, lands
 * the teardown, then lets the write finish.
 */
class CollectionsSnapshotConcurrencyTest {

    private val main = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(main)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Thread-safe, because two IO threads may touch it at once — which is the point. */
    private class MemoryOutboxStore : OutboxStore {
        val entries: MutableMap<String, String> = Collections.synchronizedMap(linkedMapOf())
        override fun append(id: String, payload: String) {
            entries[id] = payload
        }
        override fun entries(): List<OutboxEntry> = synchronized(entries) { entries.map { (id, p) -> OutboxEntry(id, p) } }
        override fun remove(id: String) {
            entries.remove(id)
        }
    }

    /** Blocks inside [write] until [release] — a slow disk, held open mid-write. */
    private class HeldWriteStore(private val inner: CollectionsSnapshotStore) : CollectionsSnapshotStore by inner {
        val writeEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun write(realm: String, snapshot: CollectionsSnapshot) {
            writeEntered.countDown()
            release.await(10, TimeUnit.SECONDS)
            inner.write(realm, snapshot)
        }
    }

    @Test
    fun aWriteInFlightWhenRevocationLandsDoesNotSurviveIt() = vmTest(main) { vms ->
        val files = MemoryOutboxStore()
        val store = HeldWriteStore(JsonCollectionsSnapshotStore(files))
        val health = MutableStateFlow<String?>(SyncHealthStatus.CONNECTIVITY_ONLINE)
        vms.own(
            CollectionsViewModel(
                MockPhotosRepository(photos = listOf(Photo("/a.jpg", 1_000L, isVideo = false, contentId = "a"))),
                yearOf = { 1970 },
                snapshotStore = store,
                currentRealm = healthCheckedRealm(ownerId = { "A" }, connectivity = health),
                snapshotDispatcher = Dispatchers.IO,
            ),
        )
        // The refresh ends and its save starts writing on an IO thread — and is held there.
        settle { store.writeEntered.count == 0L }
        assertTrue(store.writeEntered.await(5, TimeUnit.SECONDS), "the save never started writing")

        // The revocation lands while that write is open.
        health.value = SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED
        settle()

        store.release.countDown()
        settle()

        assertTrue(files.entries.isEmpty(), "the snapshot survived a revocation that landed mid-write: ${files.entries.keys}")
    }

    /**
     * ⚠⚠ The TEARDOWN path outside any view model (iOS `deprovision()` → [purge]):
     * a purge issued from another thread while the view model's write is held open must still leave
     * no file — it waits on the store's fence and runs after the write.
     */
    @Test
    fun aPurgeFromTheTeardownPathWhileAWriteIsInFlightLeavesNoFile() = vmTest(main) { vms ->
        val files = MemoryOutboxStore()
        val store = HeldWriteStore(JsonCollectionsSnapshotStore(files))
        vms.own(viewModel(store, CurrentRealm { "A" }))
        settle { store.writeEntered.count == 0L }
        assertTrue(store.writeEntered.await(5, TimeUnit.SECONDS), "the save never started writing")

        // The teardown path, on its own thread — not through the view model.
        val purged = CompletableFuture.runAsync { runBlocking { store.purge() } }
        Thread.sleep(100)
        store.release.countDown()
        // ⚠ Pump main while waiting: the write's fence is released when its block resumes on main.
        settle { purged.isDone }
        purged.get(1, TimeUnit.SECONDS)
        settle()

        assertTrue(files.entries.isEmpty(), "the snapshot survived a deprovision purge: ${files.entries.keys}")
    }

    /**
     * ⚠⚠ Teardown is effective when it RETURNS: iOS `deprovision()` is synchronous on
     * the main thread and may be suspended or killed right after, so the file must already be gone — and
     * a write held open across the teardown must not bring it back once it finishes.
     */
    @Test
    fun aTeardownReturnsWithTheFileGone_evenWithAWriteInFlight() = vmTest(main) { vms ->
        val files = MemoryOutboxStore()
        val inner = JsonCollectionsSnapshotStore(files)
        inner.write("A", CollectionsSnapshot(favouriteCount = 3)) // last session's snapshot
        val store = HeldWriteStore(inner)
        vms.own(viewModel(store, CurrentRealm { "A" }))
        settle { store.writeEntered.count == 0L }
        assertTrue(store.writeEntered.await(5, TimeUnit.SECONDS), "the save never started writing")

        store.tearDown() // the teardown path, synchronously, while the write is held open
        assertTrue(files.entries.isEmpty(), "teardown returned with the file still there: ${files.entries.keys}")

        store.release.countDown()
        settle()
        assertTrue(files.entries.isEmpty(), "the write in flight brought the snapshot back: ${files.entries.keys}")
    }

    /**
     * The other ordering: the purge finishes BEFORE the view model's prepared write reaches the store —
     * the write was prepared under the old identity (its realm already read), so it must not land.
     */
    @Test
    fun aWritePreparedBeforeAPurgeDoesNotLandAfterIt() = vmTest(main) { vms ->
        val files = MemoryOutboxStore()
        val inner = JsonCollectionsSnapshotStore(files)
        // A slow disk under the start-up read, so that read still holds the fence when the purge is
        // issued — the interleaving is forced, not left to chance.
        val store = object : CollectionsSnapshotStore by inner {
            override fun read(realm: String): CollectionsSnapshot? {
                Thread.sleep(300)
                return inner.read(realm)
            }
        }
        val realmReads = AtomicInteger()
        val secondRead = CountDownLatch(1)
        val purgeDone = CountDownLatch(1)
        vms.own(
            viewModel(
                store,
                CurrentRealm {
                    // The save's realm read (the second) answers only once the purge has completed.
                    if (realmReads.incrementAndGet() >= 2) {
                        secondRead.countDown()
                        withContext(Dispatchers.IO) { purgeDone.await(5, TimeUnit.SECONDS) }
                    }
                    "A"
                },
            ),
        )
        settle { secondRead.count == 0L }
        assertTrue(secondRead.await(5, TimeUnit.SECONDS), "the save never asked for the realm")

        // ⚠⚠ On its own thread, with main pumped while it waits — never `runBlocking` here. Every
        // fenced block in the view model releases the fence when it RESUMES ON MAIN, so a purge that
        // blocks this thread while one of them is mid-flight (the start-up read, above) waits for a
        // release only this thread can run: the test parked forever, and took a full gate with it.
        val purged = CompletableFuture.runAsync { runBlocking { store.purge() } }
        settle { purged.isDone }
        purged.get(5, TimeUnit.SECONDS)
        purgeDone.countDown()
        settle()

        assertTrue(files.entries.isEmpty(), "a write prepared before the purge landed after it: ${files.entries.keys}")
    }

    private fun viewModel(store: CollectionsSnapshotStore, realm: CurrentRealm) = CollectionsViewModel(
        MockPhotosRepository(photos = listOf(Photo("/a.jpg", 1_000L, isVideo = false, contentId = "a"))),
        yearOf = { 1970 },
        snapshotStore = store,
        currentRealm = realm,
        snapshotDispatcher = Dispatchers.IO,
    )

    /**
     * Lets real IO threads and the test's main dispatcher take turns until [done], or for ~1.5 s.
     * ⚠ `runCurrent`, never `advanceUntilIdle`: virtual time would jump past the view model's read
     * budgets while a real thread is still working, turning every wait into a "timed out" — a test that
     * then passes because nothing was written at all.
     */
    private fun kotlinx.coroutines.test.TestScope.settle(done: () -> Boolean = { false }) {
        repeat(75) {
            testScheduler.runCurrent()
            if (done()) return
            Thread.sleep(20)
        }
        testScheduler.runCurrent()
    }
}
