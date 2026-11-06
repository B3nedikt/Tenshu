package app.tenshu.sdk.internal

import org.tenshu.core.common.util.ParsedPairingUri as SharedParsedPairingUri
import org.tenshu.core.common.util.PairingUri as SharedPairingUri

// Parses the QR / paste payload that an existing realm device emits for
// pairing — the canonical role-host form
//   `tenshu://join?o=<ownerId>&d=<base32(deviceId)>&k=<base32(pubkey)>&s=<base32(secret)>`
// via the shared `:core-common` `PairingUri` helper (single cross-platform
// parser; sync-pairing-inverted-direction.md §6 P1 flag day — the legacy
// `tenshu://pair?…` shape is retired with no shim).
//
// `Pairing.submit` is a *joiner* entry point, so only a join URI yields a
// parse here; everything else (offer/legacy/unknown/malformed) returns null
// and surfaces as the contract's `invalidUri` error kind. The apex's
// pre-submit gate (`web/apex/src/lib/pairingUri.ts`) distinguishes the
// legacy/unknown-role cases for user-facing copy before submit is reached.
internal data class ParsedPairingUri(
    val ownerId: String,
    val deviceId: String,
    val otherDevicePublicKey: ByteArray,
    val pairingSecret: String,
)

internal fun parsePairingUri(raw: String): ParsedPairingUri? {
    val parsed = SharedPairingUri.parse(raw) as? SharedParsedPairingUri.Join ?: return null
    return ParsedPairingUri(
        ownerId = parsed.ownerId,
        deviceId = parsed.hostDeviceId,
        otherDevicePublicKey = parsed.hostPublicKey,
        pairingSecret = parsed.pairingSecret.decodeToString(),
    )
}
