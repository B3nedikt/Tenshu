package app.tenshu.hub

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.getOrNull
import org.tenshu.sync.OfferHubPairing
import org.tenshu.sync.OfferPairingSession
import org.tenshu.sync.OfferStatus
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getOfferStatus
import org.tenshu.sync.offerHubPairing
import org.tenshu.sync.offerPairing

/** Terminal state of one offer session ([HubPairingCli.runOnce]). */
sealed interface PairingOutcome {
    /** Keys are installed proxy-side; [sas] is shown for the post-hoc comparison. */
    data class Approved(val sas: String) : PairingOutcome
    data object Denied : PairingOutcome
    data object TimedOut : PairingOutcome
    data class Failed(val reason: String) : PairingOutcome
}

/**
 * The three sync operations [HubPairingCli] drives, behind an interface.
 *
 * The seam exists to make the session deadline testable: proving that a wedged relay is bounded
 * needs a call that never answers, and [TenshuClient] is a concrete class offering no such point.
 * Production always uses the client-backed implementation, which the `HubPairingCli(client, ...)`
 * constructor supplies.
 */
interface PairingOps {
    suspend fun offerPairing(deviceName: String): TResult<OfferPairingSession>
    suspend fun offerHubPairing(printedUri: String, deviceName: String): TResult<OfferPairingSession>
    suspend fun getOfferStatus(pairingSecretHash: String): TResult<OfferStatus>
}

private class ClientPairingOps(private val client: TenshuClient) : PairingOps {
    override suspend fun offerPairing(deviceName: String) =
        client.syncService.offerPairing(deviceName)

    override suspend fun offerHubPairing(printedUri: String, deviceName: String) =
        client.syncService.offerHubPairing(printedUri, deviceName)

    override suspend fun getOfferStatus(pairingSecretHash: String) =
        client.syncService.getOfferStatus(pairingSecretHash)
}

/**
 * The hub pairing CLI. Two arming modes over the same `GetOfferStatus` poll/completion:
 *
 *  - [runHubOnce] — **H2 headless pairing** (hub proposal §11): the product-shaped
 *    printed-static-key variant. `OfferHubPairing` posts a signed ephemeral offer to the
 *    relay's hub-offer mailbox; the user scans the label printed on the device.
 *  - [runOnce] — the **H1 legacy terminal-QR fallback** (`--legacy-offer-qr`, kept until
 *    H2 is proven on-device): the STANDARD offerer role (`docs/sync/pairing-flow.md`
 *    §4.4 "Native (offerer)") with the fresh `tenshu://offer` QR rendered as ASCII.
 *
 * Either way, on approval the proxy has already installed the keys (the op's contract),
 * so the caller just continues into normal headless operation.
 */
class HubPairingCli(
    private val ops: PairingOps,
    private val out: (String) -> Unit = ::println,
    private val pollInterval: Duration = 2.seconds,
    private val timeout: Duration = 5.minutes,
    private val invertQr: Boolean = false,
) {

    constructor(
        client: TenshuClient,
        out: (String) -> Unit = ::println,
        pollInterval: Duration = 2.seconds,
        timeout: Duration = 5.minutes,
        invertQr: Boolean = false,
    ) : this(ClientPairingOps(client), out, pollInterval, timeout, invertQr)

    /** Runs a single offer session to its terminal state. Callers decide whether to re-arm. */
    suspend fun runOnce(deviceName: String): PairingOutcome = withSessionDeadline { armed ->
        val session = when (val result = ops.offerPairing(deviceName)) {
            is TResult.Failure ->
                return@withSessionDeadline PairingOutcome.Failed("offerPairing failed: ${result.error}")
            else -> result.getOrNull()
                ?: return@withSessionDeadline PairingOutcome.Failed("offerPairing returned no session")
        }
        val offerUri = session.offerUri.get()
        val hash = session.pairingSecretHash.get()
        armed()

        out("")
        out(AsciiQr.render(offerUri, invert = invertQr))
        out("Offer URI: $offerUri")
        out("[armed] Scan this code with a paired Tenshu device (Add a device -> Scan).")
        out("[armed] Waiting for approval (timeout $timeout, poll $pollInterval)...")

        pollForOutcome(hash, showSas = true)
    }

    /**
     * H2 arming (hub proposal §11.3): one **printed-static-key** offer session. Instead of
     * displaying a fresh QR, the hub posts a signed ephemeral offer to the relay's
     * `/sync/pairing/hub-offer` mailbox — the user scans the label already PRINTED on the
     * device — and polls the standard return leg. No SAS line on approval: the phone's
     * signature verification against the printed key replaces the comparison ceremony.
     *
     * v1 arming = process start / the `pair` subcommand, with the caller's bounded
     * re-arm loop; a physical GPIO arming button (Hue-button model, §11.3) is a later
     * Pi concern and would gate exactly this call.
     */
    suspend fun runHubOnce(printedUri: String, deviceName: String): PairingOutcome =
        withSessionDeadline { armed ->
            val session = when (val result = ops.offerHubPairing(printedUri, deviceName)) {
                is TResult.Failure -> {
                    val message = (result.error as? GenericError)?.message?.get() ?: result.error.toString()
                    return@withSessionDeadline if (message.startsWith(OfferHubPairing.ERROR_RELAY_UNSUPPORTED)) {
                        PairingOutcome.Failed(
                            "the relay does not support hub pairing yet — " +
                                "redeploy the relay, or fall back to --legacy-offer-qr",
                        )
                    } else {
                        PairingOutcome.Failed("offerHubPairing failed: $message")
                    }
                }
                else -> result.getOrNull()
                    ?: return@withSessionDeadline PairingOutcome.Failed("offerHubPairing returned no session")
            }
            val hash = session.pairingSecretHash.get()
            armed()

            out("")
            out("[armed] Signed pairing offer posted to the relay.")
            out("[armed] On a paired Tenshu device: Add a device -> Scan, then scan the label PRINTED on this hub.")
            out("[armed] Waiting for approval (timeout $timeout, poll $pollInterval)...")

            pollForOutcome(hash, showSas = false)
        }


    /**
     * Polls until a terminal state. Unbounded by design — [withSessionDeadline] is what stops
     * waiting on it, so a `getOfferStatus` that never returns ends this loop at the deadline
     * instead of only when the next iteration begins. ⚠ It ends the *wait*: the orphaned call
     * itself keeps running, for the reason [withSessionDeadline] spells out.
     */
    private suspend fun pollForOutcome(hash: String, showSas: Boolean): PairingOutcome {
        while (true) {
            delay(pollInterval)
            // A transient poll failure (relay blip) is not a terminal state — keep polling
            // inside the session budget, mirroring the wizard VM's tolerance.
            val status = ops.getOfferStatus(hash).getOrNull() ?: continue
            when (status.state.get()) {
                OfferStatus.APPROVED -> {
                    val sas = status.sas.get()
                    out("[approved] Keys installed — this hub is now a realm device.")
                    if (showSas) {
                        out("[approved] Confirmation code (compare with the approving device): $sas")
                    }
                    return PairingOutcome.Approved(sas)
                }
                OfferStatus.DENIED -> {
                    out("[denied] The responder declined this pairing offer.")
                    return PairingOutcome.Denied
                }
                else -> Unit // pending — keep polling
            }
        }
    }

    /**
     * Bounds a whole offer session by [timeout] — arming included.
     *
     * ⚠⚠ The budget used to be a loop guard: `offerPairing` ran before the loop with nothing
     * covering it, and `while (elapsedNow() < timeout)` was tested at the TOP of each poll, so it
     * bounded when a poll *started*, never when `getOfferStatus` *returned*. [timeout] therefore
     * did not bound a session at all: a wedged relay hung the hub forever, and `Main`'s re-arm
     * loop — which treats [PairingOutcome.TimedOut] as "mint a fresh offer" — could never run.
     * ⚠ In production that budget is this parameter's 5-minute default; `tenshu-hub` exposes no
     * flag for it, so nobody could shorten their way out of the hang either.
     * `docs/tech-debt.md` § "Two 30-second budgets".
     *
     * ⚠⚠ **What this bounds is the WAIT, not the operation.** These are ordinary `IoOperation`s,
     * so `ExecutionContext` runs the implementation with `scope.async { … }.await()` where `scope`
     * is the long-lived `sessionScope` — a child of the session, not of this coroutine. Cancelling
     * the await therefore orphans the call rather than unwinding it: a wedged relay leaks one
     * in-flight request per re-arm window, and a late `getOfferStatus` can still install keys
     * (that op's contract) after this returned [PairingOutcome.TimedOut]. That is strictly better
     * than hanging forever, and it is not the whole fix. Making the op itself unwind needs the
     * caller-bound evaluation scope `CancellableLocalTaskExecutor.executeAsyncCancellable` opens,
     * which `DefaultInteractorProvider` selects only for a `withTask`-tagged call — a sync-lane
     * change, and one the [PairingOps] fake cannot verify because it bypasses the executor
     * entirely. Recorded in `docs/tech-debt.md`, not fixed here.
     *
     * [block] reports arming so the expiry message can say which half ran out; only a
     * [TimeoutCancellationException] is caught, so cancellation from the caller still propagates.
     */
    private suspend fun withSessionDeadline(
        block: suspend (armed: () -> Unit) -> PairingOutcome,
    ): PairingOutcome {
        var armed = false
        // ⚠⚠ `withTimeoutOrNull`, NOT `withTimeout` in a `catch (e: TimeoutCancellationException)`.
        // That catch is not scoped to this deadline: an ENCLOSING `withTimeout` — a caller's own
        // budget, or a test's — throws the same type through here, and catching it reported
        // somebody else's expiry as this session's and swallowed their cancellation.
        // `withTimeoutOrNull` compares the exception's coroutine with its own, so only this
        // deadline yields null. Pinned by `anOuterDeadlineIsNotReportedAsOurs`.
        val outcome = withTimeoutOrNull(timeout) { block { armed = true } }
        if (outcome != null) return outcome
        out(
            if (armed) "[timeout] No approval within $timeout; this offer session is dead."
            else "[timeout] The relay did not arm an offer within $timeout; nothing was posted."
        )
        return PairingOutcome.TimedOut
    }
}
