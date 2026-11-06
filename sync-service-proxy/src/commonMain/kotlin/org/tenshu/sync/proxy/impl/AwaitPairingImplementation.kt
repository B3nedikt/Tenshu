package org.tenshu.sync.proxy.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTBoolean
import org.tenshu.sync.AwaitPairing
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay

/**
 * §7.5 Phase L2 long-running pairing wait. Loops the shared
 * [PairingCompletion] (the *same* completion logic `GetPairingStatus` runs
 * once — no duplication) until the encrypted master key arrives or the budget
 * elapses. Runs as a cancellable Layer-2 task: cancelling the client's
 * [org.tenshu.core.TaskHandle] cancels `requestJob` (cross-process) or the
 * caller coroutine (in-process); the `CancellationException` teardown clears
 * the in-proxy handshake, which subsumes the explicit `CancelPairing` lever
 * once the wait is running.
 */
internal class AwaitPairingImplementation(
    private val relay: SyncRelay,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val pairingSecret = (params?.get(AwaitPairing.Params.PAIRING_SECRET) as TString).get()
        val otherDevicePublicKey =
            (params[AwaitPairing.Params.OTHER_DEVICE_PUBLIC_KEY] as TByteArray).get()

        val completion = PairingCompletion(relay, syncManager, cryptoManager)

        try {
            var attempts = 0
            // `tryComplete` now throws on a relay poll failure (network / CORS / 5xx)
            // rather than masking it as "still pending". A single blip mid-wait shouldn't
            // abort a multi-minute pairing, so tolerate a bounded run of *consecutive*
            // failures and keep polling; only surface the error once the relay stays
            // unreachable across [MAX_CONSECUTIVE_FAILURES] ticks. Any successful poll
            // (paired, or a genuine still-pending 404) resets the counter.
            var consecutiveFailures = 0
            while (attempts < MAX_ATTEMPTS) {
                val completed = try {
                    completion.tryComplete(executionContext, pairingSecret, otherDevicePublicKey)
                } catch (e: TenshuException) {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) throw e
                    delay(POLL_INTERVAL_MS)
                    attempts++
                    continue
                }
                consecutiveFailures = 0
                if (completed) {
                    return true.toTBoolean(executionContext.context) as T
                }
                delay(POLL_INTERVAL_MS)
                attempts++
            }

            // Budget exhausted — same "timed out" signal the wizard already
            // maps from a `false` GetPairingStatus result.
            return false.toTBoolean(executionContext.context) as T
        } catch (e: CancellationException) {
            // Handle cancelled (UI back-out, or a wire cancel from Drive):
            // burn the in-proxy ephemeral handshake (asset A8). Equivalent to
            // what `CancelPairing` did from the wizard, now folded into the
            // task's own teardown so there is no separate lever to forget.
            syncManager.clearHandshake(pairingSecret)
            throw e
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L

        // ~5 minutes — mirrors the old PairingWizardViewModel poll budget so
        // the timeout UX (and the bounded in-proxy lifetime the threat model
        // assumes for asset A8) is unchanged.
        const val MAX_ATTEMPTS = 60

        // How many *consecutive* relay poll failures to tolerate before surfacing the
        // error. Small enough that a genuinely-down relay is reported within ~15 s, large
        // enough to ride out a transient blip.
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
