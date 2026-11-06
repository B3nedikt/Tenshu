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
 * Contract for the §2.3 redeem path + disconnect on the mocked Publisher. Free signup is covered by
 * [MockPublisherClientTest]; this suite covers the redeem (advanced) surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockPublisherClientPhase3bTest {

    @Test
    fun redeem_bindsTierForAnyNonBlankCode() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        val result = client.redeem("any-code-the-user-types")
        assertTrue(result is PublisherClient.RedeemResult.Redeemed)
        assertEquals(PublisherAccount.Plan.Rei, client.account.value?.plan)
    }

    @Test
    fun redeem_rejectsBlankCode() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        val result = client.redeem("")
        assertTrue(result is PublisherClient.RedeemResult.Invalid)
    }

    @Test
    fun disconnect_clearsAccount() = runTest {
        val client = MockPublisherClient(InMemorySecureStorageDelegate())
        client.register(email = "alice@example.com", displayName = null)
        client.verifyOtp("123456")
        val account = client.account.value
        assertNotNull(account)
        assertEquals(PublisherAccount.Plan.Free, account.plan)

        client.disconnect()
        assertNull(client.account.value)
    }
}
