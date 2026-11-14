package app.tenshu.control.ui.screens.models

import androidx.lifecycle.viewModelScope
import app.tenshu.control.inference.ForegroundIndexStanding
import app.tenshu.control.inference.ForegroundIndexStanding.Stopped
import app.tenshu.control.inference.ForegroundIndexStanding.Waiting
import app.tenshu.control.inference.HeldModel
import app.tenshu.control.inference.IndexCoverage
import app.tenshu.control.inference.IndexCoverageSource
import app.tenshu.control.inference.IndexPassPosition
import app.tenshu.control.inference.IndexProgressSource
import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OnDeviceIndexSource
import app.tenshu.control.inference.OnDeviceModelSource
import app.tenshu.control.inference.StoredIndex
import app.tenshu.control.ui.screens.models.PhotosModelsScreen.IndexAction.Kind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.tenshu.inference.InferenceCapability
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Photos' indexing section (`ios-on-device-inference.md` §14.19.6) as values, a case per state in
 * the order the section says them, and the on-device repository that feeds it.
 */
class PhotosIndexingSectionTest {

    private val copy = PhotosModelsCopy
    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
    private val model = HeldModel("siglip", "SigLIP", 2, onDeviceBytes = 2_600_000_000, licence = "Apache-2.0")
    private fun listed(standing: ModelSetup.Standing = ModelSetup.Standing.Ready) = ModelsAvailability.Listed(
        ModelsAvailability.Catalog.READ, ModelsAvailability.Downloads.KNOWN,
        listOf(ModelRow("siglip", "SigLIP", ModelSetup.Held(model, standing, ModelSetup.Update.None))),
    )

    private val reach = IndexCoverage(total = 1_416, notOnDevice = 106)

    private fun state(
        availability: ModelsAvailability = listed(),
        on: Boolean = true,
        realm: Boolean = on,
        read: Boolean = true,
        change: (ModelsUiState) -> ModelsUiState = { it },
    ) = change(
        ModelsUiState(
            capabilities = OnDeviceModelsCapabilities,
            availability = availability,
            consents = listOf(CapabilityConsent(search, realm = realm, device = on)),
            consentsRead = read,
        ),
    )

    private fun section(state: ModelsUiState): PhotosModelsScreen.Indexing = assertNotNull(photosModelsScreen(state, "iPhone", null).indexing)
    private val PhotosModelsScreen.Indexing.kinds get() = actions.map { it.kind }

    @Test
    fun noSectionWhereThereIsNoPassOrNoListOrNoModel() {
        assertNull(photosModelsScreen(state(change = { it.copy(capabilities = OnDeviceModelsCapabilities.copy(indexing = false)) }), "iPhone", null).indexing)
        assertNull(photosModelsScreen(state(ModelsAvailability.Loading), "iPhone", null).indexing)
        assertNull(photosModelsScreen(state(ModelsAvailability.NotConnected), "iPhone", null).indexing)
        val empty = ModelsAvailability.Listed(ModelsAvailability.Catalog.READ, ModelsAvailability.Downloads.KNOWN, emptyList())
        assertNull(photosModelsScreen(state(empty), "iPhone", null).indexing)
    }

    /** States 1 and 2: nothing to press, and why. */
    @Test
    fun noModelReadyAndTheSwitchOffOfferNothingAndSayWhich() {
        val notReady = section(state(listed(ModelSetup.Standing.Checking(ModelSetup.Standing.Checking.How.Running))))
        assertEquals(copy.INDEX_WHEN_READY, notReady.status)
        assertTrue(notReady.actions.isEmpty())

        val notRead = section(state(read = false))
        assertEquals(copy.CONSENT_NOT_READ, notRead.status)
        assertTrue(notRead.actions.isEmpty(), "a switch not read yet is not off, and not on")

        val everywhere = section(state(on = false))
        assertEquals(copy.INDEX_NEEDS_THE_SWITCH, everywhere.status)
        assertEquals(copy.INDEX_SWITCH_OFF_EVERYWHERE, everywhere.detail)
        assertTrue(everywhere.actions.isEmpty())
        val here = section(state(on = false, realm = true))
        assertEquals(copy.indexSwitchOffHere("iPhone"), here.detail)
        assertTrue(here.actions.isEmpty())
    }

    /** States 3 and 4: paused comes before a pass on the bar, which stops at its next photo. */
    @Test
    fun pausedOffersResumeAndARunningPassShowsItsBarAndPause() {
        val running = { s: ModelsUiState -> s.copy(indexProgress = IndexProgressState(indexing = true, done = 412, total = 1_310), foregroundIndex = ForegroundIndexStanding.Asked) }
        val paused = section(state { running(it).copy(indexingPaused = true) })
        assertEquals(copy.indexPaused("iPhone"), paused.status)
        assertEquals(listOf(Kind.RESUME), paused.kinds)

        val bar = section(state(change = running))
        assertEquals("Indexing 412 of 1,310", bar.status)
        assertEquals(copy.INDEX_KEEP_OPEN, bar.detail)
        assertEquals(PhotosModelsScreen.Progress.Bar(412.0 / 1_310), bar.progress)
        assertEquals(listOf(Kind.PAUSE), bar.kinds)
        assertNull(bar.coverage, "the bar says how far it got")

        // A walk that has not counted the library yet: busy, never *0 of 0*.
        val walking = section(state { it.copy(indexProgress = IndexProgressState(indexing = true)) })
        assertEquals(copy.INDEX_LOOKING, walking.status)
        assertEquals(PhotosModelsScreen.Progress.Busy, walking.progress)
    }

    /**
     * ⚠⚠ §15.7 Q2: *Keep Photos open* only while a leave would stop the pass. A launched continued
     * task (iOS 26+, accepted) carries it; no task (below iOS 26), a request not yet launched, or a
     * refused one, does not — the runtime's word, never the OS version.
     */
    @Test
    fun theRunningLineFollowsTheContinuedTask() {
        val running = { s: ModelsUiState -> s.copy(indexProgress = IndexProgressState(indexing = true, done = 3, total = 9), foregroundIndex = ForegroundIndexStanding.Asked) }
        val stops = section(state(change = running))
        assertEquals("Keep Photos open. Indexing stops when you leave.", stops.detail)
        val carried = section(state { running(it).copy(indexContinued = true) })
        assertEquals("You can leave Photos. Indexing goes on, and iOS shows how far it has got.", carried.detail)
        assertEquals(stops.status, carried.status)
        assertEquals(stops.kinds, carried.kinds, "the task changes the line, not what can be pressed")
        // The walk before the count, too.
        assertEquals(copy.INDEX_GOES_ON, section(state { it.copy(indexProgress = IndexProgressState(indexing = true), indexContinued = true) }).detail)

        // The press's message follows the same rule, as the state stands when it is drawn.
        fun started(continued: Boolean) = photosModelsScreen(state { it.copy(message = ModelsMessage.IndexingStarted, indexContinued = continued) }, "iPhone", null).message
        assertEquals("Indexing started. Keep Photos open. Indexing stops when you leave.", started(false))
        assertEquals("Indexing started. You can leave Photos. Indexing goes on, and iOS shows how far it has got.", started(true))
    }

    /** State 5: the phone's three waits, each with when it goes on, and no *Index now*. */
    @Test
    fun eachWaitSaysWhyAndThatItGoesOnByItself() {
        val expected = mapOf(
            Waiting.Why.WARM to (copy.indexWarm("iPhone") to copy.INDEX_GOES_ON_COOLED),
            Waiting.Why.LOW_POWER_MODE to (copy.INDEX_LOW_POWER to copy.INDEX_GOES_ON_LOW_POWER),
            Waiting.Why.BATTERY_LOW to (copy.INDEX_BATTERY_LOW to copy.INDEX_GOES_ON_BATTERY),
        )
        assertEquals(Waiting.Why.entries.toSet(), expected.keys, "a wait with no sentence")
        for ((why, said) in expected) {
            val waiting = section(state { it.copy(foregroundIndex = Waiting(why)) })
            assertEquals(said.first, waiting.status, "$why")
            assertEquals(said.second, waiting.detail, "$why")
            assertEquals(listOf(Kind.PAUSE), waiting.kinds, "$why: a pass that goes on by itself is not pressed again")
        }
        assertEquals("Waiting: this iPhone is warm.", copy.indexWarm("iPhone"))
        // ⚠ The phone's waits are not the user's pause (maintainer, 2026-10-15).
        assertEquals(listOf("Waiting: Low Power Mode is on.", "Waiting: the battery is low."), listOf(copy.INDEX_LOW_POWER, copy.INDEX_BATTERY_LOW))
    }

    /** States 6 and 7: stopped by Photos or by memory, and not asked: *Index now*, and no *Pause*. */
    @Test
    fun aStoppedOrUnaskedPassOffersIndexNow() {
        val left = section(state { it.copy(foregroundIndex = Stopped(Stopped.Why.LEFT_THE_APP)) })
        assertEquals(copy.INDEX_STOPPED_LEFT, left.status)
        assertEquals(listOf(Kind.INDEX_NOW), left.kinds)
        val memory = section(state { it.copy(foregroundIndex = Stopped(Stopped.Why.LOW_MEMORY)) })
        assertEquals("Stopped: iPhone ran low on memory.", memory.status)
        assertEquals(listOf(Kind.INDEX_NOW), memory.kinds)

        // ⚠ Idle has no "not indexing" line: its first line is the coverage line, else the prompt.
        val idle = section(state { it.copy(foregroundIndex = ForegroundIndexStanding.NotAsked) })
        assertEquals(copy.INDEX_PROMPT, idle.status)
        assertNull(idle.coverage, "the first line said twice")
        assertEquals(listOf(Kind.INDEX_NOW), idle.kinds)
        // ⚠ Not read yet is the same section: a repository says `null` until it has.
        assertEquals(idle, section(state()))
        val known = section(state { it.copy(storedIndex = StoredIndex(1_204), coverage = reach) })
        assertEquals("1,204 of 1,310 photos on this iPhone can be searched. 106 aren't on this iPhone.", known.status)
        assertNull(known.coverage)
        val afterUpdate = section(state { it.copy(storedIndex = StoredIndex(0, clearedByOsUpdate = true)) })
        assertEquals("The index was cleared after an iOS update. Index now to search your photos again.", afterUpdate.status)
        assertEquals(listOf(Kind.INDEX_NOW), afterUpdate.kinds)
        for (s in listOf(idle, known, afterUpdate)) assertTrue(s.status.isNotBlank(), "an idle section with a blank first line")

        // A press being answered: nothing runs yet, so neither *Index now* nor *Pause*.
        val pressing = section(state { it.copy(startingIndex = true) })
        assertEquals(copy.STARTING, pressing.status)
        assertEquals(emptyList(), pressing.kinds)
        // ⚠ And over a stopped pass: its *Index now* is the one being answered.
        assertEquals(emptyList(), section(state { it.copy(foregroundIndex = Stopped(Stopped.Why.LEFT_THE_APP), startingIndex = true) }).kinds)
    }

    /**
     * ⚠⚠ The maintainer's rule (2026-10-15): *Pause* only while a pass runs (or an ask is
     * starting) or waits to go on by itself; never idle, stopped, not ready or switched off.
     * *Resume* whenever the user's pause is on.
     */
    @Test
    fun pauseIsOfferedOnlyWhileAPassRunsOrWaitsToGoOnByItself() {
        val running = { s: ModelsUiState -> s.copy(indexProgress = IndexProgressState(indexing = true, done = 1, total = 2)) }
        val with = mapOf(
            "running" to section(state(change = running)),
            "starting" to section(state { it.copy(foregroundIndex = ForegroundIndexStanding.Asked) }),
            "warm" to section(state { it.copy(foregroundIndex = Waiting(Waiting.Why.WARM)) }),
            "low power" to section(state { it.copy(foregroundIndex = Waiting(Waiting.Why.LOW_POWER_MODE)) }),
            "battery" to section(state { it.copy(foregroundIndex = Waiting(Waiting.Why.BATTERY_LOW)) }),
        )
        with.forEach { (what, s) -> assertTrue(Kind.PAUSE in s.kinds, "$what: no Pause") }
        val without = mapOf(
            "idle" to section(state()),
            "after an update" to section(state { it.copy(storedIndex = StoredIndex(0, clearedByOsUpdate = true)) }),
            "left Photos" to section(state { it.copy(foregroundIndex = Stopped(Stopped.Why.LEFT_THE_APP)) }),
            "low memory" to section(state { it.copy(foregroundIndex = Stopped(Stopped.Why.LOW_MEMORY)) }),
            "no model ready" to section(state(listed(ModelSetup.Standing.Checking(ModelSetup.Standing.Checking.How.Running)))),
            "switch off" to section(state(on = false)),
            "pressing" to section(state { it.copy(startingIndex = true) }),
        )
        without.forEach { (what, s) -> assertFalse(Kind.PAUSE in s.kinds, "$what: Pause where nothing runs") }
        assertEquals(listOf(Kind.RESUME), section(state { it.copy(indexingPaused = true) }).kinds)
        assertEquals(listOf(Kind.RESUME), section(state { running(it).copy(indexingPaused = true) }).kinds)
    }

    /** The coverage line: *can be searched*, never *indexed*, and the update's word over a bare zero. */
    @Test
    fun theCoverageLineSaysWhatCanBeSearchedAndWhyItIsEmptyAfterAnUpdate() {
        // Read under a stopped pass, where the line is the detail (idle, it is the first line itself).
        fun line(stored: StoredIndex?, coverage: IndexCoverage?) = section(state { it.copy(storedIndex = stored, coverage = coverage, foregroundIndex = Stopped(Stopped.Why.LEFT_THE_APP)) }).coverage
        assertEquals("1,204 of 1,310 photos on this iPhone can be searched. 106 aren't on this iPhone.", line(StoredIndex(1_204), reach))
        assertEquals("640 photos can be searched.", line(StoredIndex(640), null))
        assertEquals("1 photo can be searched.", line(StoredIndex(1), null))
        assertEquals("No photos can be searched yet.", line(StoredIndex(0), null))
        assertEquals("1,310 photos on this iPhone can be indexed. 106 aren't on this iPhone.", line(StoredIndex(null), reach))
        assertNull(line(null, null))
        for (said in listOfNotNull(line(StoredIndex(1_204), reach), line(StoredIndex(640), null))) {
            assertFalse("indexed" in said, "the store's count is not what was indexed: $said")
        }

        // ⚠⚠ After an iOS update: the reason, never *0 of N*; and once a pass has finished, the count.
        assertEquals(
            "The index was cleared after an iOS update. Index now to search your photos again.",
            line(StoredIndex(0, clearedByOsUpdate = true), null),
        )
        assertEquals(
            "The index was cleared after an iOS update. 120 photos can be searched again so far.",
            line(StoredIndex(120, clearedByOsUpdate = true), null),
        )
        assertEquals("1,310 of 1,310 photos on this iPhone can be searched.", line(StoredIndex(1_310, clearedByOsUpdate = true), IndexCoverage(1_310, 0)))
    }

    // ─── The repository behind it ────────────────────────────────────────────────────────────

    private class Index : OnDeviceIndexSource {
        val bar = MutableStateFlow<IndexPassPosition?>(null)
        val reached = MutableStateFlow<IndexCoverage?>(null)
        val asked = MutableStateFlow<ForegroundIndexStanding>(ForegroundIndexStanding.NotAsked)
        val held = MutableStateFlow(StoredIndex(null))
        val carried = MutableStateFlow(false)
        var answer: () -> IndexStartOutcome = { IndexStartOutcome.Started }
        override val progress = object : IndexProgressSource { override val progress: Flow<IndexPassPosition?> = bar }
        override val coverage = object : IndexCoverageSource { override val coverage: Flow<IndexCoverage?> = reached }
        override val standing: Flow<ForegroundIndexStanding> = asked
        override val stored: Flow<StoredIndex> = held
        override val continued: Flow<Boolean> = carried
        override suspend fun indexNow(): IndexStartOutcome = answer()
    }

    private class Pause : IndexingPause {
        val row = MutableStateFlow(false)
        val writes = mutableListOf<Boolean>()
        override fun paused(): Flow<Boolean> = row
        override suspend fun set(paused: Boolean) {
            writes += paused
            row.value = paused
        }
    }

    private object Source : OnDeviceModelSource {
        override val models: Flow<ModelsAvailability> = MutableStateFlow(
            ModelsAvailability.Listed(ModelsAvailability.Catalog.READ, ModelsAvailability.Downloads.KNOWN, emptyList()),
        )
        override val serves: Flow<Map<String, List<String>>> = MutableStateFlow(emptyMap())
        override suspend fun download(modelId: String, allowsCellular: Boolean) = ModelActionResult.Accepted
        override suspend fun cancel(modelId: String) = ModelActionResult.Accepted
        override suspend fun checkAgain() = Unit
    }

    private object Consents : ModelsConsents {
        override fun consents(): Flow<List<CapabilityConsent>> = MutableStateFlow(listOf(CapabilityConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)))
        override suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean) = true
        override suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean) = true
    }

    private fun repository(scope: CoroutineScope, index: Index?, pause: Pause? = null) =
        OnDeviceModelsRepository(Source, null, OnDeviceModelsCapabilities, { ModelDeleteOutcome.Removed }, Consents, scope, indexing = index, pause = pause)

    @Test
    fun aRepositoryWithNoPassSaysNoIndexingSectionWhateverItWasGiven() = runTest {
        val scope = CoroutineScope(Job())
        assertFalse(repository(scope, null).capabilities.indexing, "a section that can only refuse is drawn")
        assertTrue(repository(scope, Index()).capabilities.indexing)
        assertEquals(IndexStartOutcome.Failed, repository(scope, null).startIndexing())
        assertNull(repository(scope, null).foregroundIndex().first())
        assertFalse(repository(scope, null).indexContinued().first(), "no pass, no task")
        // ⚠ A driver that throws is a failure nobody named, answered, never an escape.
        val throwing = Index().apply { answer = { error("the driver threw") } }
        assertEquals(IndexStartOutcome.Failed, repository(scope, throwing).startIndexing())
        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theViewModelReadsThePassThroughTheRepositoryAndEveryPressReachesIt() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val scope = CoroutineScope(Job())
        val index = Index()
        val pause = Pause()
        val vm = ModelsViewModel(repository(scope, index, pause))
        try {
            index.bar.value = IndexPassPosition(3, 10)
            index.asked.value = Waiting(Waiting.Why.LOW_POWER_MODE)
            index.held.value = StoredIndex(7, clearedByOsUpdate = true)
            index.reached.value = reach
            advanceUntilIdle()
            val now = vm.uiState.value
            assertEquals(IndexProgressState(indexing = true, done = 3, total = 10), now.indexProgress)
            assertEquals(Waiting(Waiting.Why.LOW_POWER_MODE), now.foregroundIndex)
            assertEquals(StoredIndex(7, clearedByOsUpdate = true), now.storedIndex)
            assertEquals(reach, now.coverage)
            assertFalse(now.indexContinued)
            index.carried.value = true
            advanceUntilIdle()
            assertTrue(vm.uiState.value.indexContinued, "the continued task did not reach the screen's state")
            index.carried.value = false
            advanceUntilIdle()
            assertFalse(vm.uiState.value.indexContinued)

            photosIndexingPress(vm, PhotosModelsScreen.IndexAction(Kind.PAUSE, "x"))
            advanceUntilIdle()
            assertEquals(listOf(true), pause.writes)
            assertTrue(vm.uiState.value.indexingPaused)
            photosIndexingPress(vm, PhotosModelsScreen.IndexAction(Kind.RESUME, "x"))
            advanceUntilIdle()
            assertEquals(listOf(true, false), pause.writes)

            index.answer = { IndexStartOutcome.Waiting }
            photosIndexingPress(vm, PhotosModelsScreen.IndexAction(Kind.INDEX_NOW, "x"))
            advanceUntilIdle()
            assertNull(vm.uiState.value.message, "a press the phone holds back is the section's to say")
            index.answer = { error("the driver threw") }
            photosIndexingPress(vm, PhotosModelsScreen.IndexAction(Kind.INDEX_NOW, "x"))
            advanceUntilIdle()
            assertEquals(ModelsMessage.IndexFailed, vm.uiState.value.message)
            assertFalse(vm.uiState.value.startingIndex)
        } finally {
            vm.viewModelScope.cancel()
            scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
