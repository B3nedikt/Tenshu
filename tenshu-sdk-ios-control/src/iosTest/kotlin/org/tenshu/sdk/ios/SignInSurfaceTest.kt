package org.tenshu.sdk.ios

import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.ui.screens.services.FreeAccountSignupStep
import app.tenshu.control.ui.screens.services.FreeAccountSignupUiState
import app.tenshu.control.ui.screens.services.FreeAccountSignupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The iOS half of S2-pre: the email-OTP sign-in seam
 * (`ios-services-status-and-purchase.md` §3.6). What is worth pinning here is **not** the state
 * machine — that is `FreeAccountSignupViewModel`, shared with Compose and tested there — but the
 * three things the iOS bridge adds: the flattening SwiftUI reads, the not-connected default, and
 * the fact that the surface hands out the real view model rather than a stub.
 *
 * ⚠ `Dispatchers.setMain` is not ceremony here: `viewModelScope` is `Main.immediate` on K/N, so a
 * `launch` would otherwise run against the real main loop that no test drives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInSurfaceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ---- The flatteners SwiftUI switches on ----------------------------------------------

    /**
     * ⚠ A `switch` in Swift over these keys has a `default:` arm, so a renamed or missing key does
     * not fail to compile — it silently falls through to an empty view. The keys are therefore
     * load-bearing strings, pinned here rather than trusted.
     */
    @Test
    fun stepKeys_cover_every_step() = vmTest(dispatcher) { vms ->
        val vm = vms.own(FreeAccountSignupViewModel(RecordingPublisherClient()))
        assertEquals("form", ControlSurface.signInStepKey(vm.uiState.value))

        assertEquals(
            setOf("form", "code", "done"),
            setOf(
                ControlSurface.signInStepKey(FreeAccountSignupUiState()),
                ControlSurface.signInStepKey(
                    FreeAccountSignupUiState(step = FreeAccountSignupStep.EnterCode),
                ),
                ControlSurface.signInStepKey(
                    FreeAccountSignupUiState(step = FreeAccountSignupStep.Done),
                ),
            ),
        )
    }

    /**
     * The CTA gate must agree with the view model's own guard. `onVerify` returns silently on a
     * short code, so a button enabled ahead of it would do nothing when tapped — the failure that
     * reads as a broken app rather than as validation.
     */
    @Test
    fun canVerify_agrees_with_the_view_models_own_guard() = vmTest(dispatcher) { vms ->
        val client = RecordingPublisherClient()
        val vm = vms.own(FreeAccountSignupViewModel(client))

        vm.onCodeChange("1234")
        assertFalse(ControlSurface.signInCanVerify(vm.uiState.value), "a short code must not enable the CTA")
        vm.onVerify()
        advanceUntilIdle()
        assertEquals(0, client.verifyCalls, "the view model itself refuses a short code")

        vm.onCodeChange("123456")
        assertTrue(ControlSurface.signInCanVerify(vm.uiState.value))
        vm.onVerify()
        advanceUntilIdle()
        assertEquals(1, client.verifyCalls)
    }

    /** The field is digits-only and capped, so a paste of a longer string cannot arm the CTA. */
    @Test
    fun canVerify_is_false_for_a_code_the_field_would_reject() = vmTest(dispatcher) { vms ->
        val vm = vms.own(FreeAccountSignupViewModel(RecordingPublisherClient()))
        vm.onCodeChange("12a4b6")
        assertEquals("1246", vm.uiState.value.code)
        assertFalse(ControlSurface.signInCanVerify(vm.uiState.value))
    }

    // ---- The not-connected default -------------------------------------------------------

    /**
     * ⚠⚠ The invariant of `NotConnectedControlRepositories`, applied to commerce: with no runtime
     * installed, **nothing reports success**. A `RegisterResult.OtpSent` here would tell the user
     * an email is on its way that nobody sent, and they would then wait for a code that cannot
     * arrive — the quiet failure this whole family exists to prevent.
     */
    @Test
    fun notConnectedClient_never_reports_success() = runTest(dispatcher) {
        val client = NotConnectedPublisherClient()

        val registered = client.register("someone@example.com", null)
        assertTrue(registered is PublisherClient.RegisterResult.Error, "got $registered")
        assertTrue(client.verifyOtp("123456") is PublisherClient.VerifyResult.Error)
        assertTrue(client.redeem("code") is PublisherClient.RedeemResult.Error)
        assertTrue(
            client.startCheckout("tenshu.drive.basic", withdrawalAcknowledged = true)
                is PublisherClient.CheckoutResult.Error,
        )
        assertTrue(client.openBillingPortal() is PublisherClient.PortalResult.Error)

        // Truthful, not fabricated: with no runtime nothing is bound on this device.
        assertNull(client.account.value)
        assertNull(client.currentSessionToken())
    }

    /**
     * ⚠ The error must be a *returned* result, not a throw. `FreeAccountSignupViewModel` consumes
     * these in a bare `viewModelScope.launch` with no `try`, so a throwing client would escape
     * uncaught instead of reaching the screen — this test would then fail on the exception rather
     * than on the assertion, which is exactly the signal wanted.
     */
    @Test
    fun notConnected_surfaces_as_an_error_the_screen_can_render() = vmTest(dispatcher) { vms ->
        val vm = vms.own(FreeAccountSignupViewModel(NotConnectedPublisherClient()))
        vm.onEmailChange("someone@example.com")
        vm.onSubmit()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("form", ControlSurface.signInStepKey(state), "a failed register must not advance")
        assertNotNull(state.error, "the failure must be visible, not silent")
        assertFalse(state.busy, "the spinner must clear on failure")
    }

    // ---- The surface hands out the real thing --------------------------------------------

    /**
     * ⚠ A fresh instance per call, so a cancelled sign-in cannot leave a typed email or a pending
     * OTP behind the next one.
     */
    @Test
    fun signInViewModel_is_not_shared_between_screens() = vmTest(dispatcher) { vms ->
        val first = vms.own(ControlSurface.signInViewModel())
        first.onEmailChange("someone@example.com")

        val second = vms.own(ControlSurface.signInViewModel())
        assertEquals("", second.uiState.value.email)
    }
}

/** Counts calls so a guard can be shown to hold in the view model, not only in the flattener. */
private class RecordingPublisherClient : PublisherClient {
    var verifyCalls = 0
        private set

    override val account: StateFlow<PublisherAccount?> = MutableStateFlow(null)
    override fun currentSessionToken(): String? = null

    override suspend fun register(email: String, displayName: String?) =
        PublisherClient.RegisterResult.OtpSent

    override suspend fun verifyOtp(code: String): PublisherClient.VerifyResult {
        verifyCalls += 1
        return PublisherClient.VerifyResult.Verified
    }

    override suspend fun redeem(code: String) = PublisherClient.RedeemResult.Redeemed
    override suspend fun startCheckout(productId: String, withdrawalAcknowledged: Boolean) =
        PublisherClient.CheckoutResult.Unavailable

    override suspend fun openBillingPortal() = PublisherClient.PortalResult.Unavailable
    override suspend fun syncPlan() = PublisherClient.PlanSyncResult.SignedOut
    override suspend fun disconnect() = Unit
}
