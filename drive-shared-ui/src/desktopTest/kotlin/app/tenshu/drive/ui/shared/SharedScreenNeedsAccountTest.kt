package app.tenshu.drive.ui.shared

import app.tenshu.drive.ui.FakeContactsRepository
import app.tenshu.drive.ui.FakeFilesRepository
import app.tenshu.drive.ui.FakeMembershipRepository
import app.tenshu.drive.ui.screens.shared.SharedScreenViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An accepted folder whose acceptance the relay refused for want of an account (`402`): the member is told to
 * create one, at accept time, instead of being left silently "Invited" — and the notice clears once the queued
 * acceptance goes through (maintainer decision 2026-11-12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedScreenNeedsAccountTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun anAcceptTheRelayRefusesForWantOfAnAccount_saysCreateAnAccount_andClearsOnceItGoesThrough() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val membership = FakeMembershipRepository()
        val vm = vms.own(SharedScreenViewModel(FakeFilesRepository(), FakeContactsRepository(), membership))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.needsAccountToJoin)

        membership.awaitingAccount = setOf("realm-joined") // the acceptance drop answered 402
        vm.onAcceptPendingGrant("grantor-fp")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsAccountToJoin, "told at accept time")

        membership.awaitingAccount = emptySet() // an account exists; the next poll delivered it
        vm.pollPendingGrants()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.needsAccountToJoin, "the notice clears by itself")
    }

    @Test
    fun aBackgroundDelivery_clearsTheNotice_onAnOpenScreen() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val membership = FakeMembershipRepository(awaitingAccount = setOf("realm-joined"))
        val vm = vms.own(SharedScreenViewModel(FakeFilesRepository(), FakeContactsRepository(), membership))
        runCurrent()
        assertTrue(vm.uiState.value.needsAccountToJoin)

        membership.awaitingAccount = emptySet() // Control's poll delivered it; this screen was told nothing
        advanceTimeBy(31_000L)
        runCurrent()

        assertFalse(vm.uiState.value.needsAccountToJoin, "the open screen re-checks while the notice shows")
    }
}
