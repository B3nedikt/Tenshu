package org.tenshu.core.common

import org.tenshu.core.common.security.AeadBackend

/**
 * Size limits of the pairing routes that **both** the relay enforces and the client must fit
 * (F7, threat model T-P-15 / T-P-16). One copy, here in `:core-common` — the lowest module the
 * relay and every client share — so the client's budget cannot drift from the relay's cap.
 */
object PairingWireLimits {

    /**
     * Largest sealed device name (decoded bytes) `POST /sync/pairing/request` and
     * `POST /sync/pairing/hub-offer` accept; anything longer is a `400`.
     *
     * ⚠ The budget for the **name** is much smaller than this and depends on the call: a sealed
     * name is `nonce ‖ AEAD(lp(aad) ‖ pad(be16(len) ‖ name)) ‖ tag` — `encryptWithAad` frames the
     * associated data *inside* the ciphertext — so the AAD (hash, device id, keys) is paid out of
     * the same 1 KiB. Use [maxSealedDeviceNameBytes] rather than subtracting by hand.
     */
    const val MAX_SEALED_DEVICE_NAME_BYTES: Int = 1024

    /** Plaintext bucket of a sealed name: the ciphertext length reveals only the 64-byte bucket. */
    const val SEALED_DEVICE_NAME_PAD_BLOCK: Int = 64

    /** The `be16(len)` frame in front of the padded name. */
    const val SEALED_DEVICE_NAME_LENGTH_PREFIX: Int = 2

    /** `encryptWithAad`'s `be32(aad.size)` frame. */
    private const val AAD_FRAME_PREFIX: Int = 4

    /**
     * Longest UTF-8 device name that seals to at most [MAX_SEALED_DEVICE_NAME_BYTES] when the
     * framed associated data is [associatedDataSize] bytes (before `encryptWithAad`'s own 4-byte
     * prefix). `0` when nothing fits.
     */
    fun maxSealedDeviceNameBytes(associatedDataSize: Int): Int {
        val forPadded = MAX_SEALED_DEVICE_NAME_BYTES - AeadBackend.AEAD_MIN_CIPHERTEXT_SIZE -
            AAD_FRAME_PREFIX - associatedDataSize
        val padded = (forPadded / SEALED_DEVICE_NAME_PAD_BLOCK) * SEALED_DEVICE_NAME_PAD_BLOCK
        return (padded - SEALED_DEVICE_NAME_LENGTH_PREFIX).coerceAtLeast(0)
    }
}
