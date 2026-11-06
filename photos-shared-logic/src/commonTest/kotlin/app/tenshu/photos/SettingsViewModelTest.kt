package app.tenshu.photos

import app.tenshu.ingest.IngestSourceInfo
import app.tenshu.ingest.IngestSources
import app.tenshu.photos.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The Settings tab (`photos-app.md` S7 / PH-4).
 *
 * ⚠⚠ The first two are the ones that matter: "could not reach Control" and "nothing is being
 * backed up" are different answers, and a screen that renders them the same tells a user their
 * backup is off when it is merely unreachable.
 */
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun source(id: String, enabled: Boolean = true) = IngestSourceInfo(
        sourceId = id,
        appId = "app.tenshu.drive",
        displayName = id,
        kind = "GALLERY",
        enabled = enabled,
        status = "UP_TO_DATE",
        pendingCount = 0,
        lastRunAtMs = 0,
        lastError = "",
    )

    /** A registry stand-in: `null` means the read failed, a list means it succeeded. */
    private class FakeRegistry(var result: List<IngestSourceInfo>?) : IngestSources {
        val writes = mutableListOf<Pair<String, Boolean>>()

        override suspend fun sources(): List<IngestSourceInfo>? = result

        override suspend fun setEnabled(sourceId: String, enabled: Boolean): Boolean {
            writes += sourceId to enabled
            result = result?.map { if (it.sourceId == sourceId) it.copy(enabled = enabled) else it }
            return true
        }
    }

    private class FakeSettings(var skip: Boolean = true) : app.tenshu.ingest.IngestSettings {
        val writes = mutableListOf<Boolean>()
        override fun observeSkipExistingContent() = kotlinx.coroutines.flow.flowOf(skip)
        override suspend fun readSkipExistingContent(): Boolean = skip
        override suspend fun setSkipExistingContent(enabled: Boolean) {
            writes += enabled
            skip = enabled
        }
    }

    private class FakeGridMotion(var play: Boolean = true) : app.tenshu.photos.settings.GridMotionSettings {
        val writes = mutableListOf<Boolean>()
        override fun observePlayVideosInGrid() = kotlinx.coroutines.flow.flowOf(play)
        override suspend fun readPlayVideosInGrid(): Boolean = play
        override suspend fun setPlayVideosInGrid(enabled: Boolean) {
            writes += enabled
            play = enabled
        }
    }

    // ── M5: "Play videos in the grid" (photos-video-support.md D2) ──────────────────────────

    @Test
    fun theGridMotionSwitchReadsTheStoredRowAndDefaultsOn() = vmTest { vms ->
        val viewModel = vms.own(SettingsViewModel(FakeRegistry(emptyList()), gridMotion = FakeGridMotion(play = false)))
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.state.value.playVideosInGrid)

        val inert = vms.own(SettingsViewModel(FakeRegistry(emptyList())))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(inert.state.value.playVideosInGrid, "no store: the declared default, which is on")
    }

    @Test
    fun flippingTheGridMotionSwitchWritesTheRowAndReconciles() = vmTest { vms ->
        val store = FakeGridMotion(play = true)
        val viewModel = vms.own(SettingsViewModel(FakeRegistry(emptyList()), gridMotion = store))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.setPlayVideosInGrid(false)
        assertFalse(viewModel.state.value.playVideosInGrid, "optimistic: the screen shows the new value at once")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(false), store.writes)
        assertFalse(viewModel.state.value.playVideosInGrid, "and the reconcile agrees with the row")
    }

    @Test
    fun withoutAStoreTheGridMotionSwitchIsInertNotAbsent() = vmTest { vms ->
        val viewModel = vms.own(SettingsViewModel(FakeRegistry(emptyList())))
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.setPlayVideosInGrid(false)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.playVideosInGrid, "nothing to write to: the row stays at its default")
    }

    // ── PH-4b: adding a source, not only managing one ────────────────────────────────────────

    private class FakeMediaSource(
        private val buckets: List<app.tenshu.ingest.backup.MediaBucket>,
    ) : app.tenshu.ingest.backup.MediaSourceProvider {
        override suspend fun listBuckets(includeVideo: Boolean) = buckets
        override suspend fun listNewItems(bucketId: String, sinceCursor: String?, includeVideo: Boolean) =
            emptyList<app.tenshu.ingest.backup.MediaItem>()
        override fun observeChanges(watched: Set<String>) =
            kotlinx.coroutines.flow.emptyFlow<Unit>()
    }

    /** ⚠ Delegates the twenty methods it does not care about; `NoopBackupPreferences` is an
     *  object, so it cannot be subclassed — and a hand-written stub of the whole interface would
     *  need editing every time the engine grows a setting. */
    private class FakeBackupPrefs(
        initial: List<String> = emptyList(),
    ) : app.tenshu.ingest.backup.BackupPreferences by app.tenshu.ingest.backup.NoopBackupPreferences {
        var buckets = initial
            private set

        override suspend fun readSelectedBuckets(): List<String> = buckets
        override suspend fun setSelectedBuckets(ids: List<String>) { buckets = ids }
    }

    private fun bucket(id: String, name: String, count: Int = 10) =
        app.tenshu.ingest.backup.MediaBucket(id = id, displayName = name, itemCount = count)

    @Test
    fun aDeviceThatCannotSeeItsFoldersOffersNoPicker() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Absent, not empty. Desktop's drain lives in Control, so Photos there cannot enumerate
        // folders — and an empty list would say "you have no folders", which is a different and
        // false claim from "this device cannot answer".
        val vm = vms.own(SettingsViewModel(FakeRegistry(listOf(source("a")))))
        testScheduler.advanceUntilIdle()

        assertFalse(vm.state.value.canAddSources)
        assertTrue(vm.state.value.addableBuckets.isEmpty())
    }

    @Test
    fun thePickerListsThisDevicesFoldersAndWhichAreOn() = vmTest(dispatcher) { vms ->
        val media = FakeMediaSource(listOf(bucket("cam", "Camera", 812), bucket("shots", "Screenshots", 40)))
        val prefs = FakeBackupPrefs(initial = listOf("cam"))

        val vm = vms.own(SettingsViewModel(FakeRegistry(emptyList()), null, media, prefs))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.canAddSources)
        assertEquals(listOf("Camera", "Screenshots"), vm.state.value.addableBuckets.map { it.displayName })
        assertEquals(listOf(true, false), vm.state.value.addableBuckets.map { it.selected })
    }

    @Test
    fun tickingAFolderWritesTheSelectionTheDrainReads() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The whole point of PH-4b. The row written here is the one `SyncedFolderController`
        // enumerates — not a second record in Photos that nothing acts on.
        val media = FakeMediaSource(listOf(bucket("cam", "Camera"), bucket("shots", "Screenshots")))
        val prefs = FakeBackupPrefs()

        val vm = vms.own(SettingsViewModel(FakeRegistry(emptyList()), null, media, prefs))
        testScheduler.advanceUntilIdle()
        vm.setBucketSelected("shots", true)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("shots"), prefs.buckets)
        assertTrue(vm.state.value.addableBuckets.single { it.id == "shots" }.selected)
    }

    @Test
    fun untickingRemovesOnlyThatFolder() = vmTest(dispatcher) { vms ->
        // ⚠ Read-modify-write against the stored row: the selection is synced and the drain may be
        // reading it, so writing the screen's copy back wholesale would clobber a change made
        // elsewhere between the read and the tap.
        val media = FakeMediaSource(listOf(bucket("cam", "Camera"), bucket("shots", "Screenshots")))
        val prefs = FakeBackupPrefs(initial = listOf("cam", "shots"))

        val vm = vms.own(SettingsViewModel(FakeRegistry(emptyList()), null, media, prefs))
        testScheduler.advanceUntilIdle()
        vm.setBucketSelected("cam", false)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("shots"), prefs.buckets)
    }

    @Test
    fun aFolderListThatCannotBeReadIsEmptyRatherThanACrash() = vmTest(dispatcher) { vms ->
        val throwing = object : app.tenshu.ingest.backup.MediaSourceProvider {
            override suspend fun listBuckets(includeVideo: Boolean): List<app.tenshu.ingest.backup.MediaBucket> =
                error("permission revoked mid-read")
            override suspend fun listNewItems(bucketId: String, sinceCursor: String?, includeVideo: Boolean) =
                emptyList<app.tenshu.ingest.backup.MediaItem>()
            override fun observeChanges(watched: Set<String>) = kotlinx.coroutines.flow.emptyFlow<Unit>()
        }

        val vm = vms.own(SettingsViewModel(FakeRegistry(emptyList()), null, throwing, FakeBackupPrefs()))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.addableBuckets.isEmpty())
    }

    @Test
    fun theDuplicateSkipDefaultsOnAndSurvivesARead() = vmTest(dispatcher) { vms ->
        // ⚠ Both halves matter. The seed keeps the switch from rendering "off" while the row
        // resolves — a switch that shows the wrong position is worse than one that shows none —
        // and the read is what makes it reflect the row after.
        val vm = vms.own(SettingsViewModel(FakeRegistry(listOf(source("a"))), FakeSettings(skip = true)))
        assertTrue(vm.state.value.skipExistingContent, "seeded from the preference's own default")

        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.skipExistingContent)
    }

    @Test
    fun turningTheSkipOffWritesTheSharedRow() = vmTest(dispatcher) { vms ->
        val settings = FakeSettings(skip = true)
        val vm = vms.own(SettingsViewModel(FakeRegistry(listOf(source("a"))), settings))
        testScheduler.advanceUntilIdle()

        vm.setSkipExistingContent(false)
        // Optimistic: the switch moves before the write lands, like the per-source toggle.
        assertFalse(vm.state.value.skipExistingContent)

        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), settings.writes)
        assertFalse(vm.state.value.skipExistingContent)
    }

    @Test
    fun withNoPreferenceServiceTheSwitchIsInertRatherThanWrong() = vmTest(dispatcher) { vms ->
        // ⚠ The preview / test path. It must not crash, and it must not claim the drain is
        // skipping when nothing is there to tell it to.
        val vm = vms.own(SettingsViewModel(FakeRegistry(listOf(source("a")))))
        testScheduler.advanceUntilIdle()

        vm.setSkipExistingContent(false)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.skipExistingContent, "an inert switch does not move")
    }

    @Test
    fun anUnreachableProxyIsNotAnEmptyBackupList() = vmTest(dispatcher) { vms ->
        val vm = vms.own(SettingsViewModel(FakeRegistry(result = null)))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.readFailed)
        assertFalse(
            vm.state.value.nothingBackedUp,
            "unreachable rendered as 'nothing is backed up' tells the user their backup is off",
        )
    }

    @Test
    fun aDeviceThatIngestsNothingSaysSo() = vmTest(dispatcher) { vms ->
        val vm = vms.own(SettingsViewModel(FakeRegistry(result = emptyList())))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.nothingBackedUp)
        assertFalse(vm.state.value.readFailed)
    }

    @Test
    fun togglingASourceWritesItAndKeepsTheScreenInStep() = vmTest(dispatcher) { vms ->
        val registry = FakeRegistry(result = listOf(source("camera"), source("screenshots")))
        val vm = vms.own(SettingsViewModel(registry))
        testScheduler.advanceUntilIdle()

        vm.setEnabled("camera", false)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("camera" to false), registry.writes)
        assertEquals(false, vm.state.value.sources.single { it.sourceId == "camera" }.enabled)
        assertEquals(true, vm.state.value.sources.single { it.sourceId == "screenshots" }.enabled)
    }

    @Test
    fun theToggleShowsAtOnce_beforeTheWriteLands() = vmTest(dispatcher) { vms ->
        // ⚠ Optimistic then reconciled: a switch that waits for a round trip feels broken, and one
        // that never reconciles lies. This pins the first half; the test above pins the second.
        val vm = vms.own(SettingsViewModel(FakeRegistry(result = listOf(source("camera")))))
        testScheduler.advanceUntilIdle()

        vm.setEnabled("camera", false)

        assertEquals(
            false,
            vm.state.value.sources.single().enabled,
            "the switch moves before the coroutine runs",
        )
    }
}
