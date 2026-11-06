package org.tenshu.core.common.epc

import org.tenshu.core.common.transport.TaskTransport

/**
 * Drives EPC measure 2 ([AppAttest]) over an established channel — Phase 4a of
 * `docs/proposals/ios-proxy-architecture.md`. A fixed **positional prelude** of
 * two exchanges runs before any operation traffic, fitting the lock-step
 * request/response transports EPC uses (loopback, relay rendezvous):
 *
 *  1. app → proxy: an init frame  → proxy replies with a fresh challenge;
 *  2. app → proxy: the attestation → proxy replies with the verdict.
 *
 * After a successful verdict the proxy serves operations; before it (or after a
 * rejection) operation frames are refused. Because attestation is the first
 * thing on the channel, the prelude is positional — operation frames need no
 * tagging.
 *
 * Client side: [EpcAttestationClient.attest]. Proxy side: [EpcAttestationServer]
 * is a small state machine the proxy's channel handler routes its first two
 * frames into (see the Phase-4a tests for the gated-handler composition; the
 * live acceptor wiring lands with the iOS app, Phase 6).
 */
class EpcAttestationClient(private val attestor: AppAttestor) {

    /** Run the prelude over [transport]; returns the proxy's verdict. */
    suspend fun attest(transport: TaskTransport): AppAttestResult {
        val challenge = transport.call(INIT_FRAME)
        val verdict = transport.call(attestor.attest(challenge))
        return decodeVerdict(verdict)
    }
}

/**
 * Proxy-side attestation state machine. The channel handler feeds it the first
 * two client frames via [onPreludeFrame] (init, then attestation) and reads
 * [isAttested] to gate operations afterwards.
 */
class EpcAttestationServer(
    private val verifier: AppAttestVerifier,
    private val challengeProvider: () -> ByteArray,
) {
    enum class State { AWAIT_INIT, AWAIT_ATTESTATION, ATTESTED, REJECTED }

    var state: State = State.AWAIT_INIT
        private set

    /** Set once verification completes (attested or rejected). */
    var result: AppAttestResult? = null
        private set

    val isAttested: Boolean get() = state == State.ATTESTED

    /** True while the channel is still in the attestation prelude (frames belong to it). */
    val inPrelude: Boolean get() = state == State.AWAIT_INIT || state == State.AWAIT_ATTESTATION

    private var challenge: ByteArray? = null

    /** Process one prelude frame; returns the proxy's reply frame. */
    fun onPreludeFrame(frame: ByteArray): ByteArray = when (state) {
        State.AWAIT_INIT -> {
            val fresh = challengeProvider()
            challenge = fresh
            state = State.AWAIT_ATTESTATION
            fresh
        }
        State.AWAIT_ATTESTATION -> {
            val verdict = verifier.verify(challenge!!, frame)
            result = verdict
            state = if (verdict is AppAttestResult.Attested) State.ATTESTED else State.REJECTED
            encodeVerdict(verdict)
        }
        State.ATTESTED, State.REJECTED ->
            throw EpcAttestationException("attestation prelude already complete ($state)")
    }
}

/** Attestation-prelude protocol error. */
class EpcAttestationException(message: String) : RuntimeException(message)

/** Empty marker the app sends to request a challenge (step 1). */
internal val INIT_FRAME = ByteArray(0)

// Verdict wire form: [1][keyId-utf8] attested, [0][reason-utf8] rejected.

internal fun encodeVerdict(result: AppAttestResult): ByteArray = when (result) {
    is AppAttestResult.Attested -> byteArrayOf(1) + result.keyId.encodeToByteArray()
    is AppAttestResult.Rejected -> byteArrayOf(0) + result.reason.encodeToByteArray()
}

internal fun decodeVerdict(frame: ByteArray): AppAttestResult {
    if (frame.isEmpty()) return AppAttestResult.Rejected("empty verdict")
    val body = frame.copyOfRange(1, frame.size).decodeToString()
    return if (frame[0].toInt() == 1) AppAttestResult.Attested(keyId = body)
    else AppAttestResult.Rejected(reason = body)
}
