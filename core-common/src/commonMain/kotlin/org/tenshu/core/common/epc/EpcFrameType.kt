package org.tenshu.core.common.epc

/**
 * The 1-byte frame-type tag carried as the **first plaintext byte inside an EPC seal** on the
 * proxy→client direction, so a single ordered reader can tell a reply to a `call` ([DATA]) from an
 * unsolicited server push ([CALLBACK]) — the demux that lets `onCallback` ride the same channel as
 * request/response (proposal §10; the 3b "onCallback over rendezvous" gap).
 *
 * It lives **inside** the seal, so the relay (which stores the sealed frame as an opaque blob) never
 * sees it — the rendezvous data shape is unchanged, no threat-model amendment. Only the holder of the
 * proxy→client key, after `EpcSession.open`, reads the tag. Client→proxy frames carry no tag (that
 * direction is requests only; the proxy already distinguishes the `ClientHello` by handshake state).
 */
object EpcFrameType {
    /** A reply to a client `call`, routed to the waiting caller in FIFO order. */
    const val DATA: Byte = 0

    /** An unsolicited proxy→client push, routed to `TaskTransport.onCallback`. */
    const val CALLBACK: Byte = 1
}
