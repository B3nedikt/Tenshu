package app.tenshu.control.epc

/**
 * The proxy-side EPC pairing seam (proposal §10.3/§10.6). "Show QR" provisions the relay
 * rendezvous session and serves the EPC accept loop; this interface hides that behind two
 * callbacks so [EpcPairingViewModel] stays pure orchestration + state and is unit-testable
 * without a live relay.
 *
 * The production implementation (`DefaultEpcPairingHost`) lives **platform-side**:
 * `EpcRendezvousAcceptor` / `RendezvousRelayClient` come from `:sync-service-proxy` /
 * `:epc-rendezvous-client`, which are on Control's android/desktop source sets only, not
 * commonMain.
 */
fun interface EpcPairingHost {
    /**
     * Mint a fresh pairing secret, provision + serve the EPC rendezvous channel, and:
     *
     * - call [onInvite] once with the `tenshu://epc/pair#…` URI to render (QR / manual string);
     * - call [onSas] once a peer completes the handshake, with the 6-digit SAS the user
     *   confirms against the app's screen (EPC *measure 1*, §10.5 — load-bearing).
     *
     * Suspends until cancelled (the serve loop runs until the screen is dismissed); the impl
     * tears the relay session down on cancellation.
     */
    suspend fun run(onInvite: (pairingUri: String) -> Unit, onSas: (sas: String) -> Unit)
}
