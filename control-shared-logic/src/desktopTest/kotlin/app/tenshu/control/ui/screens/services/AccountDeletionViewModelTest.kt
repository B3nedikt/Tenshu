package app.tenshu.control.ui.screens.services

import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.data.PublisherClient.AccountDeletionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The in-app account-deletion flow (`commerce-accounts-and-sessions.md` §9.7): send a code, type it,
 * acknowledge, delete. ⚠ Every refusal asserts that **no confirmation reached the client** — a VM
 * that showed the right error while still calling delete would pass a state-only test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun theHappyPath_sendsACode_thenDeletes() = vmTest { vms ->
        val client = FakeDeletionClient()
        val vm = vms.own(AccountDeletionViewModel(client))
        assertEquals("ada@example.com", vm.uiState.value.email)
        assertEquals(AccountDeletionStep.Explain, vm.uiState.value.step)

        vm.onSendCode()
        advanceUntilIdle()
        assertEquals(AccountDeletionStep.EnterCode, vm.uiState.value.step)
        assertEquals(1, client.requests)

        vm.onCodeChange("12 34-56")
        assertEquals("123456", vm.uiState.value.code, "digits only")
        vm.onAcknowledgedChange(true)
        vm.onConfirm()
        advanceUntilIdle()

        assertEquals(AccountDeletionStep.Deleted, vm.uiState.value.step)
        assertEquals(listOf("123456"), client.confirmations)
    }

    @Test
    fun withoutTheAcknowledgement_nothingIsDeleted() = vmTest { vms ->
        val client = FakeDeletionClient()
        val vm = vms.own(AccountDeletionViewModel(client))
        vm.onSendCode()
        advanceUntilIdle()
        vm.onCodeChange("123456")

        assertFalse(vm.uiState.value.canConfirm)
        vm.onConfirm()
        advanceUntilIdle()

        assertTrue(client.confirmations.isEmpty(), "the tick gates the VM, not only the button")
        assertEquals(AccountDeletionStep.EnterCode, vm.uiState.value.step)
    }

    @Test
    fun anIncompleteCodeIsNotSent() = vmTest { vms ->
        val client = FakeDeletionClient()
        val vm = vms.own(AccountDeletionViewModel(client))
        vm.onSendCode()
        advanceUntilIdle()
        vm.onCodeChange("123")
        vm.onAcknowledgedChange(true)
        vm.onConfirm()
        advanceUntilIdle()

        assertTrue(client.confirmations.isEmpty())
    }

    @Test
    fun confirmingIsImpossibleBeforeACodeWasSent() = vmTest { vms ->
        val client = FakeDeletionClient()
        val vm = vms.own(AccountDeletionViewModel(client))
        vm.onCodeChange("123456")
        vm.onAcknowledgedChange(true)
        vm.onConfirm()
        advanceUntilIdle()

        assertTrue(client.confirmations.isEmpty())
    }

    @Test
    fun aWrongCodeStaysOnTheCodeStep() = vmTest { vms ->
        val client = FakeDeletionClient(confirmResult = AccountDeletionResult.InvalidCode)
        val vm = vms.own(AccountDeletionViewModel(client))
        vm.onSendCode()
        advanceUntilIdle()
        vm.onCodeChange("000000")
        vm.onAcknowledgedChange(true)
        vm.onConfirm()
        advanceUntilIdle()

        assertEquals(AccountDeletionStep.EnterCode, vm.uiState.value.step)
        assertEquals(AccountDeletionError.InvalidCode, vm.uiState.value.error)
        // Typing again clears the error.
        vm.onCodeChange("111111")
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun aDeadSessionSaysSignInAndStaysPut() = vmTest { vms ->
        val client = FakeDeletionClient(requestResult = AccountDeletionResult.SignInRequired)
        val vm = vms.own(AccountDeletionViewModel(client))
        vm.onSendCode()
        advanceUntilIdle()

        assertEquals(AccountDeletionStep.Explain, vm.uiState.value.step)
        assertEquals(AccountDeletionError.SignInRequired, vm.uiState.value.error)
    }

    @Test
    fun aLostConfirmReadsAsSignedOut_notAsAWrongCode() = vmTest { vms ->
        val client = FakeDeletionClient(confirmResult = AccountDeletionResult.SignedOut)
        val vm = vms.own(AccountDeletionViewModel(client))
        vm.onSendCode()
        advanceUntilIdle()
        vm.onCodeChange("123456")
        vm.onAcknowledgedChange(true)
        vm.onConfirm()
        advanceUntilIdle()

        assertEquals(AccountDeletionError.SignedOut, vm.uiState.value.error)
    }

    @Test
    fun aResendIsAnnounced() = vmTest { vms ->
        val client = FakeDeletionClient()
        val vm = vms.own(AccountDeletionViewModel(client))
        vm.onSendCode()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.resent)
        vm.onSendCode()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.resent)
        assertEquals(2, client.requests)
    }

    @Test
    fun aClientMessageIsShownAsIs() = vmTest { vms ->
        val client = FakeDeletionClient(requestResult = AccountDeletionResult.Error("Wait an hour."))
        val vm = vms.own(AccountDeletionViewModel(client))
        vm.onSendCode()
        advanceUntilIdle()

        assertEquals(AccountDeletionError.Message("Wait an hour."), vm.uiState.value.error)
    }
}

private class FakeDeletionClient(
    private val requestResult: AccountDeletionResult = AccountDeletionResult.CodeSent,
    private val confirmResult: AccountDeletionResult = AccountDeletionResult.Deleted,
) : PublisherClient {
    var requests = 0
        private set
    val confirmations = mutableListOf<String>()

    override val account: StateFlow<PublisherAccount?> = MutableStateFlow(
        PublisherAccount(
            plan = PublisherAccount.Plan.Free,
            identityMethod = PublisherAccount.IdentityMethod.Email("ada@example.com"),
            displayName = null,
        ),
    )
    override fun currentSessionToken(): String? = "session"
    override suspend fun register(email: String, displayName: String?) = error("unused")
    override suspend fun verifyOtp(code: String) = error("unused")
    override suspend fun redeem(code: String) = error("unused")
    override suspend fun startCheckout(productId: String, withdrawalAcknowledged: Boolean) = error("unused")
    override suspend fun openBillingPortal() = error("unused")
    override suspend fun syncPlan() = error("unused")
    override suspend fun disconnect() = Unit

    override suspend fun requestAccountDeletion(): AccountDeletionResult {
        requests++
        return requestResult
    }

    override suspend fun confirmAccountDeletion(code: String): AccountDeletionResult {
        confirmations += code
        return confirmResult
    }
}
