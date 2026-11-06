package org.tenshu.systemtest

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.contact.ContactService
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.awaitInviteAck
import org.tenshu.contact.client.contactService
import org.tenshu.contact.completeContactHandshake
import org.tenshu.contact.createContactInvite
import org.tenshu.contact.redeemContactInvite
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.util.InviteLinkPayload
import org.tenshu.core.common.util.sha256
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.notifications.Notification
import org.tenshu.core.toTString
import org.tenshu.identity.Member
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.identity.client.identityService
import org.tenshu.identity.getRealmMembers
import org.tenshu.sync.acceptSharedRealmGrant
import org.tenshu.sync.client.syncService
import org.tenshu.sync.createSharedRealm
import org.tenshu.sync.grantSharedRealmMembership
import org.tenshu.sync.pollPendingGrants
import org.tenshu.sync.proxy.RealmRegistryHolder
import org.tenshu.sync.removeSharedRealmMember
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * GR-7 headline proof (`shared-realm-membership-grant.md` §6 phase-map GR-7 row): **Kr
 * rotation on member removal** between three independent users over the live dev relay
 * with real crypto.
 *
 *   1. Alice handshakes with both Bob and Charlie; creates a shared realm; grants both;
 *      both accept (their `Member` rows sync back as the acceptance signal);
 *   2. Alice writes content **C1** into the realm under the original `Kr`; Charlie pulls it;
 *   3. Alice **removes Bob** — which rotates `Kr` → `Kr′`, retires the old key into the
 *      owner's keyring, and drops a **re-key** for Charlie on the grant mailbox;
 *   4. Alice writes content **C2** under `Kr′`;
 *   5. Charlie polls → silently adopts `Kr′` (no quarantine card); his next pull delivers
 *      **C2** and, with the retired key in his keyring, he still holds **C1**;
 *   6. Bob — revoked + never re-keyed — never receives **C2** (the cryptographic cutoff
 *      that closes T-GR-02).
 *
 * The metadata-plane trial-decrypt mechanism itself is unit-proven in
 * `RetainedKeyringTrialDecryptTest`; this test proves the end-to-end rotation +
 * re-key delivery + member-cutoff over a real relay.
 */
@OptIn(ExperimentalStdlibApi::class)
class SharedRealmKrRotationSystemTest {

    @Test
    fun removeMember_rotatesKr_remainingMemberReKeys_removedMemberCutOff(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val bob = system.client("bob", TestClientRole.RealmHost)
            val charlie = system.client("charlie", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")
            charlie.bootstrapAsHost(deviceName = "Charlie Tablet")

            // 1. Handshakes: Alice ↔ Bob and Alice ↔ Charlie.
            completeHandshake(system, alice, bob, otherName = "Bob")
            completeHandshake(system, alice, charlie, otherName = "Charlie")
            val bobFingerprint = bob.ownFingerprint()
            val charlieFingerprint = charlie.ownFingerprint()
            val aliceFingerprint = alice.ownFingerprint()
            val bobPersonId = alice.personIdFor(bobFingerprint)
            val charliePersonId = alice.personIdFor(charlieFingerprint)

            // 2. Create the realm + grant both.
            val realmId = (alice.tenshuClient.syncService.createSharedRealm()
                .successValue() as TString).get()
            val bobAuthHex = (alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, bobPersonId, label = "Trip").successValue()).get()
            alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, charliePersonId, label = "Trip").successValue()

            // Both accept (poll discovers the quarantined grant, then accept consumes it).
            bob.tenshuClient.syncService.pollPendingGrants()
            charlie.tenshuClient.syncService.pollPendingGrants()
            bob.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            charlie.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()

            val bobMemberId = sha256(bobAuthHex.hexToByteArray()).toHexString()
            system.advanceUntil(timeout = 25.seconds) {
                val roster = (alice.tenshuClient.identityService.getRealmMembers(realmId)
                    .successValue()).toList()
                roster.size >= 3 // owner + Bob + Charlie
            }

            // Capture Charlie's pre-rotation wrapped Kr to prove it changes on re-key.
            val charlieKrBefore = charlie.membershipWrappedKey(realmId)
            assertNotNull(charlieKrBefore, "Charlie holds a membership row after accepting")

            // 2b. Alice writes C1 under the original Kr; Charlie pulls it.
            alice.writeRealmNote(realmId, "c1")
            system.advanceUntil(timeout = 25.seconds) { charlie.hasRealmNote(realmId, "c1") }
            assertTrue(charlie.hasRealmNote(realmId, "c1"), "Charlie receives C1 under the original Kr")

            // 3. Alice removes Bob — rotates Kr → Kr′ + re-keys Charlie.
            alice.tenshuClient.syncService.removeSharedRealmMember(realmId, bobMemberId).successValue()

            // 4. Alice writes C2 under Kr′.
            alice.writeRealmNote(realmId, "c2")

            // 5. Charlie polls → silently adopts Kr′ (no PendingGrant card surfaces).
            val charliePending = (charlie.tenshuClient.syncService.pollPendingGrants()
                .successValue()).toList()
            assertTrue(
                charliePending.isEmpty(),
                "a re-key auto-applies — it must NOT surface as a pending-grant card",
            )

            // Charlie's wrapped Kr changed (rotated) and his keyring retained the old key.
            val charlieKrAfter = charlie.membershipWrappedKey(realmId)
            assertNotNull(charlieKrAfter)
            assertFalse(
                charlieKrBefore.contentEquals(charlieKrAfter),
                "Charlie's membership Kr rotated to Kr′",
            )
            assertTrue(
                charlie.realmKeyringSize(realmId) >= 1,
                "Charlie retained the old Kr in his keyring (trial-decrypt for pre-rotation content)",
            )
            assertTrue(
                alice.realmKeyringSize(realmId) >= 1,
                "Alice (owner) retained the old Kr in her keyring",
            )

            // Charlie still holds C1 (pre-rotation content stays readable) and now gets C2.
            system.advanceUntil(timeout = 25.seconds) { charlie.hasRealmNote(realmId, "c2") }
            assertTrue(charlie.hasRealmNote(realmId, "c2"), "Charlie receives C2 under the rotated Kr′")
            assertTrue(charlie.hasRealmNote(realmId, "c1"), "Charlie still reads C1 after the rotation")

            // 6. Bob — revoked + never re-keyed — must never receive C2. Charlie already
            // pulled C2 above (so it propagated through the relay) and Bob's sync has had
            // that whole window to run; a short extra drain gives Bob every fair chance to
            // (fail to) pull it, then assert it never landed (relay-purged + no Kr′).
            delay(3.seconds)
            assertNull(
                bob.realmNote(realmId, "c2"),
                "the removed member never receives content written under Kr′ (T-GR-02 closed)",
            )
        } finally {
            system.shutdown()
        }
    }

    // ---- realm content (a Notification keyed "$realmId::name" routes into the realm) --

    private fun TestClient.writeRealmNote(realmId: String, name: String) {
        val key = "$realmId::note-$name"
        val note = executionContext.context.create<Notification>(
            type = Notification.ID,
            fields = listOf(key.toTString(executionContext.context), UnixInstantDto(0L)),
        )
        executionContext.saveData(note)
    }

    private fun TestClient.realmNote(realmId: String, name: String): Data? =
        executionContext.database.getData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = Notification.ID,
                keyValue = "$realmId::note-$name",
            ),
        )

    private fun TestClient.hasRealmNote(realmId: String, name: String): Boolean =
        realmNote(realmId, name) != null

    // ---- realm key state (white-box, via the proxy's RealmRegistry) ------------------

    private fun TestClient.membershipWrappedKey(realmId: String): ByteArray? {
        val row = executionContext.database.getData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = SharedRealmMembership.ID,
                keyValue = realmId,
            ),
        ) as? Composite ?: return null
        return runCatching { row.getField<TByteArray>(SharedRealmMembership.Fields.WRAPPED_MASTER_KEY).get() }
            .getOrNull()
    }

    private fun TestClient.realmKeyringSize(realmId: String): Int =
        koinApp.koin.get<RealmRegistryHolder>().realmRegistry
            ?.runtimeFor(realmId)?.context?.historicalDerivedKeys?.size ?: 0

    // ---- identity helpers (mirrors GrantSharedRealmSystemTest) ----------------------

    private fun TestClient.ownFingerprint(): String =
        sha256(syncManager.getRealmEncryptionKeyPair()!!.publicKey).toHexString()

    private fun TestClient.personIdFor(fingerprint: String): String =
        readRelation(ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.ID)
            .first { it.stringField(TenshuIdentity.Field.FINGERPRINT) == fingerprint }
            .stringField(TenshuIdentity.Field.PERSON_ID)!!

    private suspend fun completeHandshake(
        system: TestSystem,
        alice: TestClient,
        other: TestClient,
        otherName: String,
    ) {
        val link = (alice.tenshuClient.contactService.createContactInvite().successValue() as TString).get()
        val inviteId = InviteLinkPayload.parse(link).inviteIdHex
        other.tenshuClient.contactService.redeemContactInvite(link, selfAssertedName = otherName)
        system.advanceUntil(timeout = 20.seconds) {
            alice.inviteStatus(inviteId) == org.tenshu.contact.ContactInvite.Status.REDEEMED
        }
        alice.tenshuClient.contactService.completeContactHandshake(
            inviteId = inviteId,
            decision = CompleteContactHandshake.Decision.ACCEPT,
            shownFingerprint = alice.inviteClaim(inviteId).orEmpty(),
            newPersonName = otherName,
            selfAssertedName = "Alice",
        )
        other.tenshuClient.contactService.awaitInviteAck(inviteId).await()
        system.advanceUntil(timeout = 10.seconds) { other.identityRowsCount() > 0 }
    }

    private fun TestClient.inviteStatus(inviteId: String): String? =
        readRelation(ContactService.Relations.CONTACT_INVITES, org.tenshu.contact.ContactInvite.ID)
            .firstOrNull { it.stringField(org.tenshu.contact.ContactInvite.Field.INVITE_ID) == inviteId }
            ?.stringField(org.tenshu.contact.ContactInvite.Field.STATUS)

    /** The raw claimed fingerprint a completion prompt would display (contact-service v2). */
    private fun TestClient.inviteClaim(inviteId: String): String? =
        readRelation(ContactService.Relations.CONTACT_INVITES, org.tenshu.contact.ContactInvite.ID)
            .firstOrNull { it.stringField(org.tenshu.contact.ContactInvite.Field.INVITE_ID) == inviteId }
            ?.stringField(org.tenshu.contact.ContactInvite.Field.CLAIMED_FINGERPRINT)

    private fun TestClient.identityRowsCount(): Int =
        readRelation(ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.ID).size

    private fun TestClient.readRelation(relationId: String, schemaId: String): List<Data> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = TList.ID,
                relationId = relationId,
            ),
            isEntity = false,
            elementSchemaId = schemaId,
        )
        return executionContext.database.getArrayData(ref)?.toList() ?: emptyList()
    }

    private fun Data.stringField(fieldId: String): String? = when (this) {
        is Entity<*> -> runCatching { getFieldValue<TString>(fieldId).get() }.getOrNull()
        is Composite -> runCatching { getField<TString>(fieldId).get() }.getOrNull()
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Data?> TResult<T>.successValue(): T = (this as TResult.Success).value as T
}
