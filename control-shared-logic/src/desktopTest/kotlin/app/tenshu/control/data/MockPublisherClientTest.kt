package app.tenshu.control.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [PublisherClient] contract on the mock (`commerce-accounts-and-sessions.md` §3.2, Phase 3b):
 * register → OTP verify binds a Free account + opens a session; a non-6-digit code is rejected;
 * redeem binds the Rei (Basic-quota) tier; disconnect clears everything.
 *
 * Real commerce integration is covered by [CommercePublisherClientTest] (MockEngine); this pins the
 * dev / `TENSHU_BILLING_MOCK` stand-in's behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockPublisherClientTest {

    @Test
    fun startsWithNoAccountOrSession() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        assertNull(client.account.value)
        assertNull(client.currentSessionToken())
    }

    @Test
    fun registerThenVerify_bindsFreeAccountAndSession() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        val sent = client.register("user@example.com", displayName = "Trying It")
        assertTrue(sent is PublisherClient.RegisterResult.OtpSent)
        // Not verified yet → no account / session.
        assertNull(client.account.value)

        val verified = client.verifyOtp("123456")
        assertTrue(verified is PublisherClient.VerifyResult.Verified)

        val account = client.account.value
        assertNotNull(account)
        assertEquals(PublisherAccount.Plan.Free, account.plan)
        val email = account.identityMethod as? PublisherAccount.IdentityMethod.Email
        assertEquals("user@example.com", email?.address)
        assertEquals("Trying It", account.displayName)
        assertNotNull(client.currentSessionToken())
    }

    @Test
    fun register_rejectsBlankEmail() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        val result = client.register("", displayName = null)
        assertTrue(result is PublisherClient.RegisterResult.Error)
        assertNull(client.account.value)
    }

    @Test
    fun register_rejectsMalformedEmail() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        val result = client.register("not-an-email", displayName = null)
        assertTrue(result is PublisherClient.RegisterResult.Error)
    }

    @Test
    fun verify_rejectsNonSixDigitCode() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        client.register("user@example.com", displayName = null)
        assertTrue(client.verifyOtp("12") is PublisherClient.VerifyResult.InvalidCode)
        assertTrue(client.verifyOtp("abcdef") is PublisherClient.VerifyResult.InvalidCode)
        assertNull(client.account.value)
    }

    @Test
    fun verifyBeforeRegister_errorsCleanly() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        assertTrue(client.verifyOtp("123456") is PublisherClient.VerifyResult.Error)
    }

    @Test
    fun redeem_bindsReiTier() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        val result = client.redeem("FOUNDER-1234")
        assertTrue(result is PublisherClient.RedeemResult.Redeemed)
        assertEquals(PublisherAccount.Plan.Rei, client.account.value?.plan)
    }

    @Test
    fun redeem_rejectsBlankCode() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        assertTrue(client.redeem("   ".trim()) is PublisherClient.RedeemResult.Invalid)
        assertNull(client.account.value)
    }

    @Test
    fun startCheckout_bindsPaidTierLocally() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        val result = client.startCheckout(CommerceProducts.PREMIUM, withdrawalAcknowledged = true)
        assertTrue(result is PublisherClient.CheckoutResult.CompletedLocally)
        // ⚠ The mock binds identity + Plan only; the quota now comes from the synced entitlement
        // its dev-key writer mints (CE-4 / D5), so there is no local limit to assert here.
        assertEquals(PublisherAccount.Plan.Rei, client.account.value?.plan)
    }

    @Test
    fun startCheckout_rejectsUnknownProduct() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        assertTrue(client.startCheckout("tenshu.drive.bogus", withdrawalAcknowledged = true) is PublisherClient.CheckoutResult.Error)
        assertNull(client.account.value)
    }

    @Test
    fun openBillingPortal_isUnavailableOnMock() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        assertTrue(client.openBillingPortal() is PublisherClient.PortalResult.Unavailable)
    }

    @Test
    fun disconnect_clearsAccountAndSession() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        client.register("user@example.com", displayName = null)
        client.verifyOtp("123456")
        assertNotNull(client.account.value)

        client.disconnect()
        assertNull(client.account.value)
        assertNull(client.currentSessionToken())
    }
}
