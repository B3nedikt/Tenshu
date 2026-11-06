package org.tenshu.core.common.security

import org.tenshu.core.security.SyncCryptoManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

// Phase 4 slice D — wrap/unwrap contract for the IDB master-key wrapper.
// Wired against the real BouncyCastle backends (AEAD = XChaCha20-Poly1305)
// so the round-trip exercises the same envelope shape the JS-side store
// will hold at runtime.
//
// Pinned contracts:
//   - wrap → unwrap returns the original master-key bytes byte-for-byte.
//   - Wrong wrap key fails with `MasterKeyUnwrapException` (auth tag).
//   - Bit-flip in the ciphertext fails with `MasterKeyUnwrapException`.
//   - Re-wrapping the same key produces a *different* blob (fresh nonce
//     each call) — the wrapper isn't deterministic; existing blobs are
//     not invalidated when the source produces a new wrap.
//   - Non-32-byte wrap keys round-trip (HKDF stretch in slice 4.4f).
class MasterKeyWrapperTest {

    private val crypto = DefaultSyncCryptoManager(
        ed25519 = BouncyCastleEd25519Backend(),
        x25519 = BouncyCastleX25519Backend(),
        aead = BouncyCastleAeadBackend(),
        kdf = BouncyCastleKdfBackend(),
    )
    private val wrapper = MasterKeyWrapper(crypto)

    @Test
    fun wrapUnwrap_roundTrips() {
        val masterPrivate = Random(0xA1).nextBytes(32)
        val wrapKey = Random(0xC0FFEE).nextBytes(32)

        val blob = wrapper.wrap(masterPrivate, wrapKey)
        val unwrapped = wrapper.unwrap(blob, wrapKey)

        assertContentEquals(masterPrivate, unwrapped)
    }

    @Test
    fun unwrap_wrongWrapKey_throwsTypedException() {
        val masterPrivate = Random(1).nextBytes(32)
        val wrapKey = Random(2).nextBytes(32)
        val wrongKey = Random(3).nextBytes(32)

        val blob = wrapper.wrap(masterPrivate, wrapKey)

        assertFailsWith<MasterKeyUnwrapException> {
            wrapper.unwrap(blob, wrongKey)
        }
    }

    @Test
    fun unwrap_corruptedBlob_throwsTypedException() {
        val masterPrivate = Random(4).nextBytes(32)
        val wrapKey = Random(5).nextBytes(32)
        val blob = wrapper.wrap(masterPrivate, wrapKey)

        // Flip a byte in the ciphertext. Pick a position past the 24-byte
        // nonce so we're guaranteed to mutate authenticated data, not just
        // the AEAD's IV (which would also fail, just for a different reason
        // — we want the auth-tag failure path specifically).
        val tampered = blob.bytes.copyOf().also { it[30] = (it[30].toInt() xor 0x01).toByte() }

        assertFailsWith<MasterKeyUnwrapException> {
            wrapper.unwrap(WrappedMasterKey(tampered), wrapKey)
        }
    }

    @Test
    fun wrap_isNonDeterministic() {
        // XChaCha20-Poly1305 generates a fresh 24-byte nonce per encryption,
        // so wrapping the same plaintext under the same key twice should
        // produce two distinct blobs. Pin this so a future regression to a
        // deterministic AEAD (which would leak plaintext equality) shows up
        // here.
        val masterPrivate = Random(6).nextBytes(32)
        val wrapKey = Random(7).nextBytes(32)

        val a = wrapper.wrap(masterPrivate, wrapKey)
        val b = wrapper.wrap(masterPrivate, wrapKey)

        assertNotEquals(a, b)
        // Both still round-trip to the same plaintext under the same key.
        assertContentEquals(masterPrivate, wrapper.unwrap(a, wrapKey))
        assertContentEquals(masterPrivate, wrapper.unwrap(b, wrapKey))
    }

    @Test
    fun wrapKeyPair_roundTrips() {
        // Phase 5 producer/unwrap path: wrap a full Ed25519 master keypair
        // (private seed + public key) so the unwrap site doesn't need to
        // re-derive the public key from the seed. The plaintext layout is
        // owned by MasterKeyWrapper (1-byte version + priv + pub); callers
        // get a typed `SyncCryptoManager.KeyPair` back.
        val keyPair = SyncCryptoManager.KeyPair(
            publicKey = Random(0xA1).nextBytes(32),
            privateKey = Random(0xA2).nextBytes(32),
        )
        val wrapKey = Random(0xB0).nextBytes(32)

        val blob = wrapper.wrap(keyPair, wrapKey)
        val unwrapped = wrapper.unwrapKeyPair(blob, wrapKey)

        assertContentEquals(keyPair.privateKey, unwrapped.privateKey)
        assertContentEquals(keyPair.publicKey, unwrapped.publicKey)
    }

    @Test
    fun unwrapKeyPair_wrongWrapKey_throwsTypedException() {
        val keyPair = SyncCryptoManager.KeyPair(
            publicKey = Random(1).nextBytes(32),
            privateKey = Random(2).nextBytes(32),
        )
        val wrapKey = Random(3).nextBytes(32)
        val wrongKey = Random(4).nextBytes(32)

        val blob = wrapper.wrap(keyPair, wrapKey)

        assertFailsWith<MasterKeyUnwrapException> {
            wrapper.unwrapKeyPair(blob, wrongKey)
        }
    }

    @Test
    fun wrapKeyPair_rejectsNon32ByteHalves() {
        val wrapKey = Random(5).nextBytes(32)
        val tooShort = SyncCryptoManager.KeyPair(
            publicKey = Random(6).nextBytes(31),
            privateKey = Random(7).nextBytes(32),
        )
        assertFailsWith<IllegalArgumentException> { wrapper.wrap(tooShort, wrapKey) }

        val tooLong = SyncCryptoManager.KeyPair(
            publicKey = Random(8).nextBytes(32),
            privateKey = Random(9).nextBytes(64),
        )
        assertFailsWith<IllegalArgumentException> { wrapper.wrap(tooLong, wrapKey) }
    }

    @Test
    fun wrapKeyPairWithEntropy_roundTripsBothHalvesAndEntropy() {
        // v2 producer/unwrap path (reveal-phrase support): the blob carries
        // the keypair *and* the 24-word phrase's 32-byte BIP-39 entropy.
        val keyPair = SyncCryptoManager.KeyPair(
            publicKey = Random(0xA1).nextBytes(32),
            privateKey = Random(0xA2).nextBytes(32),
        )
        val entropy = Random(0xEE).nextBytes(32)
        val wrapKey = Random(0xB0).nextBytes(32)

        val blob = wrapper.wrap(keyPair, entropy, wrapKey)
        val (unwrappedKeyPair, unwrappedEntropy) = wrapper.unwrapKeyPairAndEntropy(blob, wrapKey)

        assertContentEquals(keyPair.privateKey, unwrappedKeyPair.privateKey)
        assertContentEquals(keyPair.publicKey, unwrappedKeyPair.publicKey)
        assertContentEquals(entropy, unwrappedEntropy)
        // `unwrapKeyPair` still works against a v2 blob (ignores the entropy).
        assertContentEquals(keyPair.privateKey, wrapper.unwrapKeyPair(blob, wrapKey).privateKey)
    }

    @Test
    fun unwrapKeyPairAndEntropy_v1Blob_returnsNullEntropy() {
        // Back-compat: an existing v1 (keypair-only) blob unwraps to a null
        // entropy, which the reveal-phrase path treats as "unavailable" until
        // the next passkey re-registration upgrades the blob to v2.
        val keyPair = SyncCryptoManager.KeyPair(
            publicKey = Random(0x11).nextBytes(32),
            privateKey = Random(0x12).nextBytes(32),
        )
        val wrapKey = Random(0x13).nextBytes(32)

        val v1Blob = wrapper.wrap(keyPair, wrapKey)
        val (unwrappedKeyPair, entropy) = wrapper.unwrapKeyPairAndEntropy(v1Blob, wrapKey)

        assertContentEquals(keyPair.privateKey, unwrappedKeyPair.privateKey)
        assertNull(entropy)
    }

    @Test
    fun wrapKeyPairWithEntropy_rejectsNon32ByteEntropy() {
        val keyPair = SyncCryptoManager.KeyPair(
            publicKey = Random(0x21).nextBytes(32),
            privateKey = Random(0x22).nextBytes(32),
        )
        val wrapKey = Random(0x23).nextBytes(32)
        assertFailsWith<IllegalArgumentException> {
            wrapper.wrap(keyPair, Random(0x24).nextBytes(16), wrapKey)
        }
    }

    @Test
    fun unwrapKeyPairWithEntropy_wrongWrapKey_throwsTypedException() {
        val keyPair = SyncCryptoManager.KeyPair(
            publicKey = Random(0x31).nextBytes(32),
            privateKey = Random(0x32).nextBytes(32),
        )
        val entropy = Random(0x33).nextBytes(32)
        val wrapKey = Random(0x34).nextBytes(32)
        val blob = wrapper.wrap(keyPair, entropy, wrapKey)

        assertFailsWith<MasterKeyUnwrapException> {
            wrapper.unwrapKeyPairAndEntropy(blob, Random(0x35).nextBytes(32))
        }
    }

    @Test
    fun wrap_nonStandardWrapKeyLengths_roundTrip() {
        // The AEAD requires a 32-byte key; slice 4.4f HKDF-stretches anything
        // else. Cover the short + long cases so callers passing arbitrary
        // wrap-key sources (e.g. the future WebAuthn PRF output, which is
        // 32 bytes today but the spec allows variable length) don't have to
        // know about the constraint.
        val masterPrivate = Random(8).nextBytes(32)

        for (size in listOf(16, 24, 64)) {
            val wrapKey = Random(size).nextBytes(size)
            val blob = wrapper.wrap(masterPrivate, wrapKey)
            assertContentEquals(masterPrivate, wrapper.unwrap(blob, wrapKey))
        }
    }
}
