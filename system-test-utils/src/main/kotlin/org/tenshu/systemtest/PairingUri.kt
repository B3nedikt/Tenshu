package org.tenshu.systemtest

import org.tenshu.core.common.util.ParsedPairingUri
import org.tenshu.core.common.util.PairingUri as CanonicalPairingUri

/**
 * Parsed pairing URI as produced by `SyncManagerImpl.getPairingString()` —
 * the canonical `tenshu://join?o=…&d=…&k=…&s=…` form, parsed via the shared
 * `:core-common` `PairingUri` helper (one parser everywhere; the legacy
 * `tenshu://pair?…` form is retired — sync-pairing-inverted-direction.md §6 P1).
 *
 * The out-of-band channel in production is a QR code; in a SystemTest this
 * struct travels as a Kotlin reference between two `TestClient`s.
 */
data class PairingUri(
    val hostPairingPublicKey: ByteArray,
    val secret: String,
    val ownerId: String,
    val deviceId: String
) {
    companion object {
        fun parse(uri: String): PairingUri {
            val parsed = CanonicalPairingUri.parse(uri)
            require(parsed is ParsedPairingUri.Join) {
                "Not a canonical join pairing URI (${parsed::class.simpleName}): $uri"
            }
            return PairingUri(
                hostPairingPublicKey = parsed.hostPublicKey,
                secret = parsed.pairingSecret.decodeToString(),
                ownerId = parsed.ownerId,
                deviceId = parsed.hostDeviceId
            )
        }
    }
}
