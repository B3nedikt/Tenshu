package app.tenshu.control.epc

import org.tenshu.core.common.epc.EpcPairingPayload
import org.tenshu.core.common.epc.EpcPairingUri
import org.tenshu.core.common.util.deriveEpcRendezvousSessionId

/**
 * The two derived halves of one EPC pairing (proposal §10.1–§10.3):
 *
 * - [uri] — the `tenshu://epc/pair#…` blob the proxy shows (render as a QR + offer as a
 *   copyable manual string for the camera-less path).
 * - [sessionId] — the hex id keying the relay's `/sync/epc` mailbox that the proxy
 *   `provision()`s. The app, scanning [uri], derives the **same** id from the same secret,
 *   so both ends meet on one mailbox without a directory or either knowing the other's
 *   address.
 */
data class EpcPairingInvite(val uri: String, val sessionId: String)

/**
 * Builds an [EpcPairingInvite] from a fresh pairing [secret] (§10.2/§10.3). Pure — no I/O,
 * no RNG — so it is exhaustively unit-testable; the secret comes from the caller's secure
 * RNG (`org.tenshu.core.common.util.nextSecureRandomBytes` — see
 * `DefaultEpcPairingHost.generateSecret`). The
 * hex session id matches `deriveEpcRendezvousSessionId(secret).toHexString()`, the exact
 * encoding the relay route (`/sync/epc/{sessionId}`) and the app both use.
 */
@OptIn(ExperimentalStdlibApi::class)
fun buildEpcPairingInvite(relayBaseUrl: String, secret: ByteArray): EpcPairingInvite {
    val uri = EpcPairingUri.encode(
        EpcPairingPayload(relayBaseUrl = relayBaseUrl, pairingSecret = secret)
    )
    val sessionId = deriveEpcRendezvousSessionId(secret).toHexString()
    return EpcPairingInvite(uri = uri, sessionId = sessionId)
}
