package app.tenshu.control.ui.screens.services

import app.tenshu.control.data.CommerceProducts
import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.tenshu.viewmodel.test.vmTest

/**
 * The pure [activeSyncedStorage] decision (storage-entitlement-sync.md) plus the
 * [ServicesViewModel.openBillingPortal] result mapping (§6 manage-subscription).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServicesViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun freeAccount() = PublisherAccount(
        plan = PublisherAccount.Plan.Free,
        identityMethod = PublisherAccount.IdentityMethod.Email("a@b.c"),
        displayName = null,
    )

    @Test
    fun signingInDoesNotChangeTheFigures() {
        // ⚠⚠ The CE-4 regression, stated as a test. The card used to have two rival sources and
        // the synced one was suppressed whenever a local account existed, so signing in REPLACED
        // live figures with hardcoded literals. Same status in, same numbers out — whether or not
        // this device holds a session.
        val status = StorageStatusModel(
            hasStorage = true,
            usedBytes = 3L * 1024 * 1024 * 1024,
            limitBytes = CommerceProducts.PREMIUM_LIMIT_BYTES,
        )
        val signedOut = servicesCardState(account = null, status = status)
        val signedIn = servicesCardState(account = freeAccount(), status = status)

        assertEquals(signedOut.storage, signedIn.storage)
        assertEquals(SyncedPlanTier.Premium, signedIn.storage?.plan)
        assertEquals(3L * 1024 * 1024 * 1024, signedIn.storage?.usedBytes)
    }

    @Test
    fun identityIsPresentOnlyWhenSignedInHere() {
        val status = StorageStatusModel(hasStorage = true, usedBytes = 0L, limitBytes = 1_000L)
        assertNull(servicesCardState(account = null, status = status).identity)
        assertEquals(false, servicesCardState(account = null, status = status).signedInHere)

        val signedIn = servicesCardState(account = freeAccount(), status = status)
        assertEquals(PublisherAccount.IdentityMethod.Email("a@b.c"), signedIn.identity)
        assertEquals(true, signedIn.signedInHere)
    }

    @Test
    fun signedInBeforeTheEntitlementSyncs_hasIdentityButNoFigures() {
        // The seconds after a successful signup or purchase. The card must say "setting up"
        // rather than render a 0-byte gauge, which reads as "you have no storage".
        val state = servicesCardState(account = freeAccount(), status = null)
        assertNull(state.storage)
        assertEquals(true, state.signedInHere)
    }

    @Test
    fun noAccountAndNoStatus_isTheEmptyState() {
        val state = servicesCardState(account = null, status = null)
        assertNull(state.storage)
        assertNull(state.identity)
    }

    @Test
    fun inactiveStorageCountsAsNoStorage() {
        assertNull(fundedStorage(StorageStatusModel(hasStorage = false, usedBytes = 0L, limitBytes = 0L)))
    }

    @Test
    fun activeStorageProjectsFiguresAndTier() {
        // A 2 KiB limit isn't a real tier constant → falls back to Free (never over-claims).
        assertEquals(
            SyncedStorage(usedBytes = 512L, limitBytes = 2_048L, plan = SyncedPlanTier.Free),
            fundedStorage(StorageStatusModel(hasStorage = true, usedBytes = 512L, limitBytes = 2_048L)),
        )
    }

    @Test
    fun planTierForLimit_mapsEachTierAndFallsBackToFree() {
        assertEquals(SyncedPlanTier.Premium, planTierForLimit(CommerceProducts.PREMIUM_LIMIT_BYTES))
        assertEquals(SyncedPlanTier.Basic, planTierForLimit(CommerceProducts.BASIC_LIMIT_BYTES))
        // The Free quota + any unrecognised value both resolve to Free.
        assertEquals(SyncedPlanTier.Free, planTierForLimit(5L * 1024 * 1024 * 1024))
        assertEquals(SyncedPlanTier.Free, planTierForLimit(0L))
        assertEquals(SyncedPlanTier.Free, planTierForLimit(123L))
    }

    @Test
    fun paidTierIsReportedFromTheQuota() {
        val synced = fundedStorage(
            StorageStatusModel(
                hasStorage = true,
                usedBytes = 0L,
                limitBytes = CommerceProducts.PREMIUM_LIMIT_BYTES,
            ),
        )
        assertEquals(SyncedPlanTier.Premium, synced?.plan)
    }

    // ── openBillingPortal ─────────────────────────────────────────────────────

    @Test
    fun openBillingPortal_ready_setsPortalUrl_thenClears() = vmTest { vms ->
        val vm = vms.own(ServicesViewModel(
            FakePublisherClient(PublisherClient.PortalResult.Ready("https://stripe.test/portal/abc")),
            flowOf(null),
        ))
        vm.openBillingPortal()
        advanceUntilIdle()

        assertEquals("https://stripe.test/portal/abc", vm.portalUrl.value)
        assertNull(vm.portalNotice.value)
        assertEquals(false, vm.portalBusy.value)

        vm.onPortalUrlOpened()
        assertNull(vm.portalUrl.value)
    }

    @Test
    fun openBillingPortal_noSubscription_showsNotice_noUrl() = vmTest { vms ->
        val vm = vms.own(ServicesViewModel(
            FakePublisherClient(PublisherClient.PortalResult.NoSubscription),
            flowOf(null),
        ))
        vm.openBillingPortal()
        advanceUntilIdle()

        assertNull(vm.portalUrl.value)
        assertNotNull(vm.portalNotice.value)
    }

    @Test
    fun openBillingPortal_error_surfacesMessageAsNotice() = vmTest { vms ->
        val vm = vms.own(ServicesViewModel(
            FakePublisherClient(PublisherClient.PortalResult.Error("boom")),
            flowOf(null),
        ))
        vm.openBillingPortal()
        advanceUntilIdle()

        assertEquals("boom", vm.portalNotice.value)
        assertNull(vm.portalUrl.value)
    }

    // ── the plan is fetched when the screen opens ─────────────────────────────

    /**
     * ⚠⚠ Nothing else brings a purchase to the device: the webhook writes commerce's ledger, and
     * the account-driven writer skips a record that already says Free. A buyer who closed the
     * browser sees their plan because opening Services asks for it.
     */
    @Test
    fun aSignedInDevice_asksCommerceForItsPlan_whenTheScreenOpens() = vmTest { vms ->
        val client = FakePublisherClient(PublisherClient.PortalResult.Unavailable, account = freeAccount())
        val vm = vms.own(ServicesViewModel(client, MutableStateFlow(null)))
        vm.onResumed()
        advanceUntilIdle()

        assertEquals(1, client.syncPlanCalls)
    }

    /**
     * The billing portal changes the plan while this view model stays alive and the account stays
     * signed in — so "once per sign-in" would show the old plan until its record expired.
     */
    @Test
    fun comingBackFromTheBrowser_asksAgain() = vmTest { vms ->
        val client = FakePublisherClient(PublisherClient.PortalResult.Unavailable, account = freeAccount())
        val vm = vms.own(ServicesViewModel(client, MutableStateFlow(null)))
        vm.onResumed()
        advanceUntilIdle()
        vm.onResumed()
        advanceUntilIdle()

        assertEquals(2, client.syncPlanCalls)
    }

    @Test
    fun aSignedOutDevice_asksNothing_untilItSignsIn() = vmTest { vms ->
        val client = FakePublisherClient(PublisherClient.PortalResult.Unavailable)
        val vm = vms.own(ServicesViewModel(client, MutableStateFlow(null)))
        vm.onResumed()
        advanceUntilIdle()
        assertEquals(0, client.syncPlanCalls)

        client.accountState.value = freeAccount()
        advanceUntilIdle()
        assertEquals(1, client.syncPlanCalls)
    }

    private class FakePublisherClient(
        private val portalResult: PublisherClient.PortalResult,
        account: PublisherAccount? = null,
    ) : PublisherClient {
        val accountState = MutableStateFlow(account)
        var syncPlanCalls = 0
            private set
        override val account: StateFlow<PublisherAccount?> = accountState
        override suspend fun syncPlan(): PublisherClient.PlanSyncResult {
            syncPlanCalls += 1
            return PublisherClient.PlanSyncResult.Unavailable
        }
        override fun currentSessionToken(): String? = null
        override suspend fun register(email: String, displayName: String?) =
            PublisherClient.RegisterResult.OtpSent
        override suspend fun verifyOtp(code: String) = PublisherClient.VerifyResult.Verified
        override suspend fun redeem(code: String) = PublisherClient.RedeemResult.Redeemed
        override suspend fun startCheckout(productId: String, withdrawalAcknowledged: Boolean) =
            PublisherClient.CheckoutResult.CompletedLocally
        override suspend fun openBillingPortal() = portalResult
        override suspend fun disconnect() {}
    }
}
