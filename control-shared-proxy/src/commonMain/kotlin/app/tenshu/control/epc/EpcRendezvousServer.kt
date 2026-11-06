package app.tenshu.control.epc

/**
 * The proxy-side rendezvous subset [DefaultEpcPairingHost] drives, lifted to commonMain so the
 * pairing orchestration is unit-testable without a live relay. The production binding (each
 * platform's `PlatformProxyModule`) wraps a real `EpcRendezvousAcceptor` (`:sync-service-proxy`),
 * which is android/desktop-only — hence this seam rather than referencing it directly here.
 */
interface EpcRendezvousServer {
    /**
     * Reserve the relay rendezvous session under the proxy's device-key JWT (the admission gate).
     * Must complete before the app posts its `ClientHello`. Throws if the relay rejects the
     * provision (session cap reached / id owned by another owner).
     */
    suspend fun provision()

    /**
     * Run the EPC accept loop: complete the handshake, then decrypt each inbound op frame, pass the
     * plaintext request to [handler], and seal the reply. Suspends until cancelled.
     */
    suspend fun serve(handler: suspend (request: ByteArray) -> ByteArray)

    /**
     * Suspends until a peer completes the handshake (driven by a concurrent [serve]), then returns
     * the 6-digit SAS for the user to confirm against the app. A suspend point rather than a polled
     * flag so the host doesn't busy-loop — the production binding bridges the acceptor's `@Volatile`.
     */
    suspend fun awaitEstablishedSas(): String

    /**
     * Seal [plaintext] as an unsolicited proxy→client push (a CALLBACK frame) and append it, so a
     * server-side observer/stream update reaches the paired app. Returns false before the handshake
     * completes (no session yet). The host routes the proxy's `ClientConnection.send` through here.
     */
    suspend fun pushCallback(plaintext: ByteArray): Boolean
}
