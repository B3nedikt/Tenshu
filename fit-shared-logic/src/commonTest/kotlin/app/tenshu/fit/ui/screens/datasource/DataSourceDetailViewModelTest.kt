package app.tenshu.fit.ui.screens.datasource

import app.tenshu.fit.data.AutoDetectPreferences
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthPermissionRequester
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.ImportWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * DS-4 — the detail screen that Profile's source rows open into, and where *Import your full
 * history* and *Check sharing setup* moved to.
 *
 * The behaviours worth pinning are the asymmetries: what a **remote** source may and may not be
 * told to do (D4), and that a manual import ignores the automatic switch.
 */
class DataSourceDetailViewModelTest {

    // ⚠⚠ Without this a `ViewModel`'s `viewModelScope` — which is `Main.immediate` — never runs,
    // and every assertion reads the constructor's default state. The symptom is a null `row`
    // that looks exactly like "the registry has no such source".
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val THIS_DEVICE = "mock-this-device"
    private val OTHER_DEVICE = "mock-other-device"

    private class RecordingRequester : HealthPermissionRequester {
        var fullHistoryRequests = 0
            private set
        var settingsOpened = 0
            private set

        override fun request() = Unit
        override fun requestFullHistory() { fullHistoryRequests++ }
        override fun openHealthSettings() { settingsOpened++ }
    }

    private fun FitRepository.withHistoryAccess(access: HistoryAccess): FitRepository {
        val base = this
        return object : FitRepository by base {
            private val flow = MutableStateFlow(access)
            override fun historyAccess(): StateFlow<HistoryAccess> = flow
        }
    }

    @Test
    fun aLocalSourceCanBeImportedNow() = vmTest { vms ->
        var started: Pair<ImportWindow, Boolean>? = null
        val base = MockFitRepository(scope = backgroundScope)
        val repo = object : FitRepository by base {
            override suspend fun startImport(
                selection: Set<CategoryId>,
                window: ImportWindow,
                automatic: Boolean,
            ) { started = window to automatic }
        }
        val vm = vms.own(DataSourceDetailViewModel(DataSourceKind.HealthConnect, THIS_DEVICE, repo))
        runCurrent()

        assertTrue(vm.uiState.value.canImportNow)
        vm.importNow()
        runCurrent()

        assertEquals(ImportWindow.Recent, started?.first)
        assertEquals(
            false,
            started?.second,
            "⚠ a manual import is NOT automatic — it must run even where the switch is off (D4)",
        )
    }

    /**
     * ⚠⚠ D4's asymmetry. We cannot make an iPhone read HealthKit from here, so the screen offers
     * no import — a button that silently did nothing would be worse than its absence.
     */
    @Test
    fun aRemoteSourceOffersNoImport() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val vm = vms.own(DataSourceDetailViewModel(DataSourceKind.AppleHealth, OTHER_DEVICE, repo))
        runCurrent()

        assertEquals("iPhone 15", vm.uiState.value.row?.deviceLabel)
        assertFalse(vm.uiState.value.canImportNow)
        assertFalse(vm.uiState.value.canAskForFullHistory)
    }

    /** …but the switch DOES reach it: the flag is ours and it syncs. */
    @Test
    fun aRemoteSourcesAutoImportSwitchStillReachesIt() = vmTest { vms ->
        var flipped: Triple<DataSourceKind, String, Boolean>? = null
        val base = MockFitRepository(scope = backgroundScope)
        val repo = object : FitRepository by base {
            override suspend fun setSourceAutoImport(
                kind: DataSourceKind,
                deviceId: String,
                enabled: Boolean,
            ) { flipped = Triple(kind, deviceId, enabled) }
        }
        val vm = vms.own(DataSourceDetailViewModel(DataSourceKind.AppleHealth, OTHER_DEVICE, repo))
        runCurrent()

        vm.setAutoImport(false)
        runCurrent()

        assertEquals(Triple(DataSourceKind.AppleHealth, OTHER_DEVICE, false), flipped)
    }

    /**
     * Moved here from `ProfileViewModel` with the row itself (DS-4). Already-`Granted` is not a
     * no-op: the grant is in place and the user is asking for the deep read.
     */
    @Test
    fun fullHistoryPromptsOrImportsByAccess() = vmTest { vms ->
        val requester = RecordingRequester()
        var startedWindow: ImportWindow? = null
        fun repoFor(access: HistoryAccess): FitRepository {
            val base = MockFitRepository(scope = backgroundScope).withHistoryAccess(access)
            return object : FitRepository by base {
                override suspend fun startImport(
                    selection: Set<CategoryId>,
                    window: ImportWindow,
                    automatic: Boolean,
                ) { startedWindow = window }
            }
        }

        val grantable = vms.own(DataSourceDetailViewModel(
            DataSourceKind.HealthConnect, THIS_DEVICE, repoFor(HistoryAccess.Grantable), requester,
        ))
        runCurrent()
        grantable.importFullHistory()
        runCurrent()
        assertEquals(1, requester.fullHistoryRequests)
        assertEquals(null, startedWindow)

        val granted = vms.own(DataSourceDetailViewModel(
            DataSourceKind.HealthConnect, THIS_DEVICE, repoFor(HistoryAccess.Granted), requester,
        ))
        runCurrent()
        granted.importFullHistory()
        runCurrent()
        assertEquals(1, requester.fullHistoryRequests, "an already-granted tap must not re-prompt")
        assertEquals(ImportWindow.FullHistory, startedWindow)
    }

    /**
     * ⚠ With two phones in one realm there are two rows of different kinds — and, in a
     * same-platform realm, two of the *same* kind. Matching on kind alone would open one device's
     * screen showing the other's figures.
     */
    @Test
    fun theRowIsMatchedOnDeviceAsWellAsKind() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)

        val local = vms.own(DataSourceDetailViewModel(DataSourceKind.HealthConnect, THIS_DEVICE, repo))
        runCurrent()
        assertEquals(THIS_DEVICE, local.uiState.value.row?.deviceId)

        val remote = vms.own(DataSourceDetailViewModel(DataSourceKind.AppleHealth, OTHER_DEVICE, repo))
        runCurrent()
        assertEquals(OTHER_DEVICE, remote.uiState.value.row?.deviceId)
    }

    /**
     * ⚠⚠ Q1/DS-6. The switch on Tenshu Health's detail is the **detection preference**, not an
     * auto-import flag — same control on screen, different thing underneath. Routing it to
     * `setSourceAutoImport` would write to a registry row that does not exist for this source,
     * and the toggle would silently do nothing.
     */
    @Test
    fun tenshuHealthsSwitchWritesTheDetectionPreference() = vmTest { vms ->
        var detection: Boolean? = null
        var registryWrite: String? = null
        val base = MockFitRepository(scope = backgroundScope)
        val repo = object : FitRepository by base {
            override suspend fun setSourceAutoImport(
                kind: DataSourceKind,
                deviceId: String,
                enabled: Boolean,
            ) { registryWrite = "$kind/$deviceId" }
        }
        val prefs = object : AutoDetectPreferences {
            override fun enabled(): Flow<Boolean> = flowOf(true)
            override suspend fun enabledOnce(): Boolean = true
            override suspend fun setEnabled(value: Boolean) { detection = value }
            override suspend fun promptAnswered(): Boolean = true
            override suspend fun markPromptAnswered() = Unit
        }
        val vm = vms.own(DataSourceDetailViewModel(
            DataSourceKind.TenshuHealth, null, repo, autoDetect = prefs,
        ))
        runCurrent()

        vm.setAutoImport(false)
        runCurrent()

        assertEquals(false, detection, "the switch writes the detection preference")
        assertEquals(null, registryWrite, "and never the source registry — there is no row")
    }

    @Test
    fun tenshuHealthHasNoImportHistory() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val vm = vms.own(DataSourceDetailViewModel(DataSourceKind.TenshuHealth, null, repo))
        runCurrent()

        assertTrue(vm.uiState.value.history.isEmpty(), "Fit's own recordings are not imports")
        assertFalse(vm.uiState.value.canImportNow)
    }
}
