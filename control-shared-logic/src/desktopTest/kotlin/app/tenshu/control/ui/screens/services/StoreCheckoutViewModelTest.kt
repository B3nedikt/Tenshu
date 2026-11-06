package app.tenshu.control.ui.screens.services

import app.tenshu.control.data.BillingRail
import app.tenshu.control.data.CommerceProducts
import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.data.StoreBilling
import app.tenshu.control.data.StorePurchaseHost
import app.tenshu.control.data.StorePurchaseOutcome
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store rail of [CheckoutViewModel] and the restore in [ServicesViewModel]
 * (`commerce-store-link-out.md` P3): commerce decides what may be bought, the store's sheet
 * takes the money, and only commerce's verification makes the purchase count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreCheckoutViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun ready(offers: List<String>, productId: String? = "tenshu.drive.free", upgradeOn: String? = null) =
        PublisherClient.BillingResult.Ready(
            accountId = "acc-1", productId = productId, billedBy = "Free", validUntil = null,
            offers = offers, upgradeOn = upgradeOn,
        )

    private fun storeVm(
        client: StoreClient,
        status: MutableStateFlow<StorageStatusModel?> = MutableStateFlow(null),
        store: FakeStore? = FakeStore(),
    ) = CheckoutViewModel(client, status, CommerceProducts.BASIC, billingRail = BillingRail.GOOGLE_PLAY, storeBilling = store)

    /** A store whose sheet answers [answer]; records each sheet and the host it was handed. */
    private class FakeStore(var answer: StorePurchaseOutcome = StorePurchaseOutcome.Cancelled) : StoreBilling {
        val sheets = mutableListOf<Pair<String, String>>()
        val hosts = mutableListOf<Any?>()
        override val rail = BillingRail.GOOGLE_PLAY
        override suspend fun purchase(host: StorePurchaseHost, productId: String, accountId: String): StorePurchaseOutcome {
            hosts += host.take()
            sheets += productId to accountId
            return answer
        }
        override suspend fun ownedPurchaseTokens(): List<String>? = emptyList()
        override fun manageSubscriptionsUrl(productId: String?) = ""
    }

    @Test
    fun aStoreBuild_skipsTheWaiver_andAsksForTheStoresSheet() = vmTest { vms ->
        val client = StoreClient(ready(listOf(CommerceProducts.BASIC, CommerceProducts.PREMIUM)))
        val vm = vms.own(storeVm(client))
        advanceUntilIdle()

        assertEquals(CheckoutStep.AwaitingStore, vm.uiState.value.step)
        assertEquals(StorePurchaseRequest(CommerceProducts.BASIC, "acc-1"), vm.uiState.value.storePurchase)
        assertEquals(listOf(BillingRail.GOOGLE_PLAY), client.billingRails)
        assertEquals(0, client.startCheckoutCalls, "a Play build never opens a Stripe checkout")
    }

    @Test
    fun aPlanCommerceDoesNotOffer_isNeverShownInTheStore() = vmTest { vms ->
        val client = StoreClient(ready(offers = emptyList(), productId = CommerceProducts.BASIC, upgradeOn = "Apple"))
        val vm = vms.own(storeVm(client))
        advanceUntilIdle()

        assertEquals(CheckoutStep.Error, vm.uiState.value.step)
        assertNull(vm.uiState.value.storePurchase)
        assertTrue(vm.uiState.value.error!!.contains("Apple"))
    }

    @Test
    fun aPurchase_countsOnlyOnceVerified_andDoneComesFromTheSyncedPlan() = vmTest { vms ->
        val status = MutableStateFlow<StorageStatusModel?>(null)
        val client = StoreClient(ready(listOf(CommerceProducts.BASIC)))
        val store = FakeStore(StorePurchaseOutcome.Purchased("tok-1"))
        val vm = vms.own(storeVm(client, status, store))
        advanceUntilIdle()
        val host = Any()
        vm.launchStorePurchase(host)
        advanceUntilIdle()

        assertEquals(listOf(CommerceProducts.BASIC to "acc-1"), store.sheets)
        assertEquals(listOf<Any?>(host), store.hosts, "the screen's host reaches the sheet")

        assertEquals(listOf("tok-1"), client.verified)
        assertEquals(CheckoutStep.AwaitingConfirmation, vm.uiState.value.step)
        status.value = StorageStatusModel(
            hasStorage = true, usedBytes = 0, limitBytes = CommerceProducts.quotaBytesFor(CommerceProducts.BASIC)!!,
        )
        advanceUntilIdle()
        assertEquals(CheckoutStep.Done, vm.uiState.value.step)
    }

    @Test
    fun anUnconfirmedPurchase_isRetriedByReVerifying_neverByBuyingAgain() = vmTest { vms ->
        val client = StoreClient(ready(listOf(CommerceProducts.BASIC)))
        client.verifyResults += PublisherClient.StoreVerifyResult.Retry("down")
        val store = FakeStore(StorePurchaseOutcome.Purchased("tok-2"))
        val vm = vms.own(storeVm(client, store = store))
        advanceUntilIdle()
        vm.launchStorePurchase(Any())
        advanceUntilIdle()
        assertEquals(CheckoutStep.Error, vm.uiState.value.step)

        vm.start()
        advanceUntilIdle()
        assertEquals(listOf("tok-2", "tok-2"), client.verified)
        assertNull(vm.uiState.value.storePurchase, "no second purchase sheet")
        assertEquals(1, store.sheets.size)
        assertEquals(1, client.billingRails.size)
        assertEquals(CheckoutStep.AwaitingConfirmation, vm.uiState.value.step)
    }

    @Test
    fun aCancelledSheet_verifiesNothing() = vmTest { vms ->
        val client = StoreClient(ready(listOf(CommerceProducts.BASIC)))
        val vm = vms.own(storeVm(client))
        advanceUntilIdle()
        vm.launchStorePurchase(Any())
        advanceUntilIdle()
        assertEquals(CheckoutStep.Error, vm.uiState.value.step)
        assertTrue(client.verified.isEmpty())
    }

    @Test
    fun theSheetOpensOnce_howeverOftenTheScreenHandsItsHostOver() = vmTest { vms ->
        val client = StoreClient(ready(listOf(CommerceProducts.BASIC)))
        val store = FakeStore()
        val vm = vms.own(storeVm(client, store = store))
        advanceUntilIdle()
        vm.launchStorePurchase(Any())
        vm.launchStorePurchase(Any())
        advanceUntilIdle()
        assertEquals(1, store.sheets.size)
    }

    @Test
    fun withoutAHostOrAStore_nothingOpens_andTheScreenSaysSo() = vmTest { vms ->
        val client = StoreClient(ready(listOf(CommerceProducts.BASIC)))
        val vm = vms.own(storeVm(client, store = null))
        advanceUntilIdle()
        vm.launchStorePurchase(Any())
        advanceUntilIdle()
        assertEquals(CheckoutStep.Error, vm.uiState.value.step)
        assertTrue(client.verified.isEmpty())
    }

    @Test
    fun aRealmRefusal_isNamedBeforeAnySheetOpens() = vmTest { vms ->
        val client = StoreClient(
            PublisherClient.BillingResult.Ready(
                accountId = "acc-1", productId = null, billedBy = null, validUntil = null,
                offers = emptyList(), upgradeOn = null, refusal = "subscribed_under_another_account",
            ),
        )
        val vm = vms.own(storeVm(client))
        advanceUntilIdle()
        assertEquals(CheckoutStep.Error, vm.uiState.value.step)
        assertNull(vm.uiState.value.storePurchase)
        assertTrue(vm.uiState.value.error!!.contains("different email"))
    }

    @Test
    fun openingServices_reVerifiesEveryPurchaseTheStoreHolds() = vmTest { vms ->
        val client = StoreClient(ready(emptyList()), signedIn = true)
        val store = object : StoreBilling {
            override val rail = BillingRail.GOOGLE_PLAY
            override suspend fun purchase(host: StorePurchaseHost, productId: String, accountId: String) = StorePurchaseOutcome.Cancelled
            override suspend fun ownedPurchaseTokens() = listOf("tok-a", "tok-b")
            override fun manageSubscriptionsUrl(productId: String?) = ""
        }
        val vm = vms.own(ServicesViewModel(client, MutableStateFlow(null), storeBilling = store))
        vm.onResumed()
        advanceUntilIdle()
        assertEquals(listOf("tok-a", "tok-b"), client.verified)
    }

    private class StoreClient(
        private val billing: PublisherClient.BillingResult,
        signedIn: Boolean = false,
    ) : PublisherClient {
        val billingRails = mutableListOf<BillingRail>()
        val verified = mutableListOf<String>()
        val verifyResults = ArrayDeque<PublisherClient.StoreVerifyResult>()
        var startCheckoutCalls = 0

        override suspend fun billing(rail: BillingRail): PublisherClient.BillingResult {
            billingRails += rail
            return billing
        }

        override suspend fun verifyStorePurchase(rail: BillingRail, purchaseToken: String): PublisherClient.StoreVerifyResult {
            verified += purchaseToken
            return verifyResults.removeFirstOrNull()
                ?: PublisherClient.StoreVerifyResult.Verified(CommerceProducts.BASIC, Long.MAX_VALUE)
        }

        override val account: StateFlow<PublisherAccount?> = MutableStateFlow(
            if (signedIn) PublisherAccount(PublisherAccount.Plan.Free, PublisherAccount.IdentityMethod.Email("a@b.c"), null) else null,
        )
        override fun currentSessionToken(): String? = null
        override suspend fun register(email: String, displayName: String?) = PublisherClient.RegisterResult.OtpSent
        override suspend fun verifyOtp(code: String) = PublisherClient.VerifyResult.Verified
        override suspend fun redeem(code: String) = PublisherClient.RedeemResult.Redeemed
        override suspend fun startCheckout(productId: String, withdrawalAcknowledged: Boolean): PublisherClient.CheckoutResult {
            startCheckoutCalls += 1
            return PublisherClient.CheckoutResult.Unavailable
        }
        override suspend fun openBillingPortal() = PublisherClient.PortalResult.Unavailable
        override suspend fun syncPlan() = PublisherClient.PlanSyncResult.Unavailable
        override suspend fun disconnect() {}
    }
}
