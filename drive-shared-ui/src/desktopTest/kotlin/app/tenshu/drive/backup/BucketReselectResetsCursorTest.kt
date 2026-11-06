package app.tenshu.drive.backup

import app.tenshu.ingest.backup.BackupNetworkStatus
import app.tenshu.ingest.backup.BackupEnvironment
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.ingest.backup.MediaBucket
import app.tenshu.ingest.backup.MediaItem
import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.drive.data.DesktopMediaSourceProvider
import app.tenshu.drive.data.NoopBackupFolderGrants
import app.tenshu.drive.ui.FakeFilesRepository
import org.tenshu.core.ingest.SecurityScopedFolderGrant
import app.tenshu.ingest.backup.GALLERY_SOURCE_ID
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.ingest.backup.galleryBackupSource
import app.tenshu.drive.ui.screens.settings.SyncedFoldersViewModel
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ticking a folder in Synced Folders forgets that bucket's cursor, so the folder is
 * re-scanned from the beginning (`drive-synced-folders.md` §3.9).
 *
 * ⚠⚠ Without this, ticking a folder whose destination was deleted from another device
 * does nothing at all, forever: the cursor is a monotonic watermark, so the drain finds
 * zero items past it, reports `UP_TO_DATE` and exits. Measured on the maintainer's phone
 * 2026-08-13 — bucket `-1829889111` selected, cursor `1000015966`, 787 files below it,
 * destination folder gone, and re-ticking uploaded nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BucketReselectResetsCursorTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun tickingABucketForgetsItsCursor() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = RecordingPrefs(cursors = mutableMapOf("$GALLERY_SOURCE_ID/bucket-1" to "1000015966"))
        val vm = vms.viewModel(prefs)
        advanceUntilIdle()

        vm.setBucketSelected("bucket-1", selected = true)
        advanceUntilIdle()

        assertEquals(
            emptyMap(),
            prefs.cursors,
            "Ticking a folder means 'back this folder up' — a stale watermark makes that a no-op",
        )
        assertTrue(prefs.selectedBuckets.contains("bucket-1"))
    }

    @Test
    fun theCursorIsClearedBeforeTheSelectionIsWritten() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = RecordingPrefs(cursors = mutableMapOf("$GALLERY_SOURCE_ID/bucket-1" to "999"))
        val vm = vms.viewModel(prefs)
        advanceUntilIdle()

        vm.setBucketSelected("bucket-1", selected = true)
        advanceUntilIdle()

        assertEquals(
            listOf("clearCursor:bucket-1", "setSelectedBuckets"),
            prefs.calls.filter { it.startsWith("clearCursor") || it == "setSelectedBuckets" },
            "Writing the selection is itself a drain trigger, so clearing after it races a " +
                "drain that reads the OLD cursor, finds nothing, and parks at UP_TO_DATE with " +
                "nothing left to re-trigger it",
        )
    }

    @Test
    fun anUncheckedGrantRemainsVisible() = kotlinx.coroutines.test.runTest {
        val grant = SecurityScopedFolderGrant.encode(
            grantId = "0123456789abcdef0123456789abcdef",
            displayName = "Archive",
        )
        val prefs = RecordingPrefs()
        prefs.grantedBuckets += grant
        prefs.selectedBuckets = emptyList()

        val buckets = DesktopMediaSourceProvider(
            roots = emptyList(),
            preferences = prefs,
        ).listBuckets(includeVideo = false)

        assertEquals(listOf("Archive"), buckets.map { it.displayName })
    }

    @Test
    fun untickingLeavesTheCursorAlone() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = RecordingPrefs(cursors = mutableMapOf("$GALLERY_SOURCE_ID/bucket-1" to "1000015966"))
        val vm = vms.viewModel(prefs)
        advanceUntilIdle()

        vm.setBucketSelected("bucket-1", selected = false)
        advanceUntilIdle()

        assertEquals(
            mapOf("$GALLERY_SOURCE_ID/bucket-1" to "1000015966"),
            prefs.cursors,
            "Unticking is 'stop backing this up', not 'redo it' — dropping the cursor here " +
                "would make every untick/retick pair re-enumerate the whole bucket",
        )
    }

    private fun OwnedViewModels.viewModel(prefs: RecordingPrefs): SyncedFoldersViewModel {
        val media = FakeMediaSource()
        val repo = FakeFilesRepository()
        // Never started, so no drain loop runs — the VM only reads `runsDrainInProcess`
        // and `state` from it.
        val controller = SyncedFolderController(
            preferences = prefs,
            sources = listOf(galleryBackupSource(media, prefs)),
            dataPlane = repo,
            environment = object : BackupEnvironment {
                override fun network() = BackupNetworkStatus.UNMETERED
                override fun isCharging() = true
                override fun changes() = kotlinx.coroutines.flow.emptyFlow<Unit>()
            },
        )
        return own(SyncedFoldersViewModel(
            controller = controller,
            preferences = prefs,
            mediaSource = media,
            filesRepository = repo,
            backupFolderGrants = NoopBackupFolderGrants,
        ))
    }

    private class FakeMediaSource : MediaSourceProvider {
        override suspend fun listBuckets(includeVideo: Boolean): List<MediaBucket> =
            listOf(MediaBucket(id = "bucket-1", displayName = "WhatsApp Images", itemCount = 787))

        override suspend fun listNewItems(
            bucketId: String,
            sinceCursor: String?,
            includeVideo: Boolean,
        ): List<MediaItem> = emptyList()

        override fun observeChanges(bucketIds: Set<String>): Flow<Unit> = flowOf()
    }

    /** Records the ORDER of writes — the ordering is the contract, not just the end state. */
    private class RecordingPrefs(
        val cursors: MutableMap<String, String> = mutableMapOf(),
    ) : BackupPreferences {
        val calls = mutableListOf<String>()
        var selectedBuckets: List<String> = emptyList()
        val grantedBuckets = mutableListOf<String>()

        private val enabledFlow = MutableStateFlow(true)
        private val bucketsFlow = MutableStateFlow<List<String>>(emptyList())

        override fun observePausedByUser(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
        override suspend fun setPausedByUser(paused: Boolean) = Unit
        override suspend fun readPausedByUser(): Boolean = false
        override fun observeEnabled(): Flow<Boolean> = enabledFlow
        override suspend fun setEnabled(enabled: Boolean) { enabledFlow.value = enabled }
        override fun observeSelectedBuckets(): Flow<List<String>> = bucketsFlow
        override suspend fun setSelectedBuckets(ids: List<String>) {
            calls += "setSelectedBuckets"; selectedBuckets = ids; bucketsFlow.value = ids
        }
        override fun observeGrantedBuckets(): Flow<List<String>> = flowOf(grantedBuckets)
        override suspend fun readGrantedBuckets(): List<String> = grantedBuckets.toList()
        override suspend fun addGrantedBucket(id: String) {
            if (id !in grantedBuckets) grantedBuckets += id
        }
        override suspend fun readEnabled(): Boolean = enabledFlow.value
        override suspend fun readSelectedBuckets(): List<String> = bucketsFlow.value
        override suspend fun readDocumentTreeUris(): List<String> = emptyList()
        override suspend fun readVideos(): Boolean = false
        override suspend fun readWifiOnly(): Boolean = false
        override suspend fun readChargingOnly(): Boolean = false
        override suspend fun readBackupNewFolders(): Boolean = false
        override fun observeDocumentTreeUris(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun addDocumentTreeUri(uri: String) = Unit
        override suspend fun removeDocumentTreeUri(uri: String) = Unit
        override fun observeBackupNewFolders(): Flow<Boolean> = flowOf(false)
        override suspend fun setBackupNewFolders(enabled: Boolean) = Unit
        override suspend fun readKnownBuckets(): Set<String> = emptySet()
        override suspend fun addKnownBuckets(ids: Set<String>) = Unit
        override fun observeVideos(): Flow<Boolean> = flowOf(false)
        override suspend fun setVideos(enabled: Boolean) = Unit
        override fun observeWifiOnly(): Flow<Boolean> = flowOf(false)
        override suspend fun setWifiOnly(enabled: Boolean) = Unit
        override fun observeChargingOnly(): Flow<Boolean> = flowOf(false)
        override suspend fun setChargingOnly(enabled: Boolean) = Unit
        override suspend fun readCursors(sourceId: String): Map<String, String> {
            val prefix = "$sourceId/"
            return cursors.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        }
        override suspend fun setCursor(sourceId: String, bucketId: String, cursor: String) {
            calls += "setCursor:$bucketId"; cursors["$sourceId/$bucketId"] = cursor
        }
        override suspend fun clearCursor(sourceId: String, bucketId: String) {
            calls += "clearCursor:$bucketId"; cursors.remove("$sourceId/$bucketId")
        }
        override suspend fun deviceLabel(): String = "dev1"
    }
}
