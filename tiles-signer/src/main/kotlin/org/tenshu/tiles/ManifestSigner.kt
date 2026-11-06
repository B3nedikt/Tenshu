package org.tenshu.tiles

import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.Ed25519Backend

/**
 * Signs the catalog with the **in-tree Ed25519** (`BouncyCastleEd25519Backend`) — the same
 * primitive the client's `RegionCatalogVerifier` calls (`Ed25519Backend::verify`), so there is one
 * signature stack, not two (D3/§9). A detached signature over the raw `catalog.json` bytes.
 */
class ManifestSigner(
    private val ed25519: Ed25519Backend = BouncyCastleEd25519Backend(),
) {

    data class Keypair(val privateKeySeed: ByteArray, val publicKey: ByteArray)

    /** A fresh signing keypair. The 32-byte seed is the private key; publish the public key only. */
    fun generateKeypair(seed: ByteArray = randomSeed()): Keypair =
        Keypair(privateKeySeed = seed, publicKey = ed25519.derivePublicKey(seed))

    /** Detached signature over [catalogBytes] under the [privateKeySeed]. */
    fun sign(catalogBytes: ByteArray, privateKeySeed: ByteArray): ByteArray =
        ed25519.sign(privateKeySeed, catalogBytes)

    fun publicKeyFor(privateKeySeed: ByteArray): ByteArray = ed25519.derivePublicKey(privateKeySeed)

    private fun randomSeed(): ByteArray =
        java.security.SecureRandom().generateSeed(Ed25519Backend.ED25519_SEED_SIZE)
}
