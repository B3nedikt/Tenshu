package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.HeldModel
import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelStoreAccessSource
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.download
import app.tenshu.control.inference.offersDownload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.inference.InferenceCapability
import org.tenshu.viewmodel.test.vmTest

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /**
     * ⚠⚠ The rows are the fake's, as they are the repository's: a row is *fetching* because
     * [state] says so and for as long as it does, whatever a view model pressed or forgot.
     *
     * ⚠ [gate] is the load-bearing part: a download that is accepted stays under way until the
     * test completes it, so a row cannot land because `download` was called. With no gate it
     * ends inside the call, which is the shape the cases about *what is said after* need.
     */
    private class FakeRepo(
        private var models: List<ManagedModel>,
        private val outcome: ModelDownloadOutcome = ModelDownloadOutcome.Landed,
        private val catalogUnavailable: Boolean = false,
        /** Another Control owns the model store, as the proxy would report it. */
        private var storeReadOnly: StoreReadOnly? = null,
        /**
         * Whether a download that does not land is refused **at the press** (the call's answer) or
         * accepted and ends badly afterwards (the row's to say). ⚠ Both exist: a missing catalog
         * is known before a byte moves, a bad digest only at the end.
         */
        private val refusedAtThePress: Boolean = true,
        /** What the proxy answers a removal with. ⚠ Only `Removed` actually removes. */
        private val deleteOutcome: ModelDeleteOutcome = ModelDeleteOutcome.Removed,
        /** When set, an accepted download stays under way until it is completed. */
        private val gate: CompletableDeferred<Unit>? = null,
        /** The bytes a download under way reports; the last is what its row carries. */
        private val progressTicks: List<Long> = emptyList(),
        /**
         * ⚠⚠ Load-bearing for the same reason [progressTicks] is: an index stream that emits
         * nothing cannot fail when the view model forgets to subscribe. Replayed on subscribe, after
         * which the stream stays open on [laterPasses] — ⚠ it never completes, like the real one, so
         * a fake that ended here would hide a view model that depends on re-subscribing.
         */
        private val indexTicks: List<IndexProgressState> = emptyList(),
        private val pausedInitially: Boolean = false,
        /** What the preference store holds when the screen opens. `null` ⇒ the read stays silent. */
        /** ⚠ `null`: a read that never answers. The default is the stored seed, every switch off. */
        private val consentsInitially: List<CapabilityConsent>? = listOf(CapabilityConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)),
        private val realmWriteSucceeds: Boolean = true,
        /** ⚠ `false` is a device with no id yet — the one case consent must refuse rather than fake. */
        private val deviceWriteSucceeds: Boolean = true,
        /**
         * When set, the FIRST consent write suspends until it completes. ⚠⚠ Load-bearing for the
         * ordering tests: writes that complete instantly can never land out of order.
         */
        private val firstConsentWriteGate: CompletableDeferred<Unit>? = null,
        /** What the proxy answers *Index now* with (§34). */
        private val indexStart: IndexStartOutcome = IndexStartOutcome.Started,
        /** When set, `startIndexing` suspends until it completes — a press still in flight. */
        private val indexStartGate: CompletableDeferred<Unit>? = null,
        /** The realm's other devices, as the roster and their rows would report them (§38). */
        othersInitially: Map<InferenceCapability, List<OtherDeviceConsent>>? = emptyMap(),
        /** Device ids whose row cannot be written from here. */
        private val unwritableOthers: Set<String> = emptySet(),
    ) : ModelsRepository {
        /** ⚠ Live, like the real read: a row moves when its write lands, never because the screen asked. */
        val others = MutableStateFlow(othersInitially)

        override fun otherDeviceConsents(): Flow<Map<InferenceCapability, List<OtherDeviceConsent>>?> = others

        override suspend fun clearOtherDeviceConsent(capability: InferenceCapability, deviceId: String): Boolean {
            landed += "other:$deviceId:false"
            if (deviceId in unwritableOthers) return false
            others.value = others.value.orEmpty().mapValues { (c, rows) ->
                if (c != capability) rows else rows.map { if (it.deviceId == deviceId) it.copy(allowed = false) else it }
            }
            return true
        }

        var downloads = mutableListOf<String>()
        var deletions = mutableListOf<String>()
        var pauseWrites = mutableListOf<Boolean>()
        val realmWrites = mutableListOf<Pair<InferenceCapability, Boolean>>()
        val deviceWrites = mutableListOf<Pair<InferenceCapability, Boolean>>()
        var indexSubscribes = 0

        /** Ticks of passes that start after the screen subscribed — pushed by the test. */
        val laterPasses = MutableSharedFlow<IndexProgressState>(extraBufferCapacity = 64)
        var indexStarts = 0

        override val capabilities = ControlModelsCapabilities

        private val fetching = mutableMapOf<String, ModelBytes?>()
        private val last = mutableMapOf<String, LastDownload>()

        private fun now(): ModelsState = models.state(catalogUnavailable, fetching = fetching.toMap(), last = last.toMap(), storeReadOnly = storeReadOnly)

        /** What the repository says. ⚠ A test may set it outright: a failed read, a list that ends. */
        val state: MutableStateFlow<ModelsState> = MutableStateFlow(now())
        var modelsSubscribes = 0
        var checks = 0

        override fun models(): Flow<ModelsState> = flow {
            modelsSubscribes++
            emitAll(state)
        }

        override suspend fun checkAgain() {
            checks++
            state.value = now()
        }

        override suspend fun cancel(modelId: String) = ModelActionResult.Refused(ModelActionResult.Why.Unnamed)

        override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult {
            // ⚠ As the repository it stands for: one download at a time (`capabilities`).
            if (fetching.keys.any { it != modelId }) return ModelActionResult.Refused(ModelActionResult.Why.Busy)
            downloads += modelId
            if (outcome != ModelDownloadOutcome.Landed && refusedAtThePress) {
                storeReadOnly = storeReadOnly ?: outcome.storeRefusal()
                state.value = now()
                return ModelActionResult.Refused(
                    when (outcome) {
                        ModelDownloadOutcome.NoCatalog -> ModelActionResult.Why.NoCatalog
                        ModelDownloadOutcome.UnknownModel -> ModelActionResult.Why.UnknownModel
                        ModelDownloadOutcome.IntegrityFailed -> ModelActionResult.Why.Said(ModelSetup.DownloadSaid.Failed(integrity = true))
                        ModelDownloadOutcome.StoreNotOwned, ModelDownloadOutcome.StoreLockUnavailable ->
                            ModelActionResult.Why.Said(ModelSetup.DownloadSaid.StoreRefused)
                        else -> ModelActionResult.Why.Said(ModelSetup.DownloadSaid.Failed(integrity = false))
                    },
                )
            }
            fetching[modelId] = progressTicks.lastOrNull()?.let { ModelBytes("m", it, models.first { m -> m.modelId == modelId }.byteSize) }
            state.value = now()
            if (gate == null) end(modelId) else gate.invokeOnCompletion { end(modelId) }
            return ModelActionResult.Accepted
        }

        private fun end(modelId: String) {
            fetching -= modelId
            when (outcome) {
                ModelDownloadOutcome.Landed ->
                    models = models.map { if (it.modelId == modelId) it.copy(installed = true, updateAvailable = false) else it }
                ModelDownloadOutcome.StoreNotOwned, ModelDownloadOutcome.StoreLockUnavailable ->
                    storeReadOnly = storeReadOnly ?: outcome.storeRefusal()
                else -> last[modelId] = LastDownload(
                    CATALOG_VERSION,
                    ModelSetup.DownloadSaid.Failed(integrity = outcome == ModelDownloadOutcome.IntegrityFailed),
                )
            }
            state.value = now()
        }

        private fun ModelDownloadOutcome.storeRefusal(): StoreReadOnly? = when (this) {
            ModelDownloadOutcome.StoreNotOwned -> StoreReadOnly.OwnedElsewhere
            ModelDownloadOutcome.StoreLockUnavailable -> StoreReadOnly.LockUnavailable()
            else -> null
        }

        override suspend fun delete(modelId: String): ModelDeleteOutcome {
            deletions += modelId
            when (deleteOutcome) {
                ModelDeleteOutcome.Removed ->
                    models = models.map { if (it.modelId == modelId) it.copy(installed = false, updateAvailable = false) else it }
                ModelDeleteOutcome.StoreNotOwned -> storeReadOnly = storeReadOnly ?: StoreReadOnly.OwnedElsewhere
                ModelDeleteOutcome.StoreLockUnavailable -> storeReadOnly = storeReadOnly ?: StoreReadOnly.LockUnavailable()
                ModelDeleteOutcome.Failed, ModelDeleteOutcome.Busy, ModelDeleteOutcome.StillRemoving, ModelDeleteOutcome.NotConnected -> Unit
            }
            state.value = now()
            return deleteOutcome
        }

        override suspend fun startIndexing(): IndexStartOutcome {
            indexStarts++
            indexStartGate?.await()
            return indexStart
        }

        override fun indexProgress(): Flow<IndexProgressState> = flow {
            indexSubscribes++
            indexTicks.forEach { emit(it) }
            emitAll(laterPasses)
        }

        // ⚠ Never completes, like the real `readForDevice` — and emits only what was seeded, so a
        // device that has never paused emits NOTHING, which is the case the optimistic write exists
        // for.
        override fun indexingPaused(): Flow<Boolean> =
            if (pausedInitially) flowOf(true) else emptyFlow()

        override suspend fun setIndexingPaused(paused: Boolean) {
            pauseWrites += paused
        }

        /** Every consent write in the order it LANDED — `"realm:true"`, `"device:false"`. */
        val landed = mutableListOf<String>()
        var consentSubscribes = 0
        private var gated = false

        /**
         * ⚠ What is STORED, so a re-subscribe reads back the writes — like the real repository, whose
         * read seeds from a snapshot. A fake that replayed its opening state would repaint every
         * re-read with answers the user has since changed.
         */
        private val stored = consentsInitially?.associateBy { it.capability }?.toMutableMap()

        override fun consents(): Flow<List<CapabilityConsent>> = flow {
            consentSubscribes++
            stored?.let { emit(it.values.toList()) }
        }

        override suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean): Boolean {
            realmWrites += capability to allowed
            awaitFirstGate()
            landed += "realm:$allowed"
            if (realmWriteSucceeds) store(capability) { it.copy(realm = allowed) }
            return realmWriteSucceeds
        }

        override suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean): Boolean {
            deviceWrites += capability to allowed
            awaitFirstGate()
            landed += "device:$allowed"
            if (deviceWriteSucceeds) store(capability) { it.copy(device = allowed) }
            return deviceWriteSucceeds
        }

        private fun store(capability: InferenceCapability, change: (CapabilityConsent) -> CapabilityConsent) {
            val current = stored ?: return
            current[capability] = change(current[capability] ?: CapabilityConsent(capability))
        }

        private suspend fun awaitFirstGate() {
            if (gated) return
            gated = true
            firstConsentWriteGate?.await()
        }
    }

    private fun model(id: String = "siglip", installed: Boolean = false, capabilityIds: List<String> = emptyList()) =
        ManagedModel(modelId = id, name = id, byteSize = 401_604_608, installed = installed, capabilityIds = capabilityIds)

    private val laptop = OtherDeviceConsent("dev-laptop", "Laptop", allowed = true)
    private val tablet = OtherDeviceConsent("dev-tablet", "Tablet", allowed = true)
    private val hub = OtherDeviceConsent("dev-hub", "Hub", allowed = false)

    /**
     * ⚠⚠ §38.2 — *Turn off everywhere* clears this device, then every other device that holds a
     * yes, and the realm LAST: interrupted anywhere, the feature is still on for fewer devices, never
     * a realm no over answers a later "on" would resume. ⚠ A device already off is not written.
     */
    @Test
    fun turnOffEverywhereClearsEveryDeviceBeforeTheRealm() = vmTest { vms ->
        val repo = FakeRepo(
            listOf(model()),
            consentsInitially = listOf(CapabilityConsent(search, realm = true, device = true)),
            othersInitially = mapOf(search to listOf(laptop, hub, tablet)),
        )
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.turnOffEverywhere(search)
        advanceUntilIdle()

        assertEquals(
            listOf("device:false", "other:dev-laptop:false", "other:dev-tablet:false", "realm:false"),
            repo.landed,
        )
        assertEquals(CapabilityConsent(search), vm.uiState.value.consents.single { it.capability == search })
        assertEquals(emptyList(), vm.uiState.value.otherDevices.getValue(search).filter { it.allowed })
        assertNull(vm.uiState.value.message)
    }

    /**
     * ⚠⚠ Before the other devices have been read, an empty list is "not loaded". Run then, *Turn
     * off everywhere* would clear no peer and still write the realm no, leaving their answers to
     * resume later. It waits, and works once the list arrives.
     */
    @Test
    fun turnOffEverywhereWaitsForTheOtherDevices() = vmTest { vms ->
        val repo = FakeRepo(
            listOf(model()),
            consentsInitially = listOf(CapabilityConsent(search, realm = true, device = true)),
            othersInitially = null,
        )
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.otherDevicesKnown)

        vm.turnOffEverywhere(search)
        advanceUntilIdle()
        assertEquals(emptyList(), repo.landed)
        assertEquals(CapabilityConsent(search, realm = true, device = true), vm.uiState.value.consents.single { it.capability == search })

        repo.others.value = mapOf(search to listOf(laptop))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.otherDevicesKnown)
        vm.turnOffEverywhere(search)
        advanceUntilIdle()
        assertEquals(listOf("device:false", "other:dev-laptop:false", "realm:false"), repo.landed)
    }

    /**
     * ⚠⚠ One device whose row cannot be written does not stop the rest — and is said, because it is
     * a yes left behind under the realm no this is about to write.
     */
    @Test
    fun aDeviceThatCannotBeClearedIsSaidAndTheRestStillGo() = vmTest { vms ->
        val repo = FakeRepo(
            listOf(model()),
            consentsInitially = listOf(CapabilityConsent(search, realm = true, device = true)),
            othersInitially = mapOf(search to listOf(laptop, tablet)),
            unwritableOthers = setOf("dev-laptop"),
        )
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.turnOffEverywhere(search)
        advanceUntilIdle()

        assertEquals(
            listOf("device:false", "other:dev-laptop:false", "other:dev-tablet:false", "realm:false"),
            repo.landed,
        )
        assertEquals(ModelsMessage.OtherDeviceConsentNotSaved, vm.uiState.value.message)
        // ⚠ What is stored: the laptop's yes is still there, and the list says so.
        assertEquals(listOf("dev-laptop"), vm.uiState.value.otherDevices.getValue(search).filter { it.allowed }.map { it.deviceId })
    }

    /** §38.2: one other device, switched off from here — its row alone, and the list follows the store. */
    @Test
    fun anotherDeviceIsSwitchedOffAloneAndTheListFollows() = vmTest { vms ->
        val repo = FakeRepo(
            listOf(model()),
            consentsInitially = listOf(CapabilityConsent(search, realm = true, device = true)),
            othersInitially = mapOf(search to listOf(laptop, tablet)),
            unwritableOthers = setOf("dev-tablet"),
        )
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        assertEquals(listOf(laptop, tablet), vm.uiState.value.otherDevices.getValue(search))

        vm.turnOffOtherDevice(search, "dev-laptop")
        advanceUntilIdle()

        assertEquals(listOf("other:dev-laptop:false"), repo.landed)
        assertEquals(listOf(laptop.copy(allowed = false), tablet), vm.uiState.value.otherDevices.getValue(search))
        assertEquals(CapabilityConsent(search, realm = true, device = true), vm.uiState.value.consents.single { it.capability == search })
        assertNull(vm.uiState.value.message)

        // ⚠ A write that does not land is said, and the row stays where the store has it.
        vm.turnOffOtherDevice(search, "dev-tablet")
        advanceUntilIdle()
        assertEquals(ModelsMessage.OtherDeviceConsentNotSaved, vm.uiState.value.message)
        assertTrue(vm.uiState.value.otherDevices.getValue(search).single { it.deviceId == "dev-tablet" }.allowed)
    }

    /**
     * ⚠⚠ §38.3 — a model that lands for a feature that is off here raises the question; without it
     * a downloaded model does nothing and the reason is at the bottom of the screen. Yes is the same
     * two writes as the switch, realm first.
     */
    /** ⚠ Not over consents that were never read: the seed says *off*, and is no answer. */
    @Test
    fun aFirstInstallBeforeTheConsentsWereReadAsksNothing() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(capabilityIds = listOf(search.id))), consentsInitially = null)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.download("siglip")
        advanceUntilIdle()

        assertNull(vm.uiState.value.featureOffer, "the feature question was asked of the seed")
    }

    @Test
    fun aFirstInstallOffersTheFeatureItServes() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(capabilityIds = listOf(search.id))), consentsInitially = listOf(CapabilityConsent(search)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        assertNull(vm.uiState.value.featureOffer)

        vm.download("siglip")
        advanceUntilIdle()
        assertEquals(search, vm.uiState.value.featureOffer)
        // ⚠ Nothing is written by the download itself: the question is the consent.
        assertEquals(emptyList(), repo.landed)

        vm.acceptFeatureOffer()
        advanceUntilIdle()
        assertNull(vm.uiState.value.featureOffer)
        assertEquals(listOf("realm:true", "device:true"), repo.landed)
    }

    /** "Not now" writes nothing and stops asking. */
    @Test
    fun decliningTheOfferWritesNothing() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(capabilityIds = listOf(search.id))), consentsInitially = listOf(CapabilityConsent(search)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        vm.download("siglip")
        advanceUntilIdle()

        vm.dismissFeatureOffer()
        advanceUntilIdle()

        assertNull(vm.uiState.value.featureOffer)
        assertEquals(emptyList(), repo.landed)
    }

    /**
     * ⚠ No question when there is nothing to ask: the feature is already on here, the download is
     * an update, it failed, or the model serves nothing this screen has a row for.
     */
    @Test
    fun noOfferWhenThereIsNothingToAsk() = vmTest { vms ->
        suspend fun offerAfterDownload(repo: FakeRepo): InferenceCapability? {
            val vm = vms.own(ModelsViewModel(repo))
            advanceUntilIdle()
            vm.download("siglip")
            advanceUntilIdle()
            return vm.uiState.value.featureOffer
        }
        val serving = listOf(search.id)
        val off = listOf(CapabilityConsent(search))

        assertNull(
            offerAfterDownload(
                FakeRepo(listOf(model(capabilityIds = serving)), consentsInitially = listOf(CapabilityConsent(search, realm = true, device = true))),
            ),
            "already on here",
        )
        assertNull(
            offerAfterDownload(
                FakeRepo(listOf(model(installed = true, capabilityIds = serving).copy(updateAvailable = true)), consentsInitially = off),
            ),
            "an update",
        )
        assertNull(
            offerAfterDownload(
                FakeRepo(listOf(model(capabilityIds = serving)), outcome = ModelDownloadOutcome.TransportFailed, consentsInitially = off),
            ),
            "a failed download",
        )
        assertNull(
            offerAfterDownload(FakeRepo(listOf(model(capabilityIds = listOf("textInImages"))), consentsInitially = off)),
            "a capability with no row",
        )
        // ⚠ On for the realm but not here is the second device's state — and it is asked.
        assertEquals(
            search,
            offerAfterDownload(
                FakeRepo(listOf(model(capabilityIds = serving)), consentsInitially = listOf(CapabilityConsent(search, realm = true))),
            ),
        )
    }

    @Test
    fun theListLoadsOnInit() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model()))))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.loading)
        assertEquals(listOf("siglip"), vm.uiState.value.models.map { it.modelId })
    }

    @Test
    fun aLandedDownloadRefreshesSoTheRowBecomesInstalled() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model()))))
        advanceUntilIdle()

        vm.download("siglip")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.models.single().installed)
        assertEquals(emptySet(), vm.uiState.value.busy)
        assertNull(vm.uiState.value.message)
    }

    /**
     * ⚠⚠ Busy is the row's, and downloads are one at a time. One model downloading holds no
     * other row's **removal**; a second **download** is not sent while it runs (the repository
     * would refuse it), and is once it has ended.
     */
    @Test
    fun oneDownloadAtATimeAndARemovalBesideIt() = vmTest { vms ->
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo(listOf(model("a"), model("b"), model("c", installed = true)), gate = gate)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.download("a")
        advanceUntilIdle()
        assertEquals(setOf("a"), vm.uiState.value.busy)

        vm.download("b")
        advanceUntilIdle()
        assertEquals(listOf("a"), repo.downloads)
        assertEquals(setOf("a"), vm.uiState.value.busy)
        assertNull(vm.uiState.value.message)

        // ⚠ And a third row can be removed while it runs.
        vm.confirmDelete("c")
        vm.delete()
        advanceUntilIdle()
        assertEquals(listOf("c"), repo.deletions)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptySet(), vm.uiState.value.busy)
        vm.dismissFeatureOffer()
        vm.download("b")
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), repo.downloads)
        assertEquals(listOf(true, true, false), vm.uiState.value.models.map { it.installed })
    }

    /** A refusal for being busy, of a download or of a removal, and a removal still running: each is said. */
    @Test
    fun beingBusyAndStillRemovingAreSaid() = vmTest { vms ->
        val busy = object : ModelsRepository by FakeRepo(listOf(model())) {
            override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult =
                ModelActionResult.Refused(ModelActionResult.Why.Busy)
        }
        val downloading = vms.own(ModelsViewModel(busy))
        advanceUntilIdle()
        downloading.download("siglip")
        advanceUntilIdle()
        assertEquals(ModelsMessage.ModelBusy, downloading.uiState.value.message)

        val cases = mapOf(
            ModelDeleteOutcome.Busy to ModelsMessage.ModelBusy,
            ModelDeleteOutcome.StillRemoving to ModelsMessage.StillRemoving,
        )
        cases.forEach { (outcome, expected) ->
            val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model(installed = true)), deleteOutcome = outcome)))
            advanceUntilIdle()
            vm.confirmDelete("siglip")
            vm.delete()
            advanceUntilIdle()
            assertEquals(expected, vm.uiState.value.message, "$outcome")
            assertTrue(vm.uiState.value.models.single().installed)
        }
    }

    /**
     * ⚠ A press on a row that is already being fetched sends nothing, and neither does one made
     * before the first has been answered. Nor can that row be removed.
     */
    @Test
    fun aSecondPressOnAFetchingRowDoesNothing() = vmTest { vms ->
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo(listOf(model("a")), gate = gate)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.download("a")
        vm.download("a")
        advanceUntilIdle()
        vm.download("a")
        vm.confirmDelete("a")
        advanceUntilIdle()

        assertEquals(listOf("a"), repo.downloads)
        assertNull(vm.uiState.value.pendingDeletion)
        gate.complete(Unit)
    }

    /**
     * ⚠⚠ *Downloading* is what the repository says, not what this screen remembers pressing. A
     * screen that is closed and opened again over a running download shows it, and offers no
     * second download of it.
     */
    @Test
    fun aDownloadThatIsRunningIsShownByAScreenThatOpensOverIt() = vmTest { vms ->
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo(listOf(model()), gate = gate, progressTicks = listOf(1_000L))
        val first = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        first.download("siglip")
        advanceUntilIdle()

        val reopened = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        val row = reopened.uiState.value.models.single()
        assertEquals(1_000L, row.setup.download?.writtenBytes)
        assertFalse(row.setup.offersDownload)
        reopened.download("siglip")
        advanceUntilIdle()
        assertEquals(listOf("siglip"), repo.downloads)

        // ⚠ And it lands on the screen that did not start it.
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(reopened.uiState.value.models.single().installed)
    }

    /**
     * ⚠⚠ A read that failed is that failure. No row, and nothing a press could send: the models
     * that are installed are not shown as absent with a download offered.
     */
    @Test
    fun whatIsInstalledCouldNotBeReadIsSaidAndNothingIsOffered() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(), model("b", installed = true)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        for (failed in listOf(ModelsAvailability.InstalledUnreadable, ModelsAvailability.NotConnected)) {
            repo.state.value = ModelsState(failed)
            advanceUntilIdle()

            assertEquals(failed, vm.uiState.value.availability)
            assertEquals(emptyList(), vm.uiState.value.models)
            assertNull(vm.uiState.value.anyModelUsable, "not known is not none")
            vm.download("siglip")
            vm.confirmDelete("b")
            advanceUntilIdle()
            assertEquals(emptyList(), repo.downloads)
            assertNull(vm.uiState.value.pendingDeletion)
        }
    }

    /** *Try again* asks the repository to read again, says so while it does, and holds one stream. */
    @Test
    fun tryAgainReadsAgainAndTheListFollows() = vmTest { vms ->
        val repo = FakeRepo(listOf(model()))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        repo.state.value = ModelsState(ModelsAvailability.InstalledUnreadable)
        advanceUntilIdle()

        vm.retry()
        assertTrue(vm.uiState.value.checkingAgain)
        vm.retry()
        advanceUntilIdle()

        // The open's read, and the retry's one: the second press sent none.
        assertEquals(2, repo.checks)
        assertFalse(vm.uiState.value.checkingAgain)
        assertEquals(listOf("siglip"), vm.uiState.value.models.map { it.modelId })
        assertEquals(1, repo.modelsSubscribes)
    }

    /**
     * ⚠⚠ A list whose runtime is gone ends. What is left is *not connected*: never the rows it
     * last showed, which nothing stands behind, and never an empty list. *Try again* subscribes
     * to whatever answers next.
     */
    @Test
    fun aListThatEndsIsNotConnectedAndTryAgainSubscribesAgain() = vmTest { vms ->
        var ended = true
        val inner = FakeRepo(listOf(model(installed = true)))
        val repo = object : ModelsRepository by inner {
            override fun models(): Flow<ModelsState> =
                if (ended) flowOf(inner.state.value) else inner.models()
        }
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        assertEquals(ModelsAvailability.NotConnected, vm.uiState.value.availability)
        assertEquals(emptyList(), vm.uiState.value.models)

        ended = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf("siglip"), vm.uiState.value.models.map { it.modelId })
        // ⚠ What a screen comes back to is a state, and nothing is said of it.
        assertNull(vm.uiState.value.message)
        assertNull(vm.uiState.value.featureOffer)
    }

    /**
     * ⚠⚠ A refresh whose catalog fails keeps the rows and says the list is stale: it never turns
     * every catalogued row into *installed only* and drops the rest.
     */
    @Test
    fun aStaleListKeepsItsRows() = vmTest { vms ->
        val models = listOf(model("a"), model("b", installed = true))
        val repo = FakeRepo(models)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        repo.state.value = models.state(stale = true)
        advanceUntilIdle()

        assertEquals(ModelsAvailability.Catalog.STALE, (vm.uiState.value.availability as ModelsAvailability.Listed).catalog)
        assertEquals(listOf("a", "b"), vm.uiState.value.models.map { it.modelId })
        assertFalse(vm.uiState.value.catalogUnavailable)
    }

    /** ⚠⚠ An installed model with an update can be removed; and it is still asked first. */
    @Test
    fun anInstalledModelWithAnUpdateCanBeRemoved() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(installed = true).copy(updateAvailable = true)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.models.single().updateAvailable)

        vm.confirmDelete("siglip")
        assertEquals("siglip", vm.uiState.value.pendingDeletion?.modelId)
        vm.delete()
        advanceUntilIdle()

        assertEquals(listOf("siglip"), repo.deletions)
        assertFalse(vm.uiState.value.models.single().installed)
    }

    /**
     * ⚠⚠ A confirmation is about the row as it was when it opened. Answered after a download of
     * that model has started, it removes nothing: the row is asked again before the call is made.
     */
    @Test
    fun aRemovalConfirmedOverARowThatHasSinceStartedFetchingRemovesNothing() = vmTest { vms ->
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo(listOf(model(installed = true).copy(updateAvailable = true)), gate = gate)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.confirmDelete("siglip")
        assertEquals("siglip", vm.uiState.value.pendingDeletion?.modelId)
        vm.download("siglip")
        advanceUntilIdle()
        vm.delete()
        advanceUntilIdle()

        assertEquals(emptyList(), repo.deletions)
        assertNull(vm.uiState.value.pendingDeletion)
        assertTrue(vm.uiState.value.models.single().installed)
        gate.complete(Unit)
    }

    /**
     * ⚠⚠ What ends after the press was answered is the row's to say, and it is said once, by the
     * screen that watched it happen. A screen that opens afterwards finds the row saying it and
     * is told nothing: what it opens on is a state.
     */
    @Test
    fun aDownloadThatEndsBadlyAfterItWasAcceptedIsSaidByItsRow() = vmTest { vms ->
        val cases = listOf(
            ModelDownloadOutcome.TransportFailed to ModelsMessage.DownloadFailed,
            ModelDownloadOutcome.IntegrityFailed to ModelsMessage.IntegrityFailed,
        )
        cases.forEach { (outcome, expected) ->
            val gate = CompletableDeferred<Unit>()
            val repo = FakeRepo(listOf(model(), model("other")), outcome = outcome, refusedAtThePress = false, gate = gate)
            val vm = vms.own(ModelsViewModel(repo))
            advanceUntilIdle()

            vm.download("siglip")
            advanceUntilIdle()
            assertNull(vm.uiState.value.message, "accepted: nothing to say yet")
            assertEquals(setOf("siglip"), vm.uiState.value.busy)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(expected, vm.uiState.value.message, "wrong message for $outcome")
            assertEquals(emptySet(), vm.uiState.value.busy)
            val said = ModelSetup.DownloadSaid.Failed(integrity = outcome == ModelDownloadOutcome.IntegrityFailed)
            assertEquals(said, (vm.uiState.value.models.first().setup as ModelSetup.Absent).last)
            // ⚠ Under its own row and no other.
            assertNull((vm.uiState.value.models.last().setup as ModelSetup.Absent).last)

            val later = vms.own(ModelsViewModel(repo))
            advanceUntilIdle()
            assertNull(later.uiState.value.message)
            assertEquals(said, (later.uiState.value.models.first().setup as ModelSetup.Absent).last)
        }
    }

    /**
     * ⚠⚠ Every open asks for the list to be read again: what the process read when it started is
     * not what is published days later. ⚠ The feature screen's instance asks for nothing and says
     * nothing a model did: nothing on that screen would show it or consume it.
     */
    @Test
    fun openingReadsAgainAndTheFeatureScreensInstanceTakesNoModelNews() = vmTest { vms ->
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo(
            listOf(model(capabilityIds = listOf(search.id))),
            outcome = ModelDownloadOutcome.IntegrityFailed,
            refusedAtThePress = false,
            gate = gate,
            consentsInitially = listOf(CapabilityConsent(search)),
        )
        val models = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        assertEquals(1, repo.checks)

        val feature = vms.own(ModelsViewModel(repo, forTheModelsScreen = false))
        advanceUntilIdle()
        assertEquals(1, repo.checks)

        models.download("siglip")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ModelsMessage.IntegrityFailed, models.uiState.value.message)
        assertNull(feature.uiState.value.message)
        assertNull(feature.uiState.value.featureOffer)
    }

    /**
     * ⚠⚠ Every refusal has a sentence. A store that refuses a download is said even where no
     * state says which refusal it was (a repository with no such notice), and is said once where
     * one does.
     */
    @Test
    fun aStoresRefusalIsSaidWhetherOrNotAStateSaysWhich() = vmTest { vms ->
        val refused = ModelActionResult.Refused(ModelActionResult.Why.Said(ModelSetup.DownloadSaid.StoreRefused))
        val silent = object : ModelsRepository by FakeRepo(listOf(model())) {
            override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult = refused
        }
        val vm = vms.own(ModelsViewModel(silent))
        advanceUntilIdle()

        vm.download("siglip")
        advanceUntilIdle()

        assertEquals(ModelsMessage.StoreReadOnly, vm.uiState.value.message)
        assertNull(vm.uiState.value.storeReadOnly)
    }

    /**
     * ⚠⚠ A roster that fails to read after it had read keeps the devices it listed, and says they
     * are not known now: never fewer devices because a read failed, and no *Turn off everywhere*
     * over a list nobody can vouch for.
     */
    @Test
    fun aRosterThatStopsReadingKeepsItsDevicesAndIsNotKnown() = vmTest { vms ->
        val repo = FakeRepo(
            listOf(model()),
            consentsInitially = listOf(CapabilityConsent(search, realm = true, device = true)),
            othersInitially = mapOf(search to listOf(laptop, tablet)),
        )
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.otherDevicesKnown)

        repo.others.value = null
        advanceUntilIdle()

        assertEquals(listOf(laptop, tablet), vm.uiState.value.otherDevices.getValue(search))
        assertFalse(vm.uiState.value.otherDevicesKnown)
        // ⚠ Nothing is written over it: not for every device, and not for one of the rows it left.
        vm.turnOffOtherDevice(search, "dev-laptop")
        vm.turnOffEverywhere(search)
        advanceUntilIdle()
        assertEquals(emptyList(), repo.landed)
    }

    /**
     * ⚠⚠ Installed is not usable. Where an install is prepared and checked before it can be used,
     * the feature question waits for *ready*, is never asked for a model the device cannot run,
     * and the indexing line does not count such a model as one.
     */
    @Test
    fun onlyAModelThatIsReadyCountsAsOneAndAsksTheFeatureQuestion() {
        fun held(standing: ModelSetup.Standing) = ModelsState(
            ModelsAvailability.Listed(
                ModelsAvailability.Catalog.READ,
                ModelsAvailability.Downloads.KNOWN,
                listOf(ModelRow("m", "M", ModelSetup.Held(HeldModel("m", "M", 1, 9, ""), standing, ModelSetup.Update.None))),
            ),
        )
        val absent = listOf(model("m")).state()
        val preparing = held(ModelSetup.Standing.Preparing(ModelSetup.Standing.Preparing.How.Pending))
        val ready = held(ModelSetup.Standing.Ready)

        assertEquals(ModelsNews(), newsBetween(absent, preparing))
        assertEquals(ModelsNews(), newsBetween(preparing, held(ModelSetup.Standing.Unsuitable)))
        assertEquals(ModelsNews(installedFirst = "m"), newsBetween(preparing, ready))
        assertEquals(false, ModelsUiState(ControlModelsCapabilities, availability = preparing.availability).anyModelUsable)
        assertEquals(false, ModelsUiState(ControlModelsCapabilities, availability = held(ModelSetup.Standing.Unsuitable).availability).anyModelUsable)
        assertEquals(true, ModelsUiState(ControlModelsCapabilities, availability = ready.availability).anyModelUsable)
        // ⚠ Not read, or not readable: not known either way, so not *none*.
        assertNull(ModelsUiState(ControlModelsCapabilities, availability = held(ModelSetup.Standing.NotReadYet).availability).anyModelUsable)
        assertNull(ModelsUiState(ControlModelsCapabilities, availability = held(ModelSetup.Standing.Unreadable).availability).anyModelUsable)
    }

    /**
     * What one emission, set beside the one before it, is worth saying once.
     *
     * ⚠⚠ Nothing of the first emission, nothing across a list that was not there, and each thing
     * under the model it is about.
     */
    @Test
    fun onlyWhatWasWatchedHappeningIsSaid() {
        val absent = listOf(model("a"), model("b"))
        val landed = listOf(model("a", installed = true), model("b"))
        val stale = listOf(model("a", installed = true).copy(updateAvailable = true), model("b"))
        val failed = ModelSetup.DownloadSaid.Failed(integrity = true)
        val fetching = mapOf("a" to ModelBytes("m", 1, 2))

        assertEquals(ModelsNews(), newsBetween(null, landed.state()))
        assertEquals(ModelsNews(installedFirst = "a"), newsBetween(absent.state(), landed.state()))
        assertEquals(ModelsNews(installedFirst = "a"), newsBetween(absent.state(fetching = fetching), landed.state()))
        assertEquals(ModelsNews(), newsBetween(landed.state(), landed.state()))
        assertEquals(ModelsNews(), newsBetween(ModelsState(ModelsAvailability.InstalledUnreadable), landed.state()))
        assertEquals(ModelsNews(), newsBetween(ModelsState(ModelsAvailability.Loading), landed.state()))
        // ⚠ With no catalog a held model is listed alone; the one that then appears beside it did not just land.
        assertEquals(ModelsNews(), newsBetween(landed.state(catalogUnavailable = true), landed.state()))

        assertEquals(ModelsNews(ModelsMessage.UpdatedSpacePending), newsBetween(stale.state(fetching = fetching), landed.state()))
        assertEquals(ModelsNews(ModelsMessage.UpdatedSpacePending), newsBetween(stale.state(), landed.state()))

        val last = mapOf("a" to LastDownload(CATALOG_VERSION, failed))
        assertEquals(ModelsNews(ModelsMessage.IntegrityFailed), newsBetween(absent.state(fetching = fetching), absent.state(last = last)))
        // ⚠ A fetch too short to have been seen: the row says something it did not say before.
        assertEquals(ModelsNews(ModelsMessage.IntegrityFailed), newsBetween(absent.state(), absent.state(last = last)))
        assertEquals(ModelsNews(), newsBetween(absent.state(last = last), absent.state(last = last)), "said once")
        // ⚠ An update that failed is said too, and the model is still installed.
        assertEquals(ModelsNews(ModelsMessage.IntegrityFailed), newsBetween(stale.state(fetching = fetching), stale.state(last = last)))
        // ⚠ A download that simply stopped being reported (its process died) is not called a failure.
        assertEquals(ModelsNews(), newsBetween(absent.state(fetching = fetching), absent.state()))

        assertEquals(
            ModelsNews(ModelsMessage.StoreReadOnly),
            newsBetween(absent.state(), absent.state(storeReadOnly = StoreReadOnly.OwnedElsewhere)),
        )
        assertEquals(ModelsNews(), newsBetween(null, absent.state(storeReadOnly = StoreReadOnly.OwnedElsewhere)))
    }

    /**
     * ⚠⚠ Four refusals, four messages. An integrity failure told as "try again" would send someone
     * to re-fetch the same wrong bytes from the same CDN.
     */
    @Test
    fun eachFailureOutcomeKeepsItsOwnMessage() = vmTest { vms ->
        val cases = listOf(
            ModelDownloadOutcome.TransportFailed to ModelsMessage.DownloadFailed,
            ModelDownloadOutcome.IntegrityFailed to ModelsMessage.IntegrityFailed,
            ModelDownloadOutcome.UnknownModel to ModelsMessage.UnknownModel,
            ModelDownloadOutcome.NoCatalog to ModelsMessage.CatalogUnavailable,
        )

        cases.forEach { (outcome, expected) ->
            val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model()), outcome = outcome)))
            advanceUntilIdle()

            vm.download("siglip")
            advanceUntilIdle()

            assertEquals(expected, vm.uiState.value.message, "wrong message for $outcome")
            // ⚠ And the row is released either way, or a failure strands it busy forever.
            assertEquals(emptySet(), vm.uiState.value.busy)
            // ⚠ A refusal is the press's answer. The row was never moved by it.
            assertEquals(ModelSetup.Absent::class, vm.uiState.value.models.single().setup::class)
        }
    }

    /** ⚠ A throwing repository must not strand the row busy — that is what `runCatching` is for. */
    @Test
    fun aThrowingDownloadStillReleasesTheRow() = vmTest { vms ->
        val throwing = object : ModelsRepository by FakeRepo(listOf(model())) {
            override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult = error("boom")
        }
        val vm = vms.own(ModelsViewModel(throwing))
        advanceUntilIdle()

        vm.download("siglip")
        advanceUntilIdle()

        assertEquals(emptySet(), vm.uiState.value.busy)
        assertEquals(ModelsMessage.DownloadFailed, vm.uiState.value.message)
    }

    /**
     * ⚠⚠ Deleting asks first: it is instant and the undo is a ~383 MB re-download. Nothing is
     * removed until the confirmation is answered.
     */
    @Test
    fun deletingAsksBeforeItRemovesAnything() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(installed = true)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.confirmDelete("siglip")
        advanceUntilIdle()

        assertEquals("siglip", vm.uiState.value.pendingDeletion?.modelId)
        assertTrue(repo.deletions.isEmpty())

        vm.delete()
        advanceUntilIdle()

        assertEquals(listOf("siglip"), repo.deletions)
        assertNull(vm.uiState.value.pendingDeletion)
        assertFalse(vm.uiState.value.models.single().installed)
    }

    @Test
    fun dismissingTheConfirmationRemovesNothing() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(installed = true)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.confirmDelete("siglip")
        vm.dismissDelete()
        advanceUntilIdle()

        assertNull(vm.uiState.value.pendingDeletion)
        assertTrue(repo.deletions.isEmpty())
        assertTrue(vm.uiState.value.models.single().installed)
    }

    /** ⚠ The screen is told the catalog could not be read, so it can say so instead of "none". */
    @Test
    fun anUnavailableCatalogReachesTheUiState() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model(installed = true)), catalogUnavailable = true)))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.catalogUnavailable)
        assertEquals(1, vm.uiState.value.models.size)
    }

    /**
     * ⚠⚠ The bar is the row's: the bytes the repository reports for that download reach the state
     * with the row, and go with it when the download ends.
     */
    @Test
    fun pushedProgressReachesTheUiState() = vmTest { vms ->
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<Long?>()
        val vm = vms.own(ModelsViewModel(
            FakeRepo(listOf(model()), gate = gate, progressTicks = listOf(0L, 1_000L, 2_000L)),
        ))
        advanceUntilIdle()

        vm.download("siglip")
        advanceUntilIdle()
        seen += vm.uiState.value.models.single().setup.download?.writtenBytes

        gate.complete(Unit)
        advanceUntilIdle()

        // The last tick before the download resolved is what the bar was showing.
        assertEquals(listOf<Long?>(2_000L), seen)
        // ⚠ And it is cleared afterwards, or a finished row keeps rendering a bar.
        assertNull(vm.uiState.value.models.single().setup.download)
    }

    /** ⚠ A delete has no byte count — the row must not render a determinate bar over nothing. */
    @Test
    fun deletingCarriesNoDownloadedBytes() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(installed = true)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.confirmDelete("siglip")
        vm.delete()
        assertEquals(setOf("siglip"), vm.uiState.value.removing)
        assertNull(vm.uiState.value.models.single().setup.download)
        advanceUntilIdle()

        assertNull(vm.uiState.value.models.single().setup.download)
        assertEquals(emptySet(), vm.uiState.value.removing)
    }

    /**
     * ⚠⚠ The index bar is only real if the view model actually subscribes. This is the same trap
     * `pushedProgressReachesTheUiState` covers for downloads — a UI that never collects is
     * indistinguishable, from the repository's side, from one that does.
     */
    @Test
    fun indexProgressReachesTheUiState() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(
            FakeRepo(
                listOf(model(installed = true)),
                indexTicks = listOf(
                    IndexProgressState(indexing = true, done = 1, total = 9_000),
                    IndexProgressState(indexing = true, done = 300, total = 9_000),
                ),
            ),
        ))
        advanceUntilIdle()

        assertEquals(
            IndexProgressState(indexing = true, done = 300, total = 9_000),
            vm.uiState.value.indexProgress,
        )
    }

    /**
     * ⚠⚠ A pass that STARTS after the screen opened shows up with no refresh and no retry. A view
     * model that subscribed from `refresh()` would hold a stream that had already said "not
     * indexing" until someone pressed the button.
     */
    @Test
    fun aPassThatStartsAfterTheScreenOpenedShowsUpUnasked() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(installed = true)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        assertEquals(IndexProgressState(indexing = false), vm.uiState.value.indexProgress)

        repo.laterPasses.emit(IndexProgressState(indexing = true, done = 12, total = 1_300))
        advanceUntilIdle()

        assertEquals(IndexProgressState(indexing = true, done = 12, total = 1_300), vm.uiState.value.indexProgress)
        assertEquals(1, repo.indexSubscribes)
    }

    /**
     * ⚠⚠ One subscription, two passes: the first ends, a second starts later, and the bar follows both.
     * ⚠ The subscribe count is the other half — re-subscribing on refresh or on a started pass
     * would put two collectors fighting over the row.
     */
    @Test
    fun aSecondPassIsReportedOnTheSameSubscription() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(installed = true)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        repo.laterPasses.emit(IndexProgressState(indexing = true, done = 5, total = 9))
        repo.laterPasses.emit(IndexProgressState(indexing = false))
        advanceUntilIdle()
        assertEquals(IndexProgressState(indexing = false), vm.uiState.value.indexProgress)

        repo.laterPasses.emit(IndexProgressState(indexing = true, done = 2, total = 9))
        advanceUntilIdle()

        assertEquals(IndexProgressState(indexing = true, done = 2, total = 9), vm.uiState.value.indexProgress)
        assertEquals(1, repo.indexSubscribes)
    }

    /** ⚠ Refresh reads the list only: re-subscribing a stream that never ends would stack collectors. */
    @Test
    fun refreshDoesNotReSubscribeTheLiveStreams() = vmTest { vms ->
        val repo = FakeRepo(listOf(model()))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        val consentsBefore = repo.consentSubscribes

        vm.refresh()
        vm.retry()
        advanceUntilIdle()

        assertEquals(1, repo.indexSubscribes)
        assertEquals(consentsBefore, repo.consentSubscribes)
    }

    /**
     * ⚠⚠ The switch moves on the press, not on the echo. `readForDevice` emits NOTHING on a device
     * that holds no device-setting rows — precisely a device that has never paused — so a control
     * waiting for the read to come back would not move on the very first press.
     */
    @Test
    fun pausingMovesTheStateBeforeTheWriteEchoesBack() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(installed = true)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.indexingPaused)

        vm.setIndexingPaused(true)

        assertTrue(vm.uiState.value.indexingPaused, "the state moved without waiting for the write")
        advanceUntilIdle()
        assertEquals(listOf(true), repo.pauseWrites)
    }

    /** ⚠ A device that is already paused says so from the preference, not from a press. */
    @Test
    fun anAlreadyPausedDeviceIsReadBackAsPaused() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model()), pausedInitially = true)))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.indexingPaused)
    }

    // ─── IN-D3's switches.

    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH

    private fun ModelsViewModel.consent() = uiState.value.consents.single { it.capability == search }

    /**
     * ⚠⚠ The switch is there before anything is read. A section that waited for the first emission
     * would never appear on a realm that has never held the row.
     */
    @Test
    fun theSearchSwitchIsOfferedOffBeforeAnythingIsRead() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model()))))
        advanceUntilIdle()

        assertEquals(CapabilityConsent(search, realm = false, device = false), vm.consent())
    }

    /**
     * ⚠⚠ Turning it on from this device is this device asking for it — so both answers are written.
     * Writing only the realm half would be a switch that reads "on" while the proxy, which needs
     * both, skips every photo.
     */
    /** ⚠⚠ No switch acts on the seed: before the first read, a press writes nothing (Android and iOS alike). */
    @Test
    fun aPressBeforeTheAnswersWereReadWritesNothing() = vmTest { vms ->
        val repo = FakeRepo(listOf(model()), consentsInitially = null)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.setRealmConsent(search, true)
        vm.setDeviceConsent(search, true)
        vm.turnOffEverywhere(search)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.consentsRead)
        assertEquals(emptyList(), repo.realmWrites)
        assertEquals(emptyList(), repo.deviceWrites)
    }

    @Test
    fun turningSearchOnWritesTheRealmAndThisDevice() = vmTest { vms ->
        val repo = FakeRepo(listOf(model()))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.setRealmConsent(search, true)

        assertEquals(CapabilityConsent(search, realm = true, device = true), vm.consent(), "moved on the press")
        advanceUntilIdle()
        assertEquals(listOf(search to true), repo.realmWrites)
        assertEquals(listOf(search to true), repo.deviceWrites)
        assertNull(vm.uiState.value.message)
    }

    /**
     * ⚠ Off clears this device's answer too, so a later "on" from another device cannot quietly
     * resume the work here.
     */
    @Test
    fun turningItOffClearsThisDevicesAnswerToo() = vmTest { vms ->
        val repo = FakeRepo(
            listOf(model()),
            consentsInitially = listOf(CapabilityConsent(search, realm = true, device = true)),
        )
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.setRealmConsent(search, false)
        advanceUntilIdle()

        assertEquals(listOf(search to false), repo.realmWrites)
        assertEquals(listOf(search to false), repo.deviceWrites)
        assertEquals(CapabilityConsent(search, realm = false, device = false), vm.consent())
    }

    /** The state a second device opens in: on for the realm, not yet here. */
    @Test
    fun aRealmAnswerFromAnotherDeviceIsShownWithThisDeviceStillOff() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(
            FakeRepo(listOf(model()), consentsInitially = listOf(CapabilityConsent(search, realm = true))),
        ))
        advanceUntilIdle()

        assertEquals(CapabilityConsent(search, realm = true, device = false), vm.consent())
    }

    /**
     * ⚠⚠ A device with no id cannot hold a device answer the proxy will read, so the switch goes back
     * and says so — never a switch that reads "on" over a row written under a placeholder.
     */
    @Test
    fun aDeviceAnswerThatCannotBeSavedGoesBackAndSaysSo() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(
            FakeRepo(
                listOf(model()),
                consentsInitially = listOf(CapabilityConsent(search, realm = true)),
                deviceWriteSucceeds = false,
            ),
        ))
        advanceUntilIdle()

        vm.setDeviceConsent(search, true)
        advanceUntilIdle()

        assertFalse(vm.consent().device)
        assertEquals(ModelsMessage.ConsentNotSaved, vm.uiState.value.message)
    }

    /**
     * ⚠ The realm answer landed and this device's did not: the realm stays on — rolling back a write
     * that succeeded would put the screen at odds with every other device — and the user is told.
     */
    @Test
    fun aRealmYesWhoseDeviceHalfFailsKeepsTheRealmAndSaysSo() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model()), deviceWriteSucceeds = false)))
        advanceUntilIdle()

        vm.setRealmConsent(search, true)
        advanceUntilIdle()

        assertEquals(CapabilityConsent(search, realm = true, device = false), vm.consent())
        assertEquals(ModelsMessage.ConsentNotSaved, vm.uiState.value.message)
    }

    /**
     * ⚠⚠ Switching off, the realm answer lands and this device's clear does not. The stored yes stays
     * behind, so a later "on" from another device would resume the work here without asking — the
     * state keeps that yes and the user is told, rather than a screen that reads fully off.
     */
    @Test
    fun aFailedDeviceClearWhenSwitchingOffKeepsTheStoredYesAndSaysSo() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(
            FakeRepo(
                listOf(model()),
                consentsInitially = listOf(CapabilityConsent(search, realm = true, device = true)),
                deviceWriteSucceeds = false,
            ),
        ))
        advanceUntilIdle()

        vm.setRealmConsent(search, false)
        advanceUntilIdle()

        assertEquals(CapabilityConsent(search, realm = false, device = true), vm.consent())
        assertEquals(ModelsMessage.ConsentNotSaved, vm.uiState.value.message)
    }

    /**
     * ⚠⚠ The order of the pair is the safety property. On writes the realm first and off clears this
     * device first, so a pair interrupted between its writes always leaves this device NOT consented —
     * never a stored yes under a realm no that another device's "on" would resume.
     */
    @Test
    fun onWritesTheRealmFirstAndOffClearsThisDeviceFirst() = vmTest { vms ->
        val repo = FakeRepo(listOf(model()))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.setRealmConsent(search, true)
        advanceUntilIdle()
        vm.setRealmConsent(search, false)
        advanceUntilIdle()

        assertEquals(listOf("realm:true", "device:true", "device:false", "realm:false"), repo.landed)
    }

    /**
     * ⚠⚠ On, then off, faster than the first write lands. Unserialised, the older `true` lands LAST and
     * the stored answer is the one the user took back; serialised, the writes land in press order and
     * the switch ends where the last press put it.
     */
    @Test
    fun rapidPressesLandInPressOrder() = vmTest { vms ->
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo(listOf(model()), firstConsentWriteGate = gate)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.setRealmConsent(search, true)
        advanceUntilIdle()
        vm.setRealmConsent(search, false)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("realm:true", "device:true", "device:false", "realm:false"), repo.landed)
        assertEquals(CapabilityConsent(search), vm.consent(), "the switch ends where the last press put it")
    }

    /**
     * ⚠⚠ Queued presses whose writes all fail. The second press's `before` is the FIRST press's
     * optimistic state, never anything stored — so rolling back to it would show on/on over an
     * off/off store. Only a re-read of what is stored can end in the truth.
     */
    @Test
    fun queuedPressesThatFailEndOnWhatIsStored() = vmTest { vms ->
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo(
            listOf(model()),
            consentsInitially = listOf(CapabilityConsent(search)),
            realmWriteSucceeds = false,
            deviceWriteSucceeds = false,
            firstConsentWriteGate = gate,
        )
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.setRealmConsent(search, true)
        vm.setRealmConsent(search, false)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(CapabilityConsent(search), vm.consent())
    }

    /**
     * ⚠ A landed device answer re-reads the consents, so the switch shows what is stored rather than
     * an earlier press's optimistic state (`persistConsent`).
     */
    @Test
    fun aDeviceAnswerThatLandsReReadsTheConsents() = vmTest { vms ->
        val repo = FakeRepo(listOf(model()), consentsInitially = listOf(CapabilityConsent(search, realm = true)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        val before = repo.consentSubscribes

        vm.setDeviceConsent(search, true)
        advanceUntilIdle()

        assertEquals(before + 1, repo.consentSubscribes)
    }

    @Test
    fun aRealmWriteThatFailsPutsBothBack() = vmTest { vms ->
        val repo = FakeRepo(listOf(model()), realmWriteSucceeds = false)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.setRealmConsent(search, true)
        advanceUntilIdle()

        assertEquals(CapabilityConsent(search), vm.consent())
        assertTrue(repo.deviceWrites.isEmpty(), "no device answer without a realm one")
        assertEquals(ModelsMessage.ConsentNotSaved, vm.uiState.value.message)
    }

    /** ⚠⚠ *OK* clears the message it was pressed on, never one that replaced it unseen. */
    @Test
    fun consumingTheMessageThatWasShownLeavesAReplacementStanding() = vmTest { vms ->
        val repo = FakeRepo(listOf(model()), realmWriteSucceeds = false)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        vm.setRealmConsent(search, true)
        advanceUntilIdle()
        assertEquals(ModelsMessage.ConsentNotSaved, vm.uiState.value.message)

        photosModelsMessageSeen(vm, ModelsMessage.DeleteFailed)
        assertEquals(ModelsMessage.ConsentNotSaved, vm.uiState.value.message, "a message nobody saw was cleared")
        photosModelsMessageSeen(vm, ModelsMessage.ConsentNotSaved)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun consumingAMessageClearsIt() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model()), outcome = ModelDownloadOutcome.TransportFailed)))
        advanceUntilIdle()
        vm.download("siglip")
        advanceUntilIdle()

        vm.consumeMessage()

        assertNull(vm.uiState.value.message)
    }

    // ---- Index now (§34) ----------------------------------------------------------------------

    @Test
    fun indexNowStartsAPassAndSaysSo() = vmTest { vms ->
        val repo = FakeRepo(listOf(model(installed = true)))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.indexNow()
        advanceUntilIdle()

        assertEquals(1, repo.indexStarts)
        assertEquals(ModelsMessage.IndexingStarted, vm.uiState.value.message)
        assertFalse(vm.uiState.value.startingIndex)
    }

    /**
     * ⚠⚠ Five outcomes, five messages. Two of them name an action the user can take — download a
     * model, switch the capability on — so collapsing them into "couldn't start" would send someone
     * looking for a fault in their photos.
     */
    @Test
    fun eachRefusalKeepsItsOwnMessage() = vmTest { vms ->
        val expected = mapOf(
            IndexStartOutcome.NoModel to ModelsMessage.IndexNoModel,
            IndexStartOutcome.NoConsent to ModelsMessage.IndexNoConsent,
            IndexStartOutcome.Paused to ModelsMessage.IndexPaused,
            IndexStartOutcome.NotReady to ModelsMessage.IndexNotReady,
            IndexStartOutcome.Failed to ModelsMessage.IndexFailed,
        )
        expected.forEach { (outcome, message) ->
            val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model()), indexStart = outcome)))
            advanceUntilIdle()

            vm.indexNow()
            advanceUntilIdle()

            assertEquals(message, vm.uiState.value.message, "wrong message for $outcome")
        }
        // ⚠ The map is the subject as well as the table: a case dropped from the enum would shrink
        // it silently and the loop would still pass.
        assertEquals(IndexStartOutcome.entries.size - 2, expected.size, "every outcome but Started and Waiting")
    }

    /**
     * §14.19.6: a press the phone holds back (iOS's foreground pass) says no message — the section
     * says why for as long as it is true.
     */
    @Test
    fun aPressThatWaitsForThePhoneSaysNoMessage() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model()), indexStart = IndexStartOutcome.Waiting)))
        advanceUntilIdle()
        vm.indexNow()
        advanceUntilIdle()
        assertNull(vm.uiState.value.message)
        assertFalse(vm.uiState.value.startingIndex)
    }

    /**
     * ⚠ *Index now* leaves the stream alone, started or refused: the one subscribed in `init` stays
     * open and reports the pass, so a second would only stack a collector beside it.
     */
    @Test
    fun indexNowDoesNotReSubscribe() = vmTest { vms ->
        for (outcome in listOf(IndexStartOutcome.Started, IndexStartOutcome.NoModel)) {
            val repo = FakeRepo(listOf(model(installed = true)), indexStart = outcome)
            val vm = vms.own(ModelsViewModel(repo))
            advanceUntilIdle()

            vm.indexNow()
            advanceUntilIdle()

            assertEquals(1, repo.indexSubscribes, "re-subscribed after $outcome")
        }
    }

    /**
     * ⚠ The press is a round trip to the proxy. Without the latch a double tap sends two, and the
     * second answer overwrites the first's message.
     */
    @Test
    fun aSecondPressWhileOneIsInFlightIsIgnored() = vmTest { vms ->
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo(listOf(model(installed = true)), indexStartGate = gate)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.indexNow()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.startingIndex)

        vm.indexNow()
        advanceUntilIdle()

        assertEquals(1, repo.indexStarts)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.startingIndex)
    }

    // ─── a Control that does not own the model store ─────────────────────────────────────────────

    /**
     * ⚠⚠ A standing state, told before anyone taps: the snapshot says another Control owns the
     * store, so the screen carries the notice and offers neither button — and a caller that kept
     * one reaches nothing.
     */
    @Test
    fun aStoreOwnedByAnotherControlIsSaidUpFrontAndNothingIsOffered() = vmTest { vms ->
        val repo = FakeRepo(
            listOf(model("a"), model("b", installed = true)),
            storeReadOnly = StoreReadOnly.OwnedElsewhere,
        )
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        assertEquals(StoreReadOnly.OwnedElsewhere, vm.uiState.value.storeReadOnly)
        // ⚠ Told by the notice, not by a snackbar nobody asked for.
        assertNull(vm.uiState.value.message)

        vm.download("a")
        vm.confirmDelete("b")
        advanceUntilIdle()

        assertTrue(repo.downloads.isEmpty())
        assertNull(vm.uiState.value.pendingDeletion)
        assertEquals(emptySet(), vm.uiState.value.busy)
    }

    /**
     * ⚠⚠ The backstop: the snapshot said nothing, and the download is refused. It must not read as
     * the retryable failure, it is said once, and the buttons go.
     */
    @Test
    fun aDownloadRefusedByAnotherControlIsNotOfferedAsARetry() = vmTest { vms ->
        val repo = FakeRepo(listOf(model()), outcome = ModelDownloadOutcome.StoreNotOwned)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()
        assertNull(vm.uiState.value.storeReadOnly)

        vm.download("siglip")
        advanceUntilIdle()

        assertEquals(ModelsMessage.StoreReadOnly, vm.uiState.value.message)
        assertEquals(StoreReadOnly.OwnedElsewhere, vm.uiState.value.storeReadOnly)
        assertEquals(emptySet(), vm.uiState.value.busy)

        // Once: a second press reaches nothing, and the latch survives the refresh.
        vm.consumeMessage()
        vm.download("siglip")
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("siglip"), repo.downloads)
        assertNull(vm.uiState.value.message)
        assertEquals(StoreReadOnly.OwnedElsewhere, vm.uiState.value.storeReadOnly)
    }

    /** ⚠⚠ A removal's failure is said, never discarded: the row would come back installed, unexplained. */
    @Test
    fun aFailedRemovalIsSaidNotSwallowed() = vmTest { vms ->
        val cases = listOf(
            ModelDeleteOutcome.StoreNotOwned to ModelsMessage.StoreReadOnly,
            ModelDeleteOutcome.StoreLockUnavailable to ModelsMessage.StoreReadOnly,
            ModelDeleteOutcome.Failed to ModelsMessage.DeleteFailed,
        )
        val latched = mapOf(
            ModelDeleteOutcome.StoreNotOwned to StoreReadOnly.OwnedElsewhere,
            ModelDeleteOutcome.StoreLockUnavailable to StoreReadOnly.LockUnavailable(),
        )

        cases.forEach { (outcome, expected) ->
            val repo = FakeRepo(listOf(model(installed = true)), deleteOutcome = outcome)
            val vm = vms.own(ModelsViewModel(repo))
            advanceUntilIdle()

            vm.confirmDelete("siglip")
            vm.delete()
            advanceUntilIdle()

            assertEquals(listOf("siglip"), repo.deletions)
            assertEquals(expected, vm.uiState.value.message, "wrong message for $outcome")
            assertTrue(vm.uiState.value.models.single().installed, "nothing was removed")
            assertEquals(emptySet(), vm.uiState.value.busy)
            assertEquals(latched[outcome], vm.uiState.value.storeReadOnly, "wrong state for $outcome")
        }
    }

    /** A throw is a failure nobody named — still said. */
    @Test
    fun aThrowingRemovalIsSaidToo() = vmTest { vms ->
        val throwing = object : ModelsRepository by FakeRepo(listOf(model(installed = true))) {
            override suspend fun delete(modelId: String): ModelDeleteOutcome = error("boom")
        }
        val vm = vms.own(ModelsViewModel(throwing))
        advanceUntilIdle()

        vm.confirmDelete("siglip")
        vm.delete()
        advanceUntilIdle()

        assertEquals(ModelsMessage.DeleteFailed, vm.uiState.value.message)
        assertEquals(emptySet(), vm.uiState.value.busy)
    }

    /**
     * The typed reason → outcome mapping `ClientModelsRepository` applies. ⚠⚠ `storeNotOwned` must
     * never fall into the retryable bucket, which is where every unnamed failure goes.
     */
    @Test
    fun aStoreNotOwnedReasonIsNeverTheRetryableOutcome() {
        val reason = org.tenshu.inference.ModelUnavailable.Reasons.STORE_NOT_OWNED

        assertEquals(ModelDownloadOutcome.StoreNotOwned, downloadOutcomeFor(reason))
        assertEquals(ModelDeleteOutcome.StoreNotOwned, deleteOutcomeFor(reason))
        assertEquals(ModelDownloadOutcome.TransportFailed, downloadOutcomeFor(null))
        assertEquals(ModelDeleteOutcome.Failed, deleteOutcomeFor(null))
        // Already gone is what was asked for.
        assertEquals(
            ModelDeleteOutcome.Removed,
            deleteOutcomeFor(org.tenshu.inference.ModelUnavailable.Reasons.NOT_INSTALLED),
        )
    }

    // ─── the lock could not be taken at all — nobody to quit ─────────────────────────────────────

    /**
     * ⚠⚠ Not the same state as "another Control owns it", and not the same sentence: there may be
     * no other Control. The screen says what failed and where, offers nothing that cannot work, and
     * names no remedy it cannot justify.
     */
    @Test
    fun aLockThatCouldNotBeTakenIsItsOwnStateAndNeverBlamesAnotherControl() = vmTest { vms ->
        val detail = "/data/content/inference/models/store.lock: AccessDeniedException: denied"
        val repo = FakeRepo(listOf(model("a"), model("b", installed = true)), storeReadOnly = StoreReadOnly.LockUnavailable(detail))
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        val why = vm.uiState.value.storeReadOnly
        assertEquals(StoreReadOnly.LockUnavailable(detail), why)

        vm.download("a")
        vm.confirmDelete("b")
        advanceUntilIdle()
        assertTrue(repo.downloads.isEmpty())
        assertNull(vm.uiState.value.pendingDeletion)
    }

    /** The typed backstop keeps the two apart as well — and never downgrades a state that has detail. */
    @Test
    fun aLockUnavailableRefusalLatchesItsOwnState() = vmTest { vms ->
        val repo = FakeRepo(listOf(model()), outcome = ModelDownloadOutcome.StoreLockUnavailable)
        val vm = vms.own(ModelsViewModel(repo))
        advanceUntilIdle()

        vm.download("siglip")
        advanceUntilIdle()

        assertEquals(ModelsMessage.StoreReadOnly, vm.uiState.value.message)
        assertEquals(StoreReadOnly.LockUnavailable(), vm.uiState.value.storeReadOnly)

        val reason = org.tenshu.inference.ModelUnavailable.Reasons.STORE_LOCK_UNAVAILABLE
        assertEquals(ModelDownloadOutcome.StoreLockUnavailable, downloadOutcomeFor(reason))
        assertEquals(ModelDeleteOutcome.StoreLockUnavailable, deleteOutcomeFor(reason))
    }

    /** The proxy's process-local answer reaches the snapshot as the same kind, detail included. */
    @Test
    fun theProxysRefusalKeepsItsKindOnTheWayToTheScreen() {
        assertNull(storeReadOnlyOf(null))
        assertNull(storeReadOnlyOf(access(refusal = null)))
        assertEquals(
            StoreReadOnly.OwnedElsewhere,
            storeReadOnlyOf(access(ModelStoreAccessSource.Refusal.OwnedElsewhere)),
        )
        assertEquals(
            StoreReadOnly.LockUnavailable("/p/store.lock: IOException: x"),
            storeReadOnlyOf(access(ModelStoreAccessSource.Refusal.LockUnavailable, "/p/store.lock: IOException: x")),
        )
    }

    /** ⚠ The proxy's own holder is out of this module's reach; its mapping is `ModelStoreAccessTest`'s. */
    private fun access(refusal: ModelStoreAccessSource.Refusal?, detail: String = "") =
        object : ModelStoreAccessSource {
            override val refusal: ModelStoreAccessSource.Refusal? = refusal
            override val detail: String = detail
        }

    // ─── removing frees the disk only at the next start, and nothing may say it does ─────────────

    /** ⚠ After the removal too: "removed" with nothing else said reads as "freed". */
    @Test
    fun aRemovalSaysItsSpaceIsReleasedAtTheNextStart() = vmTest { vms ->
        val vm = vms.own(ModelsViewModel(FakeRepo(listOf(model(installed = true)))))
        advanceUntilIdle()

        vm.confirmDelete("siglip")
        vm.delete()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.models.single().installed)
        assertEquals(ModelsMessage.RemovedSpacePending, vm.uiState.value.message)
    }

    /**
     * An update keeps the version it replaced on disk until then as well. ⚠ A first install
     * replaces nothing, and says nothing.
     */
    @Test
    fun anUpdateSaysThePreviousVersionsSpaceIsStillHeldAndAFirstInstallSaysNothing() = vmTest { vms ->
        val stale = ManagedModel("siglip", "siglip", 401_604_608, installed = true, updateAvailable = true)
        val updating = vms.own(ModelsViewModel(FakeRepo(listOf(stale))))
        advanceUntilIdle()
        updating.download("siglip")
        advanceUntilIdle()
        assertEquals(ModelsMessage.UpdatedSpacePending, updating.uiState.value.message)

        val fresh = vms.own(ModelsViewModel(FakeRepo(listOf(model()))))
        advanceUntilIdle()
        fresh.download("siglip")
        advanceUntilIdle()
        assertNull(fresh.uiState.value.message)
    }

    /** ⚠ A throwing repository must release the button, not strand it disabled for the session. */
    @Test
    fun aThrowingStartStillReleasesTheButton() = vmTest { vms ->
        val throwing = object : ModelsRepository by FakeRepo(listOf(model(installed = true))) {
            override suspend fun startIndexing(): IndexStartOutcome = error("boom")
        }
        val vm = vms.own(ModelsViewModel(throwing))
        advanceUntilIdle()

        vm.indexNow()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.startingIndex)
        assertEquals(ModelsMessage.IndexFailed, vm.uiState.value.message)
    }
}
