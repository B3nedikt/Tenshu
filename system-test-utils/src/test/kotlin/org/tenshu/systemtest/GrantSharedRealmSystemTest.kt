package org.tenshu.systemtest

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
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.util.InviteLinkPayload
import org.tenshu.core.common.util.sha256
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.identity.Member
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * GR-3 headline proof (`shared-realm-membership-grant.md` §6 phase map, §10): the full
 * cross-user membership grant between **two independent users** over the live dev relay
 * with **real crypto** — the integration-risk concentration the proposal flags.
 *
 *   1. Alice and Bob complete a contact handshake (both hold each other's identity +
 *      the persisted grant seed, GR-3 D8);
 *   2. Alice creates a shared realm and grants Bob membership (mints A26, signs the
 *      capability, wraps Kr to Bob's A13, drops the sealed grant);
 *   3. Bob accepts (picks up + unseals, persists A26 + membership, registers the realm
 *      with a fresh per-realm device key, admits that device, writes his `Member` row);
 *   4. Bob's `Member` row syncs back into the realm — the acceptance signal Alice sees;
 *   5. Alice removes Bob (master-signed revocation; the relay purges his device).
 */
@OptIn(ExperimentalStdlibApi::class)
class GrantSharedRealmSystemTest {

    @Test
    fun grantAcceptSyncRemove_overLiveRelay_withRealCrypto(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val bob = system.client("bob", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")

            // 1. Complete a contact handshake so both sides persist the grant seed.
            completeHandshake(system, alice, bob)
            val bobFingerprint = alice.identityRows().single().first
            val aliceFingerprint = bob.identityRows().single().first
            val bobPersonId = alice.identityRow(bobFingerprint)!!
                .stringField(TenshuIdentity.Field.PERSON_ID)!!

            // 2. Alice creates a shared realm + grants Bob.
            val createResult = alice.tenshuClient.syncService.createSharedRealm()
            val realmId = ((createResult as TResult.Success).value as TString).get()

            val grantResult = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, bobPersonId, label = "Holiday photos")
            val authorityPublicKeyHex = ((grantResult as TResult.Success).value!!).get()
            assertTrue(authorityPublicKeyHex.isNotEmpty(), "grant returns the minted A26 public key")

            // 3. Bob polls (GR-6) — the inbound poll discovers + quarantines the grant
            // as a PendingGrant before he accepts it (D4 explicit-accept).
            val pollResult = bob.tenshuClient.syncService.pollPendingGrants()
            val pending = ((pollResult as TResult.Success).value!!).toList()
            assertEquals(1, pending.size, "poll discovers Alice's pending grant")

            // 3b. Bob accepts — consumes the stored PendingGrant (no second mailbox pickup).
            val acceptResult = bob.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint)
            assertEquals(realmId, ((acceptResult as TResult.Success).value!!).get(), "Bob joins the granted realm")

            // 4. Bob's Member row syncs back into the realm — Alice sees him as a member.
            val bobMemberId = sha256(authorityPublicKeyHex.hexToByteArray()).toHexString()
            system.advanceUntil(timeout = 25.seconds) {
                alice.memberRow(realmId, bobMemberId) != null
            }
            assertNotNull(alice.memberRow(realmId, bobMemberId), "Bob's Member record synced to the owner")
            assertEquals(
                Member.Role.MEMBER,
                alice.memberRow(realmId, bobMemberId)!!.stringField(Member.Fields.ROLE),
            )

            // 4b. getRealmMembers returns the roster: the owner (self-backfilled) + Bob.
            val rosterResult = alice.tenshuClient.identityService.getRealmMembers(realmId)
            val roster = ((rosterResult as TResult.Success).value!!).toList()
            val rosterIds = roster.map { (it as Member).memberId.get() }.toSet()
            assertTrue(bobMemberId in rosterIds, "getRealmMembers includes the new member")
            assertEquals(2, roster.size, "roster = owner + Bob")

            // 5. GR-6 durable owner custody: drop Alice's in-memory realm runtime to
            // simulate a fresh session (the master key is no longer in RAM), then remove
            // Bob by his memberId. removeMember must rehydrate the realm master from the
            // persisted OwnedRealmMaster row and resolve the A26 pub from the
            // GrantedMembership roster — the same-session-only limit GR-6 closes.
            alice.koinApp.koin.get<RealmRegistryHolder>().realmRegistry?.unregister(realmId)
            val removeResult = alice.tenshuClient.syncService
                .removeSharedRealmMember(realmId, bobMemberId)
            assertTrue(
                removeResult is TResult.Success,
                "removal rehydrates the master key cross-session + posts a master-signed revocation",
            )
        } finally {
            system.shutdown()
        }
    }

    /** Drive the CI handshake to completion (mirrors ContactHandshakeSystemTest steps 1–5). */
    private suspend fun completeHandshake(system: TestSystem, alice: TestClient, bob: TestClient) {
        val createResult = alice.tenshuClient.contactService.createContactInvite()
        val link = ((createResult as TResult.Success).value as TString).get()
        val inviteId = InviteLinkPayload.parse(link).inviteIdHex

        bob.tenshuClient.contactService.redeemContactInvite(link, selfAssertedName = "Bob")
        system.advanceUntil(timeout = 20.seconds) {
            alice.inviteStatus(inviteId) == org.tenshu.contact.ContactInvite.Status.REDEEMED
        }
        alice.tenshuClient.contactService.completeContactHandshake(
            inviteId = inviteId,
            decision = CompleteContactHandshake.Decision.ACCEPT,
            shownFingerprint = alice.inviteClaim(inviteId).orEmpty(),
            newPersonName = "Bob",
            selfAssertedName = "Alice",
        )
        bob.tenshuClient.contactService.awaitInviteAck(inviteId).await()
        system.advanceUntil(timeout = 10.seconds) { bob.identityRows().isNotEmpty() }
    }

    private fun TestClient.memberRow(realmId: String, memberId: String): Data? =
        executionContext.database.getData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = Member.ID,
                keyValue = Member.compositeKey(realmId, memberId),
            ),
        )

    private fun TestClient.inviteStatus(inviteId: String): String? =
        readRelation(ContactService.Relations.CONTACT_INVITES, org.tenshu.contact.ContactInvite.ID)
            .firstOrNull { it.stringField(org.tenshu.contact.ContactInvite.Field.INVITE_ID) == inviteId }
            ?.stringField(org.tenshu.contact.ContactInvite.Field.STATUS)

    /** The raw claimed fingerprint a completion prompt would display (contact-service v2). */
    private fun TestClient.inviteClaim(inviteId: String): String? =
        readRelation(ContactService.Relations.CONTACT_INVITES, org.tenshu.contact.ContactInvite.ID)
            .firstOrNull { it.stringField(org.tenshu.contact.ContactInvite.Field.INVITE_ID) == inviteId }
            ?.stringField(org.tenshu.contact.ContactInvite.Field.CLAIMED_FINGERPRINT)

    private fun TestClient.identityRow(fingerprint: String): Data? =
        readRelation(ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.ID)
            .firstOrNull { it.stringField(TenshuIdentity.Field.FINGERPRINT) == fingerprint }

    /** `(fingerprint, selfAssertedName)` pairs. */
    private fun TestClient.identityRows(): List<Pair<String, String>> =
        readRelation(ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.ID)
            .mapNotNull { data ->
                val fp = data.stringField(TenshuIdentity.Field.FINGERPRINT) ?: return@mapNotNull null
                fp to (data.stringField(TenshuIdentity.Field.SELF_ASSERTED_NAME) ?: "")
            }

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
}
