package org.tenshu.identity.common

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.security.AeadAuthenticationException
import org.tenshu.core.common.security.BouncyCastleAeadBackend
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.BouncyCastleKdfBackend
import org.tenshu.core.common.security.BouncyCastleX25519Backend
import org.tenshu.core.common.security.DefaultSyncCryptoManager
import org.tenshu.core.common.util.Bip39
import org.tenshu.identity.SharedRealmMembership
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves SR-1 (A13 derivation + A12 wrap) and SR-2 (the membership schema)
 * compose: a shared realm's master key (A12) sealed to a member's A13 public key
 * survives a serialize→parse round-trip as a [SharedRealmMembership] and is
 * recovered only by that member's A13 private key. Real BouncyCastle crypto
 * (jvmTest), the same backends `DefaultSyncCryptoManagerTest` uses.
 */
class SharedRealmMembershipKeysTest {

    private val crypto = DefaultSyncCryptoManager(
        ed25519 = BouncyCastleEd25519Backend(),
        x25519 = BouncyCastleX25519Backend(),
        aead = BouncyCastleAeadBackend(),
        kdf = BouncyCastleKdfBackend(),
    )

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(identityServiceCommonModule)
    }
    private val context = createTestContext(registry)

    @Test
    fun create_thenResolve_recoversTheSharedRealmMasterKey() {
        val masterKey = ByteArray(32) { (it * 7 + 1).toByte() } // a stand-in A12
        val member = crypto.deriveRealmEncryptionKeyPair(Bip39.generateMnemonic()) // A13

        val membership = SharedRealmMembershipKeys.create(
            context = context,
            crypto = crypto,
            sharedRealmId = "realm-1",
            sharedRealmMasterKey = masterKey,
            recipientEncPublicKey = member.publicKey,
            role = SharedRealmMembership.Role.MEMBER,
        )

        // The row carries only ciphertext, never the plaintext A12.
        assertEquals("realm-1", membership.sharedRealmId.get())
        val wrapped = membership.wrappedMasterKey.get()
        assertTrue(wrapped.size > masterKey.size, "wrapped blob is ephemeralPub + sealed")
        assertFalse(
            wrapped.toList().windowed(masterKey.size).any { it == masterKey.toList() },
            "plaintext A12 must not appear in the wrapped row",
        )

        val recovered = SharedRealmMembershipKeys.resolveMasterKey(
            crypto = crypto,
            membership = membership,
            myEncPrivateKey = member.privateKey,
        )
        assertContentEquals(masterKey, recovered)
    }

    @Test
    fun resolve_byNonRecipientMember_fails() {
        val masterKey = ByteArray(32) { it.toByte() }
        val memberA = crypto.deriveRealmEncryptionKeyPair(Bip39.generateMnemonic())
        val memberB = crypto.deriveRealmEncryptionKeyPair(Bip39.generateMnemonic())

        val membership = SharedRealmMembershipKeys.create(
            context = context,
            crypto = crypto,
            sharedRealmId = "realm-1",
            sharedRealmMasterKey = masterKey,
            recipientEncPublicKey = memberA.publicKey,
            role = SharedRealmMembership.Role.OWNER,
        )

        // Recovery is cryptographically bound to the recipient: member B can't
        // open a membership sealed to member A.
        assertFailsWith<AeadAuthenticationException> {
            SharedRealmMembershipKeys.resolveMasterKey(crypto, membership, memberB.privateKey)
        }
    }
}
