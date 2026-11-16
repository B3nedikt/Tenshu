package org.tenshu.identity.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.identity.GetRealmMembers
import org.tenshu.identity.Member
import org.tenshu.identity.MembershipRecord
import org.tenshu.identity.OwnedRealmMaster
import org.tenshu.identity.proxy.executor.GetRealmMembersImplementation
import org.tenshu.sync.common.MembershipCapability
import org.tenshu.sync.common.MembershipRecordVerifier
import org.tenshu.sync.common.MembershipRevocation
import org.tenshu.sync.common.MemberRowAttestation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `shared-realm-membership-records.md` MR-2: `getRealmMembers` lists only members the realm's signed records
 * vouch for — granted and unrevoked, plus the owner. A `Member` row anyone with the realm key wrote is not
 * enough.
 */
class GetRealmMembersVouchedTest {

    private lateinit var testProxy: TestProxy
    private lateinit var crypto: SyncCryptoManager
    private lateinit var master: SyncCryptoManager.KeyPair
    private lateinit var realmId: String
    private val bob = ByteArray(32) { (it + 40).toByte() }
    private val bobId = MembershipRecordVerifier.memberIdOf(bob)

    @BeforeTest
    fun setup() {
        testProxy = createIdentityProxy()
        testProxy.executionContext.initIdentityService()
        crypto = testProxy.tContext.syncCryptoManager()
        master = crypto.generateDeviceSigningKeyPair()
        realmId = ownerIdForMasterPublicKey(master.publicKey)
    }

    private val ownerId get() = MembershipRecordVerifier.memberIdOf(master.publicKey)

    /** MR-3: rows are the owner's, signed by the realm master; [signed] = false writes one a member could forge. */
    private fun row(memberId: String, role: String = Member.Role.MEMBER, signed: Boolean = true, displayName: String = "") {
        val signature = if (signed) {
            crypto.signEd25519(master.privateKey, MemberRowAttestation.message(realmId, memberId, displayName, "", role, 1L, 1L))
        } else {
            null
        }
        testProxy.executionContext.saveData(
            Member.create(
                testProxy.tContext, realmId, memberId, displayName, "", role, joinedAt = 1L,
                signerPublicKey = if (signed) master.publicKey else null, signature = signature,
                signedAt = if (signed) 1L else null,
            ),
        )
    }

    private fun grantBob(issuedAt: Long = 1_000L) = testProxy.executionContext.saveData(
        MembershipRecord.create(
            testProxy.tContext, realmId, MembershipRecord.Kind.CAPABILITY, bobId, bob, 0L, issuedAt, master.publicKey,
            crypto.signEd25519(master.privateKey, MembershipCapability.message(realmId, bob, 0L, issuedAt)),
        ),
    )

    private fun revokeBob(revokedAt: Long = 2_000L) = testProxy.executionContext.saveData(
        MembershipRecord.create(
            testProxy.tContext, realmId, MembershipRecord.Kind.REVOCATION, bobId, bob, 0L, revokedAt, master.publicKey,
            crypto.signEd25519(master.privateKey, MembershipRevocation.message(realmId, bob, 0L, revokedAt)),
        ),
    )

    private fun listed(): Set<String> = runTestValue {
        GetRealmMembersImplementation().execute<TList<Member>>(
            testProxy.executionContext, null, testProxy.executionContext.implementationFactory, null,
            mapOf(GetRealmMembers.Params.REALM_ID to realmId.toTString(testProxy.tContext)),
        ).toList().map { it.memberId.get() }.toSet()
    }

    private fun <T> runTestValue(block: suspend () -> T): T {
        var out: T? = null
        runTest { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    @Test
    fun aGrantedMember_andTheOwner_areListed() {
        row(ownerId, Member.Role.OWNER)
        row(bobId)
        grantBob()
        assertEquals(setOf(ownerId, bobId), listed())
    }

    /** ⚠⚠ A row alone vouches for nothing: anyone with the realm key can write one. */
    @Test
    fun aRowNobodyGranted_isNotListed() {
        row(ownerId, Member.Role.OWNER)
        row(bobId)
        row("phantom-member-id")
        grantBob()
        assertEquals(setOf(ownerId, bobId), listed())
    }

    @Test
    fun aRevokedMember_isNotListed() {
        row(bobId)
        grantBob()
        revokeBob()
        assertEquals(emptySet(), listed() - ownerId)
    }

    /** Order-safe: the row shows the moment its capability arrives, wherever it sat before. */
    @Test
    fun aRowBeforeItsCapability_isListedOnceTheCapabilityArrives() {
        row(bobId)
        assertEquals(emptySet(), listed())
        grantBob()
        assertEquals(setOf(bobId), listed())
    }

    /** Before any grant, the owner is proven by this device holding the realm master. */
    @Test
    fun anOwnerHoldingTheMaster_isListedWithNoRecordsYet() {
        row(ownerId, Member.Role.OWNER)
        testProxy.executionContext.saveData(
            OwnedRealmMaster.create(testProxy.tContext, sharedRealmId = realmId, masterPublicKey = master.publicKey, masterPrivateKey = master.privateKey),
        )
        assertEquals(setOf(ownerId), listed())
    }

    /** ⚠⚠ MR-3: a granted member's row that the owner did not sign is not listed. */
    @Test
    fun anUnsignedRow_isNotListed_evenForAGrantedMember() {
        row(bobId, signed = false)
        grantBob()
        assertEquals(emptySet(), listed())
    }
}
