package org.tenshu.sync.common

import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.common.util.sha256

/**
 * A shared realm's **membership records** (`shared-realm-membership-records.md` §3.1–§3.2): the realm
 * master's signed capabilities and revocations, synced inside the realm as an append-only, order-free
 * set. This decides **who is a member** on every client; the relay verifies the same bytes at
 * `/sync/realm-member/admit` and `/revoke`.
 *
 * ## The rule
 *
 * - A record is valid iff its signer is the realm master — `ownerIdForMasterPublicKey(signer) ==
 *   realmId`, so no stored key is needed (the R6a rule) — its statement names this realm, its signature
 *   verifies under the statement's own domain tag, and its delegation chain is **empty**.
 * - A member is in iff some valid capability names its authority key and **no** valid revocation does.
 *   ⚠ A revocation is permanent: [MembershipChainVerifier.isRevoked] is an exact key match, and a
 *   re-invite mints a fresh A26 — so there is no re-admission kind.
 * - A member's current capability is its valid one with the highest `epoch`, then the latest
 *   `issuedAt`: order-free, so every client converges on the same one (RO D6's role changes ride it).
 *
 * ⚠⚠ A non-empty `authorityChain` is **invalid** until delegation is designed (§7.2). Accepting one now
 * would let a future chain format be judged by a verifier that never checked it.
 */
object MembershipRecordVerifier {

    sealed class Record {
        abstract val signerPublicKey: ByteArray
        abstract val authorityChain: ByteArray
        abstract val memberAuthorityPublicKey: ByteArray

        class Capability(
            val capability: MembershipCapability,
            override val signerPublicKey: ByteArray,
            override val authorityChain: ByteArray = ByteArray(0),
        ) : Record() {
            override val memberAuthorityPublicKey get() = capability.memberAuthorityPublicKey
        }

        class Revocation(
            val revocation: MembershipRevocation,
            override val signerPublicKey: ByteArray,
            override val authorityChain: ByteArray = ByteArray(0),
        ) : Record() {
            override val memberAuthorityPublicKey get() = revocation.memberAuthorityPublicKey
        }
    }

    /** `memberId = lowerhex(sha256(memberAuthorityPub))` — the `Member` row's id. */
    fun memberIdOf(memberAuthorityPublicKey: ByteArray): String = sha256(memberAuthorityPublicKey).toHexString()

    fun isValid(realmId: String, record: Record, verify: Ed25519SignatureVerifier): Boolean {
        if (record.authorityChain.isNotEmpty()) return false
        if (ownerIdForMasterPublicKey(record.signerPublicKey) != realmId) return false
        return when (record) {
            is Record.Capability ->
                record.capability.realmId == realmId &&
                    MembershipChainVerifier.verifyCapability(record.signerPublicKey, record.capability, verify)
            is Record.Revocation ->
                record.revocation.realmId == realmId &&
                    verify.verify(record.signerPublicKey, record.revocation.signedMessage(), record.revocation.signature)
        }
    }

    /**
     * The realm's current members, `memberId` → their current capability. Excludes the owner, whose
     * membership is implicit (`memberId == sha256(masterPub)`, RO D1). Invalid records are ignored.
     */
    fun members(
        realmId: String,
        records: Iterable<Record>,
        verify: Ed25519SignatureVerifier,
    ): Map<String, MembershipCapability> {
        val valid = records.filter { isValid(realmId, it, verify) }
        val revoked = valid.filterIsInstance<Record.Revocation>()
            .map { memberIdOf(it.memberAuthorityPublicKey) }
            .toSet()
        return valid.filterIsInstance<Record.Capability>()
            .groupBy { memberIdOf(it.memberAuthorityPublicKey) }
            .filterKeys { it !in revoked }
            .mapValues { (_, caps) ->
                caps.maxWith(compareBy<Record.Capability> { it.capability.epoch }.thenBy { it.capability.issuedAt })
                    .capability
            }
    }

    /**
     * A stored [org.tenshu.identity.MembershipRecord] row as a verifier [Record]; `null` for a kind this build
     * does not know (ignored, never acted on). The one parser for every reader — proxy, identity proxy, tests.
     */
    fun fromRow(row: org.tenshu.identity.MembershipRecord): Record? {
        val realmId = row.realmId.get()
        val pub = row.memberAuthorityPublicKey.get()
        val epoch = row.epoch.get()
        val at = row.at.get()
        val signature = row.signature.get()
        // ⚠⚠ The row's KEY must be the one its signed content names — else a valid record (say an old
        // capability) could be planted at another record's key, and the append-only rule would then refuse the
        // genuine record (and every re-emit of it) at that key for good.
        if (row.key.get() != org.tenshu.identity.MembershipRecord.keyOf(realmId, row.kind.get(), memberIdOf(pub), epoch, at)) return null
        return when (row.kind.get()) {
            org.tenshu.identity.MembershipRecord.Kind.CAPABILITY ->
                Record.Capability(MembershipCapability(realmId, pub, epoch, at, signature), row.signerPublicKey.get(), row.authorityChain.get())
            org.tenshu.identity.MembershipRecord.Kind.REVOCATION ->
                Record.Revocation(MembershipRevocation(realmId, pub, epoch, at, signature), row.signerPublicKey.get(), row.authorityChain.get())
            else -> null
        }
    }

    /**
     * The `memberId` of [realmId]'s **owner**, as far as [records] prove it: `sha256(masterPub)`, read off any
     * valid record's signer (a valid record is master-signed by construction). `null` with no valid record.
     */
    fun ownerMemberId(realmId: String, records: Iterable<Record>, verify: Ed25519SignatureVerifier): String? =
        records.firstOrNull { isValid(realmId, it, verify) }?.let { memberIdOf(it.signerPublicKey) }

    /** The member ids [records] say were revoked. */
    fun revokedMembers(realmId: String, records: Iterable<Record>, verify: Ed25519SignatureVerifier): Set<String> =
        records.filter { it is Record.Revocation && isValid(realmId, it, verify) }
            .map { memberIdOf(it.memberAuthorityPublicKey) }
            .toSet()
}
