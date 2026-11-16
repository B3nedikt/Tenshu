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
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.identity.IdentityService
import org.tenshu.identity.MembershipRecord
import org.tenshu.identity.client.identityService
import org.tenshu.identity.getRealmMembers
import org.tenshu.sync.acceptSharedRealmGrant
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.common.Ed25519SignatureVerifier
import org.tenshu.sync.common.MembershipCapability
import org.tenshu.sync.common.MembershipRecordVerifier
import org.tenshu.sync.common.MembershipRevocation
import org.tenshu.sync.createSharedRealm
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.grantSharedRealmMembership
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.pollPendingGrants
import org.tenshu.sync.removeSharedRealmMember
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `shared-realm-membership-records.md` MR-1 gate: a member removal **reaches every other device** through
 * the realm's signed membership records — the owner's second device and a remaining member — over the
 * live dev relay with real crypto — and (MR-2) that every device's member list then drops the removed member.
 *
 * ⚠ Before MR-1 it did not: `removeMember` deleted the `Member` row locally, a delete emits no tombstone,
 * and shared realms apply none, so only the removing device ever knew (§1, problem 2). Enforcement on the
 * receivers (dropping the removed member's row) is MR-2; this proves the authority arrives and verifies.
 */
@OptIn(ExperimentalStdlibApi::class)
class SharedRealmMembershipRecordsSystemTest {

    @Test
    fun aRemoval_reachesTheOwnersSecondDevice_andARemainingMember(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val aliceTablet = system.client("alice-tablet", TestClientRole.JoiningDevice)
            val bob = system.client("bob", TestClientRole.RealmHost)
            val charlie = system.client("charlie", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")
            charlie.bootstrapAsHost(deviceName = "Charlie Tablet")
            pair(system, host = alice, joiner = aliceTablet)

            completeHandshake(system, alice, bob, otherName = "Bob")
            completeHandshake(system, alice, charlie, otherName = "Charlie")
            val aliceFingerprint = alice.ownFingerprint()

            val realmId = (alice.tenshuClient.syncService.createSharedRealm().successValue() as TString).get()
            val bobAuthHex = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(bob.ownFingerprint()), label = "Trip")
                .successValue().get()
            alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(charlie.ownFingerprint()), label = "Trip")
                .successValue()
            bob.tenshuClient.syncService.pollPendingGrants()
            charlie.tenshuClient.syncService.pollPendingGrants()
            bob.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            charlie.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            system.advanceUntil(timeout = 25.seconds) {
                alice.tenshuClient.syncService.pollPendingGrants() // RO D7: the owner writes the members' rows
                alice.tenshuClient.identityService.getRealmMembers(realmId).successValue().toList().size >= 3
            }

            val bobMemberId = sha256(bobAuthHex.hexToByteArray()).toHexString()
            assertTrue(bobMemberId in alice.verifiedMembers(realmId), "the grant is a verified record on the owner")

            alice.tenshuClient.syncService.removeSharedRealmMember(realmId, bobMemberId).successValue()

            try {
                // ⚠ 45 s, not 30: it timed out once at 30 s with eight shared-realm system tests in one run.
                system.advanceUntil(timeout = 45.seconds) {
                    bobMemberId in charlie.verifiedRevocations(realmId) &&
                        bobMemberId in aliceTablet.verifiedRevocations(realmId)
                }
            } catch (e: Throwable) {
                println("DIAG charlie revs=${charlie.verifiedRevocations(realmId)} recs=${charlie.records(realmId).size} tablet revs=${aliceTablet.verifiedRevocations(realmId)} recs=${aliceTablet.records(realmId).size} alice recs=${alice.records(realmId).size}")
                throw e
            }
            assertTrue(bobMemberId in charlie.verifiedRevocations(realmId), "a remaining member learns of the removal")
            assertTrue(bobMemberId in aliceTablet.verifiedRevocations(realmId), "the owner's second device learns of it")
            assertTrue(bobMemberId !in charlie.verifiedMembers(realmId), "and no longer counts Bob as a member")

            // MR-2: the member list each of them shows agrees — Bob's row is gone everywhere.
            system.advanceUntil(timeout = 20.seconds) {
                bobMemberId !in charlie.listedMembers(realmId) && bobMemberId !in aliceTablet.listedMembers(realmId)
            }
            assertTrue(bobMemberId !in charlie.listedMembers(realmId), "a remaining member no longer lists Bob")
            assertTrue(bobMemberId !in aliceTablet.listedMembers(realmId), "the owner's second device no longer lists Bob")
            assertTrue(bobMemberId !in alice.listedMembers(realmId), "nor does the removing device")
        } finally {
            system.shutdown()
        }
    }

    // ---- membership records, verified the way every client will (MembershipRecordVerifier) ----

    private fun TestClient.records(realmId: String): List<MembershipRecordVerifier.Record> =
        readRelation(IdentityService.Relations.MEMBERSHIP_RECORDS, MembershipRecord.ID)
            .mapNotNull { it as? MembershipRecord }
            .filter { it.realmId.get() == realmId }
            .mapNotNull { row ->
                val pub = row.memberAuthorityPublicKey.get()
                when (row.kind.get()) {
                    MembershipRecord.Kind.CAPABILITY -> MembershipRecordVerifier.Record.Capability(
                        MembershipCapability(realmId, pub, row.epoch.get(), row.at.get(), row.signature.get()),
                        row.signerPublicKey.get(), row.authorityChain.get(),
                    )
                    MembershipRecord.Kind.REVOCATION -> MembershipRecordVerifier.Record.Revocation(
                        MembershipRevocation(realmId, pub, row.epoch.get(), row.at.get(), row.signature.get()),
                        row.signerPublicKey.get(), row.authorityChain.get(),
                    )
                    else -> null
                }
            }

    private suspend fun TestClient.listedMembers(realmId: String): Set<String> =
        tenshuClient.identityService.getRealmMembers(realmId).successValue().toList().map { it.memberId.get() }.toSet()

    private fun TestClient.verify() =
        Ed25519SignatureVerifier(executionContext.context.syncCryptoManager()::verifyEd25519)

    private fun TestClient.verifiedMembers(realmId: String): Set<String> =
        MembershipRecordVerifier.members(realmId, records(realmId), verify()).keys

    private fun TestClient.verifiedRevocations(realmId: String): Set<String> =
        MembershipRecordVerifier.revokedMembers(realmId, records(realmId), verify())

    /**
     * ⚠⚠ T-GR-02: a rotation on one owner device must move this user's OTHER device onto the new key in the same
     * session. Left on its old runtime, the tablet would go on sealing new content under the retired key — the one
     * the removed member still holds.
     */
    @Test
    fun aRotationOnOneOwnerDevice_movesTheOthersRuntimeOntoTheNewKey(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val aliceTablet = system.client("alice-tablet", TestClientRole.JoiningDevice)
            val bob = system.client("bob", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")
            pair(system, host = alice, joiner = aliceTablet)
            completeHandshake(system, alice, bob, otherName = "Bob")

            val realmId = (alice.tenshuClient.syncService.createSharedRealm().successValue() as TString).get()
            val bobAuthHex = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(bob.ownFingerprint()), label = "Trip")
                .successValue().get()
            bob.tenshuClient.syncService.pollPendingGrants()
            bob.tenshuClient.syncService.acceptSharedRealmGrant(alice.ownFingerprint()).successValue()
            system.advanceUntil(timeout = 25.seconds) {
                alice.tenshuClient.syncService.pollPendingGrants()
                alice.tenshuClient.identityService.getRealmMembers(realmId).successValue().toList().size >= 2
            }
            system.advanceUntil(timeout = 25.seconds) { aliceTablet.runtimeKeys(realmId) != null }
            val before = alice.runtimeKeys(realmId)
            assertTrue(before == aliceTablet.runtimeKeys(realmId), "both owner devices start on the same key")

            val bobMemberId = sha256(bobAuthHex.hexToByteArray()).toHexString()
            assertTrue(alice.tenshuClient.syncService.removeSharedRealmMember(realmId, bobMemberId) is TResult.Success)
            val after = alice.runtimeKeys(realmId)
            assertTrue(after != null && after != before, "the removal rotated the realm key")

            system.advanceUntil(timeout = 30.seconds) { aliceTablet.runtimeKeys(realmId) == after }
        } finally {
            system.shutdown()
        }
    }

    private fun TestClient.runtimeKeys(realmId: String): org.tenshu.core.security.SyncCryptoManager.DerivedKeys? =
        koinApp.koin.get<org.tenshu.sync.proxy.RealmRegistryHolder>().realmRegistry?.runtimeFor(realmId)?.context?.derivedKeys

    // ---- pairing (mirrors SharedRealmStreamSyncSystemTest) ----

    private suspend fun pair(system: TestSystem, host: TestClient, joiner: TestClient) {
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Alice Tablet",
            otherDevicePublicKey = parsed.hostPairingPublicKey,
        )
        val request = host.tenshuClient.syncService.getPendingPairingRequests()
            .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
        host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
        system.advanceUntil(timeout = 15.seconds) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = parsed.secret,
                otherDevicePublicKey = parsed.hostPairingPublicKey,
            )
            status is TResult.Success && status.value?.get() == true
        }
    }

    // ---- identity helpers (mirrors SharedRealmKrRotationSystemTest) ----

    private fun TestClient.ownFingerprint(): String =
        sha256(syncManager.getRealmEncryptionKeyPair()!!.publicKey).toHexString()

    private fun TestClient.personIdFor(fingerprint: String): String =
        readRelation(ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.ID)
            .first { it.stringField(TenshuIdentity.Field.FINGERPRINT) == fingerprint }
            .stringField(TenshuIdentity.Field.PERSON_ID)!!

    private suspend fun completeHandshake(system: TestSystem, alice: TestClient, other: TestClient, otherName: String) {
        val link = (alice.tenshuClient.contactService.createContactInvite().successValue() as TString).get()
        val inviteId = InviteLinkPayload.parse(link).inviteIdHex
        other.tenshuClient.contactService.redeemContactInvite(link, selfAssertedName = otherName)
        system.advanceUntil(timeout = 20.seconds) {
            alice.inviteField(inviteId, org.tenshu.contact.ContactInvite.Field.STATUS) ==
                org.tenshu.contact.ContactInvite.Status.REDEEMED
        }
        alice.tenshuClient.contactService.completeContactHandshake(
            inviteId = inviteId,
            decision = CompleteContactHandshake.Decision.ACCEPT,
            shownFingerprint = alice.inviteField(inviteId, org.tenshu.contact.ContactInvite.Field.CLAIMED_FINGERPRINT).orEmpty(),
            newPersonName = otherName,
            selfAssertedName = "Alice",
        )
        other.tenshuClient.contactService.awaitInviteAck(inviteId).await()
        system.advanceUntil(timeout = 10.seconds) {
            other.readRelation(ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.ID).isNotEmpty()
        }
    }

    private fun TestClient.inviteField(inviteId: String, field: String): String? =
        readRelation(ContactService.Relations.CONTACT_INVITES, org.tenshu.contact.ContactInvite.ID)
            .firstOrNull { it.stringField(org.tenshu.contact.ContactInvite.Field.INVITE_ID) == inviteId }
            ?.stringField(field)

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
