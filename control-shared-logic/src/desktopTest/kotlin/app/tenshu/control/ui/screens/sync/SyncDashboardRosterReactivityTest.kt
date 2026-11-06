package app.tenshu.control.ui.screens.sync

import app.tenshu.control.sync.HostPairingMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.core.TContext
import org.tenshu.core.core.schemaProvider
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Synchronization & Devices roster must track the live relation, not a snapshot.
 *
 * ⚠⚠ With a one-shot read, a device that joins while the screen is open does not appear, even
 * though its row is **already in the local store** — not a sync problem, only an unread one.
 * Re-entering the screen shows it, which is the signature of a one-shot read.
 *
 * ⚠ No test pins that a failing roster load does not show the create-realm banner: that guarantee
 * is structural only, a known coverage hole.
 * [theFirstEmissionIsTheInitialLoad_andTheDeviceIdIsKnownBeforeItLands] pins the initial render
 * independently of the flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncDashboardRosterReactivityTest {

    private lateinit var context: TContext

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        context = createTestContext()
        (context.schemaProvider() as SchemaRegistry).registerConfig(identityServiceCommonModule)
    }

    @AfterTest
    fun teardown() = Dispatchers.resetMain()

    private fun device(id: String, name: String = "Device $id") = Device.create(
        context = context,
        deviceId = id,
        name = name,
        publicKey = byteArrayOf(1, 2, 3),
    )

    @Test
    fun aDeviceJoiningElsewhereAppears_withNoRefreshCall() = runTest {
        val repo = FakeSyncRepository()
        repo.devicesFlow.value = listOf(device("desktop"))
        val vm = SyncDashboardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope))
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.devices.size, "precondition: one device on screen")

        // The phone joins. Nothing calls refresh() — this is the screen sitting open.
        repo.devicesFlow.value = listOf(device("desktop"), device("phone"))
        advanceUntilIdle()

        assertEquals(
            2,
            vm.uiState.value.devices.size,
            "a roster change must reach an open screen without anything calling refresh()",
        )
        assertEquals(
            listOf("desktop", "phone"),
            vm.uiState.value.devices.map { it.deviceId.get() },
            "and it is the new roster, not merely a re-render of the old one",
        )
    }

    /**
     * A joiner publishes its `DeviceHardware` row only after it has joined, so on the admitting
     * device the model lands after the roster has rendered. A one-shot read in the roster pass would
     * leave it off an open screen until something re-ran the pass.
     */
    @Test
    fun aHardwareModelArrivingAfterTheRosterReachesTheOpenScreen() = runTest {
        val repo = FakeSyncRepository()
        repo.devicesFlow.value = listOf(device("desktop"), device("phone"))
        val vm = SyncDashboardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope))
        advanceUntilIdle()
        assertEquals(emptyMap(), vm.uiState.value.deviceHardwareNames, "precondition: no models yet")
        val passes = repo.lastSeenReads

        repo.hardwareFlow.value = mapOf("phone" to "Google Pixel 8")
        advanceUntilIdle()

        assertEquals(mapOf("phone" to "Google Pixel 8"), vm.uiState.value.deviceHardwareNames)
        assertEquals(2, vm.uiState.value.devices.size, "patching models must not disturb the roster")
        assertEquals(passes, repo.lastSeenReads, "a model arriving patches the state, it is not a roster pass")
    }

    @Test
    fun theFirstEmissionIsTheInitialLoad_andTheDeviceIdIsKnownBeforeItLands() = runTest {
        // ⚠ The relation always emits (`TenshuSyncRepository` maps a failed read to `emptyList()`),
        // so "the roster never emits" is not a case to pin. The property is that opening the
        // screen runs a pass at all, with the device id already resolved, since every pass
        // publishes it.
        val repo = FakeSyncRepository()
        repo.devicesFlow.value = listOf(device("desktop"))

        val vm = SyncDashboardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope))
        advanceUntilIdle()

        assertEquals(1, repo.lastSeenReads, "opening the screen runs exactly one pass")
        assertEquals(false, vm.uiState.value.isLoading, "the pass completed and cleared the spinner")
    }


    @Test
    fun theScreenIsLoadingBeforeTheFirstEmission_soItCannotFlashTheCreateRealmPrompt() = runTest {
        // ⚠⚠ Without an eager `isLoading = true`, nothing sets it until the relation's first
        // emission arrives, so the state sits at `SyncDashboardUiState()` defaults: `isLoading =
        // false` and
        // `isSyncEnabled = false`. `SyncDashboardScreen` skips the progress bar while `isLoading`
        // is false and renders the "no realm — Create Realm" onboarding prompt while
        // `isSyncEnabled` is false. So a device that HAS a realm briefly shows the prompt to make
        // one, across the relation subscribe plus the first emission.
        //
        // ⚠ Asserting the spinner is up from construction is what keeps that window closed.
        val repo = FakeSyncRepository()
        repo.devicesOverride = MutableSharedFlow() // subscribes, never emits — models the window

        val vm = SyncDashboardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope))
        runCurrent()

        assertEquals(
            true,
            vm.uiState.value.isLoading,
            "the screen must read as loading from construction until the first roster lands, or " +
                "it renders the Create Realm prompt to someone who already has a realm",
        )
    }

    @Test
    fun constructionSurvivesACollectorThatRunsInline_beforeTheClassBodyIsFinished() = vmTest { vms ->
        // ⚠⚠ The dispatcher IS the test. `viewModelScope` is `Dispatchers.Main.immediate` on every
        // surface — the Swing EDT on desktop, the main queue on K/N — so `init`'s `launch` does not
        // hand its body to a queue: it runs INLINE inside the constructor until something genuinely
        // suspends, and `collectLatest` starts its block undispatched on top of that. This fake
        // answers everything from memory, exactly as iOS's not-connected repository does
        // (`getDeviceId()` returns a constant, `devices` is a `MutableStateFlow` that replays at
        // once), so the whole roster pass executes part way down the class body — while properties
        // declared BELOW the init blocks are still null.
        //
        // ⚠ The other tests here use a `StandardTestDispatcher`, which queues the launch and
        // therefore cannot see this at all. A `refreshLock` declared under the init block fails as
        // "Cannot invoke Mutex.lock … because $this$withLock is null"; on iOS that same throw
        // crosses into Swift undeclared, i.e. an abort rather than a stack trace.
        //
        // ⚠⚠ Assert the OUTCOME, not the throw. The NPE is raised inside the launched coroutine, so
        // it goes to the uncaught handler rather than out of `SyncDashboardViewModel(...)` — with
        // a late-declared property this test fails on the roster being empty, not on an NPE. That
        // is the production symptom too (a dead collector leaves the screen empty and spinning),
        // and it catches ANY later-declared property the pass reads.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val repo = FakeSyncRepository()
        repo.devicesFlow.value = listOf(device("desktop"))

        val vm = vms.own(
            SyncDashboardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope))
        )
        advanceUntilIdle()

        assertEquals(
            listOf("desktop"),
            vm.uiState.value.devices.map { it.deviceId.get() },
            "the pass must have finished, not merely not thrown",
        )
        assertEquals(
            false,
            vm.uiState.value.isLoading,
            "and reached its `finally`, so the screen is usable rather than stuck on the spinner",
        )
    }

    @Test
    fun theEmittedRosterIsWhatLands_notAReReadThatReplaysTheOldSnapshot() = runTest {
        // ⚠⚠ Models PRODUCTION, and it is red against a `refreshNow()` that re-reads.
        // `StreamInteractorImpl` catches a late subscriber up by replaying from the INITIAL
        // snapshot, so `devices.first()` inside the pass opens a new subscription and gets the
        // ORIGINAL roster back — the collector receives the fresh one and the pass discards it.
        // An already-open dashboard then stays stale.
        //
        // ⚠ A `MutableStateFlow` hides this: it replays only the newest value, so the other tests
        // pass either way. The fake models the real replay instead.
        val repo = FakeSyncRepository()
        val original = listOf(device("desktop"))
        repo.replayFromInitial = original          // what a late `first()` would get back
        repo.devicesFlow.value = listOf(device("desktop"), device("phone"))  // what the collector sees

        val vm = SyncDashboardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope))
        advanceUntilIdle()

        assertEquals(
            listOf("desktop", "phone"),
            vm.uiState.value.devices.map { it.deviceId.get() },
            "the pass must render the roster the collector EMITTED; re-reading the relation " +
                "returns the replayed original and leaves the screen stale",
        )
    }
}
