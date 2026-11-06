package app.tenshu.control.ui.screens.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import app.tenshu.control.data.BillingRail
import app.tenshu.control.data.CommerceProducts
import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.data.StoreBilling
import app.tenshu.control.data.StorePurchaseHost
import app.tenshu.control.data.StorePurchaseOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Test
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.closeBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real [CheckoutScreen] over the real [CheckoutViewModel] on a store rail, with a store sheet
 * that stays open until the test answers it — the shape of a person paying in Play's sheet.
 *
 * ⚠⚠ The defect this pins: the screen used to run the purchase inside
 * `if (storePurchase != null) { LaunchedEffect { clear the one-shot; purchase(...) } }`. Clearing
 * the one-shot recomposed the screen, the branch left composition, and the effect — the purchase —
 * was cancelled while the sheet was up: a paid answer was lost and the screen spun forever. Here
 * that reads as the sheet's call being cancelled and nothing ever reaching commerce.
 */
@OptIn(ExperimentalTestApi::class)
class CheckoutStorePurchaseScreenTest {

    private val viewModels = OwnedViewModels()

    @After
    fun tearDown() = viewModels.closeBlocking()

    /** A store sheet the test answers; records whether its call was cancelled under it. */
    private class HeldSheet : StoreBilling {
        val answer = CompletableDeferred<StorePurchaseOutcome>()
        @Volatile var opened = 0
        @Volatile var cancelled = false
        override val rail = BillingRail.GOOGLE_PLAY
        override suspend fun purchase(host: StorePurchaseHost, productId: String, accountId: String): StorePurchaseOutcome {
            check(host.take() != null) { "the screen's host reaches the sheet" }
            opened += 1
            return try {
                answer.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        override suspend fun ownedPurchaseTokens(): List<String>? = emptyList()
        override fun manageSubscriptionsUrl(productId: String?) = ""
    }

    private class Commerce : PublisherClient {
        val verified = CopyOnWriteArrayList<String>()
        override suspend fun billing(rail: BillingRail) = PublisherClient.BillingResult.Ready(
            accountId = "acc-1", productId = null, billedBy = null, validUntil = null,
            offers = listOf(CommerceProducts.BASIC), upgradeOn = null,
        )
        override suspend fun verifyStorePurchase(rail: BillingRail, purchaseToken: String): PublisherClient.StoreVerifyResult {
            verified += purchaseToken
            return PublisherClient.StoreVerifyResult.Verified(CommerceProducts.BASIC, Long.MAX_VALUE)
        }
        override val account: StateFlow<PublisherAccount?> = MutableStateFlow(null)
        override fun currentSessionToken(): String? = "session"
        override suspend fun register(email: String, displayName: String?) = PublisherClient.RegisterResult.OtpSent
        override suspend fun verifyOtp(code: String) = PublisherClient.VerifyResult.Verified
        override suspend fun redeem(code: String) = PublisherClient.RedeemResult.Redeemed
        override suspend fun startCheckout(productId: String, withdrawalAcknowledged: Boolean) =
            PublisherClient.CheckoutResult.Unavailable
        override suspend fun openBillingPortal() = PublisherClient.PortalResult.Unavailable
        override suspend fun syncPlan() = PublisherClient.PlanSyncResult.Unavailable
        override suspend fun disconnect() {}
    }

    private fun storeVm(commerce: Commerce, sheet: HeldSheet) = viewModels.own(
        CheckoutViewModel(
            publisherClient = commerce,
            storageStatusFlow = MutableStateFlow(null),
            productId = CommerceProducts.BASIC,
            planPollIntervalMs = 60_000,
            billingRail = BillingRail.GOOGLE_PLAY,
            storeBilling = sheet,
        ),
    )

    @Test
    fun aPurchaseInTheSheet_survivesTheScreensOwnRecomposition_andReachesCommerce() = runComposeUiTest {
        val commerce = Commerce()
        val sheet = HeldSheet()
        val vm = storeVm(commerce, sheet)
        setContent { CheckoutScreen(viewModel = vm, onDone = {}, onCancel = {}, storeHost = Any()) }

        waitUntil(timeoutMillis = 10_000) { sheet.opened == 1 }
        // Let every recomposition the hand-over causes run while the sheet is still up.
        waitForIdle()
        assertTrue(!sheet.cancelled, "the purchase was cancelled under the open sheet")

        sheet.answer.complete(StorePurchaseOutcome.Purchased("tok-paid"))
        waitUntil(timeoutMillis = 10_000) { commerce.verified.isNotEmpty() }
        waitUntil(timeoutMillis = 10_000) { vm.uiState.value.step == CheckoutStep.AwaitingConfirmation }
        assertEquals(listOf("tok-paid"), commerce.verified.toList())
        assertEquals(1, sheet.opened, "one sheet, never a second")
    }

    @Test
    fun aPurchaseInTheSheet_survivesTheScreenLeavingComposition() = runComposeUiTest {
        // A rotation tears the screen down while Play's sheet is in front; the VM survives it.
        val commerce = Commerce()
        val sheet = HeldSheet()
        val vm = storeVm(commerce, sheet)
        var shown by mutableStateOf(true)
        setContent {
            if (shown) CheckoutScreen(viewModel = vm, onDone = {}, onCancel = {}, storeHost = Any())
        }
        waitUntil(timeoutMillis = 10_000) { sheet.opened == 1 }
        shown = false
        waitForIdle()

        sheet.answer.complete(StorePurchaseOutcome.Purchased("tok-rotated"))
        waitUntil(timeoutMillis = 10_000) { commerce.verified.isNotEmpty() }
        assertTrue(!sheet.cancelled)

        shown = true
        waitForIdle()
        assertEquals(1, sheet.opened, "coming back does not open the sheet again")
    }
}
