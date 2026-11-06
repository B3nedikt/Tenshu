package app.tenshu.control.inference

import androidx.lifecycle.viewModelScope
import app.tenshu.control.inference.ModelSetup.Standing
import app.tenshu.control.ui.screens.models.CapabilityConsent
import app.tenshu.control.ui.screens.models.ModelDeleteOutcome
import app.tenshu.control.ui.screens.models.ModelsConsents
import app.tenshu.control.ui.screens.models.ModelsMessage
import app.tenshu.control.ui.screens.models.ModelsViewModel
import app.tenshu.control.ui.screens.models.OnDeviceModelsCapabilities
import app.tenshu.control.ui.screens.models.OnDeviceModelsRepository
import app.tenshu.control.ui.screens.models.PhotosModelsCopy
import app.tenshu.control.ui.screens.models.PhotosModelsScreen
import app.tenshu.control.ui.screens.models.photosModelsScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.PrepareKey
import org.tenshu.inference.proxy.ReadinessStanding
import org.tenshu.inference.proxy.RuntimeFingerprint
import org.tenshu.inference.proxy.catalog.InstalledArtifactData
import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.JournalledModelDownload
import org.tenshu.inference.proxy.catalog.ModelArtifactData
import org.tenshu.inference.proxy.catalog.ModelDownloadJournalView
import org.tenshu.inference.proxy.catalog.ModelDownloadOutcome
import org.tenshu.inference.proxy.catalog.ModelDownloadProgressSnapshot
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.downloadKey
import org.tenshu.inference.proxy.PreflightReport
import org.tenshu.inference.proxy.PrepareRefusal
import org.tenshu.inference.proxy.PrepareReport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real pieces, joined: the shared [ModelsViewModel] over [OnDeviceModelsRepository] over
 * [JoinedModelSource], with the flows of one graph and its two doors (the downloader, the removal
 * operation) in the test's hands (`ios-on-device-inference.md` §14.18.14).
 *
 * ⚠⚠ Nothing lands because it was pressed: a download is journalled, installed, prepared and
 * checked when the case moves the graph, and a removal changes the store when the case does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceModelsComposedTest {

    private val main = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(main)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val model = "siglip"
    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH

    private val entry = ModelEntryData(
        modelId = model, name = "SigLIP", modelVersion = 1, capabilities = listOf(search), labelSetVersion = 1,
        runtime = "coreml", artifactVersion = 1,
        artifacts = listOf(
            ModelArtifactData("vision", "v1.tar", "a".repeat(64), 400_000_000),
            ModelArtifactData("text", "t1.tar", "b".repeat(64), 309_000_000),
        ),
        licence = "Apache-2.0",
    )
    private val record = InstalledModelData(
        entry.modelId, entry.modelVersion, entry.artifacts.map { InstalledArtifactData(it.role, "models/${it.path}", it.byteSize, it.sha256) }, 1, entry.licence,
    )
    private val prepareKey = PrepareKey(model, record.artifacts.map { it.sha256 })
    private val preflightKey = PreflightKey(RuntimeFingerprint("a".repeat(64), "b".repeat(64), "ane", "23A1", 1), "vocab", "100", 1)
    private val noInstallKey = PreflightKey(RuntimeFingerprint("", "", "ane", "23A1", 1), "vocab", "100", 1)
    private val installed = InstalledRead.Read(record, prepareKey, preflightKey, onDeviceBytes = 2_600_000_000)
    private val nothingInstalled = InstalledRead.Read(null, null, null)

    private class Consents : ModelsConsents {
        val rows = MutableStateFlow(listOf(CapabilityConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)))
        val writes = mutableListOf<String>()
        override fun consents(): Flow<List<CapabilityConsent>> = rows
        override suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean): Boolean {
            writes += "realm $allowed"
            rows.value = rows.value.map { it.copy(realm = allowed) }
            return true
        }
        override suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean): Boolean {
            writes += "device $allowed"
            rows.value = rows.value.map { it.copy(device = allowed) }
            return true
        }
    }

    private inner class Graph(scope: TestScope) {
        val retired = MutableStateFlow(false)
        val readiness = MutableStateFlow<ReadinessStanding>(ReadinessStanding.For(noInstallKey, AiReadinessState.NoModel))
        val journal = MutableStateFlow<ModelDownloadJournalView>(ModelDownloadJournalView.Read(emptyMap()))
        val outcome = MutableStateFlow<ModelDownloadOutcome?>(null)
        val progress = MutableStateFlow<Map<String, ModelDownloadProgressSnapshot>>(emptyMap())
        val compiling = MutableStateFlow<PrepareKey?>(null)
        val probing = MutableStateFlow<PreflightKey?>(null)
        var store: InstalledRead = nothingInstalled
        val calls = mutableListOf<String>()
        var admit: (() -> Unit)? = null
        var removal = CompletableDeferred<ModelDeleteOutcome>()

        fun stands(state: AiReadinessState) { readiness.value = ReadinessStanding.For(preflightKey, state) }

        val source = JoinedModelSource(
            model, scope.backgroundScope, retired, readiness, journal, outcome, progress, compiling, probing,
            prepare = MutableStateFlow<PrepareReport?>(null),
            prepareRefusal = MutableStateFlow<PrepareRefusal?>(null),
            preflight = MutableStateFlow<PreflightReport?>(null),
            readInstalled = { store },
            loadCatalog = { calls += "catalog"; CatalogRead.Read(entry, 2_600_000_000) },
            // ⚠ As the downloader: the call goes on until the model is installed, long after it was admitted.
            startDownload = { id, cellular, admitted -> calls += "download $id $cellular"; admit = admitted; awaitCancellation() },
            cancelDownload = { id -> calls += "cancel $id" },
            recheck = { calls += "recheck" },
            isRetired = { retired.value },
            context = UnconfinedTestDispatcher(scope.testScheduler),
            readOffered = { org.tenshu.inference.proxy.AiReadiness.KeyAnswer.Open },
            loadAgainOf = { calls += "loadAgain" },
        )
        val consents = Consents()
        val repository = OnDeviceModelsRepository(
            source = source,
            notOffered = null,
            capabilities = OnDeviceModelsCapabilities,
            // ⚠ The operation's door: it answers when the case lets it, having changed what the case changed.
            remove = { id -> calls += "remove $id"; removal.await() },
            consents = consents,
            scope = scope.backgroundScope,
        )
    }

    private fun ModelsViewModel.row(): PhotosModelsScreen.Row =
        assertIs<PhotosModelsScreen.Body.Rows>(photosModelsScreen(uiState.value, "iPhone", onWifi = true).body).rows.single()
    private fun ModelsViewModel.setup(): ModelSetup = uiState.value.models.single().setup

    @Test
    fun fromThePressToTheFeatureQuestionEveryStepIsTheGraphsAndTheScreenSaysEachOne() = runTest(main) {
        val graph = Graph(this)
        val vm = ModelsViewModel(graph.repository)
        runCurrent()
        // The open asked for a read: the one coalesced check, and the catalog.
        assertTrue("recheck" in graph.calls && "catalog" in graph.calls)
        assertEquals("709 MB download · about 2.6 GB on this iPhone · Apache-2.0", vm.row().facts)
        assertEquals(listOf(PhotosModelsCopy.DOWNLOAD, PhotosModelsCopy.USE_MOBILE_DATA), vm.row().actions.map { it.label })

        vm.download(model, allowsCellular = false)
        runCurrent()
        assertTrue("download $model false" in graph.calls)
        // ⚠ Not answered and not journalled: *starting*, and the row has not moved.
        assertEquals(PhotosModelsCopy.STARTING, vm.row().status)
        assertIs<ModelSetup.Absent>(vm.setup())
        // The downloader has written the record: admitted, and its view names it.
        graph.journal.value = ModelDownloadJournalView.Read(mapOf(model to JournalledModelDownload(entry.downloadKey(), false)))
        graph.admit!!()
        runCurrent()
        assertEquals(emptySet(), vm.uiState.value.starting)
        assertIs<ModelSetup.Fetching>(vm.setup())
        assertEquals(listOf(PhotosModelsCopy.CANCEL), vm.row().actions.map { it.label })

        // It is committed: the store holds it, the journal lets go, readiness is about the new install.
        graph.store = installed
        graph.stands(AiReadinessState.Preparing)
        graph.journal.value = ModelDownloadJournalView.Read(emptyMap())
        runCurrent()
        assertEquals(PhotosModelsCopy.PREPARING, vm.row().status)
        graph.compiling.value = prepareKey
        runCurrent()
        assertEquals(PhotosModelsCopy.PREPARING_NOW, vm.row().status)
        graph.compiling.value = null
        graph.stands(AiReadinessState.AwaitingVerdict)
        graph.probing.value = preflightKey
        runCurrent()
        assertEquals(PhotosModelsCopy.checking("iPhone"), vm.row().status)
        assertNull(vm.uiState.value.featureOffer, "installed is not usable")
        graph.probing.value = null
        graph.stands(AiReadinessState.Ready)
        runCurrent()
        assertEquals(PhotosModelsCopy.READY, vm.row().status)
        assertEquals(listOf(PhotosModelsCopy.REMOVE), vm.row().actions.map { it.label })
        // ⚠ Asked now, from what the catalog says the model serves; and yes writes realm, then device.
        assertEquals(search, vm.uiState.value.featureOffer)
        vm.acceptFeatureOffer()
        runCurrent()
        assertEquals(listOf("realm true", "device true"), graph.consents.writes)
        assertEquals(PhotosModelsCopy.featureOn("iPhone"), photosModelsScreen(vm.uiState.value, "iPhone", true).feature?.status)
        vm.viewModelScope.cancel()
    }

    @Test
    fun aRemovalIsRemovingUntilTheStoreHasSaidSoAndATeardownIsNotConnected() = runTest(main) {
        val graph = Graph(this)
        graph.store = installed
        graph.stands(AiReadinessState.Ready)
        val vm = ModelsViewModel(graph.repository)
        val other = ModelsViewModel(graph.repository)
        runCurrent()
        assertEquals(PhotosModelsCopy.READY, vm.row().status)
        assertNull(vm.uiState.value.featureOffer, "what a screen opens on is a state, not news")

        vm.confirmDelete(model)
        vm.delete()
        runCurrent()
        assertEquals(listOf("remove $model"), graph.calls.filter { it.startsWith("remove") })
        // ⚠ On every screen over the graph's one repository, not only the one that pressed.
        assertEquals(PhotosModelsCopy.REMOVING, vm.row().status)
        assertEquals(PhotosModelsCopy.REMOVING, other.row().status)
        assertEquals(emptyList(), other.row().actions)

        // The operation answers; the store's read that follows it is what lets the row go.
        graph.removal.complete(ModelDeleteOutcome.Removed)
        runCurrent()
        assertEquals(PhotosModelsCopy.REMOVING, vm.row().status)
        graph.store = nothingInstalled
        graph.readiness.value = ReadinessStanding.For(noInstallKey, AiReadinessState.NoModel)
        runCurrent()
        assertIs<ModelSetup.Absent>(vm.setup())
        assertEquals(emptySet(), other.uiState.value.removing)
        assertEquals(ModelsMessage.RemovedSpacePending, vm.uiState.value.message)
        assertEquals(ModelsMessage.RemovedSpacePending, other.uiState.value.message, "the screen that watched is told by the list")

        // The graph is torn down under both: a failure, never the last list and never an empty one.
        graph.retired.value = true
        runCurrent()
        for (screen in listOf(vm, other)) {
            assertEquals(ModelsAvailability.NotConnected, screen.uiState.value.availability)
            assertEquals(
                PhotosModelsScreen.Body.Notice(PhotosModelsCopy.NOT_CONNECTED, tryAgain = PhotosModelsCopy.TRY_AGAIN),
                photosModelsScreen(screen.uiState.value, "iPhone", true).body,
            )
            screen.viewModelScope.cancel()
        }
    }
}
