package app.tenshu.control.ui.screens.models

import androidx.lifecycle.viewModelScope
import app.tenshu.control.inference.HeldModel
import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelSetup.Standing
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OfferedModel
import app.tenshu.control.inference.OnDeviceModelSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.inference.InferenceCapability
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Photos-on-iOS's repository over its source (`ios-on-device-inference.md` §14.18.14), and the
 * shared view model over that repository.
 *
 * ⚠⚠ The source here moves only when a case moves it: a row is fetching, held or gone because
 * the case set it so, never because `download` or `delete` was called. And it can misbehave as
 * the real one can: end, throw, be retired under a collector, answer late or never.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceModelsRepositoryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
    private val offered = OfferedModel("siglip", "SigLIP", 2, downloadBytes = 709_000_000, onDeviceBytes = 2_600_000_000, licence = "Apache-2.0")

    private fun listed(setup: ModelSetup?) = ModelsAvailability.Listed(
        ModelsAvailability.Catalog.READ, ModelsAvailability.Downloads.KNOWN,
        listOfNotNull(setup?.let { ModelRow("siglip", "SigLIP", it) }),
    )

    private val absent = ModelSetup.Absent(offered, last = null)
    private fun fetching(written: Long? = null) = ModelSetup.Fetching(offered, ModelSetup.Download(false, written, 709_000_000, held = null))
    private fun held(standing: Standing, version: Int = 2, update: ModelSetup.Update = ModelSetup.Update.None) =
        ModelSetup.Held(HeldModel("siglip", "SigLIP", version, onDeviceBytes = 2_600_000_000, licence = "Apache-2.0"), standing, update)

    private class FakeSource(first: ModelsAvailability = ModelsAvailability.Loading) : OnDeviceModelSource {
        val list = MutableStateFlow(first)
        val catalog = MutableStateFlow<Map<String, List<String>>>(emptyMap())
        var subscribes = 0

        /** When set, a collection fails with it after what is there now. */
        var throws: Exception? = null

        /** When set, a collection just ends after what is there now, saying nothing. */
        var ends = false

        override val models: Flow<ModelsAvailability> = flow {
            subscribes++
            throws?.let { emit(list.value); throw it }
            if (ends) return@flow emit(list.value)
            emitAll(list.transformWhile { emit(it); it != ModelsAvailability.NotConnected })
        }
        /** When set, the catalog's side fails with it. */
        var servesThrows: Exception? = null
        override val serves: Flow<Map<String, List<String>>> = flow {
            servesThrows?.let { throw it }
            emitAll(catalog)
        }

        val downloads = mutableListOf<Pair<String, Boolean>>()
        var downloadAnswers: suspend () -> ModelActionResult = { ModelActionResult.Accepted }
        override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult {
            downloads += modelId to allowsCellular
            return downloadAnswers()
        }

        val cancels = mutableListOf<String>()
        var cancelAnswers: suspend () -> ModelActionResult = { ModelActionResult.Accepted }
        override suspend fun cancel(modelId: String): ModelActionResult {
            cancels += modelId
            return cancelAnswers()
        }

        val withdrawn = mutableListOf<InferenceCapability>()
        override suspend fun consentWithdrawn(capability: InferenceCapability) {
            withdrawn += capability
        }

        var checks = 0
        var checksEnded = 0
        var check: suspend () -> Unit = {}
        override suspend fun checkAgain() {
            checks++
            check()
            checksEnded++
        }
    }

    private class Fixture(val scope: TestScope, val source: FakeSource, repositoryScope: CoroutineScope = scope.backgroundScope) {
        val removals = mutableListOf<String>()
        var remove: suspend (String) -> ModelDeleteOutcome = { ModelDeleteOutcome.Removed }
        class Consents : ModelsConsents {
            val rows = MutableStateFlow(listOf(CapabilityConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)))
            override fun consents(): Flow<List<CapabilityConsent>> = rows
            var written = true
            override suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean) = written
            override suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean) = written
        }
        val consents = Consents()
        val repository = OnDeviceModelsRepository(
            source = source,
            notOffered = null,
            capabilities = OnDeviceModelsCapabilities,
            remove = { modelId -> removals += modelId; remove(modelId) },
            consents = consents,
            scope = repositoryScope,
        )
        val seen = mutableListOf<ModelsState>()
        val collecting = scope.backgroundScope.launch { repository.models().collect { seen += it } }
        val now: ModelsState get() { scope.runCurrent(); return seen.last() }
    }

    private fun TestScope.fixture(first: ModelsAvailability = ModelsAvailability.Loading) = Fixture(this, FakeSource(first))

    private val ModelsViewModel.row get() = uiState.value.models.single()

    // ─── A switch turned off lets go of what was loaded for it (§14.18.15)

    @Test
    fun aConsentSwitchedOffLetsGoOfWhatWasLoadedForItOnceTheWriteWentThrough() = kotlinx.coroutines.test.runTest(UnconfinedTestDispatcher()) {
        val fixture = fixture()
        val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH

        assertTrue(fixture.repository.setDeviceConsent(search, false))
        assertTrue(fixture.repository.setRealmConsent(search, false))
        runCurrent()
        assertEquals(listOf(search, search), fixture.source.withdrawn)

        // ⚠ Switched on, or a write that did not go through: nothing is let go.
        fixture.repository.setDeviceConsent(search, true)
        fixture.consents.written = false
        assertFalse(fixture.repository.setRealmConsent(search, false))
        runCurrent()
        assertEquals(listOf(search, search), fixture.source.withdrawn)
    }

    // ─── The list

    @Test
    fun everyValueOfTheListIsTheSourcesAndOnlyWhatServesAndWhatIsBeingRemovedIsAdded() = vmTest {
        val f = fixture()
        assertEquals(ModelsState(ModelsAvailability.Loading), f.now)
        for (said in listOf(
            ModelsAvailability.InstalledUnreadable,
            listed(null),
            ModelsAvailability.Listed(ModelsAvailability.Catalog.UNAVAILABLE, ModelsAvailability.Downloads.UNREADABLE, emptyList()),
            listed(absent), listed(fetching(12)), listed(held(Standing.Unsuitable)),
        )) {
            f.source.list.value = said
            assertEquals(ModelsState(said), f.now, "$said")
        }
        f.source.catalog.value = mapOf("siglip" to listOf(search.id))
        assertEquals(mapOf("siglip" to listOf(search.id)), f.now.serves)
        assertNull(f.now.storeReadOnly)
    }

    @Test
    fun aPhoneThatIsOfferedNoModelIsToldSoByTheRepositoryAndItsFlowStays() = vmTest { vms ->
        val why = ModelsAvailability.NotOffered.Why.NOT_ENOUGH_MEMORY
        val repository = OnDeviceModelsRepository.notOffered(why, backgroundScope)
        val seen = mutableListOf<ModelsState>()
        val collecting = backgroundScope.launch { repository.models().collect { seen += it } }
        runCurrent()
        assertEquals(listOf(ModelsState(ModelsAvailability.NotOffered(why))), seen)
        assertTrue(collecting.isActive, "a flow that completed would be read as not connected")

        // ⚠ No switch that changes nothing, and nothing that can be pressed does anything.
        assertEquals(emptyList(), repository.capabilities.features)
        assertFalse(repository.capabilities.otherDevices)
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.NotConnected), repository.download("siglip", false))
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.NotConnected), repository.cancel("siglip"))
        assertEquals(ModelDeleteOutcome.NotConnected, repository.delete("siglip"))
        assertFalse(repository.setRealmConsent(search, true))
        assertFalse(repository.setDeviceConsent(search, true))
        repository.checkAgain()

        val vm = vms.own(ModelsViewModel(repository))
        runCurrent()
        assertEquals(ModelsAvailability.NotOffered(why), vm.uiState.value.availability)
        assertEquals(emptyList(), vm.uiState.value.consents)
    }

    @Test
    fun noRuntimeIsNotConnectedAndNeverAnEmptyList() = vmTest {
        val repository = OnDeviceModelsRepository.notConnected(backgroundScope)
        assertEquals(listOf(ModelsState(ModelsAvailability.NotConnected)), repository.models().toList())
        assertEquals(ModelDeleteOutcome.NotConnected, repository.delete("siglip"))
    }

    /** A teardown under an open screen, and the three ways a source can stop. */
    @Test
    fun aSourceThatIsRetiredOrEndsOrThrowsLeavesNotConnectedAndTheFlowEnds() = vmTest { vms ->
        for (how in listOf("retired", "ends", "throws")) {
            val f = fixture(listed(held(Standing.Ready)))
            val vm = vms.own(ModelsViewModel(f.repository))
            runCurrent()
            assertEquals(true, vm.uiState.value.anyModelUsable, how)
            f.collecting.cancelAndJoin()
            vm.viewModelScope.cancel()
            runCurrent()

            when (how) {
                "retired" -> Unit
                "ends" -> f.source.ends = true
                else -> f.source.throws = IllegalStateException("the store went away")
            }
            val seen = mutableListOf<ModelsState>()
            val collecting = backgroundScope.launch { f.repository.models().collect { seen += it } }
            val open = vms.own(ModelsViewModel(f.repository))
            runCurrent()
            if (how == "retired") {
                assertEquals(true, open.uiState.value.anyModelUsable)
                f.source.list.value = ModelsAvailability.NotConnected
                runCurrent()
            }
            assertEquals(ModelsAvailability.NotConnected, seen.last().availability, how)
            assertFalse(collecting.isActive, "$how: the flow ends once it has said so")
            // ⚠ Never the last list, and never an empty one.
            assertEquals(ModelsAvailability.NotConnected, open.uiState.value.availability, how)
            assertNull(open.uiState.value.anyModelUsable)

            // *Try again* asks the same, retired, repository and is told the same.
            f.source.list.value = ModelsAvailability.NotConnected
            f.source.ends = false
            f.source.throws = null
            open.retry()
            runCurrent()
            assertEquals(ModelsAvailability.NotConnected, open.uiState.value.availability, how)
            assertFalse(open.uiState.value.checkingAgain)
            open.viewModelScope.cancel()
        }
        // The next graph has a repository of its own, and a screen built over it reads that one.
        val next = fixture(listed(absent))
        val vm = vms.own(ModelsViewModel(next.repository))
        runCurrent()
        assertIs<ModelSetup.Absent>(vm.row.setup)
    }

    /** ⚠ Not only the list: an input that throws must not leave the screens at their last value. */
    @Test
    fun anyInputThatThrowsIsNotConnectedSaidAndTheFlowEnds() = vmTest {
        val source = FakeSource(listed(held(Standing.Ready)))
        source.servesThrows = IllegalStateException("the catalog's side failed")
        val f = Fixture(this, source)
        runCurrent()
        assertEquals(ModelsAvailability.NotConnected, f.seen.last().availability)
        assertFalse(f.collecting.isActive)
    }

    @Test
    fun everyScreenReadsOneCollectionOfTheSourceAndAScreenThatOpensLaterReadsItAfresh() = vmTest {
        val f = fixture(listed(absent))
        val second = mutableListOf<ModelsState>()
        val other = backgroundScope.launch { f.repository.models().collect { second += it } }
        runCurrent()
        assertEquals(1, f.source.subscribes)
        assertEquals(f.seen.last(), second.last())

        f.collecting.cancelAndJoin()
        other.cancelAndJoin()
        runCurrent()
        // ⚠ Moved with nobody collecting: what the earlier screens last saw is not replayed.
        f.source.list.value = listed(held(Standing.Ready))
        val later = mutableListOf<ModelsState>()
        backgroundScope.launch { f.repository.models().collect { later += it } }
        runCurrent()
        assertEquals(2, f.source.subscribes)
        assertEquals(listOf(ModelsState(listed(held(Standing.Ready)))), later)
    }

    // ─── The actions

    @Test
    fun aDownloadAndACancelAnswerWhatTheSourceAnsweredAndAThrowIsARefusalNobodyNamed() = vmTest {
        val f = fixture(listed(absent))
        val whys = listOf(
            ModelActionResult.Why.Said(ModelSetup.DownloadSaid.NeedsSpace(3_000_000_000, 1_000_000_000)),
            ModelActionResult.Why.Said(ModelSetup.DownloadSaid.JournalUnreadable),
            ModelActionResult.Why.OtherCellularChoice, ModelActionResult.Why.NoCatalog,
            ModelActionResult.Why.UnknownModel, ModelActionResult.Why.NotConnected, ModelActionResult.Why.Unnamed,
        )
        for (why in whys) {
            f.source.downloadAnswers = { ModelActionResult.Refused(why) }
            f.source.cancelAnswers = { ModelActionResult.Refused(why) }
            assertEquals(ModelActionResult.Refused(why), f.repository.download("siglip", allowsCellular = true))
            assertEquals(ModelActionResult.Refused(why), f.repository.cancel("siglip"))
        }
        assertTrue(f.source.downloads.all { it == "siglip" to true }, "the cellular choice is the caller's")

        f.source.downloadAnswers = { throw IllegalStateException("boom") }
        f.source.cancelAnswers = { throw IllegalStateException("boom") }
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.Unnamed), f.repository.download("siglip", false))
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.Unnamed), f.repository.cancel("siglip"))
        // ⚠ Nothing moved the list: a row is what the source says, whatever was pressed.
        assertEquals(ModelsState(listed(absent)), f.now)
    }

    /** I1: a press sets nothing. The row is *starting* until answered and *fetching* when the source says so. */
    @Test
    fun aPressMovesNoRowAndTheRowFollowsTheSource() = vmTest { vms ->
        val f = fixture(listed(absent))
        val answer = CompletableDeferred<ModelActionResult>()
        f.source.downloadAnswers = { answer.await() }
        val vm = vms.own(ModelsViewModel(f.repository))
        runCurrent()

        vm.download("siglip", allowsCellular = true)
        runCurrent()
        assertEquals(setOf("siglip"), vm.uiState.value.starting)
        assertIs<ModelSetup.Absent>(vm.row.setup)
        vm.download("siglip", allowsCellular = false)
        runCurrent()
        assertEquals(listOf("siglip" to true), f.source.downloads, "a second press before the answer sends nothing")

        answer.complete(ModelActionResult.Accepted)
        runCurrent()
        assertEquals(emptySet(), vm.uiState.value.starting)
        assertIs<ModelSetup.Absent>(vm.row.setup, "accepted is not fetching: the journal says that")
        f.source.list.value = listed(fetching(100))
        runCurrent()
        assertEquals(100L, assertIs<ModelSetup.Fetching>(vm.row.setup).download.writtenBytes)

        // A cancel that is refused is said, and the row stays.
        f.source.cancelAnswers = { ModelActionResult.Refused(ModelActionResult.Why.Said(ModelSetup.DownloadSaid.JournalUnreadable)) }
        vm.cancel("siglip")
        runCurrent()
        assertEquals(ModelsMessage.CancelFailed, vm.uiState.value.message)
        assertIs<ModelSetup.Fetching>(vm.row.setup)
        // One that is accepted moves nothing either, until the source does.
        f.source.cancelAnswers = { ModelActionResult.Accepted }
        vm.cancel("siglip")
        runCurrent()
        assertIs<ModelSetup.Fetching>(vm.row.setup)
        f.source.list.value = listed(absent)
        runCurrent()
        assertIs<ModelSetup.Absent>(vm.row.setup)
    }

    // ─── A removal

    @Test
    fun aRemovalIsMarkedFromItsClaimUntilTheSourceHasSaidTheModelIsGone() = vmTest {
        val f = fixture(listed(held(Standing.Ready)))
        val removal = CompletableDeferred<ModelDeleteOutcome>()
        f.remove = { removal.await() }

        val first = async { f.repository.delete("siglip") }
        val second = async { f.repository.delete("siglip") }
        runCurrent()
        assertEquals(setOf("siglip"), f.now.removing)
        assertEquals(listOf("siglip"), f.removals, "a second press joins the first call")

        // ⚠⚠ The operation has answered and the source has not moved yet: still *removing*.
        removal.complete(ModelDeleteOutcome.Removed)
        runCurrent()
        assertEquals(setOf("siglip"), f.now.removing)
        assertFalse(first.isCompleted)

        f.source.list.value = listed(absent)
        runCurrent()
        assertEquals(ModelDeleteOutcome.Removed, first.await())
        assertEquals(ModelDeleteOutcome.Removed, second.await())
        assertEquals(ModelsState(listed(absent), removalEndings = mapOf("siglip" to ModelDeleteOutcome.Removed)), f.now)
        // ⚠ Never installed and not removing in between.
        val from = f.seen.indexOfFirst { "siglip" in it.removing }
        assertTrue(f.seen.drop(from).none { it.removing.isEmpty() && (it.availability as ModelsAvailability.Listed).models.single().setup is ModelSetup.Held })
        assertEquals(0, f.source.checks)
    }

    @Test
    fun aRemovalThatFailsOrThrowsLetsGoAndTheModelIsStillThere() = vmTest {
        // ⚠ Not one that was cut off: nobody knows that of it (the case below the retired one).
        val endings: List<suspend (String) -> ModelDeleteOutcome> = listOf(
            { ModelDeleteOutcome.Failed },
            { throw IllegalStateException("the journal could not be read") },
        )
        for (ending in endings) {
            val f = fixture(listed(held(Standing.Ready)))
            f.remove = ending
            assertEquals(ModelDeleteOutcome.Failed, f.repository.delete("siglip"))
            // ⚠ Its ending travels with the mark's going: *failed*, said by the call and not inferred.
            assertEquals(ModelsState(listed(held(Standing.Ready)), removalEndings = mapOf("siglip" to ModelDeleteOutcome.Failed)), f.now)
        }
    }

    @Test
    fun theBoundIsOnTheCallersWaitAndTheRowIsRemovingUntilTheRemovalHasEnded() = vmTest {
        val f = fixture(listed(held(Standing.Ready)))
        val removal = CompletableDeferred<ModelDeleteOutcome>()
        f.remove = { removal.await() }
        val slow = async { f.repository.delete("siglip") }
        runCurrent()
        advanceTimeBy(61_000)
        assertEquals(ModelDeleteOutcome.StillRemoving, slow.await())
        assertEquals(setOf("siglip"), f.now.removing)
        // ⚠ A download pressed meanwhile is passed on: which runs first is the downloader's to order.
        assertEquals(ModelActionResult.Accepted, f.repository.download("siglip", false))
        assertEquals(listOf("siglip" to false), f.source.downloads)

        f.source.list.value = listed(absent)
        removal.complete(ModelDeleteOutcome.Removed)
        runCurrent()
        assertEquals(emptySet(), f.now.removing)
        assertEquals(listOf("siglip"), f.removals)
    }

    @Test
    fun aSourceThatDoesNotMoveAfterARemovalIsReadAgainFirstAndNoScreenIsToldTheRemovalFailed() = vmTest { vms ->
        val f = fixture(listed(held(Standing.Ready)))
        val reading = CompletableDeferred<Unit>()
        f.source.check = { reading.await() }
        val watching = vms.own(ModelsViewModel(f.repository))
        runCurrent()
        // The view model's own open asked for a read; let that one through.
        reading.complete(Unit)
        runCurrent()
        val again = CompletableDeferred<Unit>()
        f.source.check = { again.await() }
        val checksBefore = f.source.checks

        val done = async { f.repository.delete("siglip") }
        runCurrent()
        assertEquals(setOf("siglip"), f.now.removing)
        advanceTimeBy(10_001)
        runCurrent()
        // ⚠ The read that was asked for is waited for: the mark is still there while it runs.
        assertEquals(checksBefore + 1, f.source.checks)
        assertEquals(setOf("siglip"), f.now.removing)
        again.complete(Unit)
        runCurrent()
        assertEquals(ModelDeleteOutcome.Removed, done.await())
        assertEquals(emptySet(), f.now.removing)
        // ⚠⚠ The list still holds the model (the source never moved), and the removal happened:
        // a screen that only watched says what the removal answered, never *couldn't remove*.
        assertTrue(watching.uiState.value.models.single().setup is ModelSetup.Held)
        assertEquals(ModelsMessage.RemovedSpacePending, watching.uiState.value.message)
    }

    /** A teardown under a removal: the mark goes, the caller is answered, and every list ends. */
    @Test
    fun aRetiredRepositoryLetsGoOfItsRemovalAnswersItsCallerAndEndsEveryListNotConnected() = vmTest { vms ->
        // ⚠ A scope of its own, as in the graph: retiring cancels it, and the screens' are not it.
        val f = Fixture(this, FakeSource(listed(held(Standing.Ready))), CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        f.remove = { kotlinx.coroutines.awaitCancellation() }
        val waiting = async { f.repository.delete("siglip") }
        runCurrent()
        assertEquals(setOf("siglip"), f.now.removing)

        val watching = vms.own(ModelsViewModel(f.repository))
        runCurrent()
        assertEquals(setOf("siglip"), watching.uiState.value.removing)

        f.repository.retire()
        runCurrent()
        // ⚠⚠ Neither *removed* nor *failed*: the operation never answered, and may still
        // complete in the graph that was torn down. And not left waiting.
        assertEquals(ModelDeleteOutcome.NotConnected, waiting.await())
        // ⚠⚠ No ending is recorded, so a screen that watched says nothing of the removal: never
        // *couldn't remove, it is still installed*. Its mark goes with the list.
        assertNull(watching.uiState.value.message)
        assertEquals(emptySet(), watching.uiState.value.removing)
        assertEquals(ModelsAvailability.NotConnected, watching.uiState.value.availability)
        // ⚠ The source never said so, and its collection died with the scope: said all the same.
        assertEquals(ModelsAvailability.NotConnected, f.seen.last().availability)
        assertFalse(f.collecting.isActive)
        assertEquals(listOf(ModelsAvailability.NotConnected), f.repository.models().toList().map { it.availability })
        // A press after it is answered too, and nothing is left marked.
        assertEquals(ModelDeleteOutcome.NotConnected, f.repository.delete("siglip"))
        f.repository.checkAgain()
    }

    /**
     * The teardown cancels the graph's scope **before** it retires the repository: the operation
     * is cut off under a scope that is not the repository's, while the removal may still land.
     */
    @Test
    fun aRemovalCutOffByAScopeThatIsNotTheRepositorysRecordsNoEndingAndIsAnsweredNotConnected() = vmTest { vms ->
        val f = fixture(listed(held(Standing.Ready)))
        // An earlier removal of the same model that failed: its ending is not the next one's.
        f.remove = { ModelDeleteOutcome.Failed }
        assertEquals(ModelDeleteOutcome.Failed, f.repository.delete("siglip"))
        assertEquals(mapOf("siglip" to ModelDeleteOutcome.Failed), f.now.removalEndings)

        val cutOff = CompletableDeferred<Unit>()
        f.remove = { cutOff.await(); throw CancellationException("the graph's scope was cancelled") }
        val watching = vms.own(ModelsViewModel(f.repository))
        val pressing = vms.own(ModelsViewModel(f.repository))
        runCurrent()
        pressing.confirmDelete("siglip")
        pressing.delete()
        runCurrent()
        assertEquals(setOf("siglip"), watching.uiState.value.removing)

        cutOff.complete(Unit)
        runCurrent()
        assertEquals(emptySet(), f.now.removing)
        assertEquals(emptyMap(), f.now.removalEndings, "nothing is recorded as how a removal ended that nobody saw end")
        // ⚠ The screen that pressed says it could not reach its library; the one that watched
        // says nothing. Neither says the model is still installed.
        assertEquals(ModelsMessage.NotConnected, pressing.uiState.value.message)
        assertNull(watching.uiState.value.message)
        assertEquals(emptySet(), pressing.uiState.value.removing)
    }

    // ─── Checking again

    @Test
    fun checkingAgainIsTheSourcesCheckItsCallersWaitIsBoundedAndTheCheckIsNot() = vmTest {
        val f = fixture(listed(absent))
        f.repository.checkAgain()
        assertEquals(1, f.source.checksEnded)

        val gate = CompletableDeferred<Unit>()
        f.source.check = { gate.await() }
        val waiting = launch { f.repository.checkAgain() }
        runCurrent()
        advanceTimeBy(45_001)
        runCurrent()
        assertTrue(waiting.isCompleted, "the caller is let go")
        assertEquals(1, f.source.checksEnded)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, f.source.checksEnded, "and the check ran to its end")

        // A caller that leaves stops nothing.
        val again = CompletableDeferred<Unit>()
        f.source.check = { again.await() }
        val leaving = launch { f.repository.checkAgain() }
        runCurrent()
        leaving.cancelAndJoin()
        again.complete(Unit)
        runCurrent()
        assertEquals(3, f.source.checksEnded)

        // And a check that throws does not escape.
        f.source.check = { throw IllegalStateException("boom") }
        f.repository.checkAgain()
        assertEquals(4, f.source.checks)
    }

    // ─── The feature question, where an install is prepared and checked before it can be used

    @Test
    fun theFeatureQuestionIsAskedWhenAModelIsSeenToBecomeUsableAndNotWhenItsStandingWasOnlyJustRead() = vmTest { vms ->
        fun open(first: ModelsAvailability): Pair<Fixture, ModelsViewModel> {
            val f = fixture(first)
            f.source.catalog.value = mapOf("siglip" to listOf(search.id))
            val vm = vms.own(ModelsViewModel(f.repository))
            runCurrent()
            return f to vm
        }
        fun Pair<Fixture, ModelsViewModel>.then(vararg standings: Standing, version: Int = 2): InferenceCapability? {
            for (standing in standings) {
                first.source.list.value = listed(held(standing, version))
                first.scope.runCurrent()
            }
            return second.uiState.value.featureOffer.also { second.viewModelScope.cancel() }
        }
        val preparing = Standing.Preparing(Standing.Preparing.How.Now)
        val checking = Standing.Checking(Standing.Checking.How.Running)

        // A relaunch over a ready model: its standing is read, which is not the model becoming ready.
        assertNull(open(listed(held(Standing.NotReadYet))).then(Standing.Ready))
        assertNull(open(listed(held(Standing.Unreadable))).then(Standing.Ready))
        // A download that lands, is prepared, is checked and passes: asked, at the end and not before.
        val landing = open(listed(fetching(1)))
        landing.first.source.list.value = listed(held(Standing.NotReadYet))
        runCurrent()
        assertNull(landing.second.uiState.value.featureOffer)
        landing.first.source.list.value = listed(held(preparing))
        runCurrent()
        assertNull(landing.second.uiState.value.featureOffer)
        assertEquals(search, landing.then(checking, Standing.Ready))
        // A screen opened while it was being checked watched it become usable too.
        assertEquals(search, open(listed(held(checking))).then(Standing.Ready))
        // One that ends unsuitable is never asked about.
        assertNull(open(listed(held(preparing))).then(checking, Standing.Unsuitable))
        // ⚠⚠ Removed and downloaded again on one open screen: a new install, asked about again.
        val again = open(listed(held(Standing.Ready)))
        for (step in listOf(listed(absent), listed(fetching(1)), listed(held(Standing.NotReadYet)), listed(held(preparing)))) {
            again.first.source.list.value = step
            runCurrent()
            assertNull(again.second.uiState.value.featureOffer)
        }
        assertEquals(search, again.then(checking, Standing.Ready))
        // An update of a model that was usable here is not a first install.
        assertNull(open(listed(held(Standing.Ready, version = 1))).then(Standing.NotReadYet, preparing, checking, Standing.Ready, version = 2))
    }

    @Test
    fun whatThisPlatformCanDoIsWhatItsRepositorySays() {
        val can = OnDeviceModelsCapabilities
        assertTrue(can.cancel)
        assertTrue(can.cellularChoice)
        assertFalse(can.oneDownloadAtATime)
        assertEquals(ModelsCapabilities.SpaceReturns.TOWERS_AT_ONCE, can.spaceReturns)
        assertEquals(listOf(search), can.features)
        // ⚠ 5e-2 (§14.19.6): the foreground pass is bound; a repository given none says false.
        assertTrue(can.indexing)
        assertTrue(can.otherDevices)
    }

    /** Over the real repository: a removal from the screen, said once, with the row removing throughout. */
    @Test
    fun aRemovalFromTheScreenIsSaidOnceAndOneThatOutlastsTheWaitIsSaidWhenItEnds() = vmTest { vms ->
        val f = fixture(listed(held(Standing.Ready)))
        val removal = CompletableDeferred<ModelDeleteOutcome>()
        f.remove = { removal.await() }
        val vm = vms.own(ModelsViewModel(f.repository))
        runCurrent()
        vm.confirmDelete("siglip")
        vm.delete()
        runCurrent()
        assertEquals(setOf("siglip"), vm.uiState.value.removing)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(ModelsMessage.StillRemoving, vm.uiState.value.message)
        vm.consumeMessage()

        removal.complete(ModelDeleteOutcome.Removed)
        runCurrent()
        assertEquals(setOf("siglip"), vm.uiState.value.removing, "until the source says it is gone")
        assertNull(vm.uiState.value.message)
        f.source.list.value = listed(absent)
        runCurrent()
        assertEquals(emptySet(), vm.uiState.value.removing)
        assertEquals(ModelsMessage.RemovedSpacePending, vm.uiState.value.message)
        assertIs<ModelSetup.Absent>(vm.row.setup)
    }

    /** A repository whose list and whose removal's answer the case moves, each by itself. */
    private class Scripted(base: ModelsRepository, first: ModelsState) : ModelsRepository by base {
        val state = MutableStateFlow(first)
        var removal = CompletableDeferred<ModelDeleteOutcome>()
        override val capabilities = OnDeviceModelsCapabilities
        override fun models(): Flow<ModelsState> = state
        override suspend fun delete(modelId: String): ModelDeleteOutcome = removal.await()
    }

    /**
     * ⚠⚠ The end of a removal reaches a screen twice, as its call's answer and as the list
     * letting go, in either order. It is said once, whichever comes first, also when the message
     * was read in between.
     */
    @Test
    fun theEndOfARemovalIsSaidOnceWhicheverOfTheAnswerAndTheListComesFirst() = vmTest { vms ->
        val installed = ModelsState(listed(held(Standing.Ready)))
        val removing = installed.copy(removing = setOf("siglip"))
        val gone = ModelsState(listed(absent), removalEndings = mapOf("siglip" to ModelDeleteOutcome.Removed))
        for (answerFirst in listOf(true, false)) {
            val repository = Scripted(OnDeviceModelsRepository.notConnected(backgroundScope), installed)
            val vm = vms.own(ModelsViewModel(repository))
            runCurrent()
            vm.confirmDelete("siglip")
            vm.delete()
            repository.state.value = removing
            runCurrent()
            assertNull(vm.uiState.value.message)

            if (answerFirst) repository.removal.complete(ModelDeleteOutcome.Removed) else repository.state.value = gone
            runCurrent()
            // ⚠ Not said by the list while this screen's own call has not answered.
            assertEquals(if (answerFirst) ModelsMessage.RemovedSpacePending else null, vm.uiState.value.message, "answer first: $answerFirst")
            vm.consumeMessage()
            if (answerFirst) repository.state.value = gone else repository.removal.complete(ModelDeleteOutcome.Removed)
            runCurrent()
            assertEquals(if (answerFirst) null else ModelsMessage.RemovedSpacePending, vm.uiState.value.message, "answer first: $answerFirst")
            assertEquals(emptySet(), vm.uiState.value.removing)
            vm.consumeMessage()

            // The next removal of that model, by another screen, is this one's to hear of again.
            repository.state.value = installed
            runCurrent()
            repository.state.value = removing
            runCurrent()
            repository.state.value = gone
            runCurrent()
            assertEquals(ModelsMessage.RemovedSpacePending, vm.uiState.value.message, "answer first: $answerFirst")
            vm.viewModelScope.cancel()
        }
    }

    /** A removal the list never showed as removing leaves nothing behind that could swallow the next one's ending. */
    @Test
    fun aRemovalTheListNeverShowedLeavesNothingThatSwallowsTheNextOnesEnding() = vmTest { vms ->
        val installed = ModelsState(listed(held(Standing.Ready)))
        val gone = ModelsState(listed(absent), removalEndings = mapOf("siglip" to ModelDeleteOutcome.Removed))
        val repository = Scripted(OnDeviceModelsRepository.notConnected(backgroundScope), installed)
        val vm = vms.own(ModelsViewModel(repository))
        runCurrent()
        vm.confirmDelete("siglip")
        vm.delete()
        repository.removal.complete(ModelDeleteOutcome.Removed)
        repository.state.value = gone
        runCurrent()
        assertEquals(ModelsMessage.RemovedSpacePending, vm.uiState.value.message)
        vm.consumeMessage()

        // Somebody else's removal of the same model, later: this screen watched it end.
        repository.state.value = installed
        runCurrent()
        repository.state.value = installed.copy(removing = setOf("siglip"))
        runCurrent()
        repository.state.value = gone
        runCurrent()
        assertEquals(ModelsMessage.RemovedSpacePending, vm.uiState.value.message)
    }

    /** A list that ends takes the repository's marks with it: nothing stands behind them any more. */
    @Test
    fun aListThatEndsLeavesNoMarkThatSwallowsTheEndingOfARemovalTheNextListShows() = vmTest { vms ->
        val installed = ModelsState(listed(held(Standing.Ready)))
        val removing = installed.copy(removing = setOf("siglip"))
        val gone = ModelsState(listed(absent), removalEndings = mapOf("siglip" to ModelDeleteOutcome.Removed))
        val base = Scripted(OnDeviceModelsRepository.notConnected(backgroundScope), installed)
        // ⚠ It ends WITHOUT a last word: a flow that says *not connected* first lets go of the
        // removal in that emission, and the marks go by themselves.
        val ended = ModelsState(ModelsAvailability.Loading, serves = mapOf("ended" to emptyList()))
        val repository = object : ModelsRepository by base {
            override fun models(): Flow<ModelsState> = base.state.transformWhile { if (it != ended) emit(it); it != ended }
        }
        val vm = vms.own(ModelsViewModel(repository))
        runCurrent()
        vm.confirmDelete("siglip")
        vm.delete()
        base.state.value = removing
        runCurrent()
        // Its own call answers while the list still shows the removal; then the list ends
        // without ever letting go of it.
        base.removal.complete(ModelDeleteOutcome.Removed)
        runCurrent()
        vm.consumeMessage()
        base.state.value = ended
        runCurrent()
        assertEquals(ModelsAvailability.NotConnected, vm.uiState.value.availability)
        assertEquals(emptySet(), vm.uiState.value.removing, "a row is not left removing by a list that is gone")

        // The next list: somebody else's removal of the same model, watched to its end.
        base.state.value = installed
        vm.retry()
        runCurrent()
        base.state.value = removing
        runCurrent()
        base.state.value = gone
        runCurrent()
        assertEquals(ModelsMessage.RemovedSpacePending, vm.uiState.value.message)
    }

    /** A removal the store refused ends and turns the store read-only in one emission: both are said. */
    @Test
    fun aRemovalsEndingIsNotDroppedByAnEmissionThatAlsoTurnsTheStoreReadOnly() {
        val before = ModelsState(listed(held(Standing.Ready)), removing = setOf("siglip"))
        val now = ModelsState(
            listed(held(Standing.Ready)), storeReadOnly = StoreReadOnly.OwnedElsewhere,
            removalEndings = mapOf("siglip" to ModelDeleteOutcome.StoreNotOwned),
        )
        assertEquals(
            ModelsNews(ModelsMessage.StoreReadOnly, removalsEnded = mapOf("siglip" to ModelDeleteOutcome.StoreNotOwned)),
            newsBetween(before, now),
        )
    }
}
