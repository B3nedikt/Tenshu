package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.contact.AwaitInviteAck
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.contact.ContactInvite
import org.tenshu.contact.ContactService
import org.tenshu.contact.InviteAckResult
import org.tenshu.contact.PendingRedemptionInfo
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.awaitInviteAck
import org.tenshu.contact.client.contactService
import org.tenshu.contact.completeContactHandshake
import org.tenshu.contact.createContactInvite
import org.tenshu.contact.inviteId
import org.tenshu.contact.listPendingRedemptions
import org.tenshu.contact.redeemContactInvite
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.util.InviteLinkPayload
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * CI-3 headline proof (`contact-invitation-handshake.md` §12 test plan): the
 * full mutual contact handshake between **two independent users** over the
 * live dev relay with **real crypto** (the `SharedRealmStreamSyncSystemTest`
 * shape — its history shows stub crypto hides curve bugs):
 *
 *   1. Alice and Bob each bootstrap their own realm (own A13, own JWT scope);
 *   2. Alice issues an invite (`POST /sync/invite`, JWT) → link;
 *   3. Bob redeems it (anonymous sealed response into the single slot);
 *   4. Alice's sync-cycle pickup verifies the response (`REDEEMED`), the
 *      completion op accepts → sealed ack posted;
 *   5. Bob's `AwaitInviteAck` verifies the ack (`K_link` + pinned inviter key)
 *      → both sides hold each other's `TenshuIdentity` + `Person`, and the
 *      relay mailbox is freed by `done`.
 */
class ContactHandshakeSystemTest {

    @Test
    fun fullMutualHandshake_overLiveRelay_withRealCrypto(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val bob = system.client("bob", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")

            // 1. Issue (Alice).
            val createResult = alice.tenshuClient.contactService.createContactInvite()
            val link = ((createResult as TResult.Success).value as TString).get()
            val inviteId = InviteLinkPayload.parse(link).inviteIdHex

            // 2. Redeem (Bob, anonymous).
            val redeemResult = bob.tenshuClient.contactService
                .redeemContactInvite(link, selfAssertedName = "Bob")
            assertEquals(
                inviteId,
                ((redeemResult as TResult.Success).value as TString).get(),
            )

            // 2b. Bob's redemption is enumerable for restart resume (the
            // CI-4a `ListPendingRedemptions` read op, ux doc §4.2) — and
            // carries the link-pinned inviter fingerprint, never key material.
            val pendingResult = bob.tenshuClient.contactService.listPendingRedemptions()
            @Suppress("UNCHECKED_CAST")
            val pendingRows =
                ((pendingResult as TResult.Success).value as TList<PendingRedemptionInfo>).toList()
            assertEquals(1, pendingRows.size)
            assertEquals(inviteId, pendingRows.single().inviteId.get())

            // 3. Alice's sync-cycle pickup flips the invite to REDEEMED.
            system.advanceUntil(timeout = 20.seconds) {
                alice.inviteStatus(inviteId) == ContactInvite.Status.REDEEMED
            }

            // 4. Complete (Alice): accept as a new contact.
            val completeResult = alice.tenshuClient.contactService.completeContactHandshake(
                inviteId = inviteId,
                decision = CompleteContactHandshake.Decision.ACCEPT,
                shownFingerprint = alice.inviteClaim(inviteId).orEmpty(),
                newPersonName = "Bob",
                selfAssertedName = "Alice",
            )
            val fingerprint = ((completeResult as TResult.Success).value as TString).get()
            assertNotNull(alice.identityRow(fingerprint), "Alice should hold Bob's identity")

            // 5. Mutual close (Bob). The typed result carries the ack's name
            // claim for the redeem screen's success card.
            val ackHandle = bob.tenshuClient.contactService.awaitInviteAck(inviteId)
            val ackResult = ackHandle.await()
            val ack = (ackResult as TResult.Success).value as InviteAckResult
            assertEquals(AwaitInviteAck.Result.ACCEPTED, ack.status.get())
            assertEquals("Alice", ack.peerName.get())

            // Both sides hold each other's identity rows; Bob's row for Alice
            // carries her self-asserted name, quarantined.
            system.advanceUntil(timeout = 10.seconds) { bob.identityCount() >= 1 }
            val aliceIdentityAtBob = bob.identityRows().firstOrNull {
                it.second == "Alice"
            }
            assertNotNull(aliceIdentityAtBob, "Bob should hold Alice's identity")

            assertEquals(ContactInvite.Status.COMPLETED, alice.inviteStatus(inviteId))

            // The redemption record is cleared by the mutual close — nothing
            // left to resume.
            val drained = bob.tenshuClient.contactService.listPendingRedemptions()
            @Suppress("UNCHECKED_CAST")
            val drainedRows =
                ((drained as TResult.Success).value as TList<PendingRedemptionInfo>).toList()
            assertEquals(0, drainedRows.size)
        } finally {
            system.shutdown()
        }
    }

    private fun TestClient.inviteStatus(inviteId: String): String? =
        readRelation(this, ContactService.Relations.CONTACT_INVITES, ContactInvite.ID)
            .firstOrNull { it.stringField(ContactInvite.Field.INVITE_ID) == inviteId }
            ?.stringField(ContactInvite.Field.STATUS)

    /** The raw claimed fingerprint a completion prompt would display (contact-service v2). */
    private fun TestClient.inviteClaim(inviteId: String): String? =
        readRelation(this, ContactService.Relations.CONTACT_INVITES, ContactInvite.ID)
            .firstOrNull { it.stringField(ContactInvite.Field.INVITE_ID) == inviteId }
            ?.stringField(ContactInvite.Field.CLAIMED_FINGERPRINT)

    private fun TestClient.identityRow(fingerprint: String): Data? =
        readRelation(this, ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.ID)
            .firstOrNull { it.stringField(TenshuIdentity.Field.FINGERPRINT) == fingerprint }

    private fun TestClient.identityCount(): Int =
        readRelation(this, ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.ID).size

    /** `(fingerprint, selfAssertedName)` pairs. */
    private fun TestClient.identityRows(): List<Pair<String, String>> =
        readRelation(this, ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.ID)
            .mapNotNull { data ->
                val fp = data.stringField(TenshuIdentity.Field.FINGERPRINT) ?: return@mapNotNull null
                fp to (data.stringField(TenshuIdentity.Field.SELF_ASSERTED_NAME) ?: "")
            }

    private fun readRelation(
        client: TestClient,
        relationId: String,
        schemaId: String,
    ): List<Data> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = client.executionContext.context,
                type = TList.ID,
                relationId = relationId,
            ),
            isEntity = false,
            elementSchemaId = schemaId,
        )
        return client.executionContext.database.getArrayData(ref)?.toList() ?: emptyList()
    }

    private fun Data.stringField(fieldId: String): String? = when (this) {
        is Entity<*> -> runCatching { getFieldValue<TString>(fieldId).get() }.getOrNull()
        is Composite -> runCatching { getField<TString>(fieldId).get() }.getOrNull()
        else -> null
    }
}
