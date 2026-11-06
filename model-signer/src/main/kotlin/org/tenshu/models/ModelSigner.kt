package org.tenshu.models

import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.Ed25519Backend

/**
 * Signs the model catalog with the **in-tree Ed25519** — the same primitive
 * `ModelCatalogSource` verifies with, so there is one signature stack (IN-D10, inheriting
 * `maps-region-delivery.md` D3). A detached signature over the raw `catalog.json` bytes.
 *
 * ⚠ Deliberately the twin of `:tiles-signer`'s `ManifestSigner` rather than a shared abstraction:
 * the two catalogs are signed by DIFFERENT keys and a helper taking "a key" invites signing one
 * with the other's. What must not fork is the primitive, and that comes from `:core-common`.
 */
class ModelSigner(
    private val ed25519: Ed25519Backend = BouncyCastleEd25519Backend(),
) {

    data class Keypair(val privateKeySeed: ByteArray, val publicKey: ByteArray)

    /** A fresh signing keypair. The 32-byte seed is the private key; publish the public key only. */
    fun generateKeypair(seed: ByteArray = randomSeed()): Keypair =
        Keypair(privateKeySeed = seed, publicKey = ed25519.derivePublicKey(seed))

    /** Detached signature over [catalogBytes] under [privateKeySeed]. */
    fun sign(catalogBytes: ByteArray, privateKeySeed: ByteArray): ByteArray =
        ed25519.sign(privateKeySeed, catalogBytes)

    fun publicKeyFor(privateKeySeed: ByteArray): ByteArray = ed25519.derivePublicKey(privateKeySeed)

    private fun randomSeed(): ByteArray =
        java.security.SecureRandom().generateSeed(Ed25519Backend.ED25519_SEED_SIZE)
}
