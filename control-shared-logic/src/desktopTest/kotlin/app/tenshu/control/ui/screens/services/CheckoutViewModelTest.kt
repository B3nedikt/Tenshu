package app.tenshu.control.ui.screens.services

import app.tenshu.control.data.CommerceProducts
import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The two-layer confirmation contract of [CheckoutViewModel] (`commerce-accounts-and-sessions.md`
 * §9.5): a real checkout goes Acknowledge → Launching → AwaitingConfirmation and only reaches Done
 * when the *synced* quota reaches the purchased tier — never on a browser return. The mock's local
 * grant short-circuits to Done. Unknown / unavailable plans surface as errors.
 *
 * The Acknowledge step is the immediate-performance waiver (policy §5.4); it gates every path to
 * the client, which is why each test here has to pass through it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun status(hasStorage: Boolean, limitBytes: Long) =
        StorageStatusModel(hasStorage = hasStorage, usedBytes = 0L, limitBytes = limitBytes)

    /** Tick the acknowledgement and continue — what the screen's checkbox + button do. */
    private fun CheckoutViewModel.acknowledgeAndContinue() {
        onAcknowledgementChanged(true)
        confirmAcknowledgement()
    }

    @Test
    fun startsOnTheAcknowledgement_withoutTouchingTheClient() = vmTest { vms ->
        val client = FakePublisherClient(PublisherClient.CheckoutResult.Ready("https://stripe.test/x"))
        val vm = vms.own(CheckoutViewModel(client, MutableStateFlow(null), CommerceProducts.BASIC))
        advanceUntilIdle()

        assertEquals(CheckoutStep.Acknowledge, vm.uiState.value.step)
        assertFalse(vm.uiState.value.acknowledged)
        // ⚠ The point of the gate: no checkout may be opened before the waiver is given, so the
        // service is never asked for one that it would (correctly) refuse.
        assertEquals(0, client.startCheckoutCalls)
    }

    @Test
    fun continuingWithoutTheTick_doesNothing() = vmTest { vms ->
        val client = FakePublisherClient(PublisherClient.CheckoutResult.Ready("https://stripe.test/x"))
        val vm = vms.own(CheckoutViewModel(client, MutableStateFlow(null), CommerceProducts.BASIC))
        vm.confirmAcknowledgement()
        advanceUntilIdle()

        assertEquals(CheckoutStep.Acknowledge, vm.uiState.value.step)
        assertEquals(0, client.startCheckoutCalls)
    }

    @Test
    fun theAcknowledgementIsForwardedToTheService() = vmTest { vms ->
        // The waiver is only real because the *server* records it — so it has to reach the request.
        val client = FakePublisherClient(PublisherClient.CheckoutResult.Ready("https://stripe.test/x"))
        val vm = vms.own(CheckoutViewModel(client, MutableStateFlow(null), CommerceProducts.BASIC))
        vm.acknowledgeAndContinue()
        advanceUntilIdle()

        assertEquals(1, client.startCheckoutCalls)
        assertTrue(client.lastAcknowledged == true)
    }

    @Test
    fun ready_awaitsBrowser_thenConfirmsWhenSyncedQuotaArrives() = vmTest { vms ->
        // Start on Free (5 GiB) — below the Basic target, so it must not confirm early.
        val statusFlow = MutableStateFlow<StorageStatusModel?>(status(hasStorage = true, limitBytes = 5L * 1024 * 1024 * 1024))
        val client = FakePublisherClient(PublisherClient.CheckoutResult.Ready("https://stripe.test/session/abc"))
        val vm = vms.own(CheckoutViewModel(client, statusFlow, CommerceProducts.BASIC))
        vm.acknowledgeAndContinue()
        advanceUntilIdle()

        assertEquals(CheckoutStep.AwaitingConfirmation, vm.uiState.value.step)
        assertEquals("https://stripe.test/session/abc", vm.uiState.value.launchUrl)

        // The webhook grants Basic server-side → the synced quota lands on this device.
        statusFlow.value = status(hasStorage = true, limitBytes = CommerceProducts.BASIC_LIMIT_BYTES)
        advanceUntilIdle()

        assertEquals(CheckoutStep.Done, vm.uiState.value.step)
        assertNull(vm.uiState.value.launchUrl)
    }

    /**
     * ⚠⚠ The screen used to only WATCH the synced record, and nothing wrote it: the device has to
     * ask commerce for the plan. Without the asking, a paid customer sat on this screen until the
     * entitlement writer's next renewal tick.
     */
    @Test
    fun whileTheCustomerIsInTheBrowser_thePlanIsFetched_untilItArrives() = vmTest { vms ->
        val statusFlow = MutableStateFlow<StorageStatusModel?>(status(hasStorage = true, limitBytes = 5L * 1024 * 1024 * 1024))
        val client = FakePublisherClient(PublisherClient.CheckoutResult.Ready("https://stripe.test/x"))
        val vm = vms.own(
            CheckoutViewModel(client, statusFlow, CommerceProducts.BASIC, planPollIntervalMs = 1_000, planPollAttempts = 50),
        )
        vm.acknowledgeAndContinue()
        advanceTimeBy(3_500)
        assertEquals(3, client.syncPlanCalls)

        statusFlow.value = status(hasStorage = true, limitBytes = CommerceProducts.BASIC_LIMIT_BYTES)
        advanceUntilIdle()

        assertEquals(CheckoutStep.Done, vm.uiState.value.step)
        // It stops asking once the plan is here.
        assertEquals(3, client.syncPlanCalls)
    }

    /** A customer who abandons the browser must not leave a request loop running. */
    @Test
    fun theFetchingIsBounded() = vmTest { vms ->
        val client = FakePublisherClient(PublisherClient.CheckoutResult.Ready("https://stripe.test/x"))
        val vm = vms.own(
            CheckoutViewModel(client, MutableStateFlow(null), CommerceProducts.BASIC, planPollIntervalMs = 1_000, planPollAttempts = 4),
        )
        vm.acknowledgeAndContinue()
        advanceUntilIdle()

        assertEquals(4, client.syncPlanCalls)
        assertEquals(CheckoutStep.AwaitingConfirmation, vm.uiState.value.step)
    }

    /**
     * ⚠⚠ Android suspends a backgrounded app, so nothing is asked while the customer pays; and the
     * window is bounded, so a payment finishing after it found nobody asking and left the screen
     * waiting forever. Coming back asks at once and opens the window again.
     */
    @Test
    fun comingBackFromTheBrowser_asksAtOnce_evenAfterTheWindowRanOut() = vmTest { vms ->
        val client = FakePublisherClient(PublisherClient.CheckoutResult.Ready("https://stripe.test/x"))
        val vm = vms.own(
            CheckoutViewModel(client, MutableStateFlow(null), CommerceProducts.BASIC, planPollIntervalMs = 1_000, planPollAttempts = 2),
        )
        vm.acknowledgeAndContinue()
        advanceUntilIdle()
        assertEquals(2, client.syncPlanCalls)

        vm.onResumed()
        runCurrent()
        assertEquals(3, client.syncPlanCalls)
        advanceUntilIdle()
        assertEquals(5, client.syncPlanCalls)
    }

    @Test
    fun resuming_beforeAnyCheckoutStarted_asksNothing() = vmTest { vms ->
        val client = FakePublisherClient(PublisherClient.CheckoutResult.Ready("https://stripe.test/x"))
        val vm = vms.own(CheckoutViewModel(client, MutableStateFlow(null), CommerceProducts.BASIC))
        vm.onResumed()
        advanceUntilIdle()

        assertEquals(0, client.syncPlanCalls)
    }

    @Test
    fun aRefusedCheckout_saysWhy_andFetchesNothing() = vmTest { vms ->
        for (refusal in listOf(
            PublisherClient.CheckoutResult.SignInRequired,
            PublisherClient.CheckoutResult.AlreadySubscribed,
            PublisherClient.CheckoutResult.PlanChangeRequired,
            PublisherClient.CheckoutResult.SubscribedUnderAnotherAccount,
        )) {
            val client = FakePublisherClient(refusal)
            val vm = vms.own(CheckoutViewModel(client, MutableStateFlow(null), CommerceProducts.BASIC))
            vm.acknowledgeAndContinue()
            advanceUntilIdle()

            assertEquals(CheckoutStep.Error, vm.uiState.value.step, refusal.toString())
            assertTrue(!vm.uiState.value.error.isNullOrBlank(), refusal.toString())
            assertEquals(0, client.syncPlanCalls, refusal.toString())
        }
    }

    @Test
    fun completedLocally_isDoneWithoutBrowser() = vmTest { vms ->
        val vm = vms.own(CheckoutViewModel(
            FakePublisherClient(PublisherClient.CheckoutResult.CompletedLocally),
            MutableStateFlow(null),
            CommerceProducts.BASIC,
        ))
        vm.acknowledgeAndContinue()
        advanceUntilIdle()

        assertEquals(CheckoutStep.Done, vm.uiState.value.step)
        assertNull(vm.uiState.value.launchUrl)
    }

    @Test
    fun unavailable_surfacesError() = vmTest { vms ->
        val vm = vms.own(CheckoutViewModel(
            FakePublisherClient(PublisherClient.CheckoutResult.Unavailable),
            MutableStateFlow(null),
            CommerceProducts.PREMIUM,
        ))
        vm.acknowledgeAndContinue()
        advanceUntilIdle()

        assertEquals(CheckoutStep.Error, vm.uiState.value.step)
        assertTrue(vm.uiState.value.error != null)
    }

    @Test
    fun unknownProduct_errorsWithoutCallingTheClient() = vmTest { vms ->
        // Would go AwaitingConfirmation if the client were consulted — an unknown id must not be.
        val client = FakePublisherClient(PublisherClient.CheckoutResult.Ready("https://stripe.test/x"))
        val vm = vms.own(CheckoutViewModel(client, MutableStateFlow(null), "tenshu.drive.bogus"))
        vm.acknowledgeAndContinue()
        advanceUntilIdle()

        assertEquals(CheckoutStep.Error, vm.uiState.value.step)
        assertEquals(0, client.startCheckoutCalls)
    }

    @Test
    fun onLaunchUrlOpened_clearsTheOneShot() = vmTest { vms ->
        val vm = vms.own(CheckoutViewModel(
            FakePublisherClient(PublisherClient.CheckoutResult.Ready("https://stripe.test/x")),
            MutableStateFlow(status(hasStorage = false, limitBytes = 0L)),
            CommerceProducts.BASIC,
        ))
        vm.acknowledgeAndContinue()
        advanceUntilIdle()
        assertEquals("https://stripe.test/x", vm.uiState.value.launchUrl)

        vm.onLaunchUrlOpened()
        assertNull(vm.uiState.value.launchUrl)
    }

    private class FakePublisherClient(
        private val checkoutResult: PublisherClient.CheckoutResult,
    ) : PublisherClient {
        var startCheckoutCalls = 0
            private set
        var lastAcknowledged: Boolean? = null
            private set
        var syncPlanCalls = 0
            private set

        override suspend fun syncPlan(): PublisherClient.PlanSyncResult {
            syncPlanCalls += 1
            return PublisherClient.PlanSyncResult.Unavailable
        }

        override val account: StateFlow<PublisherAccount?> = MutableStateFlow(null)
        override fun currentSessionToken(): String? = null
        override suspend fun register(email: String, displayName: String?) =
            PublisherClient.RegisterResult.OtpSent
        override suspend fun verifyOtp(code: String) = PublisherClient.VerifyResult.Verified
        override suspend fun redeem(code: String) = PublisherClient.RedeemResult.Redeemed
        override suspend fun startCheckout(
            productId: String,
            withdrawalAcknowledged: Boolean,
        ): PublisherClient.CheckoutResult {
            startCheckoutCalls += 1
            lastAcknowledged = withdrawalAcknowledged
            return checkoutResult
        }
        override suspend fun openBillingPortal() = PublisherClient.PortalResult.Unavailable
        override suspend fun disconnect() {}
    }
}
