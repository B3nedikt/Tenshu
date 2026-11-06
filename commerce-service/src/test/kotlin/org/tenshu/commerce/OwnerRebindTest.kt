package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.store.InMemoryCommerceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The §3.4 recovery path: after losing the recovery code the user re-installs with a *new*
 * `ownerId` and signs in again (`register` → `verify`). The supersede re-points the live owner to
 * the new `ownerId`; the old one is dead. This is the Stripe-path "Restore Purchases".
 */
class OwnerRebindTest {

    private val store = InMemoryCommerceStore()
    private val sender = RecordingEmailSender()

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        application { module(store = store, emailSender = sender) }
        block(client)
    }

    @Test
    fun signingInWithANewOwner_supersedesTheOldBinding() = appTest { client ->
        client.registerAndVerify(sender, "frank@example.com", ownerId = "owner-old")
        val account = store.findAccountByEmail("frank@example.com")!!
        assertEquals(account.id, store.accountIdForOwner("owner-old"))

        // Recovery code lost → new identity, same email.
        client.registerAndVerify(sender, "frank@example.com", ownerId = "owner-new")

        assertEquals(account.id, store.findAccountByEmail("frank@example.com")!!.id, "the same account, not a second one")
        assertEquals("owner-new", store.liveOwner(account.id))
        assertEquals(account.id, store.accountIdForOwner("owner-new"))
        assertNull(store.accountIdForOwner("owner-old"), "the superseded owner is dead")
    }

    @Test
    fun rebind_keepsTheEntitlementForTheNewOwner() = appTest { client ->
        client.registerAndVerify(sender, "grace@example.com", ownerId = "owner-old")
        client.registerAndVerify(sender, "grace@example.com", ownerId = "owner-new")

        val account = store.findAccountByEmail("grace@example.com")!!
        // The current entitlement is intact and now bound to owner-new — purchase preserved.
        assertNotNull(store.currentEntitlement(account.id, System.currentTimeMillis()))
        assertEquals("owner-new", store.liveOwner(account.id))
    }
}
