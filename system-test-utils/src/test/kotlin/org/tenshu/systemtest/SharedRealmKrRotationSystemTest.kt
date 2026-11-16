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
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.common.util.InviteLinkPayload
import org.tenshu.core.common.util.sha256
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.notifications.Notification
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.identity.GrantedMembership
import org.tenshu.identity.Member
import org.tenshu.identity.RetainedRealmKey
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
                alice.tenshuClient.syncService.pollPendingGrants() // RO D7: the owner writes the members' rows
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

    /**
     * PR review (Codex): a member whose acceptance the owner has not processed yet has no `Member` row, so a
     * rotation used to skip them and leave them on the retired key. Both orders must end with Bob reading content
     * written after the rotation:
     *  - [bobAcceptsFirst] = true: Bob accepts, then Alice removes Charlie before polling — the removal picks up
     *    Bob's acceptance first, so the rotation re-keys him;
     *  - false: Alice rotates before Bob accepts, so Bob's grant carries the retired key — processing his
     *    acceptance delivers the current one.
     */
    @Test
    fun aMemberAcceptingAroundARotation_endsOnTheCurrentKey_acceptedBeforeTheRemoval(): Unit =
        acceptAroundRotation(bobAcceptsFirst = true)

    @Test
    fun aMemberAcceptingAroundARotation_endsOnTheCurrentKey_acceptedAfterTheRemoval(): Unit =
        acceptAroundRotation(bobAcceptsFirst = false)

    private fun acceptAroundRotation(bobAcceptsFirst: Boolean): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val bob = system.client("bob", TestClientRole.RealmHost)
            val charlie = system.client("charlie", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")
            charlie.bootstrapAsHost(deviceName = "Charlie Tablet")
            completeHandshake(system, alice, bob, otherName = "Bob")
            completeHandshake(system, alice, charlie, otherName = "Charlie")
            val aliceFingerprint = alice.ownFingerprint()
            val realmId = (alice.tenshuClient.syncService.createSharedRealm().successValue() as TString).get()

            // Charlie joins fully (the member Alice will remove, which rotates Kr).
            val charlieAuthHex = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(charlie.ownFingerprint()), label = "Trip").successValue().get()
            charlie.tenshuClient.syncService.pollPendingGrants()
            charlie.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            val charlieMemberId = sha256(charlieAuthHex.hexToByteArray()).toHexString()
            system.advanceUntil(timeout = 25.seconds) {
                alice.tenshuClient.syncService.pollPendingGrants()
                alice.tenshuClient.identityService.getRealmMembers(realmId).successValue().toList()
                    .any { it.memberId.get() == charlieMemberId }
            }

            // Bob is granted; whether he accepts before or after the rotation is the case under test.
            alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(bob.ownFingerprint()), label = "Trip").successValue()
            if (bobAcceptsFirst) {
                bob.tenshuClient.syncService.pollPendingGrants()
                bob.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            }
            alice.tenshuClient.syncService.removeSharedRealmMember(realmId, charlieMemberId).successValue()
            alice.writeRealmNote(realmId, "after-rotation")
            if (!bobAcceptsFirst) {
                bob.tenshuClient.syncService.pollPendingGrants()
                bob.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            }

            system.advanceUntil(timeout = 45.seconds) {
                alice.tenshuClient.syncService.pollPendingGrants() // owner: process Bob's acceptance (+ re-key)
                bob.tenshuClient.syncService.pollPendingGrants() // Bob: adopt any re-key
                bob.hasRealmNote(realmId, "after-rotation")
            }
            assertTrue(bob.hasRealmNote(realmId, "after-rotation"), "Bob reads content written under the rotated key")
        } finally {
            system.shutdown()
        }
    }

    /**
     * A rotation saves two rows, the retired key and then the new membership, and they sync separately. An owner
     * device between them holds the retired `Kr`. It may hand out neither that key to an acceptance (the re-key
     * would ship it, and the member would stay on it) nor to a new grant. Alice is put in that state directly
     * (her current key marked retired):
     *  - **recently retired** = behind another device's rotation: Bob's acceptance waits in the mailbox, not
     *    consumed, and a grant is refused;
     *  - **retired over a day ago** = a rotation cut short: Alice finishes it with a fresh key, processes the
     *    waiting acceptance, and Bob ends up reading content written under the new key.
     */
    @Test
    fun anOwnerDeviceHoldingARetiredKey_waitsWhileBehind_andFinishesAnAbandonedRotation(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val bob = system.client("bob", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")
            completeHandshake(system, alice, bob, otherName = "Bob")
            val realmId = (alice.tenshuClient.syncService.createSharedRealm().successValue() as TString).get()
            val bobAuthHex = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(bob.ownFingerprint()), label = "Trip").successValue().get()
            val bobMemberId = sha256(bobAuthHex.hexToByteArray()).toHexString()
            bob.tenshuClient.syncService.pollPendingGrants()
            bob.tenshuClient.syncService.acceptSharedRealmGrant(alice.ownFingerprint()).successValue()

            val stuckKey = assertNotNull(alice.membershipWrappedKey(realmId))
            fun retire(atMillis: Long) = alice.executionContext.saveData(
                RetainedRealmKey.create(alice.executionContext.context, realmId, sha256(stuckKey).toHexString(), stuckKey, retiredAt = atMillis),
            )

            // Behind: wait, and refuse to grant.
            retire(atMillis = System.currentTimeMillis())
            repeat(5) {
                alice.tenshuClient.syncService.pollPendingGrants()
                delay(200)
            }
            assertNull(alice.memberRow(realmId, bobMemberId), "a device behind a rotation must not process the acceptance")
            assertTrue(
                alice.tenshuClient.syncService
                    .grantSharedRealmMembership(realmId, alice.personIdFor(bob.ownFingerprint()), label = "Trip") is TResult.Failure,
                "a device behind a rotation must not grant the retired key",
            )
            assertTrue(
                alice.tenshuClient.syncService.removeSharedRealmMember(realmId, bobMemberId) is TResult.Failure,
                "a device behind a rotation must not rotate again (the key would fork)",
            )

            // Abandoned long ago: once Alice has caught up on both streams, she finishes the rotation, then processes
            // the acceptance that waited. (The time-only valve also needs an hour of her own observation, so it is
            // not what fires here.)
            retire(atMillis = 1L)
            system.advanceUntil(timeout = 25.seconds) {
                alice.tenshuClient.syncService.pollPendingGrants()
                alice.memberRow(realmId, bobMemberId) != null
            }
            assertFalse(alice.membershipWrappedKey(realmId).contentEquals(stuckKey), "the abandoned rotation is finished with a fresh key")
            alice.writeRealmNote(realmId, "after-recovery")
            system.advanceUntil(timeout = 45.seconds) {
                bob.tenshuClient.syncService.pollPendingGrants() // Bob: adopt the re-key
                bob.hasRealmNote(realmId, "after-recovery")
            }
        } finally {
            system.shutdown()
        }
    }

    /**
     * GR-7 catch-up: a rotation re-keys only the members its device knows. Here Charlie's `Member` row is not on
     * Alice's device when she removes Bob — as when it has not synced from the owner device that wrote it — so the
     * rotation skips him. Once the row is there, Alice's next poll sends him the current key: he is never left
     * joined but unable to read anything new.
     */
    @Test
    fun aMemberARotationDidNotKnow_isSentTheCurrentKeyOnceTheirRowArrives(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val bob = system.client("bob", TestClientRole.RealmHost)
            val charlie = system.client("charlie", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")
            charlie.bootstrapAsHost(deviceName = "Charlie Tablet")
            completeHandshake(system, alice, bob, otherName = "Bob")
            completeHandshake(system, alice, charlie, otherName = "Charlie")
            val aliceFingerprint = alice.ownFingerprint()
            val realmId = (alice.tenshuClient.syncService.createSharedRealm().successValue() as TString).get()
            val bobAuthHex = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(bob.ownFingerprint()), label = "Trip").successValue().get()
            val charlieAuthHex = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(charlie.ownFingerprint()), label = "Trip").successValue().get()
            bob.tenshuClient.syncService.pollPendingGrants()
            charlie.tenshuClient.syncService.pollPendingGrants()
            bob.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            charlie.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            system.advanceUntil(timeout = 25.seconds) {
                alice.tenshuClient.syncService.pollPendingGrants()
                alice.tenshuClient.identityService.getRealmMembers(realmId).successValue().toList().size >= 3
            }
            val bobMemberId = sha256(bobAuthHex.hexToByteArray()).toHexString()
            val charlieMemberId = sha256(charlieAuthHex.hexToByteArray()).toHexString()

            // Charlie is unknown to this device when the rotation runs: his roster row (which a re-key needs) is not
            // here yet, as when it has not synced from the owner device that granted him.
            val rosterId = DataId.StringDataId.create(alice.executionContext.context, GrantedMembership.ID, charlieMemberId)
            val charlieAuthority = charlieAuthHex.hexToByteArray()
            // ⚠ First let Alice's own pull pass the roster row's block: its echo would otherwise put the row back
            // (ingest LWW ignores newer local writes — tech-debt.md) and the rotation would know Charlie after all.
            val registry = assertNotNull(alice.koinApp.koin.get<RealmRegistryHolder>().realmRegistry)
            val observed = registry.personalPulls.observe()
            system.advanceUntil(timeout = 25.seconds) { registry.personalPulls.completeSince(observed) }
            alice.executionContext.database.deleteData(rosterId)
            val charlieKey = assertNotNull(charlie.membershipWrappedKey(realmId))
            alice.tenshuClient.syncService.removeSharedRealmMember(realmId, bobMemberId).successValue()
            val charlieAddress = org.tenshu.core.common.util.deriveRekeyAddress(
                alice.syncManager.getRealmEncryptionKeyPair()!!.publicKey,
                assertNotNull(org.tenshu.sync.proxy.GrantContactDirectory(alice.executionContext).byFingerprint(charlie.ownFingerprint())).a13PublicKey,
                assertNotNull(org.tenshu.sync.proxy.GrantContactDirectory(alice.executionContext).byFingerprint(charlie.ownFingerprint())).grantSeed,
                realmId,
            ).toHexString()
            val relay = alice.koinApp.koin.get<org.tenshu.sync.common.SyncRelay>()
            assertNull((relay.pickupGrant(charlieAddress) as? TResult.Success)?.value, "precondition: the rotation sent Charlie nothing")
            assertContentEquals(charlieKey, charlie.membershipWrappedKey(realmId))

            // His roster row arrives; Alice's next poll catches him up.
            alice.executionContext.saveData(
                GrantedMembership.create(alice.executionContext.context, charlieMemberId, realmId, charlieAuthority),
            )
            alice.tenshuClient.syncService.pollPendingGrants()
            system.advanceUntil(timeout = 25.seconds) {
                charlie.tenshuClient.syncService.pollPendingGrants()
                !charlie.membershipWrappedKey(realmId).contentEquals(charlieKey)
            }
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⚠⚠ T-GR-02 rollback: two rotations before Charlie polls, so he skips the first key K1 — it never enters his
     * retired keyring. The relay keeps the genuine, master-signed R1 and re-serves it after he adopted K2. He must stay
     * on K2: re-keys are ordered by their signed `issuedAt`, and R1 is older than the last one he adopted.
     */
    @Test
    fun aSkippedReKey_replayedLater_neverRollsAMemberBack(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val bob = system.client("bob", TestClientRole.RealmHost)
            val dave = system.client("dave", TestClientRole.RealmHost)
            val charlie = system.client("charlie", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")
            dave.bootstrapAsHost(deviceName = "Dave Phone")
            charlie.bootstrapAsHost(deviceName = "Charlie Tablet")
            completeHandshake(system, alice, bob, otherName = "Bob")
            completeHandshake(system, alice, dave, otherName = "Dave")
            completeHandshake(system, alice, charlie, otherName = "Charlie")
            val aliceFingerprint = alice.ownFingerprint()
            val realmId = (alice.tenshuClient.syncService.createSharedRealm().successValue() as TString).get()
            val ids = listOf(bob, dave, charlie).map { member ->
                val hex = alice.tenshuClient.syncService
                    .grantSharedRealmMembership(realmId, alice.personIdFor(member.ownFingerprint()), label = "Trip").successValue().get()
                member.tenshuClient.syncService.pollPendingGrants()
                member.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
                sha256(hex.hexToByteArray()).toHexString()
            }
            system.advanceUntil(timeout = 25.seconds) {
                alice.tenshuClient.syncService.pollPendingGrants()
                alice.tenshuClient.identityService.getRealmMembers(realmId).successValue().toList().size >= 4
            }

            // Where Charlie's re-keys arrive, and the relay's own copy of whatever lands there.
            val charlieContact = assertNotNull(org.tenshu.sync.proxy.GrantContactDirectory(alice.executionContext).byFingerprint(charlie.ownFingerprint()))
            val aliceEnc = alice.syncManager.getRealmEncryptionKeyPair()!!
            val address = org.tenshu.core.common.util.deriveRekeyAddress(
                aliceEnc.publicKey, charlieContact.a13PublicKey, charlieContact.grantSeed, realmId,
            ).toHexString()
            val relay = alice.koinApp.koin.get<org.tenshu.sync.common.SyncRelay>()

            // R1: Bob removed. The relay keeps R1 — Charlie never sees it.
            alice.tenshuClient.syncService.removeSharedRealmMember(realmId, ids[0]).successValue()
            val r1 = assertNotNull((relay.pickupGrant(address) as? TResult.Success)?.value?.get(), "R1 must have been dropped")
            // K1, as the owner holds it while it is current — and its generation then.
            val aliceK1Wrapped = assertNotNull(alice.membershipWrappedKey(realmId))
            val k1Generation = assertNotNull(
                alice.executionContext.database.getData(
                    DataId.StringDataId.create(
                        alice.executionContext.context,
                        org.tenshu.identity.RealmKeyGeneration.ID,
                        org.tenshu.identity.RealmKeyGeneration.compositeKey(realmId, sha256(aliceK1Wrapped).toHexString()),
                    ),
                ) as? org.tenshu.identity.RealmKeyGeneration,
                "the rotation that made K1 recorded its generation",
            ).generation.toEpochMilliseconds()

            // R2: Dave removed. Charlie adopts K2.
            alice.tenshuClient.syncService.removeSharedRealmMember(realmId, ids[1]).successValue()
            val aliceK2 = assertNotNull(alice.membershipWrappedKey(realmId))
            val charlieBefore = assertNotNull(charlie.membershipWrappedKey(realmId))
            system.advanceUntil(timeout = 25.seconds) {
                charlie.tenshuClient.syncService.pollPendingGrants()
                !charlie.membershipWrappedKey(realmId).contentEquals(charlieBefore)
            }
            val charlieOnK2 = assertNotNull(charlie.membershipWrappedKey(realmId))
            assertTrue(charlie.realmKeyMatches(realmId, alice, aliceK2), "precondition: Charlie is on K2")

            // The replay.
            assertTrue(relay.dropGrant(address, r1) is TResult.Success)
            repeat(3) { charlie.tenshuClient.syncService.pollPendingGrants(); delay(100) }

            assertContentEquals(charlieOnK2, charlie.membershipWrappedKey(realmId), "Charlie stays on K2")

            // ⚠⚠ And a genuine master-signed answer from an owner device still on K1 — what a lagging device would send
            // to a (possibly forged) key request: fresh `issuedAt`, but K1's own, older generation. Refused.
            val crypto = alice.executionContext.context.syncCryptoManager()
            val k1 = crypto.unwrapKeyForIdentity(aliceK1Wrapped, aliceEnc.privateKey)
            val master = assertNotNull(alice.ownedRealmMaster(realmId))
            val charlieAuthority = charlieAuthorityOf(alice, realmId, charlie.ownFingerprint())
            val wrappedK1 = crypto.wrapKeyForIdentity(k1, charlieContact.a13PublicKey)
            val freshlyIssued = System.currentTimeMillis() + 60 * 60 * 1000L
            val stale = org.tenshu.contact.common.GrantPayload(
                grantId = sha256(wrappedK1).toHexString(),
                realmId = realmId,
                wrappedRealmKey = wrappedK1,
                capability = org.tenshu.contact.common.GrantCapability(realmId, charlieAuthority, 0L, freshlyIssued, ByteArray(64)),
                memberAuthorityPrivateKey = ByteArray(0),
                grantorFingerprint = aliceFingerprint,
                masterPublicKey = master.masterPublicKey.get(),
                rekeySignature = crypto.signEd25519(
                    master.masterPrivateKey.get(),
                    org.tenshu.sync.common.RealmRekey.message(realmId, charlieAuthority, wrappedK1, freshlyIssued, k1Generation),
                ),
                keyGeneration = k1Generation,
            )
            val rawAddress = address.hexToByteArray()
            assertTrue(relay.dropGrant(address, org.tenshu.contact.common.sealGrant(crypto, stale, charlieContact.a13PublicKey, rawAddress)) is TResult.Success)
            repeat(3) { charlie.tenshuClient.syncService.pollPendingGrants(); delay(100) }
            assertContentEquals(charlieOnK2, charlie.membershipWrappedKey(realmId), "a stale owner's freshly issued K1 is refused")
        } finally {
            system.shutdown()
        }
    }

    /**
     * GR-7 key request: Charlie's re-key never reaches him (here the relay "loses" it; in life, the mailbox TTL while
     * he was offline). Alice writes under the new key; Charlie's pull meets a block no key of his opens, so he asks
     * Alice for the current key, she answers with an ordinary signed re-key, and he reads what she wrote.
     */
    @Test
    fun aMemberWhoseReKeyWasLost_asksForTheKey_andReadsWhatFollowed(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val bob = system.client("bob", TestClientRole.RealmHost)
            val charlie = system.client("charlie", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")
            charlie.bootstrapAsHost(deviceName = "Charlie Tablet")
            completeHandshake(system, alice, bob, otherName = "Bob")
            completeHandshake(system, alice, charlie, otherName = "Charlie")
            val aliceFingerprint = alice.ownFingerprint()
            val realmId = (alice.tenshuClient.syncService.createSharedRealm().successValue() as TString).get()
            val bobAuthHex = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(bob.ownFingerprint()), label = "Trip").successValue().get()
            alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(charlie.ownFingerprint()), label = "Trip").successValue()
            bob.tenshuClient.syncService.pollPendingGrants()
            charlie.tenshuClient.syncService.pollPendingGrants()
            bob.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            charlie.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            system.advanceUntil(timeout = 25.seconds) {
                alice.tenshuClient.syncService.pollPendingGrants()
                alice.tenshuClient.identityService.getRealmMembers(realmId).successValue().toList().size >= 3
            }

            val charlieContact = assertNotNull(org.tenshu.sync.proxy.GrantContactDirectory(alice.executionContext).byFingerprint(charlie.ownFingerprint()))
            val aliceEnc = alice.syncManager.getRealmEncryptionKeyPair()!!
            val rekeyAddress = org.tenshu.core.common.util.deriveRekeyAddress(
                aliceEnc.publicKey, charlieContact.a13PublicKey, charlieContact.grantSeed, realmId,
            ).toHexString()
            val relay = alice.koinApp.koin.get<org.tenshu.sync.common.SyncRelay>()

            // Bob removed; the relay loses Charlie's re-key. He stays on the old key.
            val charlieKey = assertNotNull(charlie.membershipWrappedKey(realmId))
            alice.tenshuClient.syncService.removeSharedRealmMember(realmId, sha256(bobAuthHex.hexToByteArray()).toHexString()).successValue()
            assertNotNull((relay.pickupGrant(rekeyAddress) as? TResult.Success)?.value?.get(), "the re-key must have been dropped")
            repeat(2) { charlie.tenshuClient.syncService.pollPendingGrants(); delay(100) }
            assertContentEquals(charlieKey, charlie.membershipWrappedKey(realmId), "precondition: Charlie never got the key")

            // Alice writes under the new key; Charlie notices he cannot read it, asks, and is answered.
            alice.writeRealmNote(realmId, "after-the-lost-rekey")
            system.advanceUntil(timeout = 45.seconds) {
                charlie.tenshuClient.syncService.pollPendingGrants() // asks once behind; adopts once answered
                alice.tenshuClient.syncService.pollPendingGrants()   // answers the request
                charlie.hasRealmNote(realmId, "after-the-lost-rekey")
            }
        } finally {
            system.shutdown()
        }
    }

    /** Whether this member's current realm key is the one [owner]'s [ownerWrapped] wraps (each wraps to its own A13). */
    private fun TestClient.realmKeyMatches(realmId: String, owner: TestClient, ownerWrapped: ByteArray): Boolean {
        val crypto = executionContext.context.syncCryptoManager()
        val mine = crypto.unwrapKeyForIdentity(membershipWrappedKey(realmId)!!, syncManager.getRealmEncryptionKeyPair()!!.privateKey)
        val theirs = crypto.unwrapKeyForIdentity(ownerWrapped, owner.syncManager.getRealmEncryptionKeyPair()!!.privateKey)
        return mine.contentEquals(theirs)
    }

    /**
     * ⚠⚠ T-GR-02: a grant drop is an anonymous sealed box at an address the relay serves, so a removed member
     * colluding with the relay can place `{realmId, wrap(Kx, bobA13)}` at the OWNER's address, claiming the owner's
     * fingerprint. Bob must refuse it — only a re-key the realm master signed for him, for that key, is adopted.
     */
    @Test
    fun aForgedReKeyAtTheOwnersAddress_isRefused(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val bob = system.client("bob", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")
            completeHandshake(system, alice, bob, otherName = "Bob")
            val realmId = (alice.tenshuClient.syncService.createSharedRealm().successValue() as TString).get()
            val bobAuthHex = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(bob.ownFingerprint()), label = "Trip").successValue().get()
            bob.tenshuClient.syncService.pollPendingGrants()
            bob.tenshuClient.syncService.acceptSharedRealmGrant(alice.ownFingerprint()).successValue()
            val bobKey = assertNotNull(bob.membershipWrappedKey(realmId))

            // The forger: everything the colluding relay and a removed member know, but no realm master key.
            val crypto = alice.executionContext.context.syncCryptoManager()
            val bobContact = assertNotNull(org.tenshu.sync.proxy.GrantContactDirectory(alice.executionContext).byFingerprint(bob.ownFingerprint()))
            val aliceEnc = alice.syncManager.getRealmEncryptionKeyPair()!!
            val address = org.tenshu.core.common.util.deriveGrantAddress(aliceEnc.publicKey, bobContact.a13PublicKey, bobContact.grantSeed)
            val attackerKey = crypto.generateSharedRealmMasterKey()
            val wrapped = crypto.wrapKeyForIdentity(attackerKey, bobContact.a13PublicKey)
            val masterPub = assertNotNull(alice.ownedRealmMasterPublicKey(realmId))
            val forger = crypto.generateDeviceSigningKeyPair()
            val bobAuthority = bobAuthHex.hexToByteArray()
            val forged = org.tenshu.contact.common.GrantPayload(
                grantId = sha256(wrapped).toHexString(),
                realmId = realmId,
                wrappedRealmKey = wrapped,
                capability = org.tenshu.contact.common.GrantCapability(realmId, bobAuthority, 0L, System.currentTimeMillis(), ByteArray(64)),
                memberAuthorityPrivateKey = ByteArray(0),
                grantorFingerprint = alice.ownFingerprint(),
                masterPublicKey = masterPub,
                rekeySignature = crypto.signEd25519(
                    forger.privateKey,
                    org.tenshu.sync.common.RealmRekey.message(realmId, bobAuthority, wrapped, System.currentTimeMillis(), 0L),
                ),
            )
            val relay = alice.koinApp.koin.get<org.tenshu.sync.common.SyncRelay>()
            assertTrue(
                relay.dropGrant(address.toHexString(), org.tenshu.contact.common.sealGrant(crypto, forged, bobContact.a13PublicKey, address))
                    is TResult.Success,
                "the forged drop must land, or this test proves nothing",
            )

            repeat(3) { bob.tenshuClient.syncService.pollPendingGrants(); delay(100) }

            assertContentEquals(bobKey, bob.membershipWrappedKey(realmId), "Bob stays on the realm's real key")
        } finally {
            system.shutdown()
        }
    }

    /**
     * The abandoned-rotation recovery runs exactly when an owner device is behind, so its `Member` rows can be
     * stale: a member removed on another device may still be listed here. It must never re-key a member the
     * realm's signed records revoke. Alice removes Bob, then her store is put back to "Bob's rows still present"
     * (the revocation record is there) and her current key is marked retired long ago. The recovery re-keys
     * Charlie and not Bob.
     */
    @Test
    fun anAbandonedRotationRecovery_neverReKeysARevokedMember(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val alice = system.client("alice", TestClientRole.RealmHost)
            val bob = system.client("bob", TestClientRole.RealmHost)
            val charlie = system.client("charlie", TestClientRole.RealmHost)
            alice.bootstrapAsHost(deviceName = "Alice Laptop")
            bob.bootstrapAsHost(deviceName = "Bob Phone")
            charlie.bootstrapAsHost(deviceName = "Charlie Tablet")
            completeHandshake(system, alice, bob, otherName = "Bob")
            completeHandshake(system, alice, charlie, otherName = "Charlie")
            val aliceFingerprint = alice.ownFingerprint()
            val charliePersonId = alice.personIdFor(charlie.ownFingerprint())
            val realmId = (alice.tenshuClient.syncService.createSharedRealm().successValue() as TString).get()
            val bobAuthHex = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(bob.ownFingerprint()), label = "Trip").successValue().get()
            alice.tenshuClient.syncService.grantSharedRealmMembership(realmId, charliePersonId, label = "Trip").successValue()
            bob.tenshuClient.syncService.pollPendingGrants()
            charlie.tenshuClient.syncService.pollPendingGrants()
            bob.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            charlie.tenshuClient.syncService.acceptSharedRealmGrant(aliceFingerprint).successValue()
            val bobMemberId = sha256(bobAuthHex.hexToByteArray()).toHexString()
            system.advanceUntil(timeout = 25.seconds) {
                alice.tenshuClient.syncService.pollPendingGrants()
                alice.tenshuClient.identityService.getRealmMembers(realmId).successValue().toList().size >= 3
            }

            // Bob's owner-side rows, as a device that never applied the removal would still hold them.
            val bobRow = assertNotNull(alice.memberRow(realmId, bobMemberId))
            val bobRoster = assertNotNull(
                alice.executionContext.database.getData(
                    DataId.StringDataId.create(alice.executionContext.context, GrantedMembership.ID, bobMemberId),
                ),
            )
            val charlieBeforeRemoval = assertNotNull(charlie.membershipWrappedKey(realmId))
            alice.tenshuClient.syncService.removeSharedRealmMember(realmId, bobMemberId).successValue()
            system.advanceUntil(timeout = 25.seconds) {
                charlie.tenshuClient.syncService.pollPendingGrants() // Charlie adopts the removal's key
                !charlie.membershipWrappedKey(realmId).contentEquals(charlieBeforeRemoval)
            }
            alice.executionContext.saveData(bobRow)
            alice.executionContext.saveData(bobRoster)
            val bobKey = assertNotNull(bob.membershipWrappedKey(realmId))
            val charlieKey = assertNotNull(charlie.membershipWrappedKey(realmId))

            // The recovery: Alice's current key, retired long ago. A grant runs it first, once Alice has caught up
            // on both streams since she found the realm stuck — the first attempts are refused.
            val current = assertNotNull(alice.membershipWrappedKey(realmId))
            alice.executionContext.saveData(
                // Past the 24 h window but inside the 48 h one, where the caught-up gate holds.
                RetainedRealmKey.create(
                    alice.executionContext.context, realmId, sha256(current).toHexString(), current,
                    retiredAt = System.currentTimeMillis() - 25 * 60 * 60 * 1000L,
                ),
            )
            assertTrue(
                alice.tenshuClient.syncService.grantSharedRealmMembership(realmId, charliePersonId, label = "Trip") is TResult.Failure,
                "the first look only records that the realm is stuck; the recovery waits for a complete pull",
            )
            system.advanceUntil(timeout = 25.seconds) {
                alice.tenshuClient.syncService.grantSharedRealmMembership(realmId, charliePersonId, label = "Trip") is TResult.Success
            }
            assertFalse(alice.membershipWrappedKey(realmId).contentEquals(current), "the recovery rotated to a fresh key")

            system.advanceUntil(timeout = 25.seconds) {
                charlie.tenshuClient.syncService.pollPendingGrants()
                !charlie.membershipWrappedKey(realmId).contentEquals(charlieKey)
            }
            repeat(5) {
                bob.tenshuClient.syncService.pollPendingGrants()
                delay(200)
            }
            assertContentEquals(bobKey, bob.membershipWrappedKey(realmId), "a revoked member is never re-keyed")
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

    private fun TestClient.ownedRealmMaster(realmId: String): org.tenshu.identity.OwnedRealmMaster? =
        executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, org.tenshu.identity.OwnedRealmMaster.ID, realmId),
        ) as? org.tenshu.identity.OwnedRealmMaster

    private fun TestClient.retainedKeys(realmId: String): List<RetainedRealmKey> =
        executionContext.database
            .getArrayData(executionContext.relationReference(org.tenshu.identity.IdentityService.Relations.RETAINED_REALM_KEYS))
            ?.toList().orEmpty().mapNotNull { it as? RetainedRealmKey }.filter { it.sharedRealmId.get() == realmId }

    /** The A26 public key [owner] granted the member whose contact fingerprint is [memberFingerprint]. */
    private fun charlieAuthorityOf(owner: TestClient, realmId: String, memberFingerprint: String): ByteArray {
        val member = owner.executionContext.database
            .getArrayData(owner.executionContext.relationReference(org.tenshu.identity.IdentityService.Relations.MEMBERS))
            ?.toList().orEmpty().mapNotNull { it as? Member }
            .first { it.realmId.get() == realmId && it.contactFingerprint.get() == memberFingerprint }
        val roster = owner.executionContext.database.getData(
            DataId.StringDataId.create(owner.executionContext.context, GrantedMembership.ID, member.memberId.get()),
        ) as GrantedMembership
        return roster.memberAuthorityPublicKey.get()
    }

    private fun TestClient.ownedRealmMasterPublicKey(realmId: String): ByteArray? =
        (executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, org.tenshu.identity.OwnedRealmMaster.ID, realmId),
        ) as? org.tenshu.identity.OwnedRealmMaster)?.masterPublicKey?.get()

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

    private fun TestClient.memberRow(realmId: String, memberId: String): Data? =
        executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, Member.ID, Member.compositeKey(realmId, memberId)),
        )
}
