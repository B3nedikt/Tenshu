package app.tenshu.photos

import app.tenshu.outbox.OutboxEntry
import app.tenshu.outbox.OutboxStore
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.data.healthCheckedRealm
import app.tenshu.photos.data.realmFrom
import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.ui.screens.collections.CollectionSection
import app.tenshu.photos.ui.screens.collections.CollectionsLayout
import app.tenshu.photos.ui.screens.collections.CollectionsSnapshot
import app.tenshu.photos.ui.screens.collections.CollectionsSnapshotStore
import app.tenshu.photos.ui.screens.collections.CollectionsTile
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.collections.CurrentRealm
import app.tenshu.photos.ui.screens.collections.JsonCollectionsSnapshotStore
import app.tenshu.photos.ui.screens.collections.SectionLoad
import app.tenshu.photos.ui.screens.collections.SnapshotAlbum
import app.tenshu.photos.ui.screens.collections.SnapshotFace
import app.tenshu.photos.ui.screens.collections.SnapshotSection
import app.tenshu.photos.ui.screens.feed.FeedFirstPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `photos-app.md` §36 step 2 — the Collections tab draws the last result at once on a cold start and
 * refreshes behind it; the snapshot is one realm's, and every identity teardown drops it. "A slow
 * Control" is faked by reads that do not answer yet.
 */
class CollectionsSnapshotTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** `:app-outbox`'s store contract, in memory — the file stores are tested in `:app-outbox`. */
    private class MemoryOutboxStore : OutboxStore {
        val entries = linkedMapOf<String, String>()
        var failWrites = false
        override fun append(id: String, payload: String) {
            if (failWrites) error("disk full")
            entries[id] = payload
        }
        override fun entries(): List<OutboxEntry> = entries.map { (id, payload) -> OutboxEntry(id, payload) }
        override fun remove(id: String) {
            entries.remove(id)
        }
    }

    /**
     * Every Collections read hangs until [release] — a Control too busy to answer. ⚠ Uncancellably,
     * as the real transports do (the `CollectionsRefreshTimeoutTest` rule). ⚠ The
     * `…Read` variants are the ones the view model calls — `by inner` would route their defaults past
     * any override of `places()` and friends.
     */
    private class SilentRepository(
        private val inner: PhotosRepository,
        /** Which read stays silent; every read when `null`. */
        private val only: String? = null,
    ) : PhotosRepository by inner {
        private val reply = CompletableDeferred<Unit>()
        fun release() = reply.complete(Unit)
        private suspend fun wait(read: String) {
            if (only == null || only == read) withContext(NonCancellable) { reply.await() }
        }
        override suspend fun albums(): AlbumsResult { wait("albums"); return inner.albums() }
        override suspend fun favourites(): PhotoPageResult { wait("favourites"); return inner.favourites() }
        override suspend fun allPhotosRead(): List<Photo>? { wait("allPhotos"); return inner.allPhotosRead() }
        override suspend fun placesRead(): List<PhotoPlace>? { wait("places"); return inner.placesRead() }
        override suspend fun allLabelsRead(): List<PhotoLabel>? { wait("allLabels"); return inner.allLabelsRead() }
        override suspend fun faceGroups(limit: Int): Pair<List<FaceGroup>, PeopleStatus> {
            wait("faceGroups"); return inner.faceGroups(limit)
        }
    }

    private fun photo(id: String, at: Long = 1_000L) = Photo("/$id.jpg", at, isVideo = false, contentId = id)

    private val anna = SnapshotSection(
        id = "person:name:anna",
        title = "Anna",
        subtitle = "120 photos",
        photoCount = 120,
        coverPath = "/anna.jpg",
        alternateIds = listOf("person:g7"),
        coverFace = SnapshotFace("/anna.jpg", 0.1f, 0.2f, 0.3f, 0.4f),
        coverContentId = "anna",
    )

    private val lastTime = CollectionsSnapshot(
        albums = listOf(SnapshotAlbum("al1", "Trip", 5L, 12, "/trip.jpg", 400, 300, coverContentId = "trip")),
        favouriteCount = 3,
        people = listOf(anna),
        years = listOf(SnapshotSection("year:2025", "2025", "900 photos", 900, "/y.jpg", 4000, 3000, coverContentId = "y")),
        places = listOf(SnapshotSection("place:p1", "Lisbon", "Portugal", 40, "/l.jpg", coverContentId = "l")),
        peopleStatus = "available",
    )

    private val realmA = CurrentRealm { "A" }

    private fun storeWith(snapshot: CollectionsSnapshot?, realm: String = "A"): Pair<MemoryOutboxStore, JsonCollectionsSnapshotStore> {
        val files = MemoryOutboxStore()
        val store = JsonCollectionsSnapshotStore(files)
        if (snapshot != null) store.write(realm, snapshot)
        return files to store
    }

    private fun vm(
        vms: OwnedViewModels,
        repository: PhotosRepository,
        store: CollectionsSnapshotStore?,
        realm: CurrentRealm? = realmA,
    ) = vms.own(
        CollectionsViewModel(
            repository,
            yearOf = { 1970 },
            refreshTimeoutMs = 5_000,
            snapshotStore = store,
            currentRealm = realm,
            snapshotDispatcher = dispatcher,
        ),
    )

    // ── drawing it ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun coldStartWithASnapshotDrawsItsRowsBeforeAnyReadAnswers() = vmTest(dispatcher) { vms ->
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        try {
            val vm = vm(vms, repository, storeWith(lastTime).second)
            testScheduler.runCurrent()

            val state = vm.state.value
            assertEquals(listOf("Trip"), state.albums.map { it.name }, "albums from the snapshot")
            assertEquals(3, state.favouriteCount)
            assertEquals(listOf("Anna"), state.people.map { it.title })
            assertEquals("/anna.jpg", state.people.single().coverFace?.path, "the face crop survives")
            assertEquals(120, state.people.single().photoCount)
            assertEquals(listOf("2025"), state.years.map { it.title })
            assertEquals(listOf("Lisbon"), state.places.map { it.title })
            // ⚠⚠ Drawn, but never as current: every section it filled is still LOADING.
            listOf(state.albumsLoad, state.favouritesLoad, state.libraryLoad, state.placesLoad, state.peopleLoad)
                .forEach { assertEquals(SectionLoad.LOADING, it) }
        } finally {
            repository.release()
        }
    }

    // ── photos-editing.md §12.13: a cached cover never names an edited original ────────────────

    /** [SilentRepository], whose answer to [PhotosRepository.editedContentIds] can be held too. */
    private class EditsHeld(private val inner: SilentRepository, private val held: CompletableDeferred<Unit>? = null) : PhotosRepository by inner {
        override suspend fun editedContentIds(): Set<String>? {
            held?.let { withContext(NonCancellable) { it.await() } }
            return inner.editedContentIds()
        }
    }

    /**
     * ⚠⚠ The snapshot was written before Anna's photo was edited (here, or on another device while
     * Photos was closed): its face crop is cut from the original, which may now be redacted. It must
     * not be drawn — while every cover that names no edited original still is.
     */
    @Test
    fun aSnapshotFaceCutFromANowEditedOriginal_isNeverDrawn() = vmTest(dispatcher) { vms ->
        val annaEdited = photo("anna").copy(renditionPath = "/Trash/.edits/anna-1-x.jpg")
        val silent = SilentRepository(MockPhotosRepository(photos = listOf(annaEdited, photo("trip"))))
        try {
            val vm = vm(vms, EditsHeld(silent), storeWith(lastTime).second)
            testScheduler.runCurrent()

            val anna = vm.state.value.people.single()
            assertEquals("Anna", anna.title, "the card is still drawn")
            assertNull(anna.coverFace, "no face crop from the edited original")
            assertNull(anna.coverPath, "nor the original whole (iOS's round cover)")
            assertEquals("/trip.jpg", vm.state.value.albums.single().coverPath, "an unedited cover survives")
            assertEquals("/l.jpg", vm.state.value.places.single().coverPath)
        } finally {
            silent.release()
        }
    }

    /** ⚠ A cover written before its `contentId` was recorded cannot be checked, so it is not drawn. */
    @Test
    fun aSnapshotCoverWithNoContentId_isNotDrawn_butARenditionCoverIs() = vmTest(dispatcher) { vms ->
        val silent = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        val old = lastTime.copy(
            places = listOf(SnapshotSection("place:p1", "Lisbon", "Portugal", 40, "/l.jpg")),
            years = listOf(SnapshotSection("year:2025", "2025", "900 photos", 900, "/Trash/.edits/y-1-x.jpg")),
        )
        try {
            val vm = vm(vms, EditsHeld(silent), storeWith(old).second)
            testScheduler.runCurrent()

            assertNull(vm.state.value.places.single().coverPath, "unknown: not drawn")
            assertEquals("/Trash/.edits/y-1-x.jpg", vm.state.value.years.single().coverPath, "the edit's own picture")
        } finally {
            silent.release()
        }
    }

    /** ⚠ Fails closed: a Control too slow to say which photos are edited gets a snapshot with no covers. */
    @Test
    fun aSnapshotWhoseEditsCannotBeKnown_isDrawnWithNoCovers() = vmTest(dispatcher) { vms ->
        val silent = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        val held = CompletableDeferred<Unit>()
        try {
            val vm = vm(vms, EditsHeld(silent, held), storeWith(lastTime).second)
            testScheduler.advanceTimeBy(CollectionsViewModel.SNAPSHOT_EDITS_WAIT_MS + 1)
            testScheduler.runCurrent()

            val state = vm.state.value
            assertEquals(listOf("Anna"), state.people.map { it.title }, "drawn, titles and counts and all")
            assertNull(state.people.single().coverFace)
            assertEquals(listOf(null), (state.people + state.places + state.years).map { it.coverPath }.distinct())
            assertNull(state.albums.single().coverPath)
        } finally {
            held.complete(Unit)
            silent.release()
        }
    }

    /**
     * §38 defers the FIRST live refresh until the feed's first rows; the snapshot is not deferred — it
     * is drawn at construction, LOADING, and the live refresh replaces it once the gate opens.
     */
    @Test
    fun theSnapshotIsDrawnWhileTheFirstRefreshWaitsForTheFeed() = vmTest(dispatcher) { vms ->
        val gate = FeedFirstPage()
        val vm = vms.own(
            CollectionsViewModel(
                MockPhotosRepository(photos = listOf(photo("a"))),
                yearOf = { 1970 },
                refreshTimeoutMs = 5_000,
                startAfter = gate,
                snapshotStore = storeWith(lastTime).second,
                currentRealm = realmA,
                snapshotDispatcher = dispatcher,
            ),
        )
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("2025"), vm.state.value.years.map { it.title }, "drawn before the feed's rows land")
        assertEquals(SectionLoad.LOADING, vm.state.value.libraryLoad, "and not as current")

        gate.markLanded()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("1970"), vm.state.value.years.map { it.title }, "the live refresh replaced it")
    }

    /** The cards reach BOTH shells through the shared layout — rows, not placeholders. */
    @Test
    fun theSnapshotsCardsFlowThroughTheSharedLayout() = vmTest(dispatcher) { vms ->
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        try {
            val vm = vm(vms, repository, storeWith(lastTime).second)
            testScheduler.runCurrent()

            val layout = CollectionsLayout.of(vm.state.value, groupingRunsHere = true) as CollectionsLayout.Content
            assertEquals(listOf("Anna"), layout.people?.people?.map { it.title })
            assertFalse(layout.people!!.pending, "no placeholder over drawn people")
            assertFalse(layout.libraryPending, "no placeholder over drawn years")
            assertEquals(
                listOf(CollectionSection.PLACES, CollectionSection.YEARS),
                layout.rows.map { it.section },
            )
            assertEquals("3 photos", layout.tiles.first { it.kind == CollectionsTile.Kind.FAVOURITES }.subtitle)
        } finally {
            repository.release()
        }
    }

    @Test
    fun withoutASnapshotTheTabStartsFromPlaceholdersAsBefore() = vmTest(dispatcher) { vms ->
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        try {
            val vm = vm(vms, repository, storeWith(null).second)
            testScheduler.runCurrent()
            assertTrue(vm.state.value.years.isEmpty())
            assertEquals(SectionLoad.LOADING, vm.state.value.libraryLoad)
            assertFalse(vm.state.value.isEmpty, "loading, not 'nothing here yet'")
        } finally {
            repository.release()
        }
    }

    @Test
    fun liveDataReplacesTheSnapshot() = vmTest(dispatcher) { vms ->
        val vm = vm(vms, MockPhotosRepository(photos = listOf(photo("a"))), storeWith(lastTime).second)
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf("1970"), state.years.map { it.title }, "the live years, not 2025")
        assertTrue(state.places.isEmpty(), "the live answer has no places, and it wins")
        assertTrue(state.people.isEmpty(), "nor any people")
        assertTrue(state.smartAlbums.all { it.snapshotCard == null }, "no snapshot card survives a live read")
        assertEquals(SectionLoad.LOADED, state.libraryLoad)
        assertEquals(SectionLoad.LOADED, state.peopleLoad)
    }

    /** Stale beats blank, said as stale: a failed read keeps the cards, and the layout says "Couldn't refresh". */
    @Test
    fun aSectionWhoseLiveReadFailsKeepsTheSnapshot_andSaysSo() = vmTest(dispatcher) { vms ->
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))), only = "faceGroups")
        try {
            val vm = vm(vms, repository, storeWith(lastTime).second)
            testScheduler.advanceTimeBy(6_000)
            testScheduler.runCurrent()

            val state = vm.state.value
            assertEquals(SectionLoad.FAILED, state.peopleLoad)
            assertEquals(listOf("Anna"), state.people.map { it.title }, "the last people stay on screen")
            assertEquals(listOf("1970"), state.years.map { it.title }, "while the years are live")
            val layout = CollectionsLayout.of(state, groupingRunsHere = true) as CollectionsLayout.Content
            assertEquals("Couldn't refresh people.", layout.people?.failure)
        } finally {
            repository.release()
        }
    }

    // ── the file ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun aCorruptSnapshotIsIgnored() = vmTest(dispatcher) { vms ->
        val files = MemoryOutboxStore().apply { entries[JsonCollectionsSnapshotStore.entryIdFor("A")] = "{\"version\":1,\"albums\":[{" }
        val store = JsonCollectionsSnapshotStore(files)
        assertNull(store.read("A"))

        val vm = vm(vms, MockPhotosRepository(photos = listOf(photo("a"))), store)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("1970"), vm.state.value.years.map { it.title }, "the tab loads as if there were none")
    }

    @Test
    fun aSnapshotOfAnotherVersionIsIgnored() {
        val files = MemoryOutboxStore()
        val id = JsonCollectionsSnapshotStore.entryIdFor("A")
        files.entries[id] = """{"version":${CollectionsSnapshot.FORMAT_VERSION + 1},"realm":"A","favouriteCount":7}"""
        assertNull(JsonCollectionsSnapshotStore(files).read("A"), "a newer shape")
        files.entries[id] = """{"version":0,"realm":"A","favouriteCount":7}"""
        assertNull(JsonCollectionsSnapshotStore(files).read("A"), "an older shape")
    }

    @Test
    fun aSnapshotRoundTrips() {
        val (_, store) = storeWith(lastTime)
        assertEquals(lastTime.copy(realm = "A"), store.read("A"))
    }

    @Test
    fun aWriteFailureIsIgnored() = vmTest(dispatcher) { vms ->
        val files = MemoryOutboxStore().apply { failWrites = true }
        val vm = vm(vms, MockPhotosRepository(photos = listOf(photo("a"))), JsonCollectionsSnapshotStore(files))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("1970"), vm.state.value.years.map { it.title })
        assertNull(vm.state.value.error)
    }

    /** ⚠⚠ The snapshot holds cards — never a photo list, which is the library. */
    @Test
    fun aRefreshWritesTheCardsAndNeverThePhotoLists() = vmTest(dispatcher) { vms ->
        val library = listOf(photo("a", 3_000L), photo("b", 2_000L), photo("c", 1_000L))
        val repository = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(FaceGroup("g1", listOf("/a.jpg", "/b.jpg", "/c.jpg"))) to PeopleStatus.Available
        }
        val (files, store) = storeWith(null)
        vm(vms, repository, store)
        testScheduler.advanceUntilIdle()

        val written = assertNotNull(store.read("A"), "written after the refresh, under the realm")
        assertEquals(listOf("1970"), written.years.map { it.title })
        assertEquals(3, written.years.single().photoCount)
        assertEquals(3, written.people.single().photoCount)
        val payload = files.entries.getValue(JsonCollectionsSnapshotStore.entryIdFor("A"))
        // The cover is one path; the other photographs appear nowhere in the file.
        assertFalse("/b.jpg" in payload, "a photo list was persisted: $payload")
        assertFalse("/c.jpg" in payload, "a photo list was persisted: $payload")
        assertFalse("capturedAtMs" in payload, "a Photo was persisted: $payload")
    }

    // ── one realm's, and gone with the identity ───────────────────────────────────────────────

    /** ⚠⚠ A snapshot written under realm A is never drawn under realm B. */
    @Test
    fun aSnapshotWrittenUnderOneRealmIsNeverDrawnUnderAnother() = vmTest(dispatcher) { vms ->
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        try {
            val vm = vm(vms, repository, storeWith(lastTime, realm = "A").second, realm = CurrentRealm { "B" })
            testScheduler.runCurrent()
            assertTrue(vm.state.value.people.isEmpty(), "realm A's people drawn under realm B")
            assertTrue(vm.state.value.years.isEmpty())
            assertTrue(vm.state.value.albums.isEmpty())
        } finally {
            repository.release()
        }
    }

    /** ⚠⚠ Reset or sign-out (no realm on the device) deletes it, and the next cold start draws placeholders. */
    @Test
    fun noRealmDeletesTheSnapshot_andTheNextColdStartDrawsPlaceholders() = vmTest(dispatcher) { vms ->
        val (files, store) = storeWith(lastTime)
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        try {
            val afterReset = vm(vms, repository, store, realm = CurrentRealm { null })
            testScheduler.runCurrent()
            assertTrue(afterReset.state.value.people.isEmpty(), "nothing drawn for a device with no realm")
            assertTrue(files.entries.isEmpty(), "the snapshot is deleted: ${files.entries.keys}")

            // Re-onboarded into the SAME realm: still nothing to draw — the reset dropped it.
            val next = vm(vms, repository, store, realm = realmA)
            testScheduler.runCurrent()
            assertTrue(next.state.value.people.isEmpty())
            assertTrue(next.state.value.years.isEmpty())
            assertEquals(SectionLoad.LOADING, next.state.value.libraryLoad, "placeholders, loading")
        } finally {
            repository.release()
        }
    }

    /** Control's two teardown signals: no ownerId (reset / Forget this device) and a revoked device. */
    @Test
    fun controlsTeardownSignalsAreNoRealm() {
        assertNull(realmFrom(null, SyncHealthStatus.CONNECTIVITY_ONLINE), "reset or sign-out")
        assertNull(realmFrom("A", SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED), "revoked")
        assertEquals("A", realmFrom("A", SyncHealthStatus.CONNECTIVITY_ONLINE))
        assertEquals("A", realmFrom("A", null), "no health row yet is no evidence of a teardown")
        // ⚠ Locked is not a teardown — but this session cannot read the realm: unknown.
        assertFailsWith<IllegalStateException> { realmFrom("A", SyncHealthStatus.CONNECTIVITY_LOCKED) }
    }

    /** A revoked device: the realm answer is "none", so the snapshot goes and nothing is drawn. */
    @Test
    fun aRevokedDeviceDropsTheSnapshot() = vmTest(dispatcher) { vms ->
        val (files, store) = storeWith(lastTime)
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        try {
            val vm = vm(vms, repository, store, realm = CurrentRealm { realmFrom("A", SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED) })
            testScheduler.runCurrent()
            assertTrue(vm.state.value.people.isEmpty())
            assertTrue(files.entries.isEmpty())
        } finally {
            repository.release()
        }
    }

    /**
     * ⚠⚠ Never shown for an identity this session cannot read: a realm that cannot be told — Control
     * not answering, or the realm locked — draws nothing. ⚠ And drops nothing: that is not a teardown.
     */
    @Test
    fun anUnknownRealmDrawsNothing_andKeepsTheFile() = vmTest(dispatcher) { vms ->
        val (files, store) = storeWith(lastTime)
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        try {
            val locked = vm(vms, repository, store, realm = CurrentRealm { realmFrom("A", SyncHealthStatus.CONNECTIVITY_LOCKED) })
            val silent = vm(vms, repository, store, realm = CurrentRealm { awaitCancellation() })
            testScheduler.runCurrent()
            assertTrue(locked.state.value.people.isEmpty(), "a locked realm's snapshot was drawn")
            assertTrue(silent.state.value.people.isEmpty(), "an unverified realm's snapshot was drawn")
            assertEquals(setOf(JsonCollectionsSnapshotStore.entryIdFor("A")), files.entries.keys)
        } finally {
            repository.release()
        }
    }

    /**
     * The realm changed under a running app: what is on screen may mix two realms — never written. ⚠ What
     * may be written is the new realm's OWN fresh read, after the reset (no A card in it).
     */
    @Test
    fun aRealmThatChangesWhileRunningDropsTheSnapshotInsteadOfWritingIt() = vmTest(dispatcher) { vms ->
        val (files, store) = storeWith(lastTime)
        var realm = "A"
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))), only = "albums")
        try {
            vm(vms, repository, store, realm = CurrentRealm { realm })
            testScheduler.runCurrent() // the snapshot is read and drawn under A; the refresh still runs
            realm = "B"
            repository.release()
            testScheduler.advanceUntilIdle() // the refresh ends and asks again: B
            assertTrue(store.read("A") == null, "A's snapshot survived the switch")
            val underB = store.read("B")
            assertTrue(
                underB == null || (underB.albums.isEmpty() && underB.people.isEmpty() && underB.years.map { it.title } == listOf("1970")),
                "a mixed state was written under B: $underB",
            )
        } finally {
            repository.release()
        }
    }

    /**
     * ⚠⚠ Revocation arrives LATE: a fresh Control first reports the proxy's optimistic
     * `online`, and `device_unrecognized` only once the relay has refused it. The cards already drawn
     * must come off the screen, the file must go, and nothing may write it back.
     */
    @Test
    fun aRevocationAfterAnOptimisticOnlineTakesTheCardsOffAndPurges() =
        revocationArrivingLate(initialHealth = SyncHealthStatus.CONNECTIVITY_ONLINE)

    /** The same, when the health row has not even been derived yet. */
    @Test
    fun aRevocationAfterNoHealthYetTakesTheCardsOffAndPurges() =
        revocationArrivingLate(initialHealth = null)

    private fun revocationArrivingLate(initialHealth: String?) = vmTest(dispatcher) { vms ->
        val (files, store) = storeWith(lastTime)
        val health = MutableStateFlow(initialHealth)
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        try {
            val vm = vm(vms, repository, store, realm = healthCheckedRealm(ownerId = { "A" }, connectivity = health))
            testScheduler.runCurrent()
            assertEquals(listOf("Anna"), vm.state.value.people.map { it.title }, "drawn before the relay has spoken")

            health.value = SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED
            testScheduler.runCurrent()
            val state = vm.state.value
            assertTrue(state.smartAlbums.none { it.snapshotCard != null }, "a revoked realm's cards stayed: ${state.smartAlbums.map { it.title }}")
            assertTrue(state.albums.isEmpty(), "a revoked realm's albums stayed")
            assertEquals(0, state.favouriteCount)
            assertTrue(files.entries.isEmpty(), "the snapshot survived the revocation")

            repository.release()
            testScheduler.advanceUntilIdle()
            assertTrue(files.entries.isEmpty(), "written back after the revocation: ${files.entries.keys}")
        } finally {
            repository.release()
        }
    }

    /**
     * ⚠⚠ A LATE lock — a sealed Control restart first reports `online` (or nothing):
     * the session can no longer read the realm, so the cards come off; but locking is no teardown, so
     * the file stays for the next unlocked start.
     */
    @Test
    fun aLateLockHidesTheCardsButKeepsTheFile() = vmTest(dispatcher) { vms ->
        val (files, store) = storeWith(lastTime)
        val health = MutableStateFlow<String?>(SyncHealthStatus.CONNECTIVITY_ONLINE)
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        try {
            val vm = vm(vms, repository, store, realm = healthCheckedRealm(ownerId = { "A" }, connectivity = health))
            testScheduler.runCurrent()
            assertEquals(listOf("Anna"), vm.state.value.people.map { it.title })

            health.value = SyncHealthStatus.CONNECTIVITY_LOCKED
            testScheduler.runCurrent()
            val state = vm.state.value
            assertTrue(state.smartAlbums.none { it.snapshotCard != null }, "a locked realm's cards stayed: ${state.smartAlbums.map { it.title }}")
            assertTrue(state.albums.isEmpty(), "a locked realm's albums stayed")
            assertEquals(setOf(JsonCollectionsSnapshotStore.entryIdFor("A")), files.entries.keys, "a lock is not a teardown")
        } finally {
            repository.release()
        }
    }

    /**
     * The realm (or the file) can answer AFTER a section's read has already failed. A
     * FAILED section with nothing in it takes the cards and stays FAILED; a section that LOADED — even
     * empty — stays authoritative.
     */
    @Test
    fun aLateSnapshotFillsAnEmptyFailedSection_butNeverOneThatLoaded() = vmTest(dispatcher) { vms ->
        val (_, store) = storeWith(lastTime)
        val realmAnswers = CompletableDeferred<Unit>()
        val repository = MockPhotosRepository(photos = listOf(photo("a"))).apply {
            peopleOutcome = emptyList<FaceGroup>() to PeopleStatus.Failed
        }
        val vm = vm(vms, repository, store, realm = CurrentRealm { realmAnswers.await(); "A" })
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertEquals(SectionLoad.FAILED, vm.state.value.peopleLoad, "People failed before the realm answered")
        assertEquals(SectionLoad.LOADED, vm.state.value.placesLoad)

        realmAnswers.complete(Unit)
        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        assertEquals(listOf("Anna"), state.people.map { it.title }, "the failed, empty section takes the cards")
        assertEquals(SectionLoad.FAILED, state.peopleLoad, "and stays FAILED — never drawn as current")
        assertTrue(state.places.isEmpty(), "a LOADED empty section is authoritative: ${state.places.map { it.title }}")
        assertEquals(listOf("1970"), state.years.map { it.title }, "and live years are never replaced")
    }

    /**
     * ⚠⚠ Photos stays alive while the realm changes A → B, and a section's read keeps
     * failing after the switch — A's cards (here Anna, kept because People failed) must neither stay on
     * screen nor be saved under B by a later partial refresh.
     */
    @Test
    fun aRealmSwitchWhileRunningResetsTheCards_andNeverSavesThemUnderTheNewRealm() =
        realmChangeWhileRunning(path = listOf("B"))

    /** The same through a reset: A → no realm → B. B is not a "switch" from null — it must still be clean. */
    @Test
    fun aResetThenANewRealmResetsTheCards_andNeverSavesThemUnderTheNewRealm() =
        realmChangeWhileRunning(path = listOf(null, "B"))

    private fun realmChangeWhileRunning(path: List<String?>) = vmTest(dispatcher) { vms ->
        val (files, store) = storeWith(lastTime)
        var realm: String? = "A"
        // People's read FAILS throughout, so stale-beats-blank keeps whatever People holds.
        val repository = MockPhotosRepository(photos = listOf(photo("a"))).apply {
            peopleOutcome = emptyList<FaceGroup>() to PeopleStatus.Failed
        }
        val vm = vm(vms, repository, store, realm = CurrentRealm { realm })
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("Anna"), vm.state.value.people.map { it.title }, "A's card, kept over the failure")

        for (next in path) {
            realm = next
            vm.refresh()
            testScheduler.advanceUntilIdle()
        }
        // A partial refresh under B — People still failing.
        vm.refresh()
        testScheduler.advanceUntilIdle()

        val underB = store.read("B")
        assertTrue(underB == null || underB.people.none { it.title == "Anna" }, "A's person was saved under B: ${underB?.people}")
        assertTrue(files.entries.values.none { "Anna" in it }, "A's person is on disk: ${files.entries.keys}")
        assertTrue(vm.state.value.people.none { it.title == "Anna" }, "A's person is still on screen under B")
    }

    /** Every read can be made to fail at once — a Control that answers nothing after a realm change. */
    private class FailableRepository(private val inner: PhotosRepository) : PhotosRepository by inner {
        var failing = false
        var albumNames: List<String>? = null
        override suspend fun albums(): AlbumsResult = when {
            failing -> AlbumsResult.Failed("down")
            albumNames != null -> AlbumsResult.Albums(albumNames!!.map { AlbumSummary("id-$it", it, 0L, 1, null) })
            else -> inner.albums()
        }
        override suspend fun favourites(): PhotoPageResult = if (failing) PhotoPageResult.Unreachable else inner.favourites()
        override suspend fun allPhotosRead(): List<Photo>? = if (failing) null else inner.allPhotosRead()
        override suspend fun placesRead(): List<PhotoPlace>? = if (failing) null else inner.placesRead()
        override suspend fun allLabelsRead(): List<PhotoLabel>? = if (failing) null else inner.allLabelsRead()
        override suspend fun faceGroups(limit: Int): Pair<List<FaceGroup>, PeopleStatus> =
            if (failing) emptyList<FaceGroup>() to PeopleStatus.Failed else inner.faceGroups(limit)
    }

    /**
     * ⚠⚠ The realm changes and then EVERY section fails — the change must still be seen (it is not
     * discovered by a successful refresh) and A's cards must come off.
     */
    @Test
    fun aRealmSwitchWithEverySectionFailingStillResetsTheCards() = realmChangeThenEverythingFails(path = listOf("B"))

    @Test
    fun aResetThenANewRealmWithEverySectionFailingStillResetsTheCards() =
        realmChangeThenEverythingFails(path = listOf(null, "B"))

    private fun realmChangeThenEverythingFails(path: List<String?>) = vmTest(dispatcher) { vms ->
        val (files, store) = storeWith(lastTime)
        var realm: String? = "A"
        val repository = FailableRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        val vm = vm(vms, repository, store, realm = CurrentRealm { realm })
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("1970"), vm.state.value.years.map { it.title }, "A's live years")

        repository.failing = true
        for (next in path) {
            realm = next
            vm.refresh()
            testScheduler.advanceUntilIdle()
        }

        val state = vm.state.value
        assertTrue(state.years.isEmpty(), "A's years are still on screen under B: ${state.years.map { it.title }}")
        assertTrue(state.smartAlbums.isEmpty() && state.albums.isEmpty(), "A's cards are still on screen under B")
        assertTrue(files.entries.isEmpty(), "A's snapshot outlived the realm change: ${files.entries.keys}")
    }

    /**
     * ⚠⚠ Two saves race, and the OLDER one's realm read answers last. The fence serves
     * arrival order, so without a generation stamp the older capture lands over the newer one and a
     * cold start resurrects an album that was renamed.
     */
    @Test
    fun anOlderSaveThatResumesLastNeverOverwritesANewerOne() = vmTest(dispatcher) { vms ->
        val (files, store) = storeWith(null)
        val repository = FailableRepository(MockPhotosRepository(photos = listOf(photo("a")))).apply {
            albumNames = listOf("Old name")
        }
        val olderSaveMayAnswer = CompletableDeferred<Unit>()
        var realmCalls = 0
        val vm = vm(
            vms,
            repository,
            store,
            realm = CurrentRealm {
                realmCalls++
                // The second ask is the first refresh's save: held back until the newer save has written.
                if (realmCalls == 2) olderSaveMayAnswer.await()
                "A"
            },
        )
        testScheduler.runCurrent() // refresh 1 ends; its save waits on the realm
        assertEquals(listOf("Old name"), vm.state.value.albums.map { it.name })

        repository.albumNames = listOf("New name") // the album is renamed
        vm.refresh()
        testScheduler.runCurrent() // refresh 2 ends; its save writes at once
        assertEquals(listOf("New name"), store.read("A")?.albums?.map { it.name }, "the newer save wrote")

        olderSaveMayAnswer.complete(Unit) // now the older save resumes
        testScheduler.runCurrent()
        assertEquals(
            listOf("New name"),
            store.read("A")?.albums?.map { it.name },
            "an older capture overwrote a newer one: ${files.entries.values}",
        )
    }

    /** One realm's snapshot at a time: writing B's drops A's. */
    @Test
    fun writingOneRealmsSnapshotDropsAnyOther() {
        val (files, store) = storeWith(lastTime, realm = "A")
        store.write("B", lastTime)
        assertEquals(setOf(JsonCollectionsSnapshotStore.entryIdFor("B")), files.entries.keys)
        assertNull(store.read("A"))
        store.clear()
        assertTrue(files.entries.isEmpty())
    }

    /** Without a way to tell realms apart there is no snapshot at all. */
    @Test
    fun noRealmProviderMeansNoSnapshot() = vmTest(dispatcher) { vms ->
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        try {
            val vm = vm(vms, repository, storeWith(lastTime).second, realm = null)
            testScheduler.runCurrent()
            assertTrue(vm.state.value.people.isEmpty())
        } finally {
            repository.release()
        }
    }

    // ── a card's own page ──────────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ A snapshot card can be tapped before the live refresh lands. Its page must show loading —
     * an empty grid there says "no photos" about a person who has 120.
     */
    @Test
    fun aTapOnASnapshotCardBeforeTheLiveRefreshIsLoadingNotEmpty() = vmTest(dispatcher) { vms ->
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))))
        try {
            val vm = vm(vms, repository, storeWith(lastTime).second)
            testScheduler.runCurrent()

            val page = vm.state.value.smartPage("person:name:anna")
            assertEquals("Anna", page.album?.title, "the page knows whose it is")
            assertTrue(page.loading, "and shows loading, not an empty grid")
            assertFalse(page.failed)
            assertTrue(vm.state.value.smartPage("person:g7").loading, "an alias route too")
            assertTrue(vm.state.value.smartPage("year:2025").loading, "a year too")
            assertTrue(vm.state.value.smartPage("year:1999").loading, "an id nothing answers to yet: still loading")
        } finally {
            repository.release()
        }
    }

    @Test
    fun aSnapshotCardWhoseReadFailedSaysSoRatherThanEmpty() = vmTest(dispatcher) { vms ->
        val repository = SilentRepository(MockPhotosRepository(photos = listOf(photo("a"))), only = "faceGroups")
        try {
            val vm = vm(vms, repository, storeWith(lastTime).second)
            testScheduler.advanceTimeBy(6_000)
            testScheduler.runCurrent()

            val page = vm.state.value.smartPage("person:name:anna")
            assertFalse(page.loading)
            assertTrue(page.failed, "its photographs are unknown, not absent")
        } finally {
            repository.release()
        }
    }

    @Test
    fun onceLiveTheSamePageShowsThePhotographs() = vmTest(dispatcher) { vms ->
        val vm = vm(vms, MockPhotosRepository(photos = listOf(photo("a"))), storeWith(lastTime).second)
        testScheduler.advanceUntilIdle()

        val page = vm.state.value.smartPage("year:1970")
        assertFalse(page.loading)
        assertFalse(page.failed)
        assertEquals(listOf("/a.jpg"), page.album?.photos?.map { it.path })
        // A snapshot section that no longer exists live resolves to nothing — and is not loading.
        val gone = vm.state.value.smartPage("year:2025")
        assertNull(gone.album)
        assertFalse(gone.loading)
    }
}
