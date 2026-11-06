package org.tenshu.core.security

/**
 * **Test-only** deterministic [SyncCryptoManager]: XOR "encryption", `publicKey == privateKey`
 * keypairs, and every derivation a [stubEd25519Signature] mix. Tests get reproducible bytes and
 * sign/verify + wrap/unwrap round-trips without a crypto backend, on every target. It
 * authenticates nothing, so it stays in `:core-test-utils`; production crypto is
 * `DefaultSyncCryptoManager`.
 */
class BootstrapSyncCryptoManager : SyncCryptoManager {
    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys {
        val bytes = recoveryPhrase.encodeToByteArray()
        return SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = bytes,
            sharedVolumeKey = bytes.reversedArray()
        )
    }

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray {
        if (key.isEmpty()) return payload
        return payload.mapIndexed { index, b ->
            (b.toInt() xor key[index % key.size].toInt()).toByte()
        }.toByteArray()
    }

    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray {
        return encrypt(encryptedPayload, key)
    }

    override fun generateKeyPair(): SyncCryptoManager.KeyPair {
        val dummy = "dummy".encodeToByteArray()
        return SyncCryptoManager.KeyPair(dummy, dummy)
    }

    /**
     * A FRESH 32-byte "key" per device (stub: pub == priv, which [verifyEd25519] relies on).
     *
     * ⚠⚠ Not [generateKeyPair]'s fixed `"dummy"`: since workstream B a device's id is derived from
     * this key (`deviceIdForPublicKey`), so a shared constant would give every stub device in a test
     * the SAME id — two proxies would see each other as themselves. The UUIDs ids used to be were
     * random too, so no test could pin one.
     */
    override fun generateDeviceSigningKeyPair(): SyncCryptoManager.KeyPair {
        val key = kotlin.random.Random.nextBytes(32)
        return SyncCryptoManager.KeyPair(key, key.copyOf())
    }

    override fun calculateSharedSecret(
        privateKey: ByteArray,
        otherPublicKey: ByteArray
    ): ByteArray {
        return privateKey
    }

    // A1: publicKey == privateKey, a deterministic mix of the phrase, so sign/verify round-trips.
    override fun deriveMasterKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        val seed = stubEd25519Signature(
            key = "tenshu-master-key-stub".encodeToByteArray(),
            message = recoveryPhrase.encodeToByteArray()
        )
        return SyncCryptoManager.KeyPair(publicKey = seed, privateKey = seed)
    }

    // A13: a distinct label from deriveMasterKeyPair, so A1 ≠ A13 holds even here.
    override fun deriveRealmEncryptionKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        val seed = stubEd25519Signature(
            key = "tenshu-realm-enc-key-stub".encodeToByteArray(),
            message = recoveryPhrase.encodeToByteArray()
        )
        return SyncCryptoManager.KeyPair(publicKey = seed, privateKey = seed)
    }

    // A12: deliberately the same 32 bytes on every call, so tests are reproducible.
    override fun generateSharedRealmMasterKey(): ByteArray =
        stubEd25519Signature(
            key = "tenshu-shared-realm-master-stub".encodeToByteArray(),
            message = byteArrayOf(1)
        )

    // Stub HKDF split: two distinct deterministic 32-byte keys off the A12 master,
    // mirroring the real domain-separated derivation so RealmContext is keyed the same.
    override fun deriveSharedRealmKeys(masterKey: ByteArray): SyncCryptoManager.DerivedKeys =
        SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = stubEd25519Signature(masterKey, "shared-identity".encodeToByteArray()),
            sharedVolumeKey = stubEd25519Signature(masterKey, "shared-volume".encodeToByteArray()),
        )

    // Deterministic sealed-box stub mirroring the real `ephemeralPub(32) || sealed`
    // shape. The recipient's public key is bound at the front; since the stub keeps
    // publicKey == privateKey, [unwrapKeyForIdentity] rejects a wrong key (the stub's
    // analogue of the real AEAD authentication failure). Lets self-join + membership
    // round-trip in-process without the real X25519 backend.
    override fun wrapKeyForIdentity(key: ByteArray, recipientEncPublicKey: ByteArray): ByteArray {
        val mask = stubEd25519Signature(recipientEncPublicKey, "key-wrap".encodeToByteArray())
        val sealed = ByteArray(key.size) { (key[it].toInt() xor mask[it % mask.size].toInt()).toByte() }
        return recipientEncPublicKey + sealed
    }

    override fun unwrapKeyForIdentity(wrapped: ByteArray, myEncPrivateKey: ByteArray): ByteArray {
        require(wrapped.size > 32) { "wrapped key blob too short: ${wrapped.size}" }
        val boundPub = wrapped.copyOfRange(0, 32)
        require(boundPub.contentEquals(myEncPrivateKey)) { "stub unwrap: recipient key mismatch" }
        val sealed = wrapped.copyOfRange(32, wrapped.size)
        val mask = stubEd25519Signature(boundPub, "key-wrap".encodeToByteArray())
        return ByteArray(sealed.size) { (sealed[it].toInt() xor mask[it % mask.size].toInt()).toByte() }
    }

    // Deterministic stub of the generalised sealed box, mirroring the
    // wrapKeyForIdentity stub shape: recipient pub bound at the front
    // (publicKey == privateKey stub invariant), plus a 32-byte stub tag over
    // (info, aad) so a wrong info/aad fails loudly at unseal — the stub's
    // analogue of the real AEAD/AAD authentication failure.
    override fun sealToIdentity(
        plaintext: ByteArray,
        recipientEncPublicKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val mask = stubEd25519Signature(recipientEncPublicKey, info)
        val tag = stubEd25519Signature(mask, info + aad)
        val sealed = ByteArray(plaintext.size) {
            (plaintext[it].toInt() xor mask[it % mask.size].toInt()).toByte()
        }
        return recipientEncPublicKey + tag + sealed
    }

    override fun unsealFromIdentity(
        sealed: ByteArray,
        myEncPrivateKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(sealed.size >= 64) { "sealed blob too short: ${sealed.size}" }
        val boundPub = sealed.copyOfRange(0, 32)
        require(boundPub.contentEquals(myEncPrivateKey)) { "stub unseal: recipient key mismatch" }
        val mask = stubEd25519Signature(boundPub, info)
        val tag = sealed.copyOfRange(32, 64)
        require(tag.contentEquals(stubEd25519Signature(mask, info + aad))) {
            "stub unseal: info/aad mismatch"
        }
        val body = sealed.copyOfRange(64, sealed.size)
        return ByteArray(body.size) { (body[it].toInt() xor mask[it % mask.size].toInt()).toByte() }
    }

    // verifyEd25519 recomputes this from the public key — valid only because publicKey == privateKey.
    override fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray =
        stubEd25519Signature(privateKey, message)

    override fun verifyEd25519(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean = signature.contentEquals(stubEd25519Signature(publicKey, message))
}

/**
 * **Test-only** stub "signature": a deterministic 32-byte mix of (key, message) that
 * [BootstrapSyncCryptoManager.verifyEd25519] recomputes. Not a MAC, not a signature.
 */
fun stubEd25519Signature(key: ByteArray, message: ByteArray): ByteArray {
    if (key.isEmpty()) return ByteArray(32)
    val out = ByteArray(32)
    for (i in 0 until 32) {
        var acc = key[i % key.size].toInt()
        for (j in message.indices) {
            // Mix the message byte with a position-dependent rotation so byte order matters.
            val rot = (j + i) % 8
            val mixed = (message[j].toInt() shl rot) or (message[j].toInt() ushr (8 - rot))
            acc = acc xor mixed
        }
        out[i] = acc.toByte()
    }
    return out
}
