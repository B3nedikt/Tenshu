package org.tenshu.sync.proxy

import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.AdmissionCapabilityCert
import org.tenshu.sync.common.decodeAdmissionCertChain
import org.tenshu.sync.common.encodeAdmissionCertChain

/**
 * The plaintext key bundle the host encrypts (under the pairing ECDH shared secret)
 * and hands a joining device during pairing, and the joiner's parse of it
 * (SR-3b.4 option B, `sync-multi-realm-runtime.md` §3.1; v2 fields per
 * `delegated-device-admission-authority.md` §4.2).
 *
 * Layout is fixed-width 32-byte keys, so the field count is recovered from the blob
 * length — no framing/version byte needed:
 *
 *  - **> 196 bytes** `sharedIdentityKey | sharedVolumeKey | a13PublicKey | a13PrivateKey |
 *    a33PublicKey | a33PrivateKey | certChain` — the v2 shape: the 128-byte base plus the
 *    joiner's **admission keypair (A33)** and its capability-cert chain
 *    (`encodeAdmissionCertChain`, self-framing), making the joiner a delegated admitter.
 *  - **128 bytes** `sharedIdentityKey | sharedVolumeKey | a13PublicKey | a13PrivateKey`
 *    — the realm's two symmetric data keys **plus** A13 (so the
 *    joiner can unwrap shared-realm `Kr`); no admission authority.
 *  - **64 bytes** `sharedIdentityKey | sharedVolumeKey` — host couldn't supply A13
 *    (locked / no phrase); joiner gets correct data keys but no shared-realm access.
 *
 * Any other size is **refused** ([unpack] returns `null`): there is no shape to read it as,
 * and guessing keys from it would install a realm this device can never decrypt.
 *
 * A v2 blob whose admission tail fails to parse degrades to the 128-byte read (keys +
 * A13 intact, joiner is a non-admitter) — never a crash.
 *
 * The relay never sees this plaintext (it forwards only the ciphertext) — House Rule #1.
 */
internal object PairingKeyBlob {

    private const val KEY = 32

    // Smallest possible v2 blob: 4 keys + the A33 pair + a 4-byte cert count. Sizes
    // between 128 and this are refused, like any other unknown size.
    private const val MIN_V2 = 6 * KEY + 4

    /**
     * Host side: build the plaintext blob. A13 is included when [realmEncryptionKeyPair]
     * is non-null; the admission section only on top of that, when the admitter could
     * mint a capability ([admissionKeyPair] + a non-empty [admissionCertChain]) — an
     * A1-less/locked host ships the shorter shapes and the joiner is a non-admitter,
     * today's behaviour.
     */
    fun pack(
        derivedKeys: SyncCryptoManager.DerivedKeys,
        realmEncryptionKeyPair: SyncCryptoManager.KeyPair?,
        admissionKeyPair: SyncCryptoManager.KeyPair? = null,
        admissionCertChain: List<AdmissionCapabilityCert> = emptyList(),
    ): ByteArray = when {
        realmEncryptionKeyPair == null -> derivedKeys.sharedIdentityKey + derivedKeys.sharedVolumeKey

        admissionKeyPair == null || admissionCertChain.isEmpty() ->
            derivedKeys.sharedIdentityKey +
                derivedKeys.sharedVolumeKey +
                realmEncryptionKeyPair.publicKey +
                realmEncryptionKeyPair.privateKey

        else -> derivedKeys.sharedIdentityKey +
            derivedKeys.sharedVolumeKey +
            realmEncryptionKeyPair.publicKey +
            realmEncryptionKeyPair.privateKey +
            admissionKeyPair.publicKey +
            admissionKeyPair.privateKey +
            encodeAdmissionCertChain(admissionCertChain)
    }

    data class Unpacked(
        val derivedKeys: SyncCryptoManager.DerivedKeys,
        val realmEncryptionKeyPair: SyncCryptoManager.KeyPair?,
        val admissionKeyPair: SyncCryptoManager.KeyPair? = null,
        val admissionCertChain: List<AdmissionCapabilityCert> = emptyList(),
    )

    /** Joiner side: parse the decrypted blob by length; `null` for a size no shape has. */
    fun unpack(blob: ByteArray): Unpacked? = when {
        blob.size == 4 * KEY -> baseUnpacked(blob)

        blob.size >= MIN_V2 -> {
            val base = baseUnpacked(blob)
            runCatching {
                base.copy(
                    admissionKeyPair = SyncCryptoManager.KeyPair(
                        publicKey = blob.copyOfRange(4 * KEY, 5 * KEY),
                        privateKey = blob.copyOfRange(5 * KEY, 6 * KEY),
                    ),
                    admissionCertChain = decodeAdmissionCertChain(
                        blob.copyOfRange(6 * KEY, blob.size),
                    ).also { require(it.isNotEmpty()) { "v2 blob with empty cert chain" } },
                )
            }.getOrDefault(base)
        }

        blob.size == 2 * KEY -> Unpacked(
            derivedKeys = SyncCryptoManager.DerivedKeys(
                sharedIdentityKey = blob.copyOfRange(0, KEY),
                sharedVolumeKey = blob.copyOfRange(KEY, 2 * KEY),
            ),
            realmEncryptionKeyPair = null,
        )

        else -> null
    }

    /** The 128-byte `derivedKeys + A13` prefix shared by the v1 4-key and v2 shapes. */
    private fun baseUnpacked(blob: ByteArray): Unpacked = Unpacked(
        derivedKeys = SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = blob.copyOfRange(0, KEY),
            sharedVolumeKey = blob.copyOfRange(KEY, 2 * KEY),
        ),
        realmEncryptionKeyPair = SyncCryptoManager.KeyPair(
            publicKey = blob.copyOfRange(2 * KEY, 3 * KEY),
            privateKey = blob.copyOfRange(3 * KEY, 4 * KEY),
        ),
    )
}
