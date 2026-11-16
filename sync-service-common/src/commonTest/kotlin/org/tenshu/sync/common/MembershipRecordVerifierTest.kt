package org.tenshu.sync.common

import org.koin.dsl.koinApplication
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.security.cryptoBackendsModule
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.sync.common.MembershipRecordVerifier.Record
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `shared-realm-membership-records.md` MR-1 — the membership-record verifier: master-signed only, a
 * revocation is permanent, the current capability is the highest epoch then the latest `issuedAt`, and a
 * non-empty delegation chain is refused until delegation is designed. Real Ed25519.
 */
class MembershipRecordVerifierTest {

    private val ed25519: Ed25519Backend = koinApplication { modules(cryptoBackendsModule()) }.koin.get()
    private val verify = Ed25519SignatureVerifier(ed25519::verify)

    private val masterSeed = ByteArray(32) { (it + 1).toByte() }
    private val masterPub = ed25519.derivePublicKey(masterSeed)
    private val realmId = ownerIdForMasterPublicKey(masterPub)

    private val otherSeed = ByteArray(32) { (it + 90).toByte() }
    private val otherPub = ed25519.derivePublicKey(otherSeed)

    private val bob = ed25519.derivePublicKey(ByteArray(32) { (it + 40).toByte() })
    private val bobId = MembershipRecordVerifier.memberIdOf(bob)
    private val carol = ed25519.derivePublicKey(ByteArray(32) { (it + 60).toByte() })

    private fun cap(
        authority: ByteArray = bob,
        epoch: Long = 0L,
        issuedAt: Long = 1_000L,
        seed: ByteArray = masterSeed,
        signer: ByteArray = masterPub,
        realm: String = realmId,
        chain: ByteArray = ByteArray(0),
    ) = Record.Capability(
        MembershipCapability(realm, authority, epoch, issuedAt, ed25519.sign(seed, MembershipCapability.message(realm, authority, epoch, issuedAt))),
        signer,
        chain,
    )

    private fun revoke(authority: ByteArray = bob, revokedAt: Long = 2_000L, seed: ByteArray = masterSeed, signer: ByteArray = masterPub) =
        Record.Revocation(
            MembershipRevocation(realmId, authority, 0L, revokedAt, ed25519.sign(seed, MembershipRevocation.message(realmId, authority, 0L, revokedAt))),
            signer,
        )

    private fun members(vararg records: Record) = MembershipRecordVerifier.members(realmId, records.toList(), verify)

    @Test
    fun aMasterSignedCapability_makesAMember() = assertEquals(setOf(bobId), members(cap()).keys)

    /** ⚠⚠ Only the realm master: a well-signed capability by any other key is nobody's grant. */
    @Test
    fun aCapabilityByAnyOtherKey_isIgnored() =
        assertTrue(members(cap(seed = otherSeed, signer = otherPub)).isEmpty())

    @Test
    fun aRevocation_removes() = assertTrue(members(cap(), revoke()).isEmpty())

    /** A revocation is permanent per authority key — a later capability for the same key does not undo it. */
    @Test
    fun aLaterCapability_doesNotUndoARevocation() =
        assertTrue(members(cap(issuedAt = 1_000L), revoke(revokedAt = 2_000L), cap(issuedAt = 9_000L)).isEmpty())

    @Test
    fun aRevocationByAnyOtherKey_isIgnored() =
        assertEquals(setOf(bobId), members(cap(), revoke(seed = otherSeed, signer = otherPub)).keys)

    @Test
    fun revokingOneMember_leavesTheOthers() {
        val carolId = MembershipRecordVerifier.memberIdOf(carol)
        assertEquals(setOf(carolId), members(cap(), cap(authority = carol), revoke()).keys)
    }

    /** ⚠⚠ Domain separation: a capability's signature presented as a revocation verifies as nothing. */
    @Test
    fun aCapabilitySignature_neverVerifiesAsARevocation() {
        val genuine = cap(issuedAt = 2_000L)
        val forged = Record.Revocation(
            MembershipRevocation(realmId, bob, 0L, 2_000L, genuine.capability.signature),
            masterPub,
        )
        assertFalse(MembershipRecordVerifier.isValid(realmId, forged, verify))
        assertEquals(setOf(bobId), members(genuine, forged).keys)
    }

    /** A statement about another realm is not this realm's record, even when signed by its own master. */
    @Test
    fun aStatementForAnotherRealm_isIgnored() =
        assertTrue(members(cap(realm = "some-other-realm")).isEmpty())

    /** ⚠⚠ Delegation is reserved, not built (§7.2): any chain at all is refused. */
    @Test
    fun aNonEmptyAuthorityChain_isRefused() =
        assertTrue(members(cap(chain = byteArrayOf(1))).isEmpty())

    @Test
    fun theCurrentCapability_isTheHighestEpoch_thenTheLatestIssuedAt() {
        val records = listOf(cap(epoch = 1L, issuedAt = 1_000L), cap(epoch = 0L, issuedAt = 9_000L), cap(epoch = 1L, issuedAt = 5_000L))
        for (order in listOf(records, records.reversed(), listOf(records[1], records[2], records[0]))) {
            val current = MembershipRecordVerifier.members(realmId, order, verify).getValue(bobId)
            assertEquals(1L to 5_000L, current.epoch to current.issuedAt)
        }
    }

    /** One signature, two planes: a capability record verifies over exactly what the relay's admit chain checks. */
    @Test
    fun aCapabilityRecord_isTheRelaysCapability() {
        val record = cap()
        assertTrue(MembershipChainVerifier.verifyCapability(masterPub, record.capability, verify))
    }
}
