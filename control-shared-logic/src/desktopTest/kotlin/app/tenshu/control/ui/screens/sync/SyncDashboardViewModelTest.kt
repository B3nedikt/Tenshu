package app.tenshu.control.ui.screens.sync

import app.tenshu.control.sync.HostPairingMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.common.impl.GenericErrorDto
import org.tenshu.core.common.impl.ResultFailureDto
import org.tenshu.core.TenshuException
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.core.TContext
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.tenshu.viewmodel.test.vmTest
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * Failure-path coverage for device revocation.
 *
 * The revoke affordance now exists on iOS as well (`control-ios/Sources/SyncDashboardView.swift`),
 * where it can be the *only* place a device is removable — an iPhone-only realm has no second
 * Control to fall back to. That makes a silently-swallowed refusal the worst outcome: the VM
 * re-reads the roster after every attempt, so an unchanged list is indistinguishable from a slow
 * one. And refusal is not hypothetical — `RevokeDeviceImplementation` bails when the local
 * `Device` entity is absent, the normal state of a store that was wiped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncDashboardViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(vms: OwnedViewModels, repo: FakeSyncRepository, scope: kotlinx.coroutines.CoroutineScope) =
        vms.own(SyncDashboardViewModel(repo, HostPairingMonitor(repo, scope = scope)))

    private val context: TContext = run {
        val registry = createCoreTestRegistry()
        registry.registerConfig(syncServiceCommonModule)
        createTestContext(schemaRegistry = registry)
    }

    @Test
    fun aFailedPairingStart_showsNoInPairingRow() = vmTest { vms ->
        // The row is a route back to a live session; a start that minted no code has none.
        val repo = FakeSyncRepository()
        repo.startPairingResult = ResultFailureDto(GenericErrorDto("simulated start failure"))
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(SyncDashboardViewModel(repo, monitor))
        runCurrent()

        monitor.startPairing()
        runCurrent()

        assertFalse(vm.uiState.value.isPairing, "no session, so no 'in Pairing' row")
        assertNull(vm.uiState.value.pairingRemainingSeconds)
    }

    @Test
    fun aStartedPairingSession_showsTheInPairingRow() = vmTest { vms ->
        // The positive control for the test above: the row is wired, it just must not lie.
        val repo = FakeSyncRepository(
            startPairingStatus = SyncStatus.create(
                context = context,
                deviceId = "this-device",
                ownerId = "owner-1",
                isEnabled = true,
                pairingString = "tenshu://join?o=o&d=X&k=K&s=S",
            ),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(SyncDashboardViewModel(repo, monitor))
        runCurrent()

        monitor.startPairing()
        runCurrent()

        assertTrue(vm.uiState.value.isPairing)
        assertEquals(120, vm.uiState.value.pairingRemainingSeconds)

        monitor.stopPairing()
        runCurrent()
    }

    @Test
    fun revokeSucceeds_callsThrough_andSurfacesNoError() = vmTest { vms ->
        val repo = FakeSyncRepository()
        val vm = viewModel(vms, repo, backgroundScope)

        vm.revokeDevice("device-to-remove")
        advanceUntilIdle()

        assertEquals(listOf("device-to-remove"), repo.revokedDeviceIds, "the op must be called once, with the id")
        assertNull(vm.uiState.value.error, "a successful revocation is not an error")
    }

    @Test
    fun revokeReturnsFailure_surfacesTheOpsMessage() = vmTest { vms ->
        // The shape a refusal actually takes at this boundary: the proxy catch-all converts the
        // op's exception into a Failure, so this is a returned value, not a throw.
        val repo = FakeSyncRepository(
            revokeResult = ResultFailureDto(GenericErrorDto("Local Device entity not found"))
        )
        val vm = viewModel(vms, repo, backgroundScope)

        vm.revokeDevice("ghost-device")
        advanceUntilIdle()

        assertEquals(
            "Local Device entity not found",
            vm.uiState.value.error,
            "a refused revocation must reach the user, not vanish behind a re-read",
        )
    }

    @Test
    fun revokeThrows_surfacesAnError_ratherThanVanishing() = vmTest { vms ->
        val repo = FakeSyncRepository(revokeThrows = IllegalStateException("Proxy not connected"))
        val vm = viewModel(vms, repo, backgroundScope)

        vm.revokeDevice("device-to-remove")
        advanceUntilIdle()

        assertEquals("Proxy not connected", vm.uiState.value.error, "transport loss must not pass silently")
    }

    @Test
    fun refreshAfterAFailedRevoke_keepsTheError() = vmTest { vms ->
        // `revokeDevice` ends by re-reading the roster; that re-read must not wipe the message
        // it just recorded, or the alert would never be shown.
        val repo = FakeSyncRepository(revokeResult = ResultFailureDto(GenericErrorDto("nope")))
        val vm = viewModel(vms, repo, backgroundScope)

        vm.revokeDevice("ghost-device")
        advanceUntilIdle()

        assertEquals("nope", vm.uiState.value.error)

        vm.clearError()
        assertNull(vm.uiState.value.error, "dismissing the alert clears it")
    }

    // --- ReadmitDevice: the exit for a peer the relay has stopped recognising ---

    @Test
    fun readmitSucceeds_announcesIt_becauseTheEffectIsOnAnotherDevice() = vmTest { vms ->
        val repo = FakeSyncRepository()
        val vm = viewModel(vms, repo, backgroundScope)

        vm.readmitDevice("stranded-phone")
        advanceUntilIdle()

        assertEquals(listOf("stranded-phone"), repo.readmittedDeviceIds, "the op must be called once, with the id")
        assertNull(vm.uiState.value.error, "a successful re-admission is not an error")
        // ⚠ The point of the notice. The repair lands on the OTHER device, on its next tick, so
        // there is nothing on this screen to read the outcome off — a silent success and a button
        // that does nothing are the same thing to the user.
        assertNotNull(vm.uiState.value.notice, "a successful re-admission must say so")
    }

    @Test
    fun readmitThrows_surfacesTheOpsSentence_notTheErrorId() = vmTest { vms ->
        // ⚠⚠ The trap this test exists for. `ReadmitDevice` reports all three of its guards as
        // THROWN TenshuExceptions, and `TenshuException` is `RuntimeException(error.id)` — so
        // reading `thrown.message` renders "tenshu/result/error/generic" at the user and discards
        // the sentence the op attached for exactly this moment. Delete the unwrap in
        // `failureMessage` and this test fails with the id.
        val refusal = "Only a device holding the master identity can readmit another device"
        val repo = FakeSyncRepository(readmitThrows = TenshuException(GenericErrorDto(refusal)))
        val vm = viewModel(vms, repo, backgroundScope)

        vm.readmitDevice("stranded-phone")
        advanceUntilIdle()

        assertEquals(refusal, vm.uiState.value.error, "the op's own explanation must reach the user")
        assertNull(vm.uiState.value.notice, "a refusal is not an announcement")
    }

    @Test
    fun readmitThrowsAPlainException_stillSurfacesSomething() = vmTest { vms ->
        // Transport loss is a bare throw with no typed error behind it — the fallback path.
        val repo = FakeSyncRepository(readmitThrows = IllegalStateException("Proxy not connected"))
        val vm = viewModel(vms, repo, backgroundScope)

        vm.readmitDevice("stranded-phone")
        advanceUntilIdle()

        assertEquals("Proxy not connected", vm.uiState.value.error, "transport loss must not pass silently")
    }

    @Test
    fun readmitReturnsFailure_surfacesTheMessage_andClearsOnDismiss() = vmTest { vms ->
        val repo = FakeSyncRepository(
            readmitResult = ResultFailureDto(GenericErrorDto("No Device row for stranded-phone"))
        )
        val vm = viewModel(vms, repo, backgroundScope)

        vm.readmitDevice("stranded-phone")
        advanceUntilIdle()

        assertEquals("No Device row for stranded-phone", vm.uiState.value.error)
        vm.clearError()
        vm.clearNotice()
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun revokeThrowingATypedError_alsoUnwraps() = vmTest { vms ->
        // The sibling path shares `failureMessage`, so it inherits the unwrap rather than keeping
        // the id-rendering it had before. Pinned so the two cannot drift apart again.
        val repo = FakeSyncRepository(revokeThrows = TenshuException(GenericErrorDto("nothing to revoke")))
        val vm = viewModel(vms, repo, backgroundScope)

        vm.revokeDevice("ghost-device")
        advanceUntilIdle()

        assertEquals("nothing to revoke", vm.uiState.value.error)
        assertTrue(
            vm.uiState.value.error?.startsWith("tenshu/") != true,
            "an error id is not a message a user can act on",
        )
    }
}
