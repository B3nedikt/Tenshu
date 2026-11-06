package app.tenshu.drive.backup

import app.tenshu.ingest.backup.TenshuBackupPreferences
import app.tenshu.ingest.backup.BackgroundDrainResult
import app.tenshu.ingest.backup.BackupConstraint
import app.tenshu.ingest.backup.foregroundServiceState
import app.tenshu.ingest.backup.startsForegroundService
import app.tenshu.drive.data.NoopBackupFolderGrants
import app.tenshu.drive.ui.screens.settings.SyncedFoldersViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import app.tenshu.ingest.backup.BackupEnvironment
import app.tenshu.ingest.backup.BackupNetworkStatus
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.ingest.backup.BackupUploadOutcome
import app.tenshu.ingest.backup.IngestRegistry
import app.tenshu.ingest.backup.MediaAccess
import app.tenshu.ingest.backup.MediaBucket
import app.tenshu.ingest.backup.MediaItem
import app.tenshu.ingest.backup.MAX_CONSECUTIVE_BUCKET_FAILURES
import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.drive.ui.FakeFilesRepository
import app.tenshu.ingest.backup.BackupFailureKind
import app.tenshu.ingest.backup.BackupPauseReason
import app.tenshu.ingest.backup.BackupState
import app.tenshu.ingest.backup.BackupGate
import app.tenshu.ingest.backup.BackupSettings
import app.tenshu.ingest.backup.BackupSource
import app.tenshu.ingest.backup.evaluateBackupGate
import app.tenshu.ingest.backup.NewFolderAnnouncer
import app.tenshu.ingest.backup.DuplicateContentGate
import app.tenshu.ingest.backup.DuplicateContentView
import app.tenshu.ingest.backup.NoopDuplicateContentGate
import app.tenshu.ingest.backup.NoopNewFolderAnnouncer
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.ingest.backup.aggregateState
import app.tenshu.ingest.backup.backupStatusFor
import app.tenshu.ingest.backup.documentTreeBackupSource
import app.tenshu.ingest.backup.galleryBackupSource
import org.tenshu.core.ingest.IngestSource
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import app.tenshu.ingest.IngestSettings
import app.tenshu.ingest.backup.TenshuDuplicateContentGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.tenshu.core.Service
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.ReplySizes
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.getOrNull
import org.tenshu.file.client.fileService
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncedFolderControllerTest {

    // The controller is driven by flow collectors that must run eagerly; an
    // UnconfinedTestDispatcher propagates each StateFlow emission synchronously
    // through the combine → trigger → drain chain. Every scope is owned by the
    // test's [OwnedViewModels], which cancels AND joins it before the body returns — a
    // bare `cancel()` returns while the never-completing collectors are still unwinding.
    private fun TestScope.unconfinedScope(vms: OwnedViewModels): CoroutineScope =
        vms.ownScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    private fun item(name: String, cursor: Long) =
        MediaItem(uri = "uri/$name", fileName = name, addedAtMillis = cursor, cursor = cursor.toString())

    private class FakeMediaSource(
        private val buckets: List<MediaBucket>,
        itemsByBucket: Map<String, List<MediaItem>>,
    ) : MediaSourceProvider {
        /** Mutable so a test can make an item *arrive* mid-run. */
        val itemsByBucket = itemsByBucket.toMutableMap()
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        /** Lets a test make a bucket *appear* mid-run (the new-folder prompt case). */
        var bucketsOverride: List<MediaBucket>? = null
        override suspend fun listBuckets(includeVideo: Boolean) = bucketsOverride ?: buckets
        override fun observeChanges(watched: Set<String>): Flow<Unit> = changes
        override suspend fun listNewItems(bucketId: String, sinceCursor: String?, includeVideo: Boolean): List<MediaItem> {
            val cur = sinceCursor?.toLongOrNull() ?: Long.MIN_VALUE
            return (itemsByBucket[bucketId] ?: emptyList())
                .filter { (it.cursor.toLongOrNull() ?: 0L) > cur }
                .sortedBy { it.cursor.toLongOrNull() ?: 0L }
        }
    }

    /** ⚠ `var`, not `val`: the drain re-reads the environment per file, so a test needs to be able
     *  to move it mid-drain (unplugged, dropped to cellular). */
    private class FakeEnv(
        var net: BackupNetworkStatus = BackupNetworkStatus.UNMETERED,
        var charging: Boolean = true,
    ) : BackupEnvironment {
        override fun network() = net
        override fun isCharging() = charging

        /** What the platform would raise after moving [net] / [charging]. */
        val moved = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override fun changes(): Flow<Unit> = moved
    }

    private class FakePrefs(
        enabled: Boolean,
        buckets: List<String>,
        videos: Boolean = false,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
        pausedByUser: Boolean = false,
        private val device: String? = "dev1",
    ) : BackupPreferences {
        val enabledFlow = MutableStateFlow(enabled)
        val bucketsFlow = MutableStateFlow(buckets)
        val videosFlow = MutableStateFlow(videos)
        val wifiOnlyFlow = MutableStateFlow(wifiOnly)
        val chargingOnlyFlow = MutableStateFlow(chargingOnly)
        val newFoldersFlow = MutableStateFlow(true)
        val documentTreeUrisFlow = MutableStateFlow<List<String>>(emptyList())
        val cursors = mutableMapOf<String, String>()

        // ⚠⚠ Models production, where a DEVICE-scoped `observe*()` emits the declared
        // default *before* this device's stored row (TenshuBackupPreferences.observe — the
        // seed exists so a screen renders while sync identity resolves). A fake returning
        // bare StateFlows would not notice a drain that reads its selection with `first()`
        // and therefore always enumerates an empty set. Keep the seed: it is what makes a
        // `first()` regression fail a test instead of a phone.
        private fun <T> seeded(flow: Flow<T>, seed: T): Flow<T> = flow {
            emit(seed)
            emitAll(flow)
        }

        val pausedByUserFlow = MutableStateFlow(pausedByUser)
        override fun observePausedByUser() = seeded(pausedByUserFlow, false)
        override suspend fun setPausedByUser(paused: Boolean) { pausedByUserFlow.value = paused }
        override suspend fun readPausedByUser() = pausedByUserFlow.value

        override fun observeEnabled() = seeded(enabledFlow, false)
        override suspend fun setEnabled(enabled: Boolean) { enabledFlow.value = enabled }
        override suspend fun readEnabled() = enabledFlow.value
        override fun observeSelectedBuckets() = seeded(bucketsFlow, emptyList())
        override suspend fun setSelectedBuckets(ids: List<String>) { bucketsFlow.value = ids }
        override suspend fun readSelectedBuckets() = bucketsFlow.value
        override fun observeGrantedBuckets() = flowOf(emptyList<String>())
        override suspend fun readGrantedBuckets(): List<String> = emptyList()
        override suspend fun addGrantedBucket(id: String) = Unit
        override fun observeDocumentTreeUris() = seeded(documentTreeUrisFlow, emptyList())
        override suspend fun readDocumentTreeUris() = documentTreeUrisFlow.value
        override suspend fun addDocumentTreeUri(uri: String) {
            if (uri !in documentTreeUrisFlow.value) documentTreeUrisFlow.value += uri
        }
        override suspend fun removeDocumentTreeUri(uri: String) {
            documentTreeUrisFlow.value -= uri
        }
        override fun observeBackupNewFolders() = seeded(newFoldersFlow, true)
        override suspend fun setBackupNewFolders(enabled: Boolean) { newFoldersFlow.value = enabled }
        override suspend fun readBackupNewFolders() = newFoldersFlow.value
        val knownBuckets = mutableSetOf<String>()
        override suspend fun readKnownBuckets(): Set<String> = knownBuckets.toSet()
        override suspend fun addKnownBuckets(ids: Set<String>) { knownBuckets += ids }
        override fun observeVideos() = seeded(videosFlow, false)
        override suspend fun setVideos(enabled: Boolean) { videosFlow.value = enabled }
        override suspend fun readVideos() = videosFlow.value
        // Realm-scoped in production, so no identity seed — the relation read resolves an
        // absent row to the declared default directly.
        override fun observeWifiOnly() = wifiOnlyFlow
        override suspend fun setWifiOnly(enabled: Boolean) { wifiOnlyFlow.value = enabled }
        override suspend fun readWifiOnly() = wifiOnlyFlow.value
        override fun observeChargingOnly() = seeded(chargingOnlyFlow, false)
        override suspend fun setChargingOnly(enabled: Boolean) { chargingOnlyFlow.value = enabled }
        override suspend fun readChargingOnly() = chargingOnlyFlow.value
        // Mirrors prod: cursors are stored keyed by "$sourceId/$bucketId" and read
        // back projected to one source (prefix stripped). Tests assert against the
        // namespaced key, e.g. cursors["drive.gallery/cam"].
        override suspend fun readCursors(sourceId: String): Map<String, String> {
            val prefix = "$sourceId/"
            return cursors.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        }
        override suspend fun setCursor(sourceId: String, bucketId: String, cursor: String) {
            cursors["$sourceId/$bucketId"] = cursor
        }
        override suspend fun clearCursor(sourceId: String, bucketId: String) {
            cursors.remove("$sourceId/$bucketId")
        }
        override suspend fun deviceLabel() = device
    }

    private class FakeIngestRegistry(
        @Volatile var enabled: Boolean = true,  // the central kill switch
    ) : IngestRegistry {
        val registered = mutableListOf<Triple<String, String, String>>()  // sourceId, kind, watchedPaths
        val statuses = mutableListOf<Pair<String, Long>>()                 // status, pendingCount
        override suspend fun registerSource(sourceId: String, displayName: String, sourceKind: String, watchedPaths: String) {
            registered += Triple(sourceId, sourceKind, watchedPaths)
        }
        override suspend fun updateStatus(sourceId: String, status: String, pendingCount: Long) {
            statuses += status to pendingCount
        }
        override suspend fun isEnabled(sourceId: String): Boolean = enabled
    }

    private val camBucket = MediaBucket(id = "cam", displayName = "Camera", itemCount = 0)

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        prefs: FakePrefs,
        media: FakeMediaSource,
        files: FakeFilesRepository,
        env: FakeEnv = FakeEnv(),
        registry: FakeIngestRegistry = FakeIngestRegistry(),
        announcer: NewFolderAnnouncer = NoopNewFolderAnnouncer,
        mediaAccess: MediaAccess = MediaAccess.FULL,
        duplicates: DuplicateContentGate = NoopDuplicateContentGate,
    ) = SyncedFolderController(
        preferences = prefs,
        sources = listOf(galleryBackupSource(media, prefs)),
        dataPlane = files,
        environment = env,
        registry = registry,
        announcer = announcer,
        mediaAccess = { mediaAccess },
        duplicates = duplicates,
        scope = scope,
    )

    /** Records the new-folder prompts the controller would have surfaced. */
    private class RecordingAnnouncer : NewFolderAnnouncer {
        val announced = mutableListOf<String>()
        override suspend fun announce(bucket: MediaBucket) { announced += bucket.id }
    }

    // ── The ingest-time content skip ─────────────────────────────────────────────────────────

    /** A gate that answers from a fixed set of hashes the realm "already holds". */
    private class FakeDuplicateGate(private val held: Set<String>) : DuplicateContentGate {
        var opened = 0
            private set

        override suspend fun openForDrain(): DuplicateContentView? {
            opened++
            return DuplicateContentView { hashes -> hashes.filterTo(mutableSetOf()) { it in held } }
        }
    }

    @Test
    fun contentAlreadyInTheRealmIsNotUploadedAgain() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("have.jpg", 1), item("new.jpg", 2))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        files.contentHashes["uri/have.jpg"] = "h-have"
        files.contentHashes["uri/new.jpg"] = "h-new"

        controller(
            unconfinedScope(vms), prefs, media, files,
            duplicates = FakeDuplicateGate(setOf("h-have")),
        ).start()
        advanceUntilIdle()

        assertEquals(listOf("new.jpg"), files.backupUploadCalls.map { it.second })
        // ⚠ And the cursor advanced past the skipped one — otherwise the bucket re-offers it
        // every drain forever, hashing it again each time.
        assertEquals("2", prefs.cursors["drive.gallery/cam"])
    }

    @Test
    fun anUnreadableCandidateIsUploadedRatherThanDropped() = vmTest { vms ->
        // ⚠⚠ The failure that would be invisible: a null hash means "could not read", and
        // treating it as a match looks exactly like the skip working while photos go missing.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        // No entry in `contentHashes` → contentHashOf returns null.

        controller(
            unconfinedScope(vms), prefs, media, files,
            duplicates = FakeDuplicateGate(emptySet()),
        ).start()
        advanceUntilIdle()

        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
    }

    @Test
    fun twoIdenticalAssetsInOneRunUploadOnce() = vmTest { vms ->
        // The realm knows nothing yet, so only the drain-scoped memory can catch this.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("a.jpg", 1), item("copy-of-a.jpg", 2))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        files.contentHashes["uri/a.jpg"] = "same"
        files.contentHashes["uri/copy-of-a.jpg"] = "same"

        controller(
            unconfinedScope(vms), prefs, media, files,
            duplicates = FakeDuplicateGate(emptySet()),
        ).start()
        advanceUntilIdle()

        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
    }

    @Test
    fun theDefaultGateSkipsNothingAndReadsNoBytes() = vmTest { vms ->
        // ⚠ The setting being off must cost nothing at all — not "hash and then don't skip".
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        files.contentHashes["uri/a.jpg"] = "h"

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
        assertTrue(files.contentHashCalls.isEmpty(), "no gate means no hashing")
    }

    @Test
    fun selectingAFolder_drainsIt_withoutWaitingForAMediaChange() = vmTest { vms ->
        // Ticking a folder is a request to back up what is already in it. Nothing else
        // would notice: the media observer only speaks when the *library* changes, so a
        // folder full of old photos would sit until an unrelated photo arrived or the
        // 6-hour worker woke.
        val prefs = FakePrefs(enabled = true, buckets = emptyList())
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("old.jpg", 1))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()
        assertTrue(files.backupUploadCalls.isEmpty(), "nothing selected yet")

        prefs.setSelectedBuckets(listOf("cam"))
        advanceUntilIdle()

        assertEquals(listOf("old.jpg"), files.backupUploadCalls.map { it.second })
    }

    @Test
    fun ingestNow_runsAPass_forTheBackUpNowButton() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val controller = controller(unconfinedScope(vms), prefs, media, files)

        controller.start()
        advanceUntilIdle()
        // The startup pass already took a.jpg; a later item is what the button is for.
        media.itemsByBucket["cam"] = listOf(item("a.jpg", 1), item("later.jpg", 2))

        controller.ingestNow()
        advanceUntilIdle()

        assertEquals(listOf("a.jpg", "later.jpg"), files.backupUploadCalls.map { it.second })
    }

    @Test
    fun runsDrainInProcess_isTrueOnlyWhereTheDrainActuallyRuns() = vmTest { vms ->
        // Gates the Back up now button: on desktop the drain lives in Control, so
        // ingestNow() would have no consumer to wake.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), emptyMap())
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        val android = controller(unconfinedScope(vms), prefs, media, files).also { it.start() }
        val desktop = controller(unconfinedScope(vms), prefs, media, files)
            .also { it.startRegistrationOnly() }
        advanceUntilIdle()

        assertTrue(android.runsDrainInProcess)
        assertTrue(!desktop.runsDrainInProcess)
    }

    @Test
    fun drain_readsTheStoredSelection_notThePreferenceFlowsSeededDefault() = vmTest { vms ->
        // What this whole seam exists for: `observeSelectedBuckets()` emits an empty list
        // before this device's stored row, so a drain that takes its selection from
        // `first()` enumerates nothing, reports UP_TO_DATE, and uploads not one file on a
        // device with backup on and a folder selected. The fake seeds exactly like
        // production, so this fails the moment the drain polls a flow.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("a.jpg", 1), item("b.jpg", 2))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        assertEquals(
            listOf("a.jpg", "b.jpg"),
            files.backupUploadCalls.map { it.second },
        )
    }

    @Test
    fun ingestAndAwait_drainsOnAnEnabledDevice_ratherThanReadingTheSeededDisabledDefault() = vmTest { vms ->
        // The headless (WorkManager) entry gets exactly one look at the settings. Reading
        // them from `settingsFlow().first()` would hand it the seeded `enabled = false`, so the
        // gate would say Disabled and the worker report success without draining.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("a.jpg", 1))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        // No start() — this is the headless path, with no collectors primed.
        val ran = controller(unconfinedScope(vms), prefs, media, files).ingestAndAwait()
        advanceUntilIdle()

        assertTrue(ran)
        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
    }

    @Test
    fun drain_uploadsAnOverlappingItemOnce_firstSelectedBucketWins() = vmTest { vms ->
        // iOS albums are VIEWS, not directories: the same asset can appear in several
        // selected albums. Uploading it per-album would cost a full second copy at the
        // relay (contentId is random per write and the CEK/nonce are fresh per emit, so
        // identical bytes do not collapse to one blob) and bill the owner twice.
        val shared = item("shared.jpg", 1)
        val onlyInB = item("b-only.jpg", 2)
        val prefs = FakePrefs(enabled = true, buckets = listOf("albumA", "albumB"))
        val media = FakeMediaSource(
            buckets = listOf(
                MediaBucket(id = "albumA", displayName = "Album A", itemCount = 1),
                MediaBucket(id = "albumB", displayName = "Album B", itemCount = 2),
            ),
            itemsByBucket = mapOf(
                "albumA" to listOf(shared),
                "albumB" to listOf(shared, onlyInB),
            ),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        // Uploaded once, to the first selected bucket's folder — never to Album B's.
        assertEquals(
            listOf(
                "/Backup/dev1/Album A" to "shared.jpg",
                "/Backup/dev1/Album B" to "b-only.jpg",
            ),
            files.backupUploadCalls.map { it.first to it.second },
        )
    }

    @Test
    fun drain_advancesTheCursorPastADedupedItem_soItIsNotReofferedForever() = vmTest { vms ->
        // The deduped item must be treated like Skipped, not skipped-and-forgotten:
        // if its bucket's cursor stayed behind it, every later drain would re-list it
        // and the bucket would never reach up-to-date.
        val shared = item("shared.jpg", 5)
        val prefs = FakePrefs(enabled = true, buckets = listOf("albumA", "albumB"))
        val media = FakeMediaSource(
            buckets = listOf(
                MediaBucket(id = "albumA", displayName = "Album A", itemCount = 1),
                MediaBucket(id = "albumB", displayName = "Album B", itemCount = 1),
            ),
            itemsByBucket = mapOf("albumA" to listOf(shared), "albumB" to listOf(shared)),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        assertEquals(1, files.backupUploadCalls.size)
        assertEquals("5", prefs.cursors["drive.gallery/albumA"])
        assertEquals("5", prefs.cursors["drive.gallery/albumB"])
    }

    @Test
    fun announce_seedsBaselineSilently_thenAnnouncesOnlyTheNewBucket() = vmTest { vms ->
        // First drain baselines whatever the device already has; a bucket that shows
        // up afterwards is the one worth a prompt.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val whatsapp = MediaBucket(id = "wa", displayName = "WhatsApp Images", itemCount = 12)
        val media = FakeMediaSource(buckets = listOf(camBucket), itemsByBucket = emptyMap())
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val announcer = RecordingAnnouncer()

        val ctrl = controller(unconfinedScope(vms), prefs, media, files, announcer = announcer)
        ctrl.start()
        advanceUntilIdle()
        assertTrue(announcer.announced.isEmpty(), "baseline scan must not announce")

        media.bucketsOverride = listOf(camBucket, whatsapp)
        media.changes.emit(Unit)
        advanceUntilIdle()

        assertEquals(listOf("wa"), announcer.announced)
    }

    @Test
    fun announce_standsDownUnderAndroid14PartialMediaAccess() = vmTest { vms ->
        // Under partial access MediaStore filters every query to the user's
        // selection, so "a new bucket" only means "the user picked a photo from a
        // folder they hadn't picked from before". Announcing that is noise — and
        // folding it into the known-set would poison the baseline, making the real
        // folders read as already-seen once full access is granted.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val whatsapp = MediaBucket(id = "wa", displayName = "WhatsApp Images", itemCount = 12)
        val media = FakeMediaSource(buckets = listOf(camBucket), itemsByBucket = emptyMap())
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val announcer = RecordingAnnouncer()

        controller(
            unconfinedScope(vms), prefs, media, files,
            announcer = announcer, mediaAccess = MediaAccess.PARTIAL,
        ).start()
        advanceUntilIdle()
        media.bucketsOverride = listOf(camBucket, whatsapp)
        media.changes.emit(Unit)
        advanceUntilIdle()

        assertTrue(announcer.announced.isEmpty())
        assertTrue(prefs.knownBuckets.isEmpty(), "the baseline must not be seeded from a filtered view")
    }

    @Test
    fun announce_standsDownWhenMediaAccessIsDenied() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(buckets = listOf(camBucket), itemsByBucket = emptyMap())
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val announcer = RecordingAnnouncer()

        controller(
            unconfinedScope(vms), prefs, media, files,
            announcer = announcer, mediaAccess = MediaAccess.DENIED,
        ).start()
        advanceUntilIdle()

        assertTrue(announcer.announced.isEmpty())
        assertTrue(prefs.knownBuckets.isEmpty())
    }

    @Test
    fun announce_isSuppressedWhenTheUserTurnedThePromptOff() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        prefs.newFoldersFlow.value = false
        val whatsapp = MediaBucket(id = "wa", displayName = "WhatsApp Images", itemCount = 12)
        val media = FakeMediaSource(buckets = listOf(camBucket), itemsByBucket = emptyMap())
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val announcer = RecordingAnnouncer()

        controller(unconfinedScope(vms), prefs, media, files, announcer = announcer).start()
        advanceUntilIdle()
        media.bucketsOverride = listOf(camBucket, whatsapp)
        media.changes.emit(Unit)
        advanceUntilIdle()

        assertTrue(announcer.announced.isEmpty())
        // The known-set is untouched too, so turning the prompt back on later still
        // surfaces the folder rather than having silently consumed it.
        assertTrue("wa" !in prefs.knownBuckets)
    }

    @Test
    fun announce_stillFires_whileTheDrainItselfIsPausedOffPolicy() = vmTest { vms ->
        // A prompt costs no bandwidth, so being paused on cellular is no reason to
        // withhold it — only the master switch gates announcing.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), wifiOnly = true)
        val whatsapp = MediaBucket(id = "wa", displayName = "WhatsApp Images", itemCount = 12)
        val media = FakeMediaSource(buckets = listOf(camBucket), itemsByBucket = emptyMap())
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val announcer = RecordingAnnouncer()

        controller(
            unconfinedScope(vms), prefs, media, files,
            env = FakeEnv(net = BackupNetworkStatus.METERED),
            announcer = announcer,
        ).start()
        advanceUntilIdle()
        media.bucketsOverride = listOf(camBucket, whatsapp)
        media.changes.emit(Unit)
        advanceUntilIdle()

        assertEquals(listOf("wa"), announcer.announced)
        assertTrue(files.backupUploadCalls.isEmpty(), "drain must still be paused")
    }

    @Test
    fun drain_uploadsNewItems_advancesCursor_endsUpToDate() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("a.jpg", 1), item("b.jpg", 2))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        assertEquals(
            listOf("/Backup/dev1/Camera" to "a.jpg", "/Backup/dev1/Camera" to "b.jpg"),
            files.backupUploadCalls.map { it.first to it.second },
        )
        assertEquals("2", prefs.cursors["drive.gallery/cam"])  // advanced to newest uploaded
    }

    // ---- Run-level figures: a burst of triggers is ONE run (drive-upload-progress.md §14.3) ----

    @Test
    fun aTriggerDuringADrain_continuesTheSameRun_ratherThanRestartingTheCount() = vmTest { vms ->
        // Photos becoming visible over a minute or two produce several drains; the count must not
        // restart with a new denominator at every boundary ("19 of 20" then "5 of 60"). Each
        // drain's own total is honest; the run they belong to needs one too.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("a.jpg", 1), item("b.jpg", 2))),
        )
        lateinit var ctrl: SyncedFolderController
        var injected = false
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { name ->
                // Three more photos land while the first drain is mid-flight, and something
                // pokes the loop — a burst of arrivals.
                if (name == "a.jpg" && !injected) {
                    injected = true
                    media.itemsByBucket["cam"] = media.itemsByBucket.getValue("cam") +
                        listOf(item("c.jpg", 3), item("d.jpg", 4), item("e.jpg", 5))
                    ctrl.ingestNow()
                }
                BackupUploadOutcome.Uploaded
            },
        )
        val scope = unconfinedScope(vms)
        ctrl = controller(scope, prefs, media, files)
        val seen = mutableListOf<BackupState>()
        scope.launch { ctrl.state.collect { seen += it } }
        ctrl.start()
        advanceUntilIdle()

        // ⚠ The intermediate `BackingUp` frames are conflated away by the aggregate StateFlow,
        // so the run is asserted through its SUMMARIES, which are not: two drains reporting
        // separately — JustFinished(2) then JustFinished(3) — would each be a restart of the
        // on-screen count with a fresh denominator.
        val finished = seen.filterIsInstance<BackupState.JustFinished>()
        assertEquals(
            listOf(5),
            finished.map { it.uploaded },
            "a burst is ONE run: one summary covering all five files, not one per drain",
        )
        val progress = seen.filterIsInstance<BackupState.BackingUp>()
        assertTrue(
            progress.map { it.completed } == progress.map { it.completed }.sorted(),
            "the numerator must never restart mid-run: ${progress.map { "${it.completed}/${it.total}" }}",
        )
    }

    @Test
    fun aRunEndsWhenTheEngineGoesIdle_soAnIsolatedDrainStillSummarisesImmediately() = vmTest { vms ->
        // The other half of the contract: with nothing pending, a single drain must still reach
        // its terminal state right away — coalescing must not make a lone backup hang on
        // "Backing up 2 of 2" forever.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("a.jpg", 1), item("b.jpg", 2))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val scope = unconfinedScope(vms)
        val ctrl = controller(scope, prefs, media, files)
        val seen = mutableListOf<BackupState>()
        scope.launch { ctrl.state.collect { seen += it } }
        ctrl.start()
        advanceUntilIdle()

        assertEquals(
            listOf(2),
            seen.filterIsInstance<BackupState.JustFinished>().map { it.uploaded },
            "a lone drain publishes exactly one summary for its two files",
        )
    }

    @Test
    fun aPauseMidRun_keepsThePausedState_ratherThanBeingOverwrittenBySummary() = vmTest { vms ->
        // ⚠ The run accumulator must not outlive the reason a source stopped: publishing
        // "Backed up N" over "Waiting for Wi-Fi" would claim a finished backup that is in fact
        // parked — the lie the banner's priority order avoids.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("a.jpg", 1))),
        )
        lateinit var ctrl: SyncedFolderController
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { _ ->
                // The user drops onto cellular while the drain runs, then something re-triggers.
                prefs.wifiOnlyFlow.value = true
                ctrl.ingestNow()
                BackupUploadOutcome.Uploaded
            },
        )
        val scope = unconfinedScope(vms)
        ctrl = controller(scope, prefs, media, files, env = FakeEnv(net = BackupNetworkStatus.METERED))
        ctrl.start()
        advanceUntilIdle()

        assertEquals(
            BackupState.Paused(BackupPauseReason.METERED_NETWORK),
            ctrl.state.value,
            "a paused source must not be overwritten by the run summary",
        )
    }

    // ---- `drive-upload-progress.md` §14.4: the run's bytes, so the banner's "N left" is the
    // backup and not one file ----

    private fun sizedItem(name: String, cursor: Long, sizeBytes: Long?) = MediaItem(
        uri = "uri/$name",
        fileName = name,
        addedAtMillis = cursor,
        cursor = cursor.toString(),
        sizeBytes = sizeBytes,
    )

    // ⚠ Every test below reads the frame from INSIDE the upload hook, for the reason
    // [untickingOneFolder_leavesTheOtherRunning_andCorrectsTheDenominator] gives: `state` is a
    // conflated StateFlow whose intermediate frames a collector may never observe, and it lags the
    // drain by a file. So they assert containment, never a sequence of exact frames.

    @Test
    fun theRunsRemainingBytes_coverTheWholeBackup_notTheFileInFlight() = vmTest { vms ->
        // "250 of 2271" must not sit beside a byte figure that is the current file's size.
        // The drain already enumerates every item before uploading any of them, so the
        // bytes are known up front — 1000 + 2000 + 3000 here.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf(
                "cam" to listOf(
                    sizedItem("a.jpg", 1, 1_000L),
                    sizedItem("b.jpg", 2, 2_000L),
                    sizedItem("c.jpg", 3, 3_000L),
                ),
            ),
        )
        var ref: SyncedFolderController? = null
        val seen = mutableListOf<Long?>()
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { _ ->
                (ref?.state?.value as? BackupState.BackingUp)?.let { seen += it.remainingBytes }
                BackupUploadOutcome.Uploaded
            },
        )
        val ctrl = controller(unconfinedScope(vms), prefs, media, files)
        ref = ctrl
        ctrl.start()
        advanceUntilIdle()

        assertTrue(
            6_000L in seen,
            "the first frame must state the whole run's bytes, not a.jpg's 1000; saw $seen",
        )
        assertEquals(
            seen.sortedByDescending { it ?: Long.MAX_VALUE },
            seen,
            "remaining bytes must only ever fall as items are handed off; saw $seen",
        )
    }

    @Test
    fun oneUnsizableItem_forfeitsTheRunsByteFigure_ratherThanUnderstatingIt() = vmTest { vms ->
        // ⚠ The all-or-nothing rule. A run that silently reports 4000 of a real 4000+? bytes is
        // worse than one that reports nothing, because nothing on screen distinguishes the two —
        // and the banner has an honest fallback (the proxy's journal) for exactly this case.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf(
                "cam" to listOf(
                    sizedItem("a.jpg", 1, 1_000L),
                    sizedItem("b.mp4", 2, null),
                    sizedItem("c.jpg", 3, 3_000L),
                ),
            ),
        )
        var ref: SyncedFolderController? = null
        val seen = mutableListOf<Long?>()
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { _ ->
                (ref?.state?.value as? BackupState.BackingUp)?.let { seen += it.remainingBytes }
                BackupUploadOutcome.Uploaded
            },
        )
        val ctrl = controller(unconfinedScope(vms), prefs, media, files)
        ref = ctrl
        ctrl.start()
        advanceUntilIdle()

        assertTrue(seen.isNotEmpty(), "the drain must have published at least one progress frame")
        assertTrue(
            seen.all { it == null },
            "one unsizable item in the run forfeits the figure for the whole run; saw $seen",
        )
    }

    @Test
    fun untickingAFolderMidRun_givesBackItsBytesAsWellAsItsCount() = vmTest { vms ->
        // The byte twin of [untickingOneFolder_leavesTheOtherRunning_andCorrectsTheDenominator]:
        // abandoned files must leave the numerator's denominator AND the byte total, or the
        // remaining folder counts toward bytes nobody is going to upload.
        val other = MediaBucket(id = "screens", displayName = "Screenshots", itemCount = 0)
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam", "screens"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket, other),
            itemsByBucket = mapOf(
                "cam" to listOf(
                    sizedItem("a.jpg", 1, 1_000L),
                    sizedItem("b.jpg", 2, 2_000L),
                    sizedItem("c.jpg", 3, 3_000L),
                ),
                "screens" to listOf(sizedItem("s1.png", 1, 10L), sizedItem("s2.png", 2, 20L)),
            ),
        )
        var ref: SyncedFolderController? = null
        val seen = mutableListOf<Long?>()
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { name ->
                (ref?.state?.value as? BackupState.BackingUp)?.let { seen += it.remainingBytes }
                if (name == "a.jpg") prefs.bucketsFlow.value = listOf("screens")
                BackupUploadOutcome.Uploaded
            },
        )
        val ctrl = controller(unconfinedScope(vms), prefs, media, files)
        ref = ctrl
        ctrl.start()
        advanceUntilIdle()

        assertEquals(
            listOf("a.jpg", "s1.png", "s2.png"),
            files.backupUploadCalls.map { it.second },
            "Screenshots stays selected, so its files must still upload",
        )
        // 6030 enumerated − 5000 given back when Camera was unticked − 1000 for a.jpg = 30.
        // Without the give-back the same frame reads 5030.
        assertTrue(
            30L in seen,
            "b.jpg and c.jpg were abandoned, so their 5000 bytes must leave the run's total " +
                "as well as its count; saw $seen",
        )
    }

    @Test
    fun aggregateState_reportsBytesOnlyWhenEverySourceCanSizeItsItems() {
        // Two sources back up at once (gallery + document tree). Summing only the ones that CAN
        // size their items would report a gallery's bytes as though they were the whole backup's.
        assertEquals(
            5_000L,
            (aggregateState(
                listOf(
                    BackupState.BackingUp(1, 2, "a.jpg", remainingBytes = 2_000L),
                    BackupState.BackingUp(3, 4, "b.pdf", remainingBytes = 3_000L),
                )
            ) as BackupState.BackingUp).remainingBytes,
        )
        assertNull(
            (aggregateState(
                listOf(
                    BackupState.BackingUp(1, 2, "a.jpg", remainingBytes = 2_000L),
                    BackupState.BackingUp(3, 4, "b.pdf", remainingBytes = null),
                )
            ) as BackupState.BackingUp).remainingBytes,
            "one source that cannot size its items forfeits the aggregate figure",
        )
    }

    @Test
    fun drain_ensuresDestinationOncePerBucket_notPerItem() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("a.jpg", 1), item("b.jpg", 2), item("c.jpg", 3))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        // One chain-ensure for the whole bucket. Per-item ensuring would cost one
        // expected-AlreadyExists IPC round trip per path segment per file.
        assertEquals(listOf("/Backup/dev1/Camera"), files.ensureBackupDestinationCalls)
        assertEquals(3, files.backupUploadCalls.size)
    }

    @Test
    fun drain_doesNotEnsureDestination_forBucketWithNothingNew() = vmTest { vms ->
        // Two selected buckets, new items only in "cam" — destination folders are
        // created lazily the first time a bucket uploads (`drive-synced-folders.md` §3.3), so the idle
        // bucket must not have its chain created.
        val shots = MediaBucket(id = "shots", displayName = "Screenshots", itemCount = 0)
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam", "shots"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket, shots),
            itemsByBucket = mapOf("cam" to listOf(item("a.jpg", 1))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        assertEquals(listOf("/Backup/dev1/Camera"), files.ensureBackupDestinationCalls)
    }

    @Test
    fun disabled_doesNothing() = vmTest { vms ->
        val prefs = FakePrefs(enabled = false, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        val c = controller(unconfinedScope(vms), prefs, media, files)
        c.start()
        advanceUntilIdle()

        assertTrue(files.backupUploadCalls.isEmpty())
        assertEquals(BackupState.Disabled, c.state.value)
    }

    @Test
    fun noStorage_pausesAndUploadsNothing() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(false, 0, 0))

        val c = controller(unconfinedScope(vms), prefs, media, files)
        c.start()
        advanceUntilIdle()

        assertTrue(files.backupUploadCalls.isEmpty())
        assertEquals(BackupState.Paused(BackupPauseReason.NO_STORAGE, waiting = 1), c.state.value)
    }

    @Test
    fun meteredUnderWifiOnly_pauses() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), wifiOnly = true)
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        val c = controller(unconfinedScope(vms), prefs, media, files, FakeEnv(net = BackupNetworkStatus.METERED))
        c.start()
        advanceUntilIdle()

        assertTrue(files.backupUploadCalls.isEmpty())
        // The pause says what it is holding back, not only why.
        assertEquals(BackupState.Paused(BackupPauseReason.METERED_NETWORK, waiting = 1), c.state.value)
    }

    /**
     * A network change re-evaluates the gate with no media event — otherwise the last verdict
     * stays on screen ("Paused — on mobile data" on Wi-Fi) until the next photo.
     */
    @Test
    fun joiningWifi_reevaluatesWithoutAMediaEvent() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), wifiOnly = true)
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val env = FakeEnv(net = BackupNetworkStatus.METERED)

        val c = controller(unconfinedScope(vms), prefs, media, files, env)
        c.start()
        advanceUntilIdle()
        assertTrue(files.backupUploadCalls.isEmpty())

        env.net = BackupNetworkStatus.UNMETERED
        env.moved.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
        assertTrue(c.state.value !is BackupState.Paused, "still ${c.state.value}")
    }

    /** The other direction: nothing pending, so only the status line can show it. */
    @Test
    fun leavingWifi_reportsThePauseWithoutAMediaEvent() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), wifiOnly = true)
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to emptyList()))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val env = FakeEnv(net = BackupNetworkStatus.UNMETERED)

        val c = controller(unconfinedScope(vms), prefs, media, files, env)
        c.start()
        advanceUntilIdle()
        assertEquals(BackupState.UpToDate, c.state.value)

        env.net = BackupNetworkStatus.METERED
        env.moved.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(BackupState.Paused(BackupPauseReason.METERED_NETWORK, waiting = 0), c.state.value)
    }

    /**
     * Both halves matter, and they pull in opposite directions: the cursor must stop at `b.jpg`
     * (advancing past it because `c.jpg` succeeded would lose it), and `c.jpg` must still upload.
     *
     * ⚠⚠ `c.jpg never attempted` would be the wedge — one bad file holding the folder hostage.
     * See `drive-synced-folders.md` §8c.
     */
    @Test
    fun failedItem_freezesCursorButKeepsDrainingTheBucket() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("a.jpg", 1), item("b.jpg", 2), item("c.jpg", 3))),
        )
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { name ->
                if (name == "b.jpg") BackupUploadOutcome.Failed("boom") else BackupUploadOutcome.Uploaded
            },
        )

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        // c.jpg is still uploaded — one bad file does not hold the folder hostage …
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), files.backupUploadCalls.map { it.second })
        // … and the watermark stays before b.jpg, so the next drain retries it.
        assertEquals("1", prefs.cursors["drive.gallery/cam"])
    }

    /**
     * The bound on the test above: a folder where *everything* fails must not cost one round trip
     * per file, every drain, forever.
     */
    @Test
    fun everyItemFailing_stopsTheBucketAfterTheFailureCap() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val all = (1..20).map { item("f$it.jpg", it.toLong()) }
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to all))
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { BackupUploadOutcome.Failed("unsupportedOperation") },
        )

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        assertEquals(MAX_CONSECUTIVE_BUCKET_FAILURES, files.backupUploadCalls.size)
        assertNull(prefs.cursors["drive.gallery/cam"])
    }

    /** A success resets the run of failures, so scattered bad files still drain the whole folder. */
    @Test
    fun scatteredFailures_doNotTripTheCap() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val all = (1..12).map { item("f$it.jpg", it.toLong()) }
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to all))
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            // Every third file fails — never five in a row.
            backupOutcome = { name ->
                if (name.removePrefix("f").removeSuffix(".jpg").toInt() % 3 == 0) {
                    BackupUploadOutcome.Failed("boom")
                } else {
                    BackupUploadOutcome.Uploaded
                }
            },
        )

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        assertEquals(12, files.backupUploadCalls.size)
        // f3 was the first failure, so the watermark is still on f2 — everything after it was
        // uploaded, and will be answered `Skipped` by the `drive-synced-folders.md` §3.5 backstop
        // on the next drain.
        assertEquals("2", prefs.cursors["drive.gallery/cam"])
    }

    /**
     * The reason has to survive from the data plane to the terminal state a user reads
     * (`drive-synced-folders.md` §8c).
     */
    @Test
    fun failureReason_reachesTheTerminalState() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { BackupUploadOutcome.Failed("this build has no operation for wire tag 84cf") },
        )

        val scope = unconfinedScope(vms)
        val c = controller(scope, prefs, media, files)
        // ⚠ Collected, not read off `state.value`: JustFinished is a *transient* summary that
        // auto-clears to UpToDate, and `advanceUntilIdle` runs that timer out.
        val seen = mutableListOf<BackupState>()
        scope.launch { c.state.collect { seen += it } }
        c.start()
        advanceUntilIdle()

        val finished = assertIs<BackupState.JustFinished>(seen.last { it is BackupState.JustFinished })
        assertEquals(1, finished.failed)
        assertEquals("this build has no operation for wire tag 84cf", finished.failureReason)
    }

    // ---- Which cause the summary names: the run's LAST failure (`drive-synced-folders.md` §8d) ----

    /**
     * The summary of ONE run made of [drains] coalesced drains over a single file. Each entry is
     * what that drain does with the file: `null` — the realm could not be asked (the typed cause);
     * otherwise the upload's outcome. A failed file freezes the cursor, so the next drain meets it
     * again.
     */
    private fun TestScope.summaryOfARunOf(vms: OwnedViewModels, vararg drains: BackupUploadOutcome?): BackupState.JustFinished {
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        lateinit var ctrl: SyncedFolderController
        var opened = 0
        // ⚠ A drain past the script repeats its last entry, so a stray trigger cannot change which
        // failure was last.
        fun script(drain: Int) = drains[drain.coerceAtMost(drains.lastIndex)]
        val gate = object : DuplicateContentGate {
            override suspend fun openForDrain(): DuplicateContentView {
                val mine = opened++
                // Poke the loop while this drain runs: the next one belongs to the same run.
                if (mine < drains.lastIndex) ctrl.ingestNow()
                return DuplicateContentView { if (script(mine) == null) null else emptySet() }
            }
        }
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { script(opened - 1) ?: error("uploaded a file the realm was never asked about") },
        )
        files.contentHashes["uri/a.jpg"] = "h-a"
        val scope = unconfinedScope(vms)
        ctrl = controller(scope, prefs, media, files, duplicates = gate)
        val seen = mutableListOf<BackupState>()
        scope.launch { ctrl.state.collect { seen += it } }
        ctrl.start()
        advanceUntilIdle()

        assertTrue(opened >= drains.size, "only $opened of ${drains.size} drains ran")
        return seen.filterIsInstance<BackupState.JustFinished>().single()
    }

    @Test
    fun aTypedFailureAfterAReasonedOne_namesTheTypedCauseAlone() = vmTest { vms ->
        // ⚠ Across two drains of one run, the later failure replaces BOTH fields — setting only the
        // one it has would leave the earlier drain's other cause standing beside it.
        val finished = summaryOfARunOf(vms, BackupUploadOutcome.Failed("quota"), null)

        assertEquals(BackupFailureKind.COULD_NOT_CHECK_REALM, finished.failureKind)
        assertNull(finished.failureReason, "both causes set — a platform would have to pick")
    }

    @Test
    fun aReasonedFailureAfterATypedOne_namesTheReasonAlone() = vmTest { vms ->
        val finished = summaryOfARunOf(vms, null, BackupUploadOutcome.Failed("quota"))

        assertEquals("quota", finished.failureReason)
        assertNull(finished.failureKind, "both causes set — a platform would have to pick")
    }

    @Test
    fun aCleanDrainAfterAFailedOne_keepsTheCauseOfTheFailureTheSummaryStillCounts() = vmTest { vms ->
        // The summary counts the run's failures, so it still owes their cause: a later drain in
        // which nothing failed is not a "last failure" and erases nothing.
        val typed = summaryOfARunOf(vms, null, BackupUploadOutcome.Uploaded)
        assertEquals(1 to 1, typed.uploaded to typed.failed)
        assertEquals(BackupFailureKind.COULD_NOT_CHECK_REALM, typed.failureKind)

        val reasoned = summaryOfARunOf(vms, BackupUploadOutcome.Failed("quota"), BackupUploadOutcome.Uploaded)
        assertEquals(1 to 1, reasoned.uploaded to reasoned.failed)
        assertEquals("quota", reasoned.failureReason)
    }

    @Test
    fun failuresOfMixedCausesInOneDrain_nameTheLastOne() = vmTest { vms ->
        // One sentence, not a list (§8c): the summary counts every failure and names the cause of
        // the last. Here the realm could not be asked about the first file, and the second's upload
        // was refused — then the same two the other way round.
        suspend fun summary(unreadable: String, refused: String): BackupState.JustFinished {
            val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
            val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1), item("b.jpg", 2))))
            val files = FakeFilesRepository(
                storageStatusResult = StorageStatusModel(true, 0, 100),
                backupOutcome = { name -> check(name == refused); BackupUploadOutcome.Failed("quota") },
            )
            files.contentHashes["uri/a.jpg"] = "h-a.jpg"
            files.contentHashes["uri/b.jpg"] = "h-b.jpg"
            val scope = unconfinedScope(vms)
            val c = controller(
                scope, prefs, media, files,
                duplicates = PatchyRealmGate(unreadable = setOf("h-$unreadable"), held = emptySet()),
            )
            val seen = mutableListOf<BackupState>()
            scope.launch { c.state.collect { seen += it } }
            c.start()
            advanceUntilIdle()
            return seen.filterIsInstance<BackupState.JustFinished>().single()
        }

        val refusedLast = summary(unreadable = "a.jpg", refused = "b.jpg")
        assertEquals(2, refusedLast.failed)
        assertEquals("quota" to null, refusedLast.failureReason to refusedLast.failureKind)

        val unreadableLast = summary(unreadable = "b.jpg", refused = "a.jpg")
        assertEquals(2, unreadableLast.failed)
        assertEquals(null to BackupFailureKind.COULD_NOT_CHECK_REALM, unreadableLast.failureReason to unreadableLast.failureKind)
    }

    @Test
    fun alreadyExistsSkips_advancesCursorWithoutCountingUpload() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 5))))
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { BackupUploadOutcome.Skipped },
        )

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
        assertEquals("5", prefs.cursors["drive.gallery/cam"])  // §3.5 backstop advances past the dup
    }

    // --- Drive consumes the ingest registry (register + status) ---

    @Test
    fun registersGallerySourceOnStart_evenWhenDisabled() = vmTest { vms ->
        val prefs = FakePrefs(enabled = false, buckets = emptyList())
        val media = FakeMediaSource(emptyList(), emptyMap())
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val registry = FakeIngestRegistry()

        controller(unconfinedScope(vms), prefs, media, files, registry = registry).start()
        advanceUntilIdle()

        // The source belongs in Control's inventory regardless of the master toggle.
        assertEquals(
            listOf("drive.gallery" to IngestSource.KIND_GALLERY),
            registry.registered.map { it.first to it.second },
        )
    }

    @Test
    fun startRegistrationOnly_registersWithWatchedPaths_butDoesNotDrainOrReportStatus() = vmTest { vms ->
        // Desktop tier: Control's FolderWatcher owns ingest; Drive only registers.
        val prefs = FakePrefs(enabled = true, buckets = listOf("/Pictures/Camera"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("/Pictures/Camera" to listOf(item("a.jpg", 1))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val registry = FakeIngestRegistry()

        controller(unconfinedScope(vms), prefs, media, files, registry = registry).startRegistrationOnly()
        advanceUntilIdle()

        // Registered, carrying the bucket paths as watchedPaths for Control's watcher.
        // ⚠ Registration follows the *reactive* selection (it must track later changes),
        // which leads with the seeded empty default before this device's stored row — so
        // the upsert runs twice at startup, the second one authoritative, as in
        // production; the assertion is on the effective row, not the call count. The
        // drain, by contrast, must never see that seed — see
        // `drain_readsTheStoredSelection_notThePreferenceFlowsSeededDefault`.
        assertEquals("/Pictures/Camera", registry.registered.last().third)
        // …but NO in-process drain and NO status report (Control owns both on desktop).
        assertTrue(files.backupUploadCalls.isEmpty())
        assertTrue(registry.statuses.isEmpty())
    }

    @Test
    fun reportsTerminalUpToDate_afterDrain() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val registry = FakeIngestRegistry()

        controller(unconfinedScope(vms), prefs, media, files, registry = registry).start()
        advanceUntilIdle()

        // Terminal status is deterministic (intermediate RUNNING is StateFlow-conflated).
        assertEquals(IngestSource.STATUS_UP_TO_DATE, registry.statuses.last().first)
    }

    @Test
    fun reportsIdle_whenDisabled() = vmTest { vms ->
        val prefs = FakePrefs(enabled = false, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val registry = FakeIngestRegistry()

        controller(unconfinedScope(vms), prefs, media, files, registry = registry).start()
        advanceUntilIdle()

        assertEquals(IngestSource.STATUS_IDLE, registry.statuses.last().first)
    }

    @Test
    fun centralKillSwitchDisabled_pausesDrain_evenWhenLocallyEnabled() = vmTest { vms ->
        // Master toggle ON + items present, but Control's kill switch is OFF.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val registry = FakeIngestRegistry(enabled = false)

        val c = controller(unconfinedScope(vms), prefs, media, files, registry = registry)
        c.start()
        advanceUntilIdle()

        // `drive-synced-folders.md` §5 read-gate: the kill switch short-circuits the drain before any upload.
        assertTrue(files.backupUploadCalls.isEmpty())
        assertEquals(BackupState.Disabled, c.state.value)
    }

    // --- The engine drives a second source (gallery + document-tree) ---

    @Test
    fun drainsTwoSources_independentBucketsAndCursors() = vmTest { vms ->
        // Gallery has one bucket "cam"; the document-tree source has one "bucket"
        // that is a tree URI. Both are selected and both have a new item.
        val treeUri = "content://tree/Documents"
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        prefs.documentTreeUrisFlow.value = listOf(treeUri)

        val gallery = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("photo.jpg", 1))),
        )
        val docs = FakeMediaSource(
            buckets = listOf(MediaBucket(id = treeUri, displayName = "Documents", itemCount = 0)),
            itemsByBucket = mapOf(treeUri to listOf(item("notes.pdf", 7))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        SyncedFolderController(
            prefs,
            listOf(galleryBackupSource(gallery, prefs), documentTreeBackupSource(docs, prefs)),
            files,
            FakeEnv(),
            FakeIngestRegistry(),
            scope = unconfinedScope(vms),
        ).start()
        advanceUntilIdle()

        // Each source drained its own bucket to its own destination folder…
        assertEquals(
            setOf("/Backup/dev1/Camera" to "photo.jpg", "/Backup/dev1/Documents" to "notes.pdf"),
            files.backupUploadCalls.map { it.first to it.second }.toSet(),
        )
        // …and advanced its own namespaced cursor.
        assertEquals("1", prefs.cursors["drive.gallery/cam"])
        assertEquals("7", prefs.cursors["drive.document-tree/$treeUri"])
    }

    @Test
    fun drainSurvivesThrowingEnvironment_doesNotCrashOrKillLoop() = vmTest { vms ->
        // A throwing environment read (e.g. on-device ConnectivityManager
        // SecurityException) must NOT crash the loop: the tick is dropped and a
        // later trigger re-drains successfully once the environment recovers.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val throwing = object : BackupEnvironment {
            @Volatile var explode = true
            override fun network(): BackupNetworkStatus {
                if (explode) throw IllegalStateException("connectivity read failed")
                return BackupNetworkStatus.UNMETERED
            }
            override fun isCharging() = true
            override fun changes(): Flow<Unit> = flowOf()
        }

        val c = SyncedFolderController(
            prefs, listOf(galleryBackupSource(media, prefs)), files, throwing,
            FakeIngestRegistry(), scope = unconfinedScope(vms),
        )
        c.start()
        advanceUntilIdle()
        // First tick threw → nothing uploaded, but the loop is still alive.
        assertTrue(files.backupUploadCalls.isEmpty())

        // Environment recovers; a fresh trigger drains normally.
        throwing.explode = false
        c.ingestNow()
        advanceUntilIdle()
        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
    }

    // --- ingestAndAwait (the headless background worker entry) ---

    @Test
    fun ingestAndAwait_drainsAndReturnsTrue() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        val ran = controller(unconfinedScope(vms), prefs, media, files).ingestAndAwait()
        advanceUntilIdle()

        assertTrue(ran)
        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
        assertEquals("1", prefs.cursors["drive.gallery/cam"])
    }

    @Test
    fun ingestAndAwait_returnsFalse_whenSyncIdentityUnresolved() = vmTest { vms ->
        // deviceLabel() == null (no sync identity yet) → worker should retry, no drain.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), device = null)
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        val ran = controller(unconfinedScope(vms), prefs, media, files).ingestAndAwait()
        advanceUntilIdle()

        assertEquals(false, ran)
        assertTrue(files.backupUploadCalls.isEmpty())
    }

    @Test
    fun ingestAndAwait_returnsTrue_butUploadsNothing_whenDisabled() = vmTest { vms ->
        // Gated off (master toggle) is "nothing to do", not a failure → true, no upload.
        val prefs = FakePrefs(enabled = false, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))

        val ran = controller(unconfinedScope(vms), prefs, media, files).ingestAndAwait()
        advanceUntilIdle()

        assertTrue(ran)
        assertTrue(files.backupUploadCalls.isEmpty())
    }

    @Test
    fun backupStatusFor_mapsEachState() {
        assertEquals(IngestSource.STATUS_IDLE, backupStatusFor(BackupState.Disabled).status)
        assertEquals(IngestSource.STATUS_UP_TO_DATE, backupStatusFor(BackupState.UpToDate).status)

        val running = backupStatusFor(BackupState.BackingUp(completed = 2, total = 5, currentFileName = "x.jpg"))
        assertEquals(IngestSource.STATUS_RUNNING, running.status)
        assertEquals(3L, running.pendingCount)  // total - completed

        assertEquals(
            IngestSource.STATUS_OUT_OF_SPACE,
            backupStatusFor(BackupState.Paused(BackupPauseReason.OUT_OF_SPACE)).status,
        )
        assertEquals(
            IngestSource.STATUS_OUT_OF_SPACE,
            backupStatusFor(BackupState.Paused(BackupPauseReason.NO_STORAGE)).status,
        )
        assertEquals(
            IngestSource.STATUS_PAUSED_POLICY,
            backupStatusFor(BackupState.Paused(BackupPauseReason.METERED_NETWORK)).status,
        )
        assertEquals(
            IngestSource.STATUS_UP_TO_DATE,
            backupStatusFor(BackupState.JustFinished(uploaded = 3, failed = 0)).status,
        )
        // ⚠⚠ A run that ended with failures is not up to date. Control's inventory row is the one
        // place a second device, or the maintainer reading the store, looks — `UP_TO_DATE` there
        // would hide days of rejected uploads.
        assertEquals(
            IngestSource.STATUS_ERROR,
            backupStatusFor(BackupState.JustFinished(uploaded = 0, failed = 5)).status,
        )
    }

    // --- "off" means stop, not "stop after this batch" -------------------------------------
    //
    // Flipping a Synced Folders toggle off mid-upload stops the in-flight drain, which holds
    // `drainMutex` and would otherwise upload every remaining file. All three tests below flip
    // their switch from inside the per-file upload hook, which is the only point where a drain
    // is genuinely mid-flight.

    /**
     * ⚠⚠ Unticking **one folder** writes the bucket SELECTION, which `evaluateBackupGate` never
     * reads — so a drain that re-reads only the gate keeps uploading from the unticked folder.
     */
    @Test
    fun untickingAFolderMidDrain_stopsUploadingFromIt() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf(
                "cam" to listOf(item("a.jpg", 1), item("b.jpg", 2), item("c.jpg", 3)),
            ),
        )
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { name ->
                // Untick Camera while a.jpg is uploading.
                if (name == "a.jpg") prefs.bucketsFlow.value = emptyList()
                BackupUploadOutcome.Uploaded
            },
        )

        controller(unconfinedScope(vms), prefs, media, files).start()
        advanceUntilIdle()

        assertEquals(
            listOf("a.jpg"),
            files.backupUploadCalls.map { it.second },
            "unticking Camera must stop its remaining files, not upload b.jpg and c.jpg anyway",
        )
    }

    /** The master switch: same expectation, and it must land on a Disabled badge. */
    @Test
    fun turningBackupOffMidDrain_stopsAndReadsAsDisabled() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf(
                "cam" to listOf(item("a.jpg", 1), item("b.jpg", 2), item("c.jpg", 3)),
            ),
        )
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { name ->
                if (name == "a.jpg") prefs.enabledFlow.value = false
                BackupUploadOutcome.Uploaded
            },
        )
        val controller = controller(unconfinedScope(vms), prefs, media, files)

        controller.start()
        advanceUntilIdle()

        assertEquals(
            listOf("a.jpg"),
            files.backupUploadCalls.map { it.second },
            "backup switched off must stop the drain, not finish the queue",
        )
        // ⚠ Not just "it stopped": setStable ENDS the run, so finishRuns cannot come along
        // afterwards and overwrite this with a "Backed up N" summary.
        assertEquals(BackupState.Disabled, controller.state.value)
    }

    /**
     * The environment moving off-policy is the same mechanism, and the badge must say WHY —
     * "you turned this off" and "you are on cellular" are different sentences.
     */
    @Test
    fun droppingToMeteredMidDrain_stopsAndExplainsItself() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), wifiOnly = true)
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf(
                "cam" to listOf(item("a.jpg", 1), item("b.jpg", 2), item("c.jpg", 3)),
            ),
        )
        val env = FakeEnv(net = BackupNetworkStatus.UNMETERED)
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { name ->
                if (name == "a.jpg") env.net = BackupNetworkStatus.METERED
                BackupUploadOutcome.Uploaded
            },
        )
        val controller = controller(unconfinedScope(vms), prefs, media, files, env = env)

        controller.start()
        advanceUntilIdle()

        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
        assertEquals(
            // b and c: the cursor moved past a, so the recount is the honest remainder.
            BackupState.Paused(BackupPauseReason.METERED_NETWORK, waiting = 2),
            controller.state.value,
        )
    }

    /**
     * ⚠ Deselecting one folder is not a reason to stop the others — and the run's denominator
     * must give back the files that will now never be uploaded, or the remaining folder counts
     * toward a total including work nobody is going to do (a backup is one RUN with one honest
     * denominator, `drive-upload-progress.md` §14.3).
     */
    @Test
    fun untickingOneFolder_leavesTheOtherRunning_andCorrectsTheDenominator() = vmTest { vms ->
        val other = MediaBucket(id = "screens", displayName = "Screenshots", itemCount = 0)
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam", "screens"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket, other),
            itemsByBucket = mapOf(
                "cam" to listOf(item("a.jpg", 1), item("b.jpg", 2), item("c.jpg", 3)),
                "screens" to listOf(item("s1.png", 1), item("s2.png", 2)),
            ),
        )
        // ⚠ Read the frame from INSIDE the upload hook, not from a collector. The hook runs
        // synchronously on the drain's own coroutine, so this sees the exact state the drain
        // published for that file — whereas `state` is a conflated StateFlow whose
        // intermediate frames a collector is not guaranteed to observe at all (the reason
        // the run-level tests above assert through run summaries instead).
        var controllerRef: SyncedFolderController? = null
        val totalsSeen = mutableSetOf<Int>()
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { name ->
                (controllerRef?.state?.value as? BackupState.BackingUp)?.let {
                    totalsSeen += it.total
                }
                if (name == "a.jpg") prefs.bucketsFlow.value = listOf("screens")
                BackupUploadOutcome.Uploaded
            },
        )
        val controller = controller(unconfinedScope(vms), prefs, media, files)
        controllerRef = controller

        controller.start()
        advanceUntilIdle()

        assertEquals(
            listOf("a.jpg", "s1.png", "s2.png"),
            files.backupUploadCalls.map { it.second },
            "Screenshots stays selected, so its files must still upload",
        )
        // ⚠ Asserted as a SET rather than per file: the aggregated state lags the drain by one
        // file (it is folded by a collector coroutine), so which hook sees which frame is a
        // scheduling detail. That a total of 3 was published at all is not.
        assertTrue(
            3 in totalsSeen,
            "b.jpg and c.jpg were abandoned when Camera was unticked, so the run's denominator " +
                "must have given them back (5 - 2 = 3); saw totals=" + totalsSeen,
        )
    }

    // --- Stop is a pause, with a control and a distinct state -------------------------------

    /**
     * ⚠⚠ The point of Stop over the master switch: it stops the drain **and** says something
     * different afterwards. `Disabled` means "backup is off"; `PausedByUser` means "you stopped
     * this, and Resume is right there".
     */
    @Test
    fun stop_haltsTheDrain_andReadsAsPausedByUser() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf(
                "cam" to listOf(item("a.jpg", 1), item("b.jpg", 2), item("c.jpg", 3)),
            ),
        )
        var controllerRef: SyncedFolderController? = null
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { name ->
                if (name == "a.jpg") controllerRef?.let { c -> prefs.pausedByUserFlow.value = true }
                BackupUploadOutcome.Uploaded
            },
        )
        val controller = controller(unconfinedScope(vms), prefs, media, files)
        controllerRef = controller

        controller.start()
        advanceUntilIdle()

        assertEquals(
            listOf("a.jpg"),
            files.backupUploadCalls.map { it.second },
            "Stop must halt the queue at a file boundary",
        )
        assertEquals(BackupState.PausedByUser, controller.state.value)
    }

    /** Stop is a **pause**: the queue survives, and Resume drains it from the same cursors. */
    @Test
    fun resume_picksUpWhereStopLeftOff() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), pausedByUser = true)
        val media = FakeMediaSource(
            buckets = listOf(camBucket),
            itemsByBucket = mapOf("cam" to listOf(item("a.jpg", 1), item("b.jpg", 2))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val controller = controller(unconfinedScope(vms), prefs, media, files)

        controller.start()
        advanceUntilIdle()
        assertTrue(files.backupUploadCalls.isEmpty(), "a stopped backup must not drain on start")
        assertEquals(BackupState.PausedByUser, controller.state.value)

        controller.resume()
        advanceUntilIdle()

        assertEquals(
            listOf("a.jpg", "b.jpg"),
            files.backupUploadCalls.map { it.second },
            "Resume must drain the queue Stop left behind, not forget it",
        )
    }

    /** ⚠ Stop persists, so it survives the process — otherwise "paused" would silently expire. */
    @Test
    fun stop_persistsThePause() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val controller = controller(unconfinedScope(vms), prefs, media, files)

        controller.start()
        advanceUntilIdle()
        controller.stop()
        advanceUntilIdle()

        assertTrue(prefs.readPausedByUser(), "the pause must be written, not held in memory")
    }

    /**
     * ⚠⚠ A user pause outranks an environment pause in the gate. Both can be true; only one has a
     * remedy the user can act on, and telling them "no network" when they pressed Stop would be
     * the phone blaming the world for the user's own decision.
     */
    @Test
    fun aUserPause_outranksAnEnvironmentPause() {
        val gate = evaluateBackupGate(
            settings = BackupSettings(
                enabled = true, wifiOnly = true, chargingOnly = false, pausedByUser = true,
            ),
            storage = StorageStatusModel(true, 0, 100),
            network = BackupNetworkStatus.NONE,
            charging = false,
        )
        assertEquals(BackupGate.PausedByUser, gate)
    }

    /** ⚠ But backup being OFF outranks a pause — there is nothing to resume. */
    @Test
    fun backupOff_outranksAUserPause() {
        val gate = evaluateBackupGate(
            settings = BackupSettings(
                enabled = false, wifiOnly = false, chargingOnly = false, pausedByUser = true,
            ),
            storage = StorageStatusModel(true, 0, 100),
            network = BackupNetworkStatus.UNMETERED,
            charging = true,
        )
        assertEquals(BackupGate.Disabled, gate)
    }

    // ── `photos-app.md` §40: the skip asks the realm in bounded replies, and "could not look"
    // defers ──

    /** The skip setting, on — `IngestSettingsStore` without the identity read. */
    private object SkipOn : IngestSettings {
        override fun observeSkipExistingContent(): Flow<Boolean> = flowOf(true)
        override suspend fun readSkipExistingContent(): Boolean = true
        override suspend fun setSkipExistingContent(enabled: Boolean) = Unit
    }

    /** A client whose `getService` throws, exactly as an unregistered schema or a dead proxy makes it. */
    private class UnreadableRealmClient : TenshuClient {
        override val environment: TenshuEnvironment
            get() = error("No schema registered for service id: tenshu/file/service")
        override val proxyConnected: StateFlow<Boolean> = MutableStateFlow(true)
        override fun <T : Service> getService(id: String): T = error("No schema registered for service id: $id")
        override fun init() = Unit
        override fun destroy() = Unit
    }

    /**
     * A realm of [files] files in the production store pair (`SqliteObjectStore` + the real lock),
     * read through a client that measures every reply the way the IPC transport would serialise it.
     */
    private class MeasuredRealm(files: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(fileServiceCommonModule)
                registerConfig(fileServiceClientModule)
                registerConfig(syncServiceCommonModule)
            },
            coroutineScope = scope,
            objectStore = runBlocking {
                openSqliteObjectStore(createTempDirectory("skip-read").resolve("objects.db").toString(), scope)
            },
            lock = ReentrantLockDatabaseLock(),
            implementationFactoryOverride = { id, p -> fileServiceProxyImplementationFactory(id, p.fileDatabase) },
        ).also { it.executionContext.initFileService() }
        val replies = ReplySizes.measure(proxy)
        val client: TenshuClient = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx ->
                    LocalRequestChain(proxy.localTaskExecutor, ctx)
                },
            ),
        )

        init {
            runBlocking {
                val fs = client.fileService.defaultFileSystem
                fs.createFolder("/Camera")
                repeat(files) { i ->
                    val r = fs.uploadFile(
                        "/Camera", "IMG_${10_000 + i}.jpg", "photograph $i".encodeToByteArray(),
                        sourceModifiedAt = 1_700_000_000_000L + i,
                    )
                    check(r is TResult.Success) { "seeding $i: $r" }
                }
            }
            replies.clear()
        }

        /** The hash the realm stamped on [wanted] — read before the drain is measured. */
        fun hashOf(wanted: String): String = runBlocking {
            client.fileService.defaultFileSystem.root.files.traverse()
                .filter<File> { path equalTo wanted }
                .select { fields(File.Field.PATH); relations(File.ContentHash) }
                .getAll().getOrNull()!!.toList()
                .map {
                    @Suppress("UNCHECKED_CAST")
                    (it as Entity<File>).get(File.ContentHash)!!.get()
                }
                .single()
        }.also { replies.clear() }
    }

    @Test
    fun theSkipAsksTheRealmInBoundedReplies_andStillSkipsWhatTheRealmHolds() {
        // ⚠⚠ Every file in the realm, nine properties each, in ONE reply is ~640 bytes a file,
        // so 2,000 files is ~1.3 MB: past Binder's ~1 MB transaction ceiling, where the read gets
        // no answer at all (`photos-app.md` §39/§40).
        val realm = MeasuredRealm(files = 2_000)
        val held = realm.hashOf("/Camera/IMG_11234.jpg")
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(
            listOf(camBucket),
            mapOf("cam" to listOf(item("again.jpg", 1), item("new.jpg", 2))),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        files.contentHashes["uri/again.jpg"] = held
        files.contentHashes["uri/new.jpg"] = "a-hash-the-realm-has-never-seen"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                controller(
                    scope, prefs, media, files,
                    duplicates = TenshuDuplicateContentGate(realm.client, SkipOn),
                ).ingestAndAwait()
            }
        } finally {
            scope.cancel()
            realm.scope.cancel()
        }

        assertEquals(listOf("new.jpg"), files.backupUploadCalls.map { it.second })
        assertEquals("2", prefs.cursors["drive.gallery/cam"])
        assertTrue(realm.replies.count > 0, "the skip never asked the realm")
        assertTrue(
            realm.replies.largest <= BOUNDED_REPLY_BYTES,
            "a reply of ${realm.replies.largest} bytes (replies: ${realm.replies.all}) — the skip read the whole realm",
        )
    }

    @Test
    fun aRealmThatCannotBeRead_defersTheCandidate_neverUploadsIt() {
        // ⚠⚠ The silent failure this pins: were "could not look" to mean "skip nothing", every
        // candidate would upload again — content the realm may already hold, billed twice, with
        // nothing on screen saying so. Not knowing is a failure the next drain retries: the cursor stays.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1), item("b.jpg", 2))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        files.contentHashes["uri/a.jpg"] = "h-a"
        files.contentHashes["uri/b.jpg"] = "h-b"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                controller(
                    scope, prefs, media, files,
                    duplicates = TenshuDuplicateContentGate(UnreadableRealmClient(), SkipOn),
                ).ingestAndAwait()
            }
        } finally {
            scope.cancel()
        }

        assertEquals(emptyList(), files.backupUploadCalls.map { it.second }, "a failed read re-uploaded")
        assertNull(prefs.cursors["drive.gallery/cam"], "the cursor moved past files nobody checked")
    }

    @Test
    fun aRealmThatCannotBeRead_reachesTheSummaryAsATypedCause_notEnglish() = vmTest { vms ->
        // ⚠ The summary's reason reaches the notification body and the status line
        // verbatim, so a cause the engine knows must be typed — each platform renders it from its
        // own catalogue — never an English sentence. The free-text path stays for data planes.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1), item("b.jpg", 2))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        files.contentHashes["uri/a.jpg"] = "h-a"
        files.contentHashes["uri/b.jpg"] = "h-b"

        val scope = unconfinedScope(vms)
        val c = controller(
            scope, prefs, media, files,
            duplicates = TenshuDuplicateContentGate(UnreadableRealmClient(), SkipOn),
        )
        // Collected, not read off `state.value`: JustFinished auto-clears (see above).
        val seen = mutableListOf<BackupState>()
        scope.launch { c.state.collect { seen += it } }
        c.start()
        advanceUntilIdle()

        val finished = assertIs<BackupState.JustFinished>(seen.last { it is BackupState.JustFinished })
        assertEquals(2, finished.failed)
        assertNull(finished.failureReason, "the engine's English reached the user")
        assertEquals(BackupFailureKind.COULD_NOT_CHECK_REALM, finished.failureKind)
    }

    private companion object {
        /** A quarter of Binder's ~1 MB transaction, the budget `photos-app.md` §39 sized its pages to. */
        const val BOUNDED_REPLY_BYTES = 256 * 1024
    }

    /** A gate whose realm read fails while [readable] is false, and holds nothing once it succeeds. */
    private class FlakyRealmGate : DuplicateContentGate {
        var readable = false
        override suspend fun openForDrain(): DuplicateContentView =
            DuplicateContentView { if (readable) emptySet() else null }
    }

    @Test
    fun anAssetTheRealmCouldNotBeAskedAbout_staysOfferedThroughAnOverlappingAlbum() {
        // ⚠⚠ An iOS album is a VIEW: one asset can sit in two selected albums. When
        // the first album could not check the asset (the realm read failed), the second must not
        // treat it as handled — otherwise its cursor moves past the asset, and once the first album
        // is deselected nothing ever offers that photograph again.
        val prefs = FakePrefs(enabled = true, buckets = listOf("a1", "a2"))
        val shared = item("shared.jpg", 1)
        val media = FakeMediaSource(
            buckets = listOf(
                MediaBucket(id = "a1", displayName = "Album 1", itemCount = 1),
                MediaBucket(id = "a2", displayName = "Album 2", itemCount = 1),
            ),
            itemsByBucket = mapOf("a1" to listOf(shared), "a2" to listOf(shared)),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        files.contentHashes["uri/shared.jpg"] = "h-shared"
        val gate = FlakyRealmGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = controller(scope, prefs, media, files, duplicates = gate)
            runBlocking { controller.ingestAndAwait() }
            assertEquals(emptyList(), files.backupUploadCalls.map { it.second }, "nothing could be checked")

            // The first album is deselected, and the realm can be read again.
            prefs.bucketsFlow.value = listOf("a2")
            gate.readable = true
            runBlocking { controller.ingestAndAwait() }
        } finally {
            scope.cancel()
        }

        assertEquals(listOf("shared.jpg"), files.backupUploadCalls.map { it.second }, "the photograph was lost")
    }

    /** A gate whose first [failFirst] realm lookups fail, and which then answers from [held]. */
    private class RecoveringRealmGate(private val failFirst: Int) : DuplicateContentGate {
        val held = mutableSetOf<String>()
        private var asked = 0
        override suspend fun openForDrain(): DuplicateContentView =
            DuplicateContentView { hashes -> if (++asked <= failFirst) null else hashes.filterTo(mutableSetOf()) { it in held } }
    }

    @Test
    fun anAssetReleasedByAFailedCheck_uploadsOnceThroughTheOtherAlbum_andNeverTwice() {
        // The other half of the release: the second album may get an answer the first did not — it
        // then uploads the asset, and the first album's retry finds it held rather than uploading a
        // second copy into its own folder (the duplication `seenUris` exists to stop).
        val prefs = FakePrefs(enabled = true, buckets = listOf("a1", "a2"))
        val shared = item("shared.jpg", 1)
        val media = FakeMediaSource(
            buckets = listOf(
                MediaBucket(id = "a1", displayName = "Album 1", itemCount = 1),
                MediaBucket(id = "a2", displayName = "Album 2", itemCount = 1),
            ),
            itemsByBucket = mapOf("a1" to listOf(shared), "a2" to listOf(shared)),
        )
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        files.contentHashes["uri/shared.jpg"] = "h-shared"
        // ⚠ Two: album 1 asks for its batch, then once more for the candidate itself.
        val gate = RecoveringRealmGate(failFirst = 2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = controller(scope, prefs, media, files, duplicates = gate)
            runBlocking { controller.ingestAndAwait() }
            assertEquals(
                listOf("Album 2" to "shared.jpg"),
                files.backupUploadCalls.map { it.first.substringAfterLast('/') to it.second },
                "the second album did not get its own turn at the asset",
            )

            // The realm holds it now; album 1's cursor is still on it, so the next drain re-offers it.
            gate.held += "h-shared"
            runBlocking { controller.ingestAndAwait() }
        } finally {
            scope.cancel()
        }

        assertEquals(1, files.backupUploadCalls.size, "the retry uploaded a second copy")
    }

    @Test
    fun candidatesOfUnknownSize_areHashedOneAtATime_notABatchAheadOfTheFirstUpload() = vmTest { vms ->
        // ⚠⚠ A source that cannot size its items (iOS) must not have a whole batch (32) of them
        // read end to end before the first upload — and before the next Stop could land — however
        // large they are. Sized candidates are still hashed together: that is what the batch is for.
        suspend fun hashedBeforeTheFirstUpload(size: Long?): List<String> {
            val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
            val items = listOf("a.jpg", "b.jpg", "c.jpg").mapIndexed { i, n -> item(n, i + 1L).copy(sizeBytes = size) }
            val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to items))
            lateinit var files: FakeFilesRepository
            var hashed: List<String>? = null
            files = FakeFilesRepository(
                storageStatusResult = StorageStatusModel(true, 0, 100),
                backupOutcome = {
                    if (hashed == null) hashed = files.contentHashCalls.toList()
                    BackupUploadOutcome.Uploaded
                },
            )
            items.forEach { files.contentHashes[it.uri] = "h-${it.fileName}" }
            controller(unconfinedScope(vms), prefs, media, files, duplicates = FakeDuplicateGate(emptySet())).start()
            advanceUntilIdle()
            assertEquals(3, files.backupUploadCalls.size)
            return hashed!!
        }

        assertEquals(listOf("uri/a.jpg"), hashedBeforeTheFirstUpload(size = null))
        assertEquals(listOf("uri/a.jpg", "uri/b.jpg", "uri/c.jpg"), hashedBeforeTheFirstUpload(size = 1_000))
    }

    /** A gate whose realm cannot answer a question that names any of [unreadable], and holds [held]. */
    private class PatchyRealmGate(
        private val unreadable: Set<String>,
        private val held: Set<String>,
    ) : DuplicateContentGate {
        override suspend fun openForDrain(): DuplicateContentView = DuplicateContentView { hashes ->
            if (hashes.any { it in unreadable }) null else hashes.filterTo(mutableSetOf()) { it in held }
        }
    }

    @Test
    fun couldNotLookAlternatingWithHeld_isNotARunOfFailures_andTheFolderDrainsOn() {
        // ⚠ "Held" is the realm answering, so it ends a streak of failures just as an upload does.
        // Otherwise a realm that answers every other question stops the folder after five "could
        // not look"s, none of them consecutive — and the new file behind them waits for another
        // drain.
        val pairs = MAX_CONSECUTIVE_BUCKET_FAILURES + 2
        val items = (0 until pairs).flatMap { listOf(item("unknown$it.jpg", it * 2 + 1L), item("held$it.jpg", it * 2 + 2L)) } +
            item("new.jpg", pairs * 2 + 1L)
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to items))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        items.forEach { files.contentHashes["uri/${it.fileName}"] = "h-${it.fileName}" }
        val gate = PatchyRealmGate(
            unreadable = (0 until pairs).mapTo(mutableSetOf()) { "h-unknown$it.jpg" },
            held = (0 until pairs).mapTo(mutableSetOf()) { "h-held$it.jpg" },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking { controller(scope, prefs, media, files, duplicates = gate).ingestAndAwait() }
        } finally {
            scope.cancel()
        }

        assertEquals(listOf("new.jpg"), files.backupUploadCalls.map { it.second }, "the folder stopped")
        // ⚠ Breaking the streak is not forgiving the failure: the first file was never checked, so
        // the cursor stays behind it.
        assertNull(prefs.cursors["drive.gallery/cam"], "the cursor moved past a file nobody checked")
    }

    // ── automatic backup never starts the foreground service; Back up now does ──
    // (`drive-synced-folders-phase2.md` §4.1a)

    /** Every state the Android launcher would have seen, in order. */
    private fun CoroutineScope.recordLauncherInputs(ctrl: SyncedFolderController): MutableList<BackupState> {
        val seen = mutableListOf<BackupState>()
        launch { ctrl.state.collect { seen += it } }
        return seen
    }

    @Test
    fun automaticDrains_neverAskForTheForegroundService() = vmTest { vms ->
        // ⚠⚠ Play's dataSync policy names "daily auto backup of all new photos" as a use a
        // foreground service may not carry. The startup pass, a new photo and the WorkManager job
        // are all automatic — none of them may produce the launcher's edge.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val scope = unconfinedScope(vms)
        val ctrl = controller(scope, prefs, media, files)
        val seen = scope.recordLauncherInputs(ctrl)

        ctrl.start()
        advanceUntilIdle()
        media.itemsByBucket["cam"] = listOf(item("a.jpg", 1), item("b.jpg", 2))
        media.changes.tryEmit(Unit)
        advanceUntilIdle()
        media.itemsByBucket["cam"] = listOf(item("a.jpg", 1), item("b.jpg", 2), item("c.jpg", 3))
        ctrl.drainInBackground { false }
        advanceUntilIdle()

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), files.backupUploadCalls.map { it.second })
        assertTrue(seen.any { it is BackupState.BackingUp }, "nothing was ever in flight — this proves nothing")
        assertTrue(
            seen.none(::startsForegroundService),
            "an automatic drain asked for the foreground service: $seen",
        )
    }

    @Test
    fun backUpNow_asksForTheForegroundService_onlyWhileItsOwnRunLasts() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val scope = unconfinedScope(vms)
        val ctrl = controller(scope, prefs, media, files)
        val seen = scope.recordLauncherInputs(ctrl)
        ctrl.start()
        advanceUntilIdle()

        media.itemsByBucket["cam"] = listOf(item("a.jpg", 1), item("b.jpg", 2))
        ctrl.backUpNow()
        advanceUntilIdle()

        assertTrue(seen.any(::startsForegroundService), "the user's own tap never reached the launcher: $seen")
        assertNull(ctrl.userRequest.value, "the request outlived its run")

        // ⚠ The next drain is automatic again, even straight after a user run: neither the launcher
        // nor a service still showing the user run's summary may carry it.
        val afterUserRun = seen.size
        media.itemsByBucket["cam"] = listOf(item("a.jpg", 1), item("b.jpg", 2), item("c.jpg", 3))
        media.changes.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), files.backupUploadCalls.map { it.second })
        // ⚠⚠ The same split on the wire to Control: only the user run's upload is marked user-started,
        // so only it may hold Control's foreground service (control-background-work-off-fgs.md §5.1).
        assertEquals(listOf("b.jpg"), files.userStartedBackupUploads)
        val later = seen.drop(afterUserRun)
        assertTrue(later.any { it is BackupState.BackingUp }, "the automatic drain never ran")
        assertTrue(later.none(::startsForegroundService), "$later")
        assertTrue(
            later.filterIsInstance<BackupState.BackingUp>()
                .all { foregroundServiceState(it) == BackupState.UpToDate },
            "a service still up from the user run would re-promote itself for an automatic drain",
        )
    }

    @Test
    fun backUpNow_ignoringWifiOnly_backsUpOnMobileDataOnce_andNeverTouchesTheSetting() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), wifiOnly = true)
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val ctrl = controller(unconfinedScope(vms), prefs, media, files, env = FakeEnv(net = BackupNetworkStatus.METERED))
        ctrl.start()
        advanceUntilIdle()
        assertEquals(emptyList(), files.backupUploadCalls, "on mobile data with Wi-Fi only on")
        assertEquals(setOf(BackupConstraint.WIFI_ONLY), ctrl.overridableBlockersNow())

        ctrl.backUpNow(ignore = setOf(BackupConstraint.WIFI_ONLY))
        advanceUntilIdle()

        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
        assertTrue(prefs.wifiOnlyFlow.value, "the one-time override wrote the saved setting")
        assertEquals(
            1,
            files.syncNowCalls,
            "Control's content plane holds metered bytes back too — the override must reach it, " +
                "or the file is handed over and then waits for Wi-Fi anyway",
        )

        // Once means once: the next automatic drain obeys Wi-Fi only again.
        media.itemsByBucket["cam"] = listOf(item("a.jpg", 1), item("b.jpg", 2))
        media.changes.tryEmit(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
        assertIs<BackupState.Paused>(ctrl.state.value)
        assertEquals(1, files.syncNowCalls)
    }

    @Test
    fun backUpNow_ignoringChargingOnly_runsUnplugged_withoutTouchingTheMeteredGate() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), chargingOnly = true)
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val ctrl = controller(unconfinedScope(vms), prefs, media, files, env = FakeEnv(charging = false))
        ctrl.start()
        advanceUntilIdle()
        assertEquals(setOf(BackupConstraint.CHARGING_ONLY), ctrl.overridableBlockersNow())

        ctrl.backUpNow(ignore = setOf(BackupConstraint.CHARGING_ONLY))
        advanceUntilIdle()

        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
        assertTrue(prefs.chargingOnlyFlow.value)
        assertEquals(0, files.syncNowCalls, "charging is Drive's gate alone; nothing to lift in Control")
    }

    @Test
    fun backUpNow_withoutTheOverride_stillPausesOffPolicy() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), wifiOnly = true)
        val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val ctrl = controller(unconfinedScope(vms), prefs, media, files, env = FakeEnv(net = BackupNetworkStatus.METERED))
        ctrl.start()
        advanceUntilIdle()

        ctrl.backUpNow()
        advanceUntilIdle()

        assertEquals(emptyList(), files.backupUploadCalls)
        assertEquals(BackupState.Paused(BackupPauseReason.METERED_NETWORK, waiting = 1), ctrl.state.value)
        assertEquals(0, files.syncNowCalls)
    }

    // ── the WorkManager job's budget, and what an interrupted run leaves behind ──
    // (`drive-synced-folders-phase2.md` §4.1a)

    @Test
    fun drainInBackground_yieldsAtAFileBoundary_andTheNextJobFinishesTheSameRun() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), emptyMap())
        val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
        val scope = unconfinedScope(vms)
        val ctrl = controller(scope, prefs, media, files)
        ctrl.start()
        advanceUntilIdle()
        val states = mutableListOf<BackupState>()
        scope.launch { ctrl.state.collect { states += it } }
        media.itemsByBucket["cam"] = listOf(item("a.jpg", 1), item("b.jpg", 2), item("c.jpg", 3))

        val first = ctrl.drainInBackground(shouldYield = { files.backupUploadCalls.size >= 1 })
        advanceUntilIdle()

        assertEquals(BackgroundDrainResult.YIELDED, first)
        assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
        assertEquals("1", prefs.cursors["drive.gallery/cam"], "the cursor must stop at the last accepted file")
        // Open, not finished: "Backed up 1" would say the backup is done while two files wait.
        assertEquals(BackupState.BackingUp(1, 3, ""), ctrl.state.value)

        val second = ctrl.drainInBackground(shouldYield = { false })
        advanceUntilIdle()

        assertEquals(BackgroundDrainResult.COMPLETE, second)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), files.backupUploadCalls.map { it.second })
        assertTrue(
            states.filterIsInstance<BackupState.BackingUp>().all { it.total == 3 },
            "the continuation counted the files it re-enumerated a second time: $states",
        )
        assertEquals(3, states.filterIsInstance<BackupState.JustFinished>().single().uploaded)
    }

    @Test
    fun anOsStopMidFile_losesNothing_theNextJobUploadsWhatItInterrupted() = vmTest { vms ->
        // The OS may stop the job at any point (Wi-Fi gone, the 10-minute limit). The cursor only
        // moves past a file Control accepted, so the next run re-offers exactly the interrupted one.
        val prefs = FakePrefs(enabled = true, buckets = listOf("cam"))
        val media = FakeMediaSource(listOf(camBucket), emptyMap())
        var stopOnB = true
        var stopTheJob: () -> Unit = { error("the job is not running yet") }
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(true, 0, 100),
            backupOutcome = { name ->
                if (name == "b.jpg" && stopOnB) {
                    stopOnB = false
                    stopTheJob()
                    throw CancellationException("the OS stopped the job")
                }
                BackupUploadOutcome.Uploaded
            },
        )
        val scope = unconfinedScope(vms)
        val ctrl = controller(scope, prefs, media, files)
        ctrl.start()
        advanceUntilIdle()
        val states = mutableListOf<BackupState>()
        scope.launch { ctrl.state.collect { states += it } }
        media.itemsByBucket["cam"] = listOf(item("a.jpg", 1), item("b.jpg", 2), item("c.jpg", 3))

        // LAZY: the unconfined scope would otherwise run the whole drain inside `launch`, before
        // there is a job to stop.
        val job = scope.launch(start = CoroutineStart.LAZY) { ctrl.drainInBackground(shouldYield = { false }) }
        stopTheJob = { job.cancel() }
        job.start()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals("1", prefs.cursors["drive.gallery/cam"], "the cursor moved past the interrupted file")

        assertEquals(BackgroundDrainResult.COMPLETE, ctrl.drainInBackground(shouldYield = { false }))
        advanceUntilIdle()

        assertEquals(listOf("a.jpg", "b.jpg", "b.jpg", "c.jpg"), files.backupUploadCalls.map { it.second })
        assertEquals("3", prefs.cursors["drive.gallery/cam"])
        // The stopped drain's count survives into the run's summary: a, then b and c.
        assertEquals(3, states.filterIsInstance<BackupState.JustFinished>().single().uploaded)
    }

    // ── the screen asks before it lifts one of the user's constraints ──
    // (`drive-synced-folders-phase2.md` §4.1a)

    private fun OwnedViewModels.syncedFoldersVm(
        ctrl: SyncedFolderController,
        prefs: FakePrefs,
        media: FakeMediaSource,
        files: FakeFilesRepository,
    ) = own(
        SyncedFoldersViewModel(
            controller = ctrl,
            preferences = prefs,
            mediaSource = media,
            filesRepository = files,
            backupFolderGrants = NoopBackupFolderGrants,
        ),
    )

    @Test
    fun backUpNowOnMobileData_asksFirst_andRunsOnlyIfTheUserAgrees() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), wifiOnly = true)
            val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
            val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
            val ctrl = controller(unconfinedScope(vms), prefs, media, files, env = FakeEnv(net = BackupNetworkStatus.METERED))
            ctrl.start()
            val vm = vms.syncedFoldersVm(ctrl, prefs, media, files)
            advanceUntilIdle()

            vm.backUpNow()
            advanceUntilIdle()
            assertEquals(setOf(BackupConstraint.WIFI_ONLY), vm.uiState.value.backUpNowConfirmation)
            assertEquals(emptyList(), files.backupUploadCalls, "it ran before the user answered")

            vm.dismissBackUpNowOverride()
            advanceUntilIdle()
            assertNull(vm.uiState.value.backUpNowConfirmation)
            assertEquals(emptyList(), files.backupUploadCalls, "Not now uploaded anyway")

            vm.backUpNow()
            advanceUntilIdle()
            vm.confirmBackUpNowOverride()
            advanceUntilIdle()
            assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
            assertTrue(prefs.wifiOnlyFlow.value, "agreeing once must not turn Wi-Fi only off")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun backUpNowOnPolicy_runsWithoutAsking() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), wifiOnly = true)
            val media = FakeMediaSource(listOf(camBucket), emptyMap())
            val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
            val ctrl = controller(unconfinedScope(vms), prefs, media, files)
            ctrl.start()
            val vm = vms.syncedFoldersVm(ctrl, prefs, media, files)
            advanceUntilIdle()
            media.itemsByBucket["cam"] = listOf(item("a.jpg", 1))

            vm.backUpNow()
            advanceUntilIdle()

            assertNull(vm.uiState.value.backUpNowConfirmation, "asked on Wi-Fi, where nothing is in the way")
            assertEquals(listOf("a.jpg"), files.backupUploadCalls.map { it.second })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun backUpNowWithNoConnection_offersNoOverride() = vmTest { vms ->
        // "Use mobile data this once?" on a phone with no connection offers a choice that changes
        // nothing; the footer's "Paused — no connection" is the answer.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val prefs = FakePrefs(enabled = true, buckets = listOf("cam"), wifiOnly = true)
            val media = FakeMediaSource(listOf(camBucket), mapOf("cam" to listOf(item("a.jpg", 1))))
            val files = FakeFilesRepository(storageStatusResult = StorageStatusModel(true, 0, 100))
            val ctrl = controller(unconfinedScope(vms), prefs, media, files, env = FakeEnv(net = BackupNetworkStatus.NONE))
            ctrl.start()
            val vm = vms.syncedFoldersVm(ctrl, prefs, media, files)
            advanceUntilIdle()

            vm.backUpNow()
            advanceUntilIdle()

            assertNull(vm.uiState.value.backUpNowConfirmation)
            assertEquals(emptyList(), files.backupUploadCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
