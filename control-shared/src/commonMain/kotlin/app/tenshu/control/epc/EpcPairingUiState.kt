package app.tenshu.control.epc

/**
 * State for the proxy-side EPC pairing screen (§10.3): show the QR, then the SAS to confirm.
 *
 * @property pairingUri the `tenshu://epc/pair#…` URI to render as a QR + offer as a manual
 *   string; non-null once serving has started.
 * @property sas the 6-digit short-authentication-string, non-null once a peer completes the
 *   handshake — the user compares it with the app's screen (mandatory; §10.5).
 */
data class EpcPairingUiState(
    val status: EpcPairingStatus = EpcPairingStatus.Idle,
    val pairingUri: String? = null,
    val sas: String? = null,
    val error: String? = null,
)

enum class EpcPairingStatus {
    /** Not yet started. */
    Idle,

    /** Provisioned + serving; QR is up, no peer has handshaken yet. */
    WaitingForApp,

    /** A peer completed the handshake; [EpcPairingUiState.sas] is set for confirmation. */
    Connected,

    /** Provision / serve failed; [EpcPairingUiState.error] explains. */
    Error,
}
