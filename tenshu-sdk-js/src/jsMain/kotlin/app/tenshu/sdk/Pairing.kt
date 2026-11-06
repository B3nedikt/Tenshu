@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.errorMessage
import app.tenshu.sdk.internal.looksLikeNetworkError
import app.tenshu.sdk.internal.parsePairingUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.offerPairing
import org.tenshu.sync.getOfferStatus
import org.tenshu.sync.cancelPairingOffer
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import org.tenshu.sync.stopPairing
import kotlin.js.Promise

// Existing-realm-device pairing for the web (UX `web-onboarding-ux.md`
// §4a — existing user). Wraps `:sync-service`'s `InitiatePairing` +
// `GetPairingStatus`; see `docs/sync/pairing-flow.md`.
//
// Protocol direction: web is the requester (Device B in pairing-flow
// terms). The user's existing device hosts the QR / pairing code; the
// apex scans or pastes it. The "browser hosts QR, phone scans"
// inversion is the offer flow — see
// `docs/proposals/sync-pairing-inverted-direction.md` (P3 adds
// `Pairing.offer()` here)."
//
// JS contract: `web/sdk/src/onboarding.ts`'s `PairingApi.submit`:
//   submit({pairingUri, deviceName}) →
//     Promise<TResult<PairingSession, PairingSubmitError>>
//   PairingSession {
//     observe(cb: (PairingStatus) => void): () => void
//     cancel(): Promise<void>
//   }
//   PairingStatus =
//     | {kind: 'awaiting', sas: string}
//     | {kind: 'paired'} | {kind: 'denied'}
//     | {kind: 'expired'} | {kind: 'error', message: string}
//
// **URI format.** The canonical role-host form from
// `sync-pairing-flow-improvements.md` §8 — `tenshu://join?o=…&d=…&k=…&s=…` —
// is what Control emits and what the shared `:core-common` `PairingUri`
// helper (via `internal/PairingUri.kt` here) parses. The legacy
// `tenshu://pair?…` shape was retired in the same flag-day change
// (sync-pairing-inverted-direction.md §6 P1); it parses to a typed
// legacy-rejection, surfaced as `invalidUri` at this layer (the apex's
// pre-submit gate owns the distinct user-facing copy).
//
// **SAS source.** Contract surfaces a `sas: string` on the `awaiting`
// state for out-of-band verification. The pairing URI doesn't carry it;
// `:sync-service`'s `InitiatePairing` derives it locally on the proxy
// per `sync-pairing-flow-improvements.md` §3.2 (HKDF-SHA-256 over the
// shared secret + both ephemeral pubkeys → 6-digit decimal) and returns
// it in the `PairingInitiation { publicKey, sas }` response. We pull
// the SAS from the response, not from the parsed URI.
// Host-side pairing for the apex's Settings → Data → Devices section
// (per `web-control-plane.md` §4.3). The web is BOTH a requester (the
// `submit` flow above for new-browser onboarding) and a host (this flow
// for adding another device into an already-active realm). Wraps
// `:sync-service`'s `StartPairing` + `GetPendingPairingRequests` +
// `ApprovePairingRequest` + `StopPairing` — same operation set
// `:control-shared`'s `SyncDashboardViewModel` consumes on Android +
// desktop.
//
// JS contract: `web/sdk/src/onboarding.ts`'s `PairingApi.show`:
//   show() → Promise<TResult<HostPairingSession, PairingShowError>>
//   HostPairingSession {
//     observe(cb: (HostPairingStatus) => void): () => void
//     approve(deviceId: string): Promise<TResult<void>>
//     cancel(): Promise<void>
//   }
//   HostPairingStatus =
//     | {kind: 'showing', pairingUri: string}
//     | {kind: 'request', deviceId, deviceName, sas | null}
//     | {kind: 'paired'} | {kind: 'cancelled'}
//     | {kind: 'error', message: string}
//
// Polling cadence matches `SyncDashboardViewModel` (2 s) so an incoming
// request from a peer device surfaces within roughly the same window
// users already experience on native. SAS verification is surfaced when
// the proxy populates `PairingRequest.sas` per
// `sync-pairing-flow-improvements.md` §3.2; null means the proxy
// couldn't compute the SAS yet (e.g. peer hasn't registered its
// ephemeral pubkey) and the UI should fall through with deviceName-only
// confirmation, matching the v1 native UX.

@JsExport
object Pairing {

    fun submit(pairingUri: String, deviceName: String): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            try {
                val parsed = parsePairingUri(pairingUri)
                    ?: return@promise errorResult("invalidUri", null)

                val client = JsRuntime.tenshuClient
                val initResult = client.syncService.initiatePairing(
                    ownerId = parsed.ownerId,
                    pairingSecret = parsed.pairingSecret,
                    deviceName = deviceName,
                    otherDevicePublicKey = parsed.otherDevicePublicKey,
                )
                when (initResult) {
                    is TResult.Success<*> -> {
                        // Persist deviceName so a future Session.detect()
                        // surfaces `locked` instead of `firstTime`. The
                        // pairing handshake might still time out / be
                        // denied — that's fine; the device-name persists
                        // either way so the apex can route to "unlock"
                        // and let the user re-pair without a fresh
                        // first-time prompt.
                        JsRuntime.sessionStore.setDeviceName(deviceName)
                        JsRuntime.sessionStore.flush()
                        // SAS is computed proxy-side on InitiatePairing
                        // and returned in the PairingInitiation response;
                        // see `sync-pairing-flow-improvements.md` §3.2.
                        val sas = (initResult.value as? org.tenshu.sync.PairingInitiation)
                            ?.sas?.get().orEmpty()
                        val session = buildPairingSession(
                            pairingSecret = parsed.pairingSecret,
                            otherDevicePublicKey = parsed.otherDevicePublicKey,
                            sas = sas,
                        )
                        val ok: dynamic = js("({})")
                        ok.ok = true
                        ok.value = session
                        ok
                    }

                    is TResult.Failure<*> -> errorResult(
                        kind = "other",
                        message = initResult.error.errorMessage(),
                    )

                    else -> errorResult("other", "unexpected result type")
                }
            } catch (t: Throwable) {
                // ktor-client-js surfaces fetch failures as `kotlin.Error`,
                // which `RestSyncRelay`'s internal `catch (e: Exception)`
                // misses. Map those to `network` so apex can show the
                // right copy.
                val msg = t.message ?: t::class.simpleName ?: "unknown"
                val kind = if (looksLikeNetworkError(msg)) "network" else "other"
                errorResult(kind, msg)
            }
        }

    // Host-side pairing kickoff. Calls `StartPairing` to obtain the
    // realm's pairing URI ("tenshu://join?...") and returns a session
    // that polls for incoming requests + exposes approve/cancel.
    fun show(): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            try {
                val client = JsRuntime.tenshuClient
                when (val result = client.syncService.startPairing()) {
                    is TResult.Success<*> -> {
                        val syncStatus = result.value
                        val pairingUri = (syncStatus as? org.tenshu.sync.SyncStatus)
                            ?.pairingString?.get()
                            ?: return@promise errorResult(
                                kind = "other",
                                message = "StartPairing returned no pairingString",
                            )
                        val session = buildHostPairingSession(pairingUri)
                        val ok: dynamic = js("({})")
                        ok.ok = true
                        ok.value = session
                        ok
                    }

                    is TResult.Failure<*> -> errorResult(
                        kind = "other",
                        message = result.error.errorMessage(),
                    )

                    else -> errorResult("other", "unexpected result type")
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.simpleName ?: "unknown"
                val kind = if (looksLikeNetworkError(msg)) "network" else "other"
                errorResult(kind, msg)
            }
        }

    // Offer-flow onboarding (`sync-pairing-inverted-direction.md` §5.1): THIS browser is
    // the new device — it shows the QR and an existing realm device scans it. Symmetric to
    // `submit()` but inverted: `offer()` returns immediately with the `tenshu://offer?…` URI
    // to render, then the session polls `getOfferStatus` until the responder approves/denies.
    // On `paired` the proxy has already installed the realm keys (GetOfferStatus does the
    // decrypt+install), so — like `submit()` — we persist the device name for `Session.detect`.
    //
    // JS contract: `web/sdk/src/onboarding.ts`'s `PairingApi.offer`:
    //   offer(deviceName) → Promise<TResult<OfferPairingSession, PairingSubmitError>>
    //   OfferPairingSession { pairingUri, observe(cb), cancel() }
    //   OfferPairingStatus =
    //     | {kind: 'showing', pairingUri} | {kind: 'paired', sas}
    //     | {kind: 'denied'} | {kind: 'expired'} | {kind: 'error', message}
    fun offer(deviceName: String): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            try {
                val client = JsRuntime.tenshuClient
                when (val result = client.syncService.offerPairing(deviceName)) {
                    is TResult.Success<*> -> {
                        val value = result.value as? org.tenshu.sync.OfferPairingSession
                            ?: return@promise errorResult("other", "OfferPairing returned no session")
                        JsRuntime.sessionStore.setDeviceName(deviceName)
                        JsRuntime.sessionStore.flush()
                        val session = buildOfferSession(
                            offerUri = value.offerUri.get(),
                            pairingSecretHash = value.pairingSecretHash.get(),
                        )
                        val ok: dynamic = js("({})")
                        ok.ok = true
                        ok.value = session
                        ok
                    }

                    is TResult.Failure<*> -> errorResult("other", result.error.errorMessage())
                    else -> errorResult("other", "unexpected result type")
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.simpleName ?: "unknown"
                val kind = if (looksLikeNetworkError(msg)) "network" else "other"
                errorResult(kind, msg)
            }
        }
}

// Polling cadence mirrors SyncDashboardViewModel (2 s) — pending-request
// observation + SAS reveal. The host session lifetime is bounded by the
// underlying proxy `activePairingContext`; we don't impose our own
// timeout here because the proxy already cleans up stale contexts.
private const val HOST_POLL_INTERVAL_MS = 2_000L

private fun buildHostPairingSession(pairingUri: String): dynamic {
    val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val callbacks = mutableListOf<(dynamic) -> Unit>()
    var currentStatus: dynamic = showingStatus(pairingUri)
    // Once we've surfaced a pending request, latch its deviceId so a
    // late approve()/cancel() resolves the right peer. Non-null while
    // the session is in `request` state.
    var pendingDeviceId: String? = null
    var terminal = false

    fun emit(status: dynamic, isTerminal: Boolean) {
        if (terminal) return
        currentStatus = status
        callbacks.toList().forEach { it(status) }
        if (isTerminal) {
            terminal = true
            callbacks.clear()
            pollScope.cancel()
        }
    }

    pollScope.launch {
        while (isActive) {
            val client = JsRuntime.tenshuClient
            val result = try {
                client.syncService.getPendingPairingRequests()
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.simpleName ?: "polling error"
                emit(errorStatus(msg), isTerminal = true)
                return@launch
            }
            when (result) {
                is TResult.Success<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = (result.value as? TList<PairingRequest>)?.toList() ?: emptyList()
                    val request = list.firstOrNull()
                    if (request != null && pendingDeviceId == null) {
                        pendingDeviceId = request.deviceId.get()
                        emit(
                            requestStatus(
                                deviceId = request.deviceId.get(),
                                deviceName = request.deviceName.get(),
                                sas = request.sas?.get(),
                            ),
                            isTerminal = false,
                        )
                    }
                    // No request yet → keep currentStatus = showing.
                    // If pendingDeviceId is already set, we keep it
                    // until approve()/cancel() resolves the session.
                }

                is TResult.Failure<*> -> {
                    emit(errorStatus(result.error.errorMessage()), isTerminal = true)
                    return@launch
                }
            }
            delay(HOST_POLL_INTERVAL_MS)
        }
    }

    val session: dynamic = js("({})")
    session.observe = { cb: (dynamic) -> Unit ->
        cb(currentStatus)
        if (!terminal) callbacks.add(cb)
        val unsub: () -> Unit = { callbacks.remove(cb) }
        unsub
    }
    session.approve = { deviceId: String ->
        GlobalScope.promise {
            try {
                val client = JsRuntime.tenshuClient
                when (val res = client.syncService.approvePairingRequest(deviceId)) {
                    is TResult.Success<*> -> {
                        emit(pairedStatus(), isTerminal = true)
                        val ok: dynamic = js("({})")
                        ok.ok = true
                        ok.value = undefined
                        ok
                    }

                    is TResult.Failure<*> -> errorResult(
                        kind = "other",
                        message = res.error.errorMessage(),
                    )

                    else -> errorResult("other", "unexpected result type")
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.simpleName ?: "approve error"
                val kind = if (looksLikeNetworkError(msg)) "network" else "other"
                errorResult(kind, msg)
            }
        }
    }
    session.cancel = {
        GlobalScope.promise {
            try {
                if (!terminal) {
                    val client = JsRuntime.tenshuClient
                    client.syncService.stopPairing()
                    emit(cancelledStatus(), isTerminal = true)
                }
                undefined
            } catch (t: Throwable) {
                // Best-effort: if the relay/proxy errors during cleanup,
                // still terminate the local session so the UI returns
                // to a sane state. The proxy cleans up stale contexts
                // independently.
                if (!terminal) emit(cancelledStatus(), isTerminal = true)
                undefined
            }
        }
    }
    return session
}

private fun showingStatus(pairingUri: String): dynamic {
    val s: dynamic = js("({})")
    s.kind = "showing"
    s.pairingUri = pairingUri
    return s
}

private fun requestStatus(deviceId: String, deviceName: String, sas: String?): dynamic {
    val s: dynamic = js("({})")
    s.kind = "request"
    s.deviceId = deviceId
    s.deviceName = deviceName
    s.sas = sas
    return s
}

private fun cancelledStatus(): dynamic {
    val s: dynamic = js("({})")
    s.kind = "cancelled"
    return s
}

private fun errorResult(kind: String, message: String?): dynamic {
    val err: dynamic = js("({})")
    err.ok = false
    val errorObj: dynamic = js("({})")
    errorObj.kind = kind
    if (message != null && (kind == "other" || kind == "error")) {
        errorObj.message = message
    }
    err.error = errorObj
    return err
}

// Polling-loop budget: 60 attempts × 5s = 5 minutes, matching the
// existing `PairingWizardViewModel` window in `:control-shared`.
private const val POLL_INTERVAL_MS = 5_000L
private const val POLL_MAX_ATTEMPTS = 60

// How many *consecutive* failed polls to ride out before surfacing an error. A poll now
// fails (rather than silently reporting "still pending") when the relay itself is
// unreachable — network / CORS / 5xx — so a lone blip mid-handshake must not abort a
// pairing the user is actively completing on their other device. A genuine "not approved
// yet" (relay 404) is a *successful* poll and resets the counter; only a relay that stays
// unreachable across this many ticks becomes a terminal error instead of an eventual
// timeout.
private const val POLL_MAX_CONSECUTIVE_FAILURES = 3

private fun buildPairingSession(
    pairingSecret: String,
    otherDevicePublicKey: ByteArray,
    sas: String,
): dynamic {
    val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val callbacks = mutableListOf<(dynamic) -> Unit>()
    var currentStatus: dynamic = awaitingStatus(sas)
    var terminal = false

    fun emit(status: dynamic, isTerminal: Boolean) {
        if (terminal) return
        currentStatus = status
        // Snapshot to tolerate callbacks unsubscribing during dispatch.
        callbacks.toList().forEach { it(status) }
        if (isTerminal) {
            terminal = true
            callbacks.clear()
            pollScope.cancel()
        }
    }

    pollScope.launch {
        var attempts = 0
        var consecutiveFailures = 0
        // Surface a hard poll failure (thrown fetch error, or a `TResult.Failure`) only
        // after it persists across [POLL_MAX_CONSECUTIVE_FAILURES] ticks; a single blip is
        // ridden out. Reused by both failure branches below.
        fun onPollFailure(message: String): Boolean {
            consecutiveFailures++
            if (consecutiveFailures >= POLL_MAX_CONSECUTIVE_FAILURES) {
                emit(errorStatus(message), isTerminal = true)
                return true
            }
            return false
        }
        while (isActive && attempts < POLL_MAX_ATTEMPTS) {
            val client = JsRuntime.tenshuClient
            val result = try {
                client.syncService.getPairingStatus(pairingSecret, otherDevicePublicKey)
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.simpleName ?: "polling error"
                if (onPollFailure(msg)) return@launch
                delay(POLL_INTERVAL_MS)
                attempts++
                continue
            }
            when (result) {
                is TResult.Success<*> -> {
                    consecutiveFailures = 0
                    val v = result.value
                    if ((v as? TBoolean)?.get() == true) {
                        emit(pairedStatus(), isTerminal = true)
                        return@launch
                    }
                    // false = still pending; loop and retry
                }

                is TResult.Failure<*> -> {
                    if (onPollFailure(result.error.errorMessage())) return@launch
                }
            }
            delay(POLL_INTERVAL_MS)
            attempts++
        }
        if (!terminal) {
            emit(expiredStatus(), isTerminal = true)
        }
    }

    val session: dynamic = js("({})")
    session.observe = { cb: (dynamic) -> Unit ->
        cb(currentStatus)
        if (!terminal) {
            callbacks.add(cb)
        }
        val unsub: () -> Unit = { callbacks.remove(cb) }
        unsub
    }
    session.cancel = {
        if (!terminal) {
            terminal = true
            callbacks.clear()
            pollScope.cancel()
        }
        Promise.resolve<Any?>(undefined)
    }
    return session
}

private fun awaitingStatus(sas: String): dynamic {
    val s: dynamic = js("({})")
    s.kind = "awaiting"
    s.sas = sas
    return s
}

private fun pairedStatus(): dynamic {
    val s: dynamic = js("({})")
    s.kind = "paired"
    return s
}

private fun expiredStatus(): dynamic {
    val s: dynamic = js("({})")
    s.kind = "expired"
    return s
}

private fun errorStatus(message: String): dynamic {
    val s: dynamic = js("({})")
    s.kind = "error"
    s.message = message
    return s
}

// ── Offer flow (offerer side) ────────────────────────────────────────────────

/**
 * Offerer session: emits `showing` immediately (the UI renders `pairingUri` as a QR), then
 * polls `getOfferStatus` on the same cadence as the joiner poll until the responder resolves
 * it — `approved` (→ `paired` + SAS, keys already installed proxy-side), `denied`, expiry, or a
 * persistent transport error. `cancel()` zeros the offerer's ephemeral key via `cancelPairingOffer`.
 */
private fun buildOfferSession(offerUri: String, pairingSecretHash: String): dynamic {
    val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val callbacks = mutableListOf<(dynamic) -> Unit>()
    var currentStatus: dynamic = offerShowingStatus(offerUri)
    var terminal = false

    fun emit(status: dynamic, isTerminal: Boolean) {
        if (terminal) return
        currentStatus = status
        callbacks.toList().forEach { it(status) }
        if (isTerminal) {
            terminal = true
            callbacks.clear()
            pollScope.cancel()
        }
    }

    pollScope.launch {
        var attempts = 0
        var consecutiveFailures = 0
        fun onPollFailure(message: String): Boolean {
            consecutiveFailures++
            if (consecutiveFailures >= POLL_MAX_CONSECUTIVE_FAILURES) {
                emit(errorStatus(message), isTerminal = true)
                return true
            }
            return false
        }
        while (isActive && attempts < POLL_MAX_ATTEMPTS) {
            val client = JsRuntime.tenshuClient
            val result = try {
                client.syncService.getOfferStatus(pairingSecretHash)
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.simpleName ?: "polling error"
                if (onPollFailure(msg)) return@launch
                delay(POLL_INTERVAL_MS); attempts++; continue
            }
            when (result) {
                is TResult.Success<*> -> {
                    consecutiveFailures = 0
                    val status = result.value as? org.tenshu.sync.OfferStatus
                    when (status?.state?.get()) {
                        org.tenshu.sync.OfferStatus.APPROVED -> {
                            emit(offerPairedStatus(status.sas.get()), isTerminal = true)
                            return@launch
                        }
                        org.tenshu.sync.OfferStatus.DENIED -> {
                            emit(deniedStatus(), isTerminal = true)
                            return@launch
                        }
                        // pending → keep polling
                    }
                }

                is TResult.Failure<*> -> {
                    if (onPollFailure(result.error.errorMessage())) return@launch
                }
            }
            delay(POLL_INTERVAL_MS); attempts++
        }
        if (!terminal) emit(expiredStatus(), isTerminal = true)
    }

    val session: dynamic = js("({})")
    session.pairingUri = offerUri
    session.observe = { cb: (dynamic) -> Unit ->
        cb(currentStatus)
        if (!terminal) callbacks.add(cb)
        val unsub: () -> Unit = { callbacks.remove(cb) }
        unsub
    }
    session.cancel = {
        if (!terminal) {
            terminal = true
            callbacks.clear()
            pollScope.cancel()
        }
        GlobalScope.promise {
            runCatching { JsRuntime.tenshuClient.syncService.cancelPairingOffer(pairingSecretHash) }
            undefined
        }
    }
    return session
}

private fun offerShowingStatus(pairingUri: String): dynamic {
    val s: dynamic = js("({})")
    s.kind = "showing"
    s.pairingUri = pairingUri
    return s
}

private fun offerPairedStatus(sas: String): dynamic {
    val s: dynamic = js("({})")
    s.kind = "paired"
    s.sas = sas
    return s
}

private fun deniedStatus(): dynamic {
    val s: dynamic = js("({})")
    s.kind = "denied"
    return s
}
