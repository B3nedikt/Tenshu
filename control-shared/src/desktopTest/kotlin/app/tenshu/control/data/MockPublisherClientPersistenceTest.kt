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
 * Mock-account persistence: a verified signup survives an app restart by replaying through the
 * [org.tenshu.core.common.delegate.SecureStorageDelegate] shared between the prior and fresh
 * `MockPublisherClient` instances.
 *
 * The real client persists its own session record (see [CommercePublisherClient]); this pins the
 * mock's stand-in behaviour so the UX story — "I signed up, I closed the app, my account is still
 * bound when I reopen" — is verified.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockPublisherClientPersistenceTest {

    @Test
    fun freshClientLoadsPreviouslyBoundFreeAccount() = runTest {
        val delegate = InMemorySecureStorageDelegate()

        val first = MockPublisherClient(delegate)
        first.register("test@example.com", displayName = "Trying It")
        first.verifyOtp("123456")
        assertNotNull(first.account.value)

        val second = MockPublisherClient(delegate)
        val rehydrated = second.account.value
        assertNotNull(rehydrated, "Fresh client should rehydrate the persisted account.")
        assertEquals(PublisherAccount.Plan.Free, rehydrated.plan)
        val email = rehydrated.identityMethod as? PublisherAccount.IdentityMethod.Email
        assertEquals("test@example.com", email?.address)
        assertEquals("Trying It", rehydrated.displayName)
        // A rehydrated account implies a signed-in session for the entitlement clients.
        assertNotNull(second.currentSessionToken())
    }

    @Test
    fun disconnectPersists_freshClientStartsClean() = runTest {
        val delegate = InMemorySecureStorageDelegate()

        val first = MockPublisherClient(delegate)
        first.register("test@example.com", displayName = null)
        first.verifyOtp("123456")
        first.disconnect()
        assertNull(first.account.value)

        val second = MockPublisherClient(delegate)
        assertNull(second.account.value, "After disconnect, a fresh client must start clean.")
    }

    @Test
    fun corruptedStorage_doesNotCrashAndStartsClean() = runTest {
        val delegate = InMemorySecureStorageDelegate()
        delegate.storeSecret(MockPublisherClient.STORAGE_KEY, "{ not valid JSON")

        val client = MockPublisherClient(delegate)
        assertNull(client.account.value)
        // The garbage entry should have been evicted so it doesn't poison future loads.
        assertTrue(delegate.getSecret(MockPublisherClient.STORAGE_KEY) == null)
    }
}
