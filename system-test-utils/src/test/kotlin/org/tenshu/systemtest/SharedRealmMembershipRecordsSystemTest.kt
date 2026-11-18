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

    /**
     * ⚠⚠ T-GR-02 revocation window: a removal's rotation travels in the owner's PERSONAL stream, the realm's
     * revocation record in the SHARED one — so an owner device could hold the new key without knowing whom it was
     * rotated away from, and hand it to the removed member (a key request, or catch-up: the removed member's row is
     * still there, since a local delete emits no tombstone). Closed by a personal copy of the revocation saved before
     * the rotation's rows: in the personal stream as the relay serves it, it comes first, so any owner device pulling
     * that stream in order holds it before the new key.
     */
    @Test
    fun aRemovalsRevocation_precedesItsRotation_inTheOwnersPersonalStream(): Unit = runBlocking {
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
            val bobMemberId = sha256(bobAuthHex.hexToByteArray()).toHexString()
            assertTrue(alice.tenshuClient.syncService.removeSharedRealmMember(realmId, bobMemberId) is TResult.Success)

            val personalOwner = checkNotNull(alice.syncManager.currentOwnerId)
            val wireIds = org.tenshu.sync.proxy.WireIdResolver(alice.syncManager)
            val personalCopy = alice.readRelation(IdentityService.Relations.MEMBERSHIP_RECORDS, MembershipRecord.ID)
                .mapNotNull { it as? MembershipRecord }
                .single { it.kind.get() == MembershipRecord.Kind.REVOCATION && it.key.get().startsWith("$realmId/") }
            val copyWire = wireIds.of(
                org.tenshu.core.entity.DataId.StringDataId.create(alice.executionContext.context, MembershipRecord.ID, personalCopy.key.get()),
            )
            val membershipWire = wireIds.of(
                org.tenshu.core.entity.DataId.StringDataId.create(
                    alice.executionContext.context, org.tenshu.identity.SharedRealmMembership.ID, realmId,
                ),
            )
            // The order any owner device pulls Alice's personal stream in.
            fun stream() = system.syncMailbox.getChanges(personalOwner, 0L, Int.MAX_VALUE).blocks.map { it.targetEntityIdHash?.get() }
            system.advanceUntil(timeout = 25.seconds) { copyWire in stream() && stream().count { it == membershipWire } >= 2 }
            val order = stream()
            assertTrue(
                order.indexOf(copyWire) < order.lastIndexOf(membershipWire),
                "the revocation's personal copy must reach the relay before the membership that moves to the new key",
            )

            // And the tablet, once it holds the new key, knows whom it was rotated away from.
            system.advanceUntil(timeout = 30.seconds) { aliceTablet.runtimeKeys(realmId) == alice.runtimeKeys(realmId) }
            assertTrue(bobMemberId in aliceTablet.verifiedRevocations(realmId), "an owner device with the new key holds the revocation")
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⚠⚠ T-GR-02 revocation window, push order NOT assumed. The revocation records and the rotation's rows are separate
     * blocks, and a push pass keeps going after a transient (network, 5xx) failure — so another owner device can hold
     * the new key without either record. What closes it is that the new key's
     * [org.tenshu.identity.RealmKeyGeneration] row — which a device must hold to hand the key out at all
     * (`KeyHandOutPolicy`) — names the removed member itself. So: once the tablet holds the new key, the row it holds
     * for that key names Bob, and that holds even with both revocation records taken off the tablet.
     */
    @Test
    fun theNewKeysGenerationRow_namesTheRemovedMember_onEveryOwnerDeviceHoldingTheKey(): Unit = runBlocking {
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
            val bobMemberId = sha256(bobAuthHex.hexToByteArray()).toHexString()
            assertTrue(alice.tenshuClient.syncService.removeSharedRealmMember(realmId, bobMemberId) is TResult.Success)
            system.advanceUntil(timeout = 30.seconds) { aliceTablet.runtimeKeys(realmId) == alice.runtimeKeys(realmId) }
            // ⚠ The key arriving is not the records having stopped arriving: the rotation re-emits them under the new
            // key, which the tablet can only open once it holds it. Delete before that lands and it brings them back.
            system.awaitQuiet(alice, aliceTablet)

            // Take both revocation records off the tablet: the state a failed revocation push would leave.
            aliceTablet.readRelation(IdentityService.Relations.MEMBERSHIP_RECORDS, MembershipRecord.ID)
                .mapNotNull { it as? MembershipRecord }
                .filter { it.kind.get() == MembershipRecord.Kind.REVOCATION }
                .forEach { record ->
                    aliceTablet.executionContext.database.deleteData(
                        DataId.StringDataId.create(aliceTablet.executionContext.context, MembershipRecord.ID, record.key.get()),
                    )
                }
            assertTrue(bobMemberId !in aliceTablet.verifiedRevocations(realmId), "precondition: the tablet holds no revocation record")

            // The generation row for the key the tablet now holds still names Bob.
            system.advanceUntil(timeout = 30.seconds) { aliceTablet.generationRowForCurrentKey(realmId) != null }
            val row = checkNotNull(aliceTablet.generationRowForCurrentKey(realmId))
            assertTrue(
                row.revokedMemberIds()?.contains(bobMemberId) == true,
                "the new key's generation row must name the member it was rotated away from",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⚠⚠ T-GR-02, second removal. An owner device holding the new key but neither its generation row nor the
     * revocation records (their pushes failed) must not rotate: the new key's row would carry forward an empty set,
     * omit the member removed before, and the re-grant would send them the next key from their stale rows. So it
     * refuses the removal outright, and the same removal goes through once the row is here. Also pins that a new
     * realm's first key gets its row, so a missing row always means "not here yet".
     */
    @Test
    fun anOwnerDeviceWithoutTheCurrentKeysGenerationRow_refusesToRotate(): Unit = runBlocking {
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

            val realmId = (alice.tenshuClient.syncService.createSharedRealm().successValue() as TString).get()
            val firstRow = checkNotNull(alice.generationRowForCurrentKey(realmId)) { "a new realm's first key has its generation row" }
            assertTrue(firstRow.revokedMemberIds() == emptySet<String>(), "nobody is revoked as of the first key")

            val bobAuthHex = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(bob.ownFingerprint()), label = "Trip").successValue().get()
            val charlieAuthHex = alice.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, alice.personIdFor(charlie.ownFingerprint()), label = "Trip").successValue().get()
            bob.tenshuClient.syncService.pollPendingGrants()
            charlie.tenshuClient.syncService.pollPendingGrants()
            bob.tenshuClient.syncService.acceptSharedRealmGrant(alice.ownFingerprint()).successValue()
            charlie.tenshuClient.syncService.acceptSharedRealmGrant(alice.ownFingerprint()).successValue()
            system.advanceUntil(timeout = 25.seconds) {
                alice.tenshuClient.syncService.pollPendingGrants()
                alice.tenshuClient.identityService.getRealmMembers(realmId).successValue().toList().size >= 3
            }
            val bobMemberId = sha256(bobAuthHex.hexToByteArray()).toHexString()
            val charlieMemberId = sha256(charlieAuthHex.hexToByteArray()).toHexString()
            // The tablet must be able to remove Charlie at all, or the refusal below would prove nothing.
            system.advanceUntil(timeout = 30.seconds) {
                aliceTablet.executionContext.database.getData(
                    DataId.StringDataId.create(aliceTablet.executionContext.context, org.tenshu.identity.GrantedMembership.ID, charlieMemberId),
                ) != null
            }

            assertTrue(alice.tenshuClient.syncService.removeSharedRealmMember(realmId, bobMemberId) is TResult.Success)
            system.advanceUntil(timeout = 30.seconds) {
                aliceTablet.runtimeKeys(realmId) == alice.runtimeKeys(realmId) && aliceTablet.generationRowForCurrentKey(realmId) != null
            }
            // ⚠ As above: the rotation's re-emitted records land after the key; let them, or they undo the deletes.
            system.awaitQuiet(alice, aliceTablet)

            // The window: the tablet holds the new key, but neither its generation row nor a revocation record.
            val row = checkNotNull(aliceTablet.generationRowForCurrentKey(realmId))
            val saved = Triple(row.keyId.get(), row.generation.toEpochMilliseconds(), checkNotNull(row.revokedMemberIds()))
            aliceTablet.executionContext.database.deleteData(
                DataId.StringDataId.create(
                    aliceTablet.executionContext.context, org.tenshu.identity.RealmKeyGeneration.ID, row.compositeKey.get(),
                ),
            )
            aliceTablet.readRelation(IdentityService.Relations.MEMBERSHIP_RECORDS, MembershipRecord.ID)
                .mapNotNull { it as? MembershipRecord }
                .filter { it.kind.get() == MembershipRecord.Kind.REVOCATION }
                .forEach { record ->
                    aliceTablet.executionContext.database.deleteData(
                        DataId.StringDataId.create(aliceTablet.executionContext.context, MembershipRecord.ID, record.key.get()),
                    )
                }

            assertTrue(
                aliceTablet.tenshuClient.syncService.removeSharedRealmMember(realmId, charlieMemberId) !is TResult.Success,
                "an owner device that cannot name the current key's revoked members must not rotate",
            )
            assertTrue(charlieMemberId !in aliceTablet.verifiedRevocations(realmId), "and it revoked nothing")

            // With the row back, the same removal goes through, and the new key's row names both removed members.
            aliceTablet.executionContext.saveData(
                org.tenshu.identity.RealmKeyGeneration.create(
                    aliceTablet.executionContext.context, realmId, saved.first, saved.second, saved.third,
                ),
            )
            assertTrue(aliceTablet.tenshuClient.syncService.removeSharedRealmMember(realmId, charlieMemberId) is TResult.Success)
            val next = checkNotNull(aliceTablet.generationRowForCurrentKey(realmId)).revokedMemberIds().orEmpty()
            assertTrue(bobMemberId in next && charlieMemberId in next, "the next key's row names every member removed so far")
        } finally {
            system.shutdown()
        }
    }

    /**
     * Polled mode's "sync went quiet" for [devices]: drain each on its scheduler's own `tickMutex` path (safe beside the
     * running loop) until two rounds in a row move nothing — no backlog left to pull and no new block at the relay. The
     * rule [TestSystem.syncUntilQuiet] uses in stepped mode. A test that deletes synced rows to stage a *missing* row
     * calls this first, or a block still in flight lands after the delete and the stage is gone.
     */
    private suspend fun TestSystem.awaitQuiet(vararg devices: TestClient, maxRounds: Int = 50) {
        var stillRounds = 0
        repeat(maxRounds) {
            val before = syncMailbox.lastArrivalSeq
            var backlog = false
            devices.forEach { if (it.stepOnce()) backlog = true }
            stillRounds = if (!backlog && syncMailbox.lastArrivalSeq == before) stillRounds + 1 else 0
            if (stillRounds == 2) return
        }
        throw AssertionError("sync never went quiet in $maxRounds rounds of ${devices.map { it.deviceId }}")
    }

    /** This device's [org.tenshu.identity.RealmKeyGeneration] row for the key its membership of [realmId] holds. */
    private fun TestClient.generationRowForCurrentKey(realmId: String): org.tenshu.identity.RealmKeyGeneration? {
        val membership = executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, org.tenshu.identity.SharedRealmMembership.ID, realmId),
        ) as? org.tenshu.identity.SharedRealmMembership ?: return null
        val keyId = sha256(membership.wrappedMasterKey.get()).toHexString()
        return executionContext.database.getData(
            DataId.StringDataId.create(
                executionContext.context,
                org.tenshu.identity.RealmKeyGeneration.ID,
                org.tenshu.identity.RealmKeyGeneration.compositeKey(realmId, keyId),
            ),
        ) as? org.tenshu.identity.RealmKeyGeneration
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
