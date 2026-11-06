package org.tenshu.core.common.transport

import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.common.util.toInt

object StreamFramer {
    const val TYPE_RESPONSE: Byte = 0
    const val TYPE_CALLBACK: Byte = 1

    // Phase L2 (docs/proposals/core-background-processing.md §8). A cancellable
    // call carries a client-minted task id so the proxy can register it; the
    // response echoes the id back so the client routes it to the right waiter
    // (the normal TYPE_RESPONSE path stays a strict serialised request/response
    // and is unchanged). TYPE_CANCEL is the out-of-band cancel RPC.
    const val TYPE_CANCELLABLE_REQUEST: Byte = 2
    const val TYPE_CANCELLABLE_RESPONSE: Byte = 3
    const val TYPE_CANCEL: Byte = 4

    // Correlated plain calls (`docs/protocol/desktop-ipc.md` §2.2). A plain `call()` carries a
    // client-minted **call id** — a transport-level tag only, never a task id: the proxy serves it
    // exactly like a TYPE_RESPONSE request (`ProxyGateway.call` with `taskId = null`, so no
    // in-flight registration, no detach, no sync lane) and echoes the id on the reply.
    //
    // ⚠⚠ A client may send TYPE_CORRELATED_REQUEST only on a connection whose proxy answered
    // TYPE_HELLO with TYPE_HELLO at [WIRE_REVISION_FLOOR] or above; anything else is refused and
    // the connection dropped (the pre-hello wire never reached a user — 1.0.1 re-cut).
    const val TYPE_HELLO: Byte = 5
    const val TYPE_CORRELATED_REQUEST: Byte = 6
    const val TYPE_CORRELATED_RESPONSE: Byte = 7

    /** The proxy could not produce a reply for a correlated call; the body is a UTF-8 reason. */
    const val TYPE_CORRELATED_FAILURE: Byte = 8

    /**
     * Client → proxy: stop evaluating the correlated call whose call id is the body (UTF-8) —
     * `desktop-ipc.md` §2.3, `core-background-processing.md` §6.2.1. No reply.
     *
     * ⚠⚠ Sent only on a connection whose proxy's hello carried revision ≥
     * [WIRE_REVISION_CANCELLABLE_READS]. A revision-1 proxy serves any type it does not know as a
     * plain request, so an unnegotiated cancel would reach it as a callstack to parse — and its
     * `TYPE_RESPONSE` failure would land in the uncorrelated queue.
     */
    const val TYPE_CANCEL_READ: Byte = 9

    /** Wire revision 1: correlated plain calls (§2.2). */
    const val WIRE_REVISION_CORRELATED: Int = 1

    /** Wire revision 2: + [TYPE_CANCEL_READ] (§2.3). */
    const val WIRE_REVISION_CANCELLABLE_READS: Int = 2

    /** The revision this build speaks — what its hello carries. */
    const val WIRE_REVISION: Int = WIRE_REVISION_CANCELLABLE_READS

    /**
     * The lowest proxy revision a client talks to; a hello below it fails the connection closed.
     * Every shipped Control speaks at least this (1.0.1 re-cut). A later revision's feature is
     * gated on the negotiated revision, never on this floor.
     */
    const val WIRE_REVISION_FLOOR: Int = WIRE_REVISION_CANCELLABLE_READS

    /**
     * The TYPE_HELLO body for [revision]: a magic plus the sender's wire revision. Both directions.
     *
     * ⚠ The leading `0xFF` is load-bearing: it makes the body unparseable as a callstack, so an older
     * proxy that routes the hello into `ProxyGateway.call` executes **nothing** and answers with a
     * parse failure. `UnixSocketWireNegotiationTest` pins that against the real parser.
     */
    fun helloBody(revision: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 'T'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(),
        revision.toByte(),
    )

    /** This build's hello: [helloBody] at [WIRE_REVISION]. */
    val HELLO_BODY: ByteArray = helloBody(WIRE_REVISION)

    /**
     * The revision a peer's hello announces. ⚠ Anything that is not a well-formed hello reads as
     * [WIRE_REVISION_CORRELATED] — the peer answered the hello, so it correlates, but nothing more
     * may be assumed of it: guessing high would send it frames it reads as callstacks.
     */
    fun helloRevision(body: ByteArray): Int {
        val magic = helloBody(0)
        if (body.size != magic.size) return WIRE_REVISION_CORRELATED
        for (i in 0 until magic.size - 1) if (body[i] != magic[i]) return WIRE_REVISION_CORRELATED
        return body.last().toInt().coerceAtLeast(WIRE_REVISION_CORRELATED)
    }

    /**
     * Largest payload this protocol will *read*. A frame header is four attacker-controllable
     * bytes and every read loop turned them straight into `ByteBuffer.allocate(length)`, so
     * `0x7FFFFFFF` was a 2 GiB allocation request — one hostile or corrupt header, one dead
     * daemon (or one dead client: the same loop runs there, fed by whatever bound the socket
     * path).
     *
     * ⚠ This is a sanity bound, not a policy limit, and it is deliberately far above any
     * legitimate frame. The desktop transport serialises byte payloads **inline** (unlike
     * Android, where `tokenize` moves them to a descriptor), so a frame legitimately carries a
     * whole file's bytes; anything approaching this ceiling has already been materialised in
     * the sender's heap, so a real payload cannot reach it without that side dying first.
     * A frame over the cap means the stream is hostile or desynchronised — in both cases the
     * connection is finished, and dropping it is the only honest response.
     */
    const val MAX_FRAME_BYTES: Int = 256 * 1024 * 1024

    /**
     * Whether a length read off the wire may be handed to an allocator. Rejects the negative
     * case too: `getInt()` is signed, so a header of `0xFFFFFFFF` arrives as `-1` and reaches
     * `allocate` as an `IllegalArgumentException` — survivable, but by accident rather than by
     * design, and indistinguishable in the logs from a genuine framing bug.
     */
    fun isValidFrameLength(length: Int, max: Int = MAX_FRAME_BYTES): Boolean = length in 0..max

    fun frame(payload: ByteArray, type: Byte = TYPE_RESPONSE): ByteArray {
        val lengthHeader = payload.size.toByteArray()
        val typeHeader = byteArrayOf(type)
        return lengthHeader + typeHeader + payload
    }

    /** `[4-byte UTF-8 taskId length][taskId UTF-8][body]` */
    fun packTagged(taskId: String, body: ByteArray): ByteArray {
        val id = taskId.encodeToByteArray()
        return id.size.toByteArray() + id + body
    }

    /**
     * Inverse of [packTagged].
     *
     * ⚠ The embedded id length is as attacker-controllable as the frame header, and this used
     * to trust it: a negative value threw `IllegalArgumentException` out of `copyOfRange`, one
     * past the payload threw `IndexOutOfBoundsException`, and both surfaced as an anonymous
     * exception from inside a read loop. Validated up front so a malformed frame is a stated
     * framing error rather than an arbitrary runtime failure with no context.
     */
    fun unpackTagged(payload: ByteArray): Pair<String, ByteArray> {
        require(payload.size >= Int.SIZE_BYTES) {
            "tagged frame is ${payload.size} B, too short to carry its task-id length"
        }
        val idLen = payload.copyOfRange(0, Int.SIZE_BYTES).toInt()
        require(idLen >= 0 && idLen <= payload.size - Int.SIZE_BYTES) {
            "tagged frame declares a $idLen B task id but carries ${payload.size - Int.SIZE_BYTES} B"
        }
        val idEnd = Int.SIZE_BYTES + idLen
        val taskId = payload.copyOfRange(Int.SIZE_BYTES, idEnd).decodeToString()
        val body = payload.copyOfRange(idEnd, payload.size)
        return taskId to body
    }
}
