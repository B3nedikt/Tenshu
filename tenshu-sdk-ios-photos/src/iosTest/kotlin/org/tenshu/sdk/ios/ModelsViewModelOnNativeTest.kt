package org.tenshu.sdk.ios

import androidx.lifecycle.viewModelScope
import app.tenshu.control.inference.HeldModel
import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OfferedModel
import app.tenshu.control.inference.OnDeviceModelSource
import app.tenshu.control.ui.screens.models.CapabilityConsent
import app.tenshu.control.ui.screens.models.ModelDeleteOutcome
import app.tenshu.control.ui.screens.models.ModelsConsents
import app.tenshu.control.ui.screens.models.ModelsMessage
import app.tenshu.control.ui.screens.models.ModelsViewModel
import app.tenshu.control.ui.screens.models.OnDeviceModelsCapabilities
import app.tenshu.control.ui.screens.models.OnDeviceModelsRepository
import app.tenshu.control.ui.screens.models.PhotosFeatureScreen
import app.tenshu.control.ui.screens.models.PhotosModelsScreen
import app.tenshu.control.ui.screens.models.PhotosModelsCopy
import app.tenshu.control.ui.screens.models.photosFeaturePress
import app.tenshu.control.ui.screens.models.photosIndexingPress
import app.tenshu.control.ui.screens.models.photosFeatureScreen
import app.tenshu.control.ui.screens.models.photosModelsPress
import app.tenshu.control.ui.screens.models.photosModelsScreen
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.catalog.CoreMlDiskBudget
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmBlocking
import platform.posix.usleep
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shared `ModelsViewModel` on **Kotlin/Native**, where it had never run: every action it has,
 * answered each way, over the iOS repository and a source the case moves
 * (`ios-on-device-inference.md` §14.18.14).
 *
 * ⚠⚠ The JVM suites cannot stand in for this. A cancel that was *accepted* took the process down
 * with a segmentation fault here and was green there: see `ModelsViewModel.cancel`.
 */
class ModelsViewModelOnNativeTest {

    @BeforeTest fun redirectMain() = Dispatchers.setMain(Dispatchers.Default)
    @AfterTest fun restore() = Dispatchers.resetMain()

    private val offered = OfferedModel("siglip", "SigLIP", 2, 709_000_000, 2_600_000_000, "Apache-2.0")
    private val held = ModelSetup.Held(HeldModel("siglip", "SigLIP", 2, 2_600_000_000, "Apache-2.0"), ModelSetup.Standing.Ready, ModelSetup.Update.None)
    private fun listed(setup: ModelSetup) =
        ModelsAvailability.Listed(ModelsAvailability.Catalog.READ, ModelsAvailability.Downloads.KNOWN, listOf(ModelRow("siglip", "SigLIP", setup)))

    private class Source(first: ModelsAvailability) : OnDeviceModelSource {
        val list = MutableStateFlow(first)
        override val models: Flow<ModelsAvailability> = list
        override val serves: Flow<Map<String, List<String>>> = MutableStateFlow(emptyMap())
        var answers: ModelActionResult = ModelActionResult.Accepted
        var downloads = 0
        var cancels = 0
        override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult = answers.also { downloads++ }
        override suspend fun cancel(modelId: String): ModelActionResult = answers.also { cancels++ }
        override suspend fun checkAgain() = Unit
    }

    private object Consents : ModelsConsents {
        override fun consents(): Flow<List<CapabilityConsent>> = MutableStateFlow(listOf(CapabilityConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)))
        override suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean) = true
        override suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean) = true
    }

    /**
     * ⚠ [what] is read at the failure, never at the call: built eagerly, a message printed the state
     * as it was when the wait began, which was the opposite of what failed (2026-10-25).
     */
    private suspend fun eventually(what: () -> String, condition: () -> Boolean) {
        runCatching { withTimeout(20_000) { while (!condition()) delay(10) } }
        if (!condition()) fail(what())
    }

    @Test
    fun everyActionOfTheViewModelIsAnsweredEachWayWithoutTakingTheProcessDown() = runBlocking {
        // ⚠ The repository's scope runs on a dispatcher the case can stall: see the failed removal.
        val stalling = Stalling()
        val scope = CoroutineScope(SupervisorJob() + stalling)
        val source = Source(listed(ModelSetup.Absent(offered, null)))
        var removal = ModelDeleteOutcome.Removed
        val remove: suspend (String) -> ModelDeleteOutcome = {
            // ⚠⚠ A host that starves the repository's collection just as a failed removal ends: its
            // caller is answered at once, and the list says the mark went only after the stall.
            // ⚠ After the list has said the removal is running: a stall before it hides both marks.
            if (removal == ModelDeleteOutcome.Failed) {
                delay(200)
                stalling.stallFor(500.milliseconds)
            }
            removal
        }
        val repository = OnDeviceModelsRepository(source, null, OnDeviceModelsCapabilities, remove, Consents, scope)
        val owned = OwnedViewModels()
        val vm = owned.own(ModelsViewModel(repository))
        // ⚠ Each transition of what a removal shows, timed: printed with a failure below.
        val began = TimeSource.Monotonic.markNow()
        val transitions = MutableStateFlow(emptyList<String>())
        val tracing = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            vm.uiState.map { Triple(it.removing, it.message, it.pendingDeletion?.modelId) }.distinctUntilChanged().collect { (removing, message, pending) ->
                transitions.update { it + "+${began.elapsedNow().inWholeMilliseconds}ms removing=$removing message=$message pending=$pending" }
            }
        }
        fun trace() = transitions.value.joinToString("\n", prefix = "\n")
        try {
            eventually({ "the list was not shown: ${vm.uiState.value}" }) { vm.uiState.value.models.size == 1 }

            // A download: accepted says nothing, each refusal its sentence.
            vm.download("siglip", allowsCellular = true)
            eventually({ "the download was not asked" }) { source.downloads == 1 && vm.uiState.value.starting.isEmpty() }
            assertNull(vm.uiState.value.message)
            source.answers = ModelActionResult.Refused(ModelActionResult.Why.Said(ModelSetup.DownloadSaid.NeedsSpace(3_000_000_000, 1_000_000_000)))
            vm.download("siglip", allowsCellular = false)
            eventually({ "a refused download was not said: ${vm.uiState.value}" }) { vm.uiState.value.message == ModelsMessage.NotEnoughSpace }
            vm.consumeMessage()

            // ⚠⚠ A cancel that is ACCEPTED: the answer that crashed. Then one that is refused.
            source.list.value = listed(ModelSetup.Fetching(offered, ModelSetup.Download(false, 1, 709_000_000, null)))
            eventually({ "the download was not shown" }) { vm.uiState.value.models.single().setup is ModelSetup.Fetching }
            source.answers = ModelActionResult.Accepted
            vm.cancel("siglip")
            eventually({ "the cancel was not asked" }) { source.cancels == 1 }
            delay(200)
            assertNull(vm.uiState.value.message, "an accepted cancel says nothing")
            source.answers = ModelActionResult.Refused(ModelActionResult.Why.Said(ModelSetup.DownloadSaid.JournalUnreadable))
            vm.cancel("siglip")
            eventually({ "a refused cancel was not said: ${vm.uiState.value}" }) { vm.uiState.value.message == ModelsMessage.CancelFailed }
            vm.consumeMessage()
            source.answers = ModelActionResult.Refused(ModelActionResult.Why.NotConnected)
            vm.cancel("siglip")
            eventually({ "a cancel on a retired graph was not said: ${vm.uiState.value}" }) { vm.uiState.value.message == ModelsMessage.NotConnected }
            vm.consumeMessage()

            // A removal that fails, then one that happens; and the screen as values at each step.
            source.list.value = listed(held)
            eventually({ "the model was not shown" }) { vm.uiState.value.models.single().setup is ModelSetup.Held }
            removal = ModelDeleteOutcome.Failed
            vm.confirmDelete("siglip")
            assertEquals("Remove SigLIP?", photosModelsScreen(vm.uiState.value, "iPhone", true).question?.question?.title)
            vm.delete()
            // ⚠⚠ Said, AND the row no longer removing. The call is answered before the list says its
            // mark went, and while the row says *removing* it offers no removal: a press in between
            // is refused, silently. A user cannot make that press (iOS draws no Remove, Compose disables it) but
            // a case that waited for the message alone did, on a loaded host (2026-10-25).
            eventually({ "a failed removal was not said, or its row stayed removing: ${vm.uiState.value}${trace()}" }) {
                vm.uiState.value.message == ModelsMessage.DeleteFailed && vm.uiState.value.removing.isEmpty()
            }
            vm.consumeMessage()
            removal = ModelDeleteOutcome.Removed
            vm.confirmDelete("siglip")
            assertNotNull(vm.uiState.value.pendingDeletion, "the second removal was not asked about: ${vm.uiState.value}${trace()}")
            vm.delete()
            eventually({ "the row was not removing: ${vm.uiState.value}${trace()}" }) { "siglip" in vm.uiState.value.removing }
            source.list.value = listed(ModelSetup.Absent(offered, null))
            eventually({ "the removal was not said: ${vm.uiState.value}${trace()}" }) {
                vm.uiState.value.message == ModelsMessage.RemovedSpacePending && vm.uiState.value.removing.isEmpty()
            }
            val rows = assertIs<PhotosModelsScreen.Body.Rows>(photosModelsScreen(vm.uiState.value, "iPhone", true).body)
            assertEquals(listOf(PhotosModelsScreen.Action.Kind.DOWNLOAD, PhotosModelsScreen.Action.Kind.DOWNLOAD_WITH_MOBILE_DATA), rows.rows.single().actions.map { it.kind })

            // *Try again*, and a source that is retired under the screen.
            vm.retry()
            eventually({ "the retry did not end" }) { !vm.uiState.value.checkingAgain }
            source.list.value = ModelsAvailability.NotConnected
            eventually({ "a retired source was not said: ${vm.uiState.value}" }) { vm.uiState.value.availability == ModelsAvailability.NotConnected }
        } finally {
            owned.closeAll()
            scope.cancel()
            tracing.cancel()
        }
        owned.assertNothingActive()
    }

    /**
     * [Dispatchers.Default], with every dispatch made during a stall held until it ends: a host
     * that starves one scope's threads, to order. ⚠ Holds a Default worker while it waits.
     */
    private class Stalling : CoroutineDispatcher() {
        private val until = AtomicReference<TimeSource.Monotonic.ValueTimeMark?>(null)
        fun stallFor(duration: Duration) { until.value = TimeSource.Monotonic.markNow() + duration }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val held = until.value
            Dispatchers.Default.dispatch(context, Runnable {
                while (held != null && held.hasNotPassedNow()) usleep(1_000u)
                block.run()
            })
        }
    }

    /**
     * What 5d-3 added for the SwiftUI screens, on this target: the fixture, the presses of both
     * screens (a sealed type told apart), the feature screen as values, and a binding that ends.
     */
    @Test
    fun theFixtureTheFeatureScreenAndEveryPressRunOnNative() = runBlocking {
        assertNull(PhotosSurface.installModelsFixture("no such state"), "an unknown name installs nothing")
        val before = PhotosSurface.modelsInstalls.value
        val probe = assertNotNull(PhotosSurface.installModelsFixture("readyOn"))
        assertEquals(before + 1, PhotosSurface.modelsInstalls.value)
        val owned = OwnedViewModels()
        val models = PhotosSurface.models().also { owned.own(it.viewModel) }
        val feature = PhotosSurface.modelsFeature().also { owned.own(it.viewModel) }
        val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
        try {
            assertEquals(PhotosSurface.modelsInstalls.value, models.install)
            eventually({ "the fixture's model was not shown: ${models.state.value}" }) { models.state.value.anyModelUsable == true }
            eventually({ "the other devices were not listed: ${feature.state.value}" }) { feature.state.value.otherDevicesKnown }
            fun screen() = assertNotNull(photosFeatureScreen(feature.state.value, search, "iPhone"))
            assertEquals(listOf("Alex's Mac", "iPad"), screen().others.map { it.name })
            assertEquals(PhotosFeatureScreen.Press.TurnOffOther("mac"), screen().others.first().press)

            // Every press of the feature screen: the sealed type is told apart in Kotlin.
            // ⚠ Each press is waited for by what it WROTE: the switch itself moves before the write.
            suspend fun wrote(calls: String) = eventually({ "expected \"$calls\", was \"${probe.calls.value}\"" }) { probe.calls.value == calls }
            // The Models screen's own open reads the list again; nothing else was asked yet.
            wrote("checkAgain")
            photosFeaturePress(feature.viewModel, search, PhotosFeatureScreen.Press.TurnOffOther("mac"))
            wrote("checkAgain · clear(mac)")
            eventually({ "the other device's switch did not move" }) { screen().others.first().press == null }
            photosFeaturePress(feature.viewModel, search, PhotosFeatureScreen.Press.TurnOffHere)
            wrote("checkAgain · clear(mac) · device(off)")
            eventually({ "this device's switch did not move" }) { !screen().thisDevice.on }
            photosFeaturePress(feature.viewModel, search, PhotosFeatureScreen.Press.TurnOnHere)
            wrote("checkAgain · clear(mac) · device(off) · realm(on) · device(on)")
            eventually({ "this device's switch did not move back" }) { screen().thisDevice.on && screen().everywhere != null }
            photosFeaturePress(feature.viewModel, search, assertNotNull(screen().everywhere).press)
            wrote("checkAgain · clear(mac) · device(off) · realm(on) · device(on) · device(off) · realm(off)")
            eventually({ "Turn off everywhere stayed" }) { screen().everywhere == null }

            // Every press of the Models screen, by its kind. ⚠ The fake moves only when advanced.
            val row = assertIs<PhotosModelsScreen.Body.Rows>(photosModelsScreen(models.state.value, "iPhone", probe.onWifi).body).rows.single()
            assertEquals(listOf(PhotosModelsScreen.Action.Kind.REMOVE), row.actions.map { it.kind })
            photosModelsPress(models.viewModel, row.modelId, row.actions.single())
            eventually({ "the removal was not asked about" }) { models.state.value.pendingDeletion != null }
            models.viewModel.delete()
            eventually({ "the row was not removing: ${models.state.value}" }) { row.modelId in models.state.value.removing }
            // ⚠ Waited for: the row is *removing* from the press, before the removal has run.
            eventually({ "the removal was not asked: ${probe.calls.value}" }) { probe.calls.value.endsWith("delete(${row.modelId})") }
            probe.advance()
            eventually({ "the removal was not said: ${models.state.value}" }) { models.state.value.message == ModelsMessage.RemovedSpacePending }
            eventually({ "the row did not go: ${models.state.value}" }) { models.state.value.models.single().setup is ModelSetup.Absent }
            val id = row.modelId
            for ((kind, call) in listOf(
                PhotosModelsScreen.Action.Kind.DOWNLOAD to "download($id, wifi)",
                PhotosModelsScreen.Action.Kind.DOWNLOAD_WITH_MOBILE_DATA to "download($id, mobile)",
                PhotosModelsScreen.Action.Kind.CANCEL to "cancel($id)",
                PhotosModelsScreen.Action.Kind.TRY_AGAIN to "checkAgain",
            )) {
                val before = probe.calls.value
                photosModelsPress(models.viewModel, id, PhotosModelsScreen.Action(kind, "x"))
                eventually({ "$kind did not ask \"$call\": ${probe.calls.value}" }) {
                    probe.calls.value == "$before · $call" && models.state.value.starting.isEmpty() && !models.state.value.checkingAgain
                }
            }
            assertIs<ModelSetup.Absent>(models.state.value.models.single().setup, "an accepted download moved the fake")

            // A teardown under the open screens: they are told, and the count says to build again.
            PhotosSurface.installNotConnected()
            assertTrue(PhotosSurface.modelsInstalls.value != models.install)
        } finally {
            models.close()
            feature.close()
            PhotosSurface.installNotConnected()
        }
        assertTrue(!models.viewModel.viewModelScope.isActive && !feature.viewModel.viewModelScope.isActive, "a closed binding still collects")
        // ⚠ Joined only after that assertion: a `closeAll()` in the `finally` would cancel what
        // `close()` failed to, and the assertion could no longer see it.
        owned.closeAll()
        owned.assertNothingActive()
    }

    /**
     * 5e-2 (§14.19.6): the indexing section on this target, over the fixture — its values, each
     * press told apart by its kind, and a section that moves only when the probe does.
     */
    @Test
    fun theIndexingSectionAndItsPressesRunOnNative() = runBlocking {
        val probe = assertNotNull(PhotosSurface.installModelsFixture("indexNotAsked"))
        val owned = OwnedViewModels()
        val models = PhotosSurface.models().also { owned.own(it.viewModel) }
        try {
            fun section() = photosModelsScreen(models.state.value, "iPhone", probe.onWifi).indexing
            eventually({ "the section was not drawn: ${models.state.value}" }) { section()?.status?.contains("can be searched") == true }
            val first = assertNotNull(section())
            assertEquals("1,204 of 1,310 photos on this iPhone can be searched. 106 aren't on this iPhone.", first.status)
            assertEquals(listOf(PhotosModelsScreen.IndexAction.Kind.INDEX_NOW), first.actions.map { it.kind })

            photosIndexingPress(models.viewModel, first.actions.first())
            eventually({ "Index now was not asked: ${probe.calls.value}" }) { probe.calls.value.endsWith("indexNow") && !models.state.value.startingIndex }
            eventually({ "the press was not answered" }) { models.state.value.message == ModelsMessage.IndexingStarted }
            assertEquals(first.status, section()?.status, "a press moved the fake")

            probe.advance()
            eventually({ "the pass was not shown: ${section()}" }) { section()?.status == "Indexing 412 of 1,310" }
            assertEquals(PhotosModelsScreen.Progress.Bar(412.0 / 1_310), section()?.progress)
            photosIndexingPress(models.viewModel, assertNotNull(section()).actions.single())
            eventually({ "the pause was not written: ${probe.calls.value}" }) { probe.calls.value.endsWith("pause(on)") }
            eventually({ "the section did not say paused: ${section()}" }) { section()?.actions?.map { it.kind } == listOf(PhotosModelsScreen.IndexAction.Kind.RESUME) }
            photosIndexingPress(models.viewModel, assertNotNull(section()).actions.single())
            eventually({ "the resume was not written: ${probe.calls.value}" }) { probe.calls.value.endsWith("pause(off)") }
        } finally {
            models.close()
            owned.closeAll()
            PhotosSurface.installNotConnected()
        }
        owned.assertNothingActive()
    }

    /**
     * An install that lands between a binding's two reads (what it is built over, and which
     * install that was). ⚠ Read in the other order the binding carried the later install's count
     * over the repository that was replaced, and its screen never built another.
     */
    @Test
    fun aBindingBuiltAcrossAnInstallIsOfTheEarlierInstallSoItsScreenBuildsAgain() = vmBlocking { owned ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val replaced = OnDeviceModelsRepository.notConnected(scope)
        // The install lands while the view model is being built over what it replaces.
        PhotosSurface.installModels { PhotosSurface.installNotConnected(); replaced }
        val binding = PhotosSurface.models().also { owned.own(it.viewModel) }
        try {
            assertTrue(
                binding.install != PhotosSurface.modelsInstalls.value,
                "a binding over a replaced repository carries the install that replaced it: its screen would never build again",
            )
        } finally {
            binding.close()
            PhotosSurface.installNotConnected()
            scope.cancel()
        }
    }

    /** The fixture's on-device figure is the product's own sum for its download, not a round number. */
    @Test
    fun theFixturesOnDeviceFigureIsWhatTheProductComputesForItsDownload() {
        assertEquals(
            PhotosModelsFixture.DOWNLOAD_BYTES + CoreMlDiskBudget.prepareBytes(PhotosModelsFixture.DOWNLOAD_BYTES),
            PhotosModelsFixture.ON_DEVICE_BYTES,
        )
        // The face entry's (`ios-face-grouping.md` F6), by the same sum.
        assertEquals(
            PhotosModelsFixture.FACE_DOWNLOAD_BYTES + CoreMlDiskBudget.prepareBytes(PhotosModelsFixture.FACE_DOWNLOAD_BYTES),
            PhotosModelsFixture.FACE_ON_DEVICE_BYTES,
        )
    }
}
