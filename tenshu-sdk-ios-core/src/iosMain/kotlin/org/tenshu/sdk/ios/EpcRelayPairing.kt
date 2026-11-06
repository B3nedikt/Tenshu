package org.tenshu.sdk.ios

import org.tenshu.core.common.epc.EpcPairingUri
import org.tenshu.core.common.security.DefaultSyncCryptoManager
import org.tenshu.core.common.security.createDefaultSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.common.util.deriveEpcRendezvousSessionId
import org.tenshu.epc.rendezvous.EpcRendezvousChannel
import org.tenshu.epc.rendezvous.RendezvousRelayClient
import org.tenshu.epc.rendezvous.darwinRendezvousHttpClient

/**
 * The **real** iOS EPC pairing client (proposal §10.6, client side). Given a `tenshu://epc/pair#…` URI (scanned or pasted from a Control
 * showing the QR), it derives the rendezvous session, runs the live [EpcRendezvousChannel] handshake
 * over the Darwin-backed relay client, and returns the established channel whose 6-digit SAS the user
 * confirms against Control's screen (EPC *measure 1*, §10.5). The full-duplex channel also carries
 * proxy-pushed `onCallback`s, not just request/response.
 *
 * It talks to a **real relay**: the app posts/polls anonymously by capability (no JWT — `authTokenProvider = null`),
 * and the proxy side is a live Control running `EpcRendezvousAcceptor` (slice 2b). On the simulator,
 * with no live Control, the handshake fails at the transport (connection refused) — proving the wiring
 * links and runs; the SAS round-trip is the on-device gate.
 *
 * Crypto is the **real** libsodium-backed [DefaultSyncCryptoManager]: Control's `EpcResponder` uses
 * the real `SyncCryptoManager`, and the test stub's fake X25519/HKDF/AEAD does not interoperate
 * with it — so a relay round-trip requires real crypto on both ends. The handshake is purely ephemeral (X25519 keypair + ECDH), so the four crypto backends
 * are all this needs; no master/identity keys, no storage.
 */
object EpcRelayPairing {

    /** A live relay-backed EPC channel + its SAS, for the user's confirmation against Control. */
    class Channel internal constructor(val channel: EpcRendezvousChannel) {
        /** The 6-digit SAS the user compares with Control's screen. */
        val sas: String get() = channel.sas

        // ⚠ `activate()` is NOT here: it installs Drive's surfaces, so it is an extension in Drive's
        // code (`LiveClientSession.kt`). Left here it would put Drive — and PhotoKit — into every
        // app's framework, because this object is exported by all of them
        // (`ios-per-app-frameworks.md` §5.3). Swift still calls `channel.activate()`: Kotlin/Native
        // exports an extension on a class as an Obj-C category method.
    }

    /**
     * Parse [pairingUri], run the EPC handshake over the relay it names, and return the live
     * [Channel]. Throws `EpcPairingUriException` on a malformed URI and `RendezvousRelayException`
     * if the relay is unreachable / the session is gone. SKIE surfaces this as Swift `async throws`.
     */
    @OptIn(ExperimentalStdlibApi::class)
    /**
     * [connect], but failure is a **null, never a throw** — for callers that read an invite from
     * the shared Keychain (the DEV_HARNESS pair tabs), where a stale invite is an *expected*
     * state (the relay answers "no such session" whenever Control isn't currently hosting that
     * rendezvous). An exception crossing the SKIE suspend bridge from a cancelled/raced Swift
     * task has no continuation to land on, and an uncaught coroutine exception aborts the whole
     * process on Kotlin/Native (observed live: signal 6 on a stale-invite launch). Kotlin-side
     * containment removes that class entirely. Cancellation still propagates.
     */
    suspend fun connectOrNull(pairingUri: String): Channel? = try {
        connect(pairingUri)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        println("[epc-pairing] connect failed: ${e.message}")
        null
    }

    suspend fun connect(pairingUri: String): Channel {
        val payload = EpcPairingUri.decode(pairingUri)
        val sessionId = deriveEpcRendezvousSessionId(payload.pairingSecret).toHexString()
        val client = RendezvousRelayClient(
            baseUrl = payload.relayBaseUrl,
            httpClient = darwinRendezvousHttpClient(),
            // The app is protocol-anonymous on the rendezvous mailbox (capability-addressed);
            // only the proxy provisions under a device-key JWT (§10 asymmetric admission).
            authTokenProvider = { null },
        )
        val channel = EpcRendezvousChannel.connect(
            crypto = realCrypto(),
            client = client,
            sessionId = sessionId,
        )
        return Channel(channel)
    }

    /**
     * A real libsodium-backed [SyncCryptoManager] via [createDefaultSyncCryptoManager] — the
     * production `cryptoBackendsModule()` in a fresh isolated Koin app, so this stays
     * self-contained and off any global Koin the app may later start.
     */
    // Called from Drive's pairing + loopback smokes; hidden so Swift's surface is unchanged (`ios-per-app-frameworks.md` §5.3).
    @OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
    @kotlin.native.HiddenFromObjC
    fun realCrypto(): SyncCryptoManager = createDefaultSyncCryptoManager()
}
