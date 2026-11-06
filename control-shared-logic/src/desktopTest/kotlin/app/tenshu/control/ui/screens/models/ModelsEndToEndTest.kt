package app.tenshu.control.ui.screens.models

import androidx.lifecycle.viewModelScope
import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.download
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.inference.InferenceCapability
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real [ModelsViewModel] over the real [ModelsLedger], with only the proxy's five calls
 * replaced ([FakeModelCalls]).
 *
 * ⚠⚠ `ModelsViewModelTest`'s repository is a double that restates what the ledger does (the
 * latch, what a row says last, when a model lands). These cases are the ones in which the two
 * are actually joined: what a screen shows when it is opened, closed and opened again over the
 * process's one ledger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsEndToEndTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH

    private fun facts(id: String = "siglip", version: Int = 3) =
        ModelFacts(id, name = id.uppercase(), byteSize = 383, modelVersion = version, capabilityIds = listOf(search.id))

    /** [ModelsRepository] as `ClientModelsRepository` composes it, with the consent half inert. */
    private class OverTheLedger(val ledger: ModelsLedger) : ModelsRepository {
        override val capabilities = ControlModelsCapabilities
        override fun models(): Flow<ModelsState> = ledger.state
        override suspend fun download(modelId: String, allowsCellular: Boolean) = ledger.download(modelId)
        override suspend fun cancel(modelId: String) = ModelActionResult.Refused(ModelActionResult.Why.Unnamed)
        override suspend fun checkAgain() = ledger.checkAgain()
        override suspend fun delete(modelId: String) = ledger.delete(modelId)
        override fun indexProgress(): Flow<IndexProgressState> = emptyFlow()
        override fun indexingPaused(): Flow<Boolean> = emptyFlow()
        override suspend fun setIndexingPaused(paused: Boolean) = Unit
        override suspend fun startIndexing() = IndexStartOutcome.Started
        override fun consents(): Flow<List<CapabilityConsent>> = MutableStateFlow(listOf(CapabilityConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)))
        override suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean) = true
        override suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean) = true
        override fun otherDeviceConsents() = flowOf(emptyMap<InferenceCapability, List<OtherDeviceConsent>>())
    }

    private fun TestScope.repository(calls: FakeModelCalls) =
        OverTheLedger(ModelsLedger(calls, { null }, backgroundScope, StandardTestDispatcher(testScheduler)))

    /** Past the watcher's resubscription, and everything that is due now. */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
    }

    private val ModelsViewModel.row get() = uiState.value.models.single { it.modelId == "siglip" }

    @Test
    fun aDownloadFromThePressToTheFeatureQuestionOnTwoScreensAndAcrossAReopen() = vmTest { vms ->
        val calls = FakeModelCalls(available = ModelRead.Got(listOf(facts())))
        val repo = repository(calls)
        val first = vms.own(ModelsViewModel(repo))
        settle()
        assertEquals(ModelSetup.Absent::class, first.row.setup::class)

        first.download("siglip")
        settle()
        // ⚠ The press is not answered yet (nothing has ticked), and the row is already fetching.
        assertEquals(setOf("siglip"), first.uiState.value.starting)
        assertTrue(first.row.fetching)
        first.download("siglip")
        settle()
        assertEquals(listOf("siglip"), calls.downloads)

        calls.begin("siglip")
        settle()
        calls.tick("siglip", 100)
        settle()
        assertEquals(emptySet(), first.uiState.value.starting)
        assertEquals(100L, first.row.setup.download?.writtenBytes)

        // The screen is closed, and another opened over the same ledger.
        first.viewModelScope.cancel()
        val reopened = vms.own(ModelsViewModel(repo))
        val feature = vms.own(ModelsViewModel(repo, forTheModelsScreen = false))
        settle()
        assertEquals(100L, reopened.row.setup.download?.writtenBytes)
        reopened.download("siglip")
        settle()
        assertEquals(listOf("siglip"), calls.downloads)

        calls.installed = ModelRead.Got(listOf(facts()))
        calls.endStream("siglip")
        calls.running.getValue("siglip").complete(ModelDownloadOutcome.Landed)
        settle()

        assertTrue(reopened.row.installed)
        assertNull(reopened.row.setup.download)
        assertEquals(search, reopened.uiState.value.featureOffer, "the screen that watched it land asks")
        assertNull(feature.uiState.value.featureOffer)
        assertNull(reopened.uiState.value.message)
    }

    /**
     * ⚠⚠ Leaving while *Removing…* shows. The removal is the ledger's, so it ends and the list is
     * read again; the screen that opens next shows the model gone, not installed with a Remove.
     */
    @Test
    fun leavingDuringARemovalLeavesAListThatIsRight() = vmTest { vms ->
        val calls = FakeModelCalls(ModelRead.Got(listOf(facts())), ModelRead.Got(listOf(facts())))
        calls.deleteGate = CompletableDeferred()
        val repo = repository(calls)
        val first = vms.own(ModelsViewModel(repo))
        settle()

        first.confirmDelete("siglip")
        first.delete()
        settle()
        assertEquals(setOf("siglip"), first.uiState.value.removing)
        first.viewModelScope.cancel()

        calls.installed = ModelRead.Got(emptyList())
        calls.deleteGate!!.complete(Unit)
        settle()

        val reopened = vms.own(ModelsViewModel(repo))
        settle()
        assertFalse(reopened.row.installed)
        assertEquals(false, reopened.uiState.value.anyModelUsable)
        assertEquals(listOf("siglip"), calls.deletions)
    }

    /**
     * ⚠⚠ What was published since the process started is there at the next open, with no button
     * pressed; and a list that could not be read at the first open is read again at the next.
     */
    @Test
    fun aReopenedScreenShowsWhatWasPublishedSinceAndRecoversFromAFirstReadThatFailed() = vmTest { vms ->
        val calls = FakeModelCalls(installed = ModelRead.NoAnswer, available = ModelRead.Failed)
        val repo = repository(calls)
        val first = vms.own(ModelsViewModel(repo))
        settle()
        assertEquals(ModelsAvailability.NotConnected, first.uiState.value.availability)
        first.viewModelScope.cancel()

        calls.installed = ModelRead.Got(listOf(facts(version = 3)))
        calls.available = ModelRead.Got(listOf(facts(version = 3)))
        val second = vms.own(ModelsViewModel(repo))
        settle()
        assertTrue(second.row.installed)
        assertFalse(second.row.updateAvailable)
        second.viewModelScope.cancel()

        calls.available = ModelRead.Got(listOf(facts(version = 4), facts("new")))
        val third = vms.own(ModelsViewModel(repo))
        settle()
        assertTrue(third.row.updateAvailable)
        assertEquals(listOf("siglip", "new"), third.uiState.value.models.map { it.modelId })
        assertNull(third.uiState.value.message)
    }

    /** A download that ends badly after it was accepted: said once, and by the row afterwards. */
    @Test
    fun aDownloadThatFailsIsSaidOnceAndStaysUnderItsRow() = vmTest { vms ->
        val calls = FakeModelCalls(available = ModelRead.Got(listOf(facts())))
        val repo = repository(calls)
        val vm = vms.own(ModelsViewModel(repo))
        settle()
        vm.download("siglip")
        calls.begin("siglip")
        settle()
        calls.tick("siglip", 1)
        settle()

        calls.running.getValue("siglip").complete(ModelDownloadOutcome.IntegrityFailed)
        settle()

        val said = ModelSetup.DownloadSaid.Failed(integrity = true)
        assertEquals(ModelsMessage.IntegrityFailed, vm.uiState.value.message)
        assertEquals(said, (vm.row.setup as ModelSetup.Absent).last)

        val later = vms.own(ModelsViewModel(repo))
        settle()
        assertNull(later.uiState.value.message)
        assertEquals(said, (later.row.setup as ModelSetup.Absent).last)

        // A store that refuses at the press: one sentence, the standing notice, and no retry.
        later.download("siglip")
        settle()
        calls.running.getValue("siglip").complete(ModelDownloadOutcome.StoreNotOwned)
        settle()
        assertEquals(ModelsMessage.StoreReadOnly, later.uiState.value.message)
        assertEquals(StoreReadOnly.OwnedElsewhere, later.uiState.value.storeReadOnly)
        assertEquals(StoreReadOnly.OwnedElsewhere, vm.uiState.value.storeReadOnly)
    }

    /**
     * ⚠⚠ Remove a model that has an update, leave while the removal runs, and open the screen
     * again. The removal is the ledger's, so the new screen shows the row *removing* and can
     * start nothing on it: an update pressed there would install again what was just removed.
     * When the removal has ended the row is absent, and a press then is a fresh download.
     */
    @Test
    fun aRemovalThatOutlivesItsScreenHoldsItsRowOnTheNextOne() = vmTest { vms ->
        val calls = FakeModelCalls(ModelRead.Got(listOf(facts(version = 2))), ModelRead.Got(listOf(facts(version = 3))))
        calls.deleteGate = CompletableDeferred()
        val repo = repository(calls)
        val first = vms.own(ModelsViewModel(repo))
        val beside = vms.own(ModelsViewModel(repo))
        settle()
        assertTrue(first.row.updateAvailable)

        first.confirmDelete("siglip")
        first.delete()
        settle()
        assertEquals(setOf("siglip"), beside.uiState.value.removing, "the other open screen was not told")
        first.viewModelScope.cancel()

        val reopened = vms.own(ModelsViewModel(repo))
        settle()
        assertEquals(setOf("siglip"), reopened.uiState.value.removing)
        reopened.download("siglip")
        reopened.confirmDelete("siglip")
        settle()
        assertEquals(emptyList(), calls.downloads)
        assertNull(reopened.uiState.value.pendingDeletion)
        // ⚠ And a caller that kept a button is refused by the ledger itself.
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.Busy), repo.download("siglip", false))

        calls.installed = ModelRead.Got(emptyList())
        calls.deleteGate!!.complete(Unit)
        settle()
        assertEquals(emptySet(), reopened.uiState.value.removing)
        assertFalse(reopened.row.installed)
        assertEquals(emptyList(), calls.downloads, "a download started by itself")

        reopened.download("siglip")
        settle()
        assertEquals(listOf("siglip"), calls.downloads)
        calls.running.getValue("siglip").complete(ModelDownloadOutcome.UnknownModel)
        settle()
    }

    /**
     * ⚠⚠ A removal that outlasts its caller's wait. The caller was told *still removing*; when the
     * removal ends nobody is waiting, so its ending is said off the list: *removed* when the
     * model has gone, *couldn't remove* when it is still there. And the row is *removing* for
     * all of it.
     */
    @Test
    fun aRemovalThatOutlastsItsCallersWaitIsStillGivenItsClosingMessage() = vmTest { vms ->
        for ((ends, said) in listOf(
            ModelDeleteOutcome.Removed to ModelsMessage.RemovedSpacePending,
            ModelDeleteOutcome.Failed to ModelsMessage.DeleteFailed,
        )) {
            val calls = FakeModelCalls(ModelRead.Got(listOf(facts())), ModelRead.Got(listOf(facts())))
            calls.deleteGate = CompletableDeferred()
            calls.deleteOutcome = ends
            val vm = vms.own(ModelsViewModel(repository(calls)))
            settle()
            vm.confirmDelete("siglip")
            vm.delete()
            settle()
            advanceTimeBy(61_000)
            runCurrent()
            assertEquals(ModelsMessage.StillRemoving, vm.uiState.value.message, "$ends")
            assertEquals(setOf("siglip"), vm.uiState.value.removing, "the claim is not bounded")
            vm.consumeMessage()

            // ⚠ A second press on the row does nothing while it is being removed: one call.
            vm.confirmDelete("siglip")
            assertNull(vm.uiState.value.pendingDeletion)

            if (ends == ModelDeleteOutcome.Removed) calls.installed = ModelRead.Got(emptyList())
            calls.deleteGate!!.complete(Unit)
            settle()
            assertEquals(emptySet(), vm.uiState.value.removing, "$ends")
            assertEquals(said, vm.uiState.value.message, "$ends")
            assertEquals(ends != ModelDeleteOutcome.Removed, vm.row.installed)
            assertEquals(listOf("siglip"), calls.deletions)
            vm.viewModelScope.cancel()
        }
    }

    /**
     * ⚠⚠ Told or said off the list, never both: a removal that ends inside its caller's wait is
     * said once, by the call's answer, on the screen that pressed. A second screen over the same
     * ledger pressed nothing and is told by the list. A second press from it joins the one call.
     */
    @Test
    fun aRemovalInsideTheWaitIsSaidOnceAndASecondPressJoinsTheOneCall() = vmTest { vms ->
        val calls = FakeModelCalls(ModelRead.Got(listOf(facts())), ModelRead.Got(listOf(facts())))
        calls.deleteGate = CompletableDeferred()
        val repo = repository(calls)
        val pressed = vms.own(ModelsViewModel(repo))
        val watching = vms.own(ModelsViewModel(repo))
        settle()
        // Both ask before either removal is reported: the second joins the first call.
        pressed.confirmDelete("siglip")
        watching.confirmDelete("siglip")
        pressed.delete()
        watching.delete()
        settle()
        assertEquals(listOf("siglip"), calls.deletions)
        assertEquals(setOf("siglip"), watching.uiState.value.removing)

        calls.installed = ModelRead.Got(emptyList())
        calls.deleteGate!!.complete(Unit)
        settle()
        for (vm in listOf(pressed, watching)) {
            assertEquals(ModelsMessage.RemovedSpacePending, vm.uiState.value.message)
            vm.consumeMessage()
            assertFalse(vm.row.installed)
        }
        // ⚠ Nothing is said a second time once the message was read: neither order of the
        // answer and the list's emission leaves one behind.
        settle()
        assertNull(pressed.uiState.value.message)
        assertNull(watching.uiState.value.message)

        // And a screen that pressed nothing, opened while it ran, is told by the list alone.
        calls.installed = ModelRead.Got(listOf(facts()))
        pressed.retry()
        settle()
        pressed.consumeMessage()
        calls.deleteGate = CompletableDeferred()
        calls.deleteOutcome = ModelDeleteOutcome.Failed
        pressed.confirmDelete("siglip")
        pressed.delete()
        settle()
        val late = vms.own(ModelsViewModel(repo))
        settle()
        assertEquals(setOf("siglip"), late.uiState.value.removing)
        calls.deleteGate!!.complete(Unit)
        settle()
        assertEquals(ModelsMessage.DeleteFailed, pressed.uiState.value.message)
        assertEquals(ModelsMessage.DeleteFailed, late.uiState.value.message)
        assertTrue(late.row.installed)
    }
}
