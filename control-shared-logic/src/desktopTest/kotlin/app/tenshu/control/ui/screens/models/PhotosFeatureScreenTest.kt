package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.HeldModel
import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.inference.InferenceCapability
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Photos' feature screen as values (`ios-on-device-inference.md` §14.18.15; M4): the device
 * list of §38, and what each press calls.
 */
class PhotosFeatureScreenTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
    private val copy = PhotosModelsCopy
    private val ready = ModelsAvailability.Listed(
        ModelsAvailability.Catalog.READ, ModelsAvailability.Downloads.KNOWN,
        listOf(ModelRow("m", "M", ModelSetup.Held(HeldModel("m", "M", 1, 1, ""), ModelSetup.Standing.Ready, ModelSetup.Update.None))),
    )
    private val mac = OtherDeviceConsent("mac", "Mac", allowed = true)
    private val pad = OtherDeviceConsent("pad", "", allowed = false)

    private fun state(
        realm: Boolean = false,
        device: Boolean = false,
        others: List<OtherDeviceConsent>? = emptyList(),
        read: Boolean = true,
        idKnown: Boolean? = true,
        availability: ModelsAvailability = ready,
        message: ModelsMessage? = null,
        consentsRead: Boolean = true,
    ) = ModelsUiState(
        capabilities = OnDeviceModelsCapabilities,
        availability = availability,
        consents = listOf(CapabilityConsent(search, realm, device)),
        consentsRead = consentsRead,
        otherDevices = others?.let { mapOf(search to it) }.orEmpty(),
        otherDevicesKnown = others != null,
        otherDevicesRead = read,
        thisDeviceKnown = idKnown,
        message = message,
    )

    private fun screen(state: ModelsUiState) = assertNotNull(photosFeatureScreen(state, search, "iPhone"))

    /**
     * ⚠⚠ Before the answers were read nothing can be pressed and nothing says *off*: the seed is
     * every switch off, and a press on it would write over what was stored.
     */
    @Test
    fun beforeTheAnswersWereReadNoSwitchCanBePressedAndNoneSaysOff() {
        val unread = assertNotNull(photosFeatureScreen(state(realm = false, device = false, others = listOf(OtherDeviceConsent("mac", "Mac", true)), consentsRead = false), search, "iPhone"))

        assertEquals(PhotosModelsCopy.CONSENT_NOT_READ, unread.thisDevice.status)
        assertNull(unread.thisDevice.press)
        assertEquals(emptyList(), unread.others)
        assertNull(unread.everywhere)
        val row = assertNotNull(photosModelsScreen(state(consentsRead = false), "iPhone", null).feature)
        assertEquals(PhotosModelsCopy.CONSENT_NOT_READ, row.status)
    }

    @Test
    fun thisDevicesRowSaysItsOwnAnswerAndItsPressIsTheOtherOne() {
        val off = screen(state()).thisDevice
        assertEquals(listOf("This iPhone", copy.DEVICE_OFF, false, PhotosFeatureScreen.Press.TurnOnHere), listOf(off.name, off.status, off.on, off.press))
        val on = screen(state(realm = true, device = true)).thisDevice
        assertEquals(listOf(copy.DEVICE_ON, true, PhotosFeatureScreen.Press.TurnOffHere), listOf(on.status, on.on, on.press))
        // On, and no model that can be used: it says what it waits for. Not known is not *usable*.
        for (list in listOf(ModelsAvailability.Loading, ModelsAvailability.InstalledUnreadable, ModelsAvailability.NotConnected)) {
            assertEquals(copy.STARTS_WHEN_READY, screen(state(realm = true, device = true, availability = list)).thisDevice.status, "$list")
        }
        // ⚠ A yes stored under a realm no is shown on, so that it can be switched off.
        val stranded = screen(state(realm = false, device = true)).thisDevice
        assertEquals(listOf(copy.ON_BUT_OFF_EVERYWHERE, true, PhotosFeatureScreen.Press.TurnOffHere), listOf(stranded.status, stranded.on, stranded.press))
    }

    @Test
    fun aDeviceWithNoIdHasNoSwitchAndSaysSoAndNothingElseIsOffered() {
        val unknown = screen(state(realm = true, device = false, others = null, idKnown = false))
        assertNull(unknown.thisDevice.press, "never written under a placeholder id")
        assertEquals("This iPhone hasn't finished setting up, so the switch can't be saved yet.", unknown.thisDevice.status)
        assertEquals(emptyList(), unknown.others)
        assertNull(unknown.othersNotice, "said once, by the row")
        assertNull(unknown.everywhere)
        // ⚠ Not read yet is not *has none*: the switch stays until the id is known to be missing.
        assertNotNull(screen(state(idKnown = null)).thisDevice.press)
    }

    @Test
    fun theOtherDevicesAreListedOnlyFromARosterThatIsKnownNowAndCanOnlyBeSwitchedOff() {
        val listed = screen(state(realm = true, device = true, others = listOf(mac, pad)))
        assertEquals(
            listOf(
                PhotosFeatureScreen.Device("mac", "Mac", copy.DEVICE_ON, true, PhotosFeatureScreen.Press.TurnOffOther("mac")),
                PhotosFeatureScreen.Device("pad", copy.UNNAMED_DEVICE, copy.OTHER_DEVICE_OFF, false, null),
            ),
            listed.others,
        )
        assertNull(listed.othersNotice)
        assertEquals(copy.TURN_OFF_EVERYWHERE, listed.everywhere?.label)

        // ⚠ M4: the roster cannot be read. This iPhone alone, no *Turn off everywhere*, and why.
        val unreadable = screen(state(realm = true, device = true, others = null))
        assertEquals(emptyList(), unreadable.others)
        assertNull(unreadable.everywhere)
        assertEquals(copy.OTHER_DEVICES_UNREADABLE, unreadable.othersNotice)
        assertNotNull(unreadable.thisDevice.press, "this device's own switch needs no roster")
        // ⚠ Rows a read that has since failed left behind are not drawn: nothing writes after it.
        val stale = screen(state(realm = true, device = true, others = listOf(mac)).copy(otherDevicesKnown = false))
        assertEquals(emptyList(), stale.others)
        assertNull(stale.everywhere)
        // Not read yet is its own sentence, and passes by itself.
        assertEquals(copy.OTHER_DEVICES_NOT_READ, screen(state(others = null, read = false)).othersNotice)
    }

    @Test
    fun turnOffEverywhereIsOfferedOnlyWhereThereIsAYesAnywhereAndAsksFirst() {
        assertNull(screen(state()).everywhere, "nothing to turn off")
        for (some in listOf(state(realm = true), state(device = true), state(others = listOf(mac)))) {
            val everywhere = assertNotNull(screen(some).everywhere)
            assertEquals(PhotosFeatureScreen.Press.TurnOffEverywhere, everywhere.press)
            assertEquals(
                PhotosModelsScreen.Question(
                    "Turn off \"Search photos by description\" everywhere?",
                    "Every device stops indexing, and searching by description stops working.", yes = "Turn off", no = "Keep on",
                ),
                everywhere.question,
            )
        }
    }

    @Test
    fun onlyThisScreensTwoMessagesAreItsToSayAndACapabilityWithNoSwitchHasNoScreen() {
        for (message in ModelsMessage.entries) {
            val mine = message == ModelsMessage.ConsentNotSaved || message == ModelsMessage.OtherDeviceConsentNotSaved
            assertEquals(if (mine) copy.message(message, "iPhone") else null, screen(state(message = message)).message, "$message")
        }
        assertNull(photosFeatureScreen(state(), InferenceCapability.FACE_CLUSTERING, "iPhone"))
        assertNull(photosFeatureScreen(state().copy(consents = emptyList()), search, "iPhone"))
    }

    /** A repository that records what it was asked, in order. */
    private class Recording : ModelsRepository {
        val asked = mutableListOf<String>()
        override val capabilities = OnDeviceModelsCapabilities
        val list = MutableStateFlow(ModelsState(ModelsAvailability.Loading))
        override fun models(): Flow<ModelsState> = list
        override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult = ModelActionResult.Accepted.also { asked += "download($modelId, $allowsCellular)" }
        override suspend fun cancel(modelId: String): ModelActionResult = ModelActionResult.Accepted.also { asked += "cancel($modelId)" }
        override suspend fun checkAgain() { asked += "checkAgain" }
        override suspend fun loadAgain(modelId: String) { asked += "loadAgain($modelId)" }
        override suspend fun delete(modelId: String): ModelDeleteOutcome = ModelDeleteOutcome.Removed.also { asked += "delete($modelId)" }
        override fun indexProgress(): Flow<IndexProgressState> = MutableStateFlow(IndexProgressState())
        override fun indexingPaused(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setIndexingPaused(paused: Boolean) = Unit
        override suspend fun startIndexing(): IndexStartOutcome = IndexStartOutcome.Failed
        override fun lastCoverage() = MutableStateFlow(null)
        val rows = MutableStateFlow(listOf(CapabilityConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)))
        override fun consents(): Flow<List<CapabilityConsent>> = rows
        override suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean) = true.also { asked += "realm($allowed)" }
        override suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean) = true.also { asked += "device($allowed)" }
        val others = MutableStateFlow<Map<InferenceCapability, List<OtherDeviceConsent>>?>(null)
        override fun otherDeviceConsents() = others
        override suspend fun clearOtherDeviceConsent(capability: InferenceCapability, deviceId: String) = true.also { asked += "clear($deviceId)" }
        val idKnown = MutableStateFlow(true)
        override fun thisDeviceKnown(): Flow<Boolean> = idKnown
    }

    /**
     * The two things the screen says of what could not be read reach it from the repository:
     * ⚠ the values above are built by hand, and would hold with nothing collecting either flow.
     */
    @Test
    fun aMissingDeviceIdAndARosterThatCouldNotBeReadReachTheScreenFromTheRepository() = vmTest { vms ->
        val repository = Recording()
        repository.idKnown.value = false
        val feature = vms.own(ModelsViewModel(repository, forTheModelsScreen = false))
        // Before anything was collected: neither is known, and neither is said to be missing.
        assertNull(feature.uiState.value.thisDeviceKnown)
        assertEquals(copy.OTHER_DEVICES_NOT_READ, screen(feature.uiState.value).othersNotice)
        runCurrent()
        assertEquals(false, feature.uiState.value.thisDeviceKnown)
        assertNull(screen(feature.uiState.value).thisDevice.press)

        repository.idKnown.value = true
        runCurrent()
        assertNotNull(screen(feature.uiState.value).thisDevice.press)
        // The roster answered, and what it answered is that it could not be read.
        assertEquals(copy.OTHER_DEVICES_UNREADABLE, screen(feature.uiState.value).othersNotice)
        repository.others.value = mapOf(search to listOf(mac))
        runCurrent()
        assertEquals(listOf("Mac"), screen(feature.uiState.value).others.map { it.name })
        // ⚠ A read that fails after one that worked: the row it left is not drawn.
        repository.others.value = null
        runCurrent()
        assertEquals(emptyList(), screen(feature.uiState.value).others)
        assertEquals(copy.OTHER_DEVICES_UNREADABLE, screen(feature.uiState.value).othersNotice)
    }

    /** Every press, on both screens, is the call its kind names: the shell forwards and chooses nothing. */
    @Test
    fun everyPressIsTheCallItsKindNames() = vmTest { vms ->
        val repository = Recording()
        val models = vms.own(ModelsViewModel(repository))
        val absent = ModelSetup.Absent(app.tenshu.control.inference.OfferedModel("m", "M", 1, 1, 1, ""), null)
        val fetching = ModelSetup.Fetching(null, ModelSetup.Download(false, 1, 2, null))
        fun list(setup: ModelSetup) = ModelsState(ready.copy(models = listOf(ModelRow("m", "M", setup))))
        runCurrent()
        repository.asked.clear()

        fun press(kind: PhotosModelsScreen.Action.Kind) {
            photosModelsPress(models, "m", PhotosModelsScreen.Action(kind, "x"))
            runCurrent()
        }
        repository.list.value = list(absent)
        runCurrent()
        press(PhotosModelsScreen.Action.Kind.DOWNLOAD)
        press(PhotosModelsScreen.Action.Kind.DOWNLOAD_WITH_MOBILE_DATA)
        repository.list.value = list(fetching)
        runCurrent()
        press(PhotosModelsScreen.Action.Kind.CANCEL)
        press(PhotosModelsScreen.Action.Kind.TRY_AGAIN)
        repository.list.value = ModelsState(ready)
        runCurrent()
        // ⚠ A removal asks first: the press opens the question and removes nothing.
        press(PhotosModelsScreen.Action.Kind.REMOVE)
        assertEquals("Remove M?", photosModelsScreen(models.uiState.value, "iPhone", null).question?.question?.title)
        press(PhotosModelsScreen.Action.Kind.LOAD_AGAIN)
        assertEquals(listOf("download(m, false)", "download(m, true)", "cancel(m)", "checkAgain", "loadAgain(m)"), repository.asked)
        assertEquals(PhotosModelsScreen.Action.Kind.entries.size, 6, "a kind was added: press it here")

        // The feature screen's own instance: it reads no list and says nothing a model did.
        val feature = vms.own(ModelsViewModel(repository, forTheModelsScreen = false))
        repository.others.value = mapOf(search to listOf(mac))
        runCurrent()
        repository.asked.clear()
        fun press(press: PhotosFeatureScreen.Press) {
            photosFeaturePress(feature, search, press)
            runCurrent()
        }
        press(PhotosFeatureScreen.Press.TurnOnHere)
        assertEquals(listOf("realm(true)", "device(true)"), repository.asked, "on is the realm's answer, then this device's")
        repository.asked.clear()
        press(PhotosFeatureScreen.Press.TurnOffHere)
        press(PhotosFeatureScreen.Press.TurnOffOther("mac"))
        assertEquals(listOf("device(false)", "clear(mac)"), repository.asked)
        repository.asked.clear()
        press(PhotosFeatureScreen.Press.TurnOffEverywhere)
        assertEquals(listOf("device(false)", "clear(mac)", "realm(false)"), repository.asked, "the realm goes last")
    }

    @Test
    fun wifiIsAPathThatCarriesTrafficOverWifiOrAWire() {
        assertEquals(true, photosOnWifi(satisfied = true, usesWifi = true, usesWired = false))
        assertEquals(true, photosOnWifi(satisfied = true, usesWifi = false, usesWired = true))
        assertEquals(false, photosOnWifi(satisfied = true, usesWifi = false, usesWired = false), "mobile data alone")
        assertEquals(false, photosOnWifi(satisfied = false, usesWifi = true, usesWired = false), "joined, and carrying nothing")
    }
}
