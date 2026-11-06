@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.tenshu.contact.TenshuIdentity
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keyring half of the address book against **real** `TenshuIdentity` rows (run-A review gap):
 * `ContactsAddressBookTest` could only reach the unknown-key failures, because nothing in the jsTest
 * runtime created a key. Here a key is seeded straight through the proxy's [JsRuntime.executionContext]
 * — the same `saveData` a sync merge lands through — and every op that reads or moves one is driven
 * through the published `Contacts` surface.
 *
 * ⚠ Same isolation rule as the sibling suite: one shared runtime, so every row is named uniquely.
 */
class ContactsKeyringTest : SdkRuntimeTest() {

    private fun stamp() = "${Date.now().toLong()}-${(0..99999).random()}"

    private suspend fun create(name: String): String {
        val created = Contacts.createContact(name).await()
        assertEquals(true, created.ok, "createContact must succeed; got ${JSON.stringify(created)}")
        return created.personId as String
    }

    private suspend fun contactNow(personId: String): dynamic {
        val result = Contacts.contact(personId).await()
        assertEquals(true, result.ok, "contact() must succeed; got ${JSON.stringify(result)}")
        return result.contact
    }

    /** Seeds one key for [personId] at [verification]; returns its fingerprint. */
    private suspend fun seedKey(personId: String, verification: String, name: String = "Mira"): String {
        TenshuSdk.awaitReady().await()
        val fingerprint = "fp-${stamp()}"
        val ec = JsRuntime.executionContext
        ec.saveData(
            TenshuIdentity.create(
                context = ec.context,
                fingerprint = fingerprint,
                publicKey = ByteArray(32) { (it * 7).toByte() },
                personId = personId,
                selfAssertedName = name,
                verification = verification,
                verifiedAt = 0L,
                source = TenshuIdentity.Source.INVITE_LINK,
                // ⚠ No grant seed (a pre-GR-3 key): a grantable contact gives the sync scheduler a
                // grant mailbox to poll, and that background work raced the shared runtime's
                // cursors under `JsSyncCursorDurabilityTest`. Nothing here needs grantability.
            ),
        )
        // The client relation reads through the proxy; wait until the row is visible.
        var i = 0
        while (i < 100 && contactNow(personId)?.identities?.unsafeCast<Array<dynamic>>()
                ?.none { it.fingerprint == fingerprint } != false
        ) {
            delay(20); i++
        }
        return fingerprint
    }

    private fun keyring(contact: dynamic): Array<dynamic> = contact.identities.unsafeCast<Array<dynamic>>()

    @Test
    fun aSeededKey_projectsIntoTheRow_withItsClaimAndRung() = GlobalScope.promise {
        val personId = create("Keyed ${stamp()}")
        val fp = seedKey(personId, TenshuIdentity.Verification.UNVERIFIED, name = "Self-claimed")
        val row = contactNow(personId)
        assertEquals("unverified", row.trust)
        val identity = keyring(row).single()
        assertEquals(fp, identity.fingerprint)
        assertEquals("Self-claimed", identity.selfAssertedName, "the claim is carried, quarantined")
        assertEquals("UNVERIFIED", identity.verification)
        assertEquals("INVITE_LINK", identity.source)
    }

    @Test
    fun setVerified_changesOnce_thenIsANoOp() = GlobalScope.promise {
        val personId = create("Verify ${stamp()}")
        val fp = seedKey(personId, TenshuIdentity.Verification.UNVERIFIED)

        val first = Contacts.setVerified(fp, true).await()
        assertEquals(true, first.ok, "got ${JSON.stringify(first)}")
        assertEquals(true, first.changed)
        val again = Contacts.setVerified(fp, true).await()
        assertEquals(true, again.ok)
        assertEquals(false, again.changed, "already VERIFIED: a visible no-op, not a second write")

        val row = contactNow(personId)
        assertEquals("verified", row.trust)
        assertEquals("VERIFIED", keyring(row).single().verification)
        assertTrue((keyring(row).single().verifiedAtMillis as Double) > 0.0, "elevation stamps verifiedAt")

        val demoted = Contacts.setVerified(fp, false).await()
        assertEquals(true, demoted.changed)
        assertEquals("unverified", contactNow(personId).trust)
    }

    @Test
    fun setVerifiedOnAnAttestedKey_isANoOp_inBothDirections() = GlobalScope.promise {
        val personId = create("Attested ${stamp()}")
        val fp = seedKey(personId, TenshuIdentity.Verification.ATTESTED)
        assertEquals("verified", contactNow(personId).trust, "ATTESTED-only is verified (Q2b)")

        for (verified in listOf(true, false)) {
            val result = Contacts.setVerified(fp, verified).await()
            assertEquals(true, result.ok)
            assertEquals(false, result.changed, "a local verdict may neither claim nor overwrite ATTESTED")
        }
        assertEquals("ATTESTED", keyring(contactNow(personId)).single().verification)
    }

    @Test
    fun aMixedKeyring_needsReverification_andConnectedContactsDoesNotCheckmarkIt() = GlobalScope.promise {
        val mixed = create("Mixed ${stamp()}")
        seedKey(mixed, TenshuIdentity.Verification.VERIFIED)
        seedKey(mixed, TenshuIdentity.Verification.UNVERIFIED)
        val attested = create("Attested only ${stamp()}")
        seedKey(attested, TenshuIdentity.Verification.ATTESTED)

        assertEquals("needsReverification", contactNow(mixed).trust)
        assertEquals("verified", contactNow(attested).trust)

        val connected = Contacts.connectedContacts().await()
        assertEquals(false, connected.first { it.personId == mixed }.verified, "mixed ≠ verified (Q2b)")
        assertEquals(true, connected.first { it.personId == attested }.verified, "ATTESTED-only = verified (Q2b)")
    }

    @Test
    fun relinkIdentity_movesTheKey_andItsVerificationStaysOnTheKey() = GlobalScope.promise {
        val s = stamp()
        val from = create("Relink from $s")
        val to = create("Relink to $s")
        val fp = seedKey(from, TenshuIdentity.Verification.VERIFIED)

        val moved = Contacts.relinkIdentity(fp, to).await()
        assertEquals(true, moved.ok, "got ${JSON.stringify(moved)}")
        assertEquals(true, moved.changed)

        assertEquals("notConnected", contactNow(from).trust, "the key left its old contact")
        val target = contactNow(to)
        assertEquals(fp, keyring(target).single().fingerprint)
        assertEquals("VERIFIED", keyring(target).single().verification, "verification moves with the key")
    }

    @Test
    fun unlinkIdentity_dropsTheKey_andTheContactStays() = GlobalScope.promise {
        val personId = create("Unlink ${stamp()}")
        val fp = seedKey(personId, TenshuIdentity.Verification.UNVERIFIED)

        val result = Contacts.unlinkIdentity(fp).await()
        assertEquals(true, result.ok, "got ${JSON.stringify(result)}")
        assertEquals(true, result.changed)
        val row = contactNow(personId)
        assertNotNull(row, "unlinking a key never deletes the contact")
        assertEquals(0, keyring(row).size)
        assertEquals("notConnected", row.trust)
    }

    @Test
    fun merge_movesTheSourcesKeysOntoTheTarget() = GlobalScope.promise {
        val s = stamp()
        val source = create("Merge source $s")
        val target = create("Merge target $s")
        val fp = seedKey(source, TenshuIdentity.Verification.VERIFIED)

        val merged = Contacts.mergeContacts(source, target).await()
        assertEquals(true, merged.ok, "got ${JSON.stringify(merged)}")
        assertNull(contactNow(source))
        val row = contactNow(target)
        assertEquals(listOf(fp), keyring(row).map { it.fingerprint as String })
        assertEquals("verified", row.trust, "a moved key keeps its own rung")
    }
}
