package app.tenshu.control.ui.screens.offlinemaps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMapsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /**
     * ⚠ [progressTicks] and [gate] are the load-bearing part of this fake. The previous version
     * completed a download instantly and emitted no progress at all, so it modelled only the happy
     * path — and a fake that does that cannot fail when the UI never subscribes to progress, which is
     * exactly how Control shipped an indeterminate spinner over a working byte-accurate stream.
     */
    private class FakeRepo(
        initial: List<OfflineMapRegion>,
        private val downloadOk: Boolean = true,
        /** Refuses unless the caller passes the override — the metered gate, as the proxy applies it. */
        private val meteredUnlessAllowed: Boolean = false,
        private val catalogUnavailable: Boolean = false,
        private val progressTicks: List<Long> = emptyList(),
        /** Held open so a test can observe the in-flight state before the download resolves. */
        private val gate: CompletableDeferred<Unit>? = null,
        private val throwOnDownload: Boolean = false,
    ) : OfflineMapsRepository {
        var regions = initial
        val downloaded = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var progressSubscriptions = 0

        override suspend fun regions() = OfflineMapsSnapshot(regions, catalogUnavailable)

        val allowedMetered = mutableListOf<Boolean>()

        override suspend fun download(regionId: String, allowMetered: Boolean): RegionDownloadOutcome {
            downloaded += regionId
            allowedMetered += allowMetered
            if (meteredUnlessAllowed && !allowMetered) return RegionDownloadOutcome.NeedsMeteredConsent
            gate?.await()
            if (throwOnDownload) throw IllegalStateException("transport died")
            if (downloadOk) regions = regions.map { if (it.regionId == regionId) it.copy(installed = true) else it }
            return if (downloadOk) RegionDownloadOutcome.Landed else RegionDownloadOutcome.Failed
        }

        override fun downloadProgress(regionId: String): Flow<Long> = flow {
            progressSubscriptions++
            progressTicks.forEach { emit(it) }
            // Then hang, like the real stream does while the download is still running — it ends only
            // when the download does.
            gate?.await()
        }

        override suspend fun delete(regionId: String) {
            deleted += regionId
            regions = regions.map { if (it.regionId == regionId) it.copy(installed = false) else it }
        }
    }

    private val seed = listOf(
        OfflineMapRegion("at", "Österreich", 95_000_000L, installed = true),
        OfflineMapRegion("de-bayern", "Bayern", 420_000_000L, installed = false),
    )

    @Test
    fun loadsRegionsOnInit() = vmTest(dispatcher) { vms ->
        val vm = vms.own(OfflineMapsViewModel(FakeRepo(seed)))
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(false, s.loading)
        assertEquals(listOf("at", "de-bayern"), s.regions.map { it.regionId })
    }

    @Test
    fun downloadMarksInstalledAndRefreshes() = vmTest(dispatcher) { vms ->
        val repo = FakeRepo(seed)
        val vm = vms.own(OfflineMapsViewModel(repo))
        advanceUntilIdle()

        vm.download("de-bayern")
        advanceUntilIdle()

        assertEquals(listOf("de-bayern"), repo.downloaded)
        assertTrue(vm.uiState.value.regions.first { it.regionId == "de-bayern" }.installed)
        assertNull(vm.uiState.value.busyRegionId)
    }

    @Test
    fun downloadFailureEmitsAMessage() = vmTest(dispatcher) { vms ->
        val vm = vms.own(OfflineMapsViewModel(FakeRepo(seed, downloadOk = false)))
        advanceUntilIdle()

        vm.download("de-bayern")
        advanceUntilIdle()

        assertEquals(OfflineMapsMessage.DownloadFailed, vm.uiState.value.message)
        assertTrue(vm.uiState.value.regions.first { it.regionId == "de-bayern" }.installed.not())
    }

    @Test
    fun deleteEvictsAndRefreshes() = vmTest(dispatcher) { vms ->
        val repo = FakeRepo(seed)
        val vm = vms.own(OfflineMapsViewModel(repo))
        advanceUntilIdle()

        vm.delete("at")
        advanceUntilIdle()

        assertEquals(listOf("at"), repo.deleted)
        assertTrue(vm.uiState.value.regions.first { it.regionId == "at" }.installed.not())
    }

    @Test
    fun downloadPublishesProgressWhileInFlight() = vmTest(dispatcher) { vms ->
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo(seed, progressTicks = listOf(42_000_000L, 210_000_000L), gate = gate)
        val vm = vms.own(OfflineMapsViewModel(repo))
        advanceUntilIdle()

        vm.download("de-bayern")
        advanceUntilIdle()

        // Still downloading: the row knows which region, and how far along.
        assertEquals("de-bayern", vm.uiState.value.busyRegionId)
        assertEquals(210_000_000L, vm.uiState.value.downloadedBytes)
        assertEquals(1, repo.progressSubscriptions)

        gate.complete(Unit)
        advanceUntilIdle()

        // Finished: progress is cleared so no stale bar survives the download.
        assertNull(vm.uiState.value.busyRegionId)
        assertNull(vm.uiState.value.downloadedBytes)
    }

    @Test
    fun downloadStartsAtZeroBytesBeforeTheFirstTick() = vmTest(dispatcher) { vms ->
        val gate = CompletableDeferred<Unit>()
        val vm = vms.own(OfflineMapsViewModel(FakeRepo(seed, progressTicks = emptyList(), gate = gate)))
        advanceUntilIdle()

        vm.download("de-bayern")
        advanceUntilIdle()

        // 0, not null: the row must read as a download from the first frame, not as a delete.
        assertEquals(0L, vm.uiState.value.downloadedBytes)
        gate.complete(Unit)
    }

    @Test
    fun aThrowingDownloadStillClearsTheBusyRow() = vmTest(dispatcher) { vms ->
        val vm = vms.own(OfflineMapsViewModel(FakeRepo(seed, throwOnDownload = true)))
        advanceUntilIdle()

        vm.download("de-bayern")
        advanceUntilIdle()

        // Without the runCatching the row would stay busy forever with no way back.
        assertNull(vm.uiState.value.busyRegionId)
        assertNull(vm.uiState.value.downloadedBytes)
        assertEquals(OfflineMapsMessage.DownloadFailed, vm.uiState.value.message)
    }

    @Test
    fun aSecondDownloadIsIgnoredWhileOneIsInFlight() = vmTest(dispatcher) { vms ->
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo(seed, progressTicks = listOf(42_000_000L), gate = gate)
        val vm = vms.own(OfflineMapsViewModel(repo))
        advanceUntilIdle()

        vm.download("de-bayern")
        advanceUntilIdle()
        vm.download("at")
        advanceUntilIdle()

        // The second tap must not move the busy slot, or "de-bayern" would render as idle — with a
        // download button — while its fetch is still running.
        assertEquals(listOf("de-bayern"), repo.downloaded)
        assertEquals("de-bayern", vm.uiState.value.busyRegionId)
        assertEquals(42_000_000L, vm.uiState.value.downloadedBytes)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun deleteShowsNoDownloadProgress() = vmTest(dispatcher) { vms ->
        val repo = FakeRepo(seed)
        val vm = vms.own(OfflineMapsViewModel(repo))
        advanceUntilIdle()

        vm.delete("at")
        advanceUntilIdle()

        // A delete must not subscribe to a download stream, nor render a determinate bar.
        assertEquals(0, repo.progressSubscriptions)
        assertNull(vm.uiState.value.downloadedBytes)
    }

    @Test
    fun anUnreadableCatalogStillListsResidentRegions() = vmTest(dispatcher) { vms ->
        val resident = listOf(OfflineMapRegion("at", "Österreich", 95_000_000L, installed = true))
        val vm = vms.own(OfflineMapsViewModel(FakeRepo(resident, catalogUnavailable = true)))
        advanceUntilIdle()

        val s = vm.uiState.value
        assertTrue(s.catalogUnavailable)
        // The regression this guards: the offline case used to report an empty list, hiding the very
        // regions that still work without a network.
        assertEquals(listOf("at"), s.regions.map { it.regionId })
    }

    @Test
    fun retryShowsLoadingAgain() = vmTest(dispatcher) { vms ->
        val vm = vms.own(OfflineMapsViewModel(FakeRepo(emptyList(), catalogUnavailable = true)))
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.loading)

        vm.retry()
        assertTrue(vm.uiState.value.loading)

        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.loading)
    }

    @Test
    fun searchIsHeldInStateAndNarrowsNothingInTheRegionList() = vmTest(dispatcher) { vms ->
        val vm = vms.own(OfflineMapsViewModel(FakeRepo(seed)))
        advanceUntilIdle()

        vm.search("bay")
        assertEquals("bay", vm.uiState.value.query)
        // ⚠ The list itself is deliberately NOT narrowed: filtering happens in the pure `groupRegions`
        // at render time, so `regions` stays the full set the "n of m" count is measured against.
        assertEquals(listOf("at", "de-bayern"), vm.uiState.value.regions.map { it.regionId })

        vm.search("")
        assertEquals("", vm.uiState.value.query)
    }

    @Test
    fun aRefreshKeepsTheQuery() = vmTest(dispatcher) { vms ->
        val repo = FakeRepo(seed)
        val vm = vms.own(OfflineMapsViewModel(repo))
        advanceUntilIdle()

        vm.search("bay")
        vm.delete("at")
        advanceUntilIdle()

        // A mutation re-fetches; losing the filter mid-list would be a small but real papercut.
        assertEquals("bay", vm.uiState.value.query)
    }

    @Test
    fun aMeteredRefusalAsksInsteadOfReportingAFailure() = vmTest(dispatcher) { vms ->
        val repo = FakeRepo(seed, meteredUnlessAllowed = true)
        val vm = vms.own(OfflineMapsViewModel(repo))
        advanceUntilIdle()

        vm.download("de-bayern")
        advanceUntilIdle()

        // ⚠ Not a failure message: nothing is broken, and "check your connection" would be a lie to
        // someone whose connection is fine. The region is carried so the prompt can name it and its size.
        assertNull(vm.uiState.value.message)
        assertEquals("de-bayern", vm.uiState.value.meteredConsentRegion?.regionId)
        assertNull(vm.uiState.value.busyRegionId, "the row must not be left spinning behind the prompt")
    }

    @Test
    fun confirmingTheMeteredPromptRetriesWithTheOverride() = vmTest(dispatcher) { vms ->
        val repo = FakeRepo(seed, meteredUnlessAllowed = true)
        val vm = vms.own(OfflineMapsViewModel(repo))
        advanceUntilIdle()

        vm.download("de-bayern")
        advanceUntilIdle()
        vm.confirmMeteredDownload()
        advanceUntilIdle()

        assertEquals(listOf(false, true), repo.allowedMetered, "the retry must carry the override")
        assertTrue(vm.uiState.value.regions.first { it.regionId == "de-bayern" }.installed)
        assertNull(vm.uiState.value.meteredConsentRegion)
    }

    @Test
    fun dismissingTheMeteredPromptDownloadsNothing() = vmTest(dispatcher) { vms ->
        val repo = FakeRepo(seed, meteredUnlessAllowed = true)
        val vm = vms.own(OfflineMapsViewModel(repo))
        advanceUntilIdle()

        vm.download("de-bayern")
        advanceUntilIdle()
        vm.dismissMeteredConsent()
        advanceUntilIdle()

        assertNull(vm.uiState.value.meteredConsentRegion)
        assertEquals(listOf(false), repo.allowedMetered, "declining must not spend the data anyway")
        assertTrue(vm.uiState.value.regions.first { it.regionId == "de-bayern" }.installed.not())
    }

    @Test
    fun anOrdinaryDownloadNeverAsksForMeteredConsent() = vmTest(dispatcher) { vms ->
        val repo = FakeRepo(seed)
        val vm = vms.own(OfflineMapsViewModel(repo))
        advanceUntilIdle()

        vm.download("de-bayern")
        advanceUntilIdle()

        assertNull(vm.uiState.value.meteredConsentRegion, "an unmetered download must not prompt")
        assertEquals(listOf(false), repo.allowedMetered, "and must not silently pass the override")
    }

    @Test
    fun formatBytes_showsMbAndGb() {
        assertEquals("90 MB", formatBytes(95_000_000L))
        assertEquals("1.9 GB", formatBytes(2_040_109_465L))
    }

    @Test
    fun downloadPercent_clampsAndSurvivesAnUnknownTotal() {
        assertEquals(0, downloadPercent(0L, 420_000_000L))
        assertEquals(50, downloadPercent(210_000_000L, 420_000_000L))
        assertEquals(100, downloadPercent(420_000_000L, 420_000_000L))
        // Over-reporting (the staged file can exceed a stale advertised size) must not exceed the ring.
        assertEquals(100, downloadPercent(500_000_000L, 420_000_000L))
        // A region listed from the install index alone may carry no usable total — never divide by it.
        assertEquals(0, downloadPercent(10L, 0L))
    }
}
