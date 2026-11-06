package org.tenshu.core.common.app.proxy

import kotlinx.coroutines.delay
import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.common.proxy.ClientConnection

/**
 * Desktop's [TargetDelivery] (`control-cross-app-open.md` TT-3).
 *
 * ⭐⭐ **The Android constraint does not apply here, and the contrast is the point.** On Android a
 * background proxy may neither start an activity nor raise one, so Control mints a ticket and the
 * foreground caller fires it (§5e.1). On macOS there is no background-activity-launch rule: the
 * Control daemon is unsandboxed and LaunchServices will both launch *and* activate on its behalf.
 * So desktop keeps CO-D3's original shape — Control does everything and the caller does nothing,
 * which is why [TargetDelivery.Delivered.launchTicketId] is always `null` here.
 *
 * ⚠⚠ **Raising is still a separate act from delivering**, exactly as §5e.1 found on Android. A
 * connected handler is sent the request *and* asked to come forward; the push alone would leave the
 * user looking at the app they tapped in. What differs between the platforms is only **who is
 * permitted to raise**, never whether raising is needed.
 *
 * ⚠ [launch] is `open -b`-shaped: idempotent, and on an already-running app it activates rather
 * than starting a second copy.
 */
internal class DesktopTargetDelivery(
    private val connectionFor: (clientId: String) -> ClientConnection?,
    private val launch: (appId: String) -> Boolean,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : TargetDelivery {

    override suspend fun deliver(
        appId: String,
        request: OpenTargetRequest,
        caller: ClientConnection,
    ): TargetDelivery.Delivered? {
        // 1. Already connected: push, then raise. ⚠ Both — see the class KDoc.
        connectionFor(appId)?.let { handler ->
            handler.send(request)
            // ⚠ A failed raise is not a failed open: the handler HAS the request and will show it
            // when the user gets to it. Reporting failure here would retract a delivery that
            // actually happened.
            launch(appId)
            return TargetDelivery.Delivered(launchTicketId = null)
        }

        // 2. Cold. ⚠⚠ The payload cannot ride the launch on desktop: `fun main()` takes no
        //    arguments in either desktop app (CO-D3), so the app has to come up, connect over the
        //    socket, and be pushed to — which is the one case where Control must wait for anything.
        if (!launch(appId)) return null

        val handler = awaitConnection(appId) ?: return null
        handler.send(request)
        return TargetDelivery.Delivered(launchTicketId = null)
    }

    /**
     * Poll until the freshly-launched app is connected, or give up.
     *
     * ⚠ Bounded, and giving up is a real outcome: an app that never connects produces
     * `LAUNCH_FAILED` and a sentence, rather than a caller waiting forever on a boot that failed.
     *
     * ⚠ This is **not** CO-D8's rejected pending-target. Nothing is stored, nothing outlives this
     * call, and if the caller goes away the wait goes with it — it is a bounded wait inside one
     * request, not a queue that survives it.
     */
    private suspend fun awaitConnection(appId: String): ClientConnection? {
        var waited = 0L
        while (waited < connectTimeoutMs) {
            delay(pollIntervalMs)
            waited += pollIntervalMs
            connectionFor(appId)?.let { return it }
        }
        return null
    }

    internal companion object {
        /**
         * ⚠⚠ **A precedent, not a measurement** — `control-cross-app-open.md` §6b's first open
         * question, still open. The tray's `raiseWindowWhenReady` waits ~10 s for the *daemon*, and
         * a cold JVM app with a Compose window to build is not a daemon; it could plausibly need
         * more, or reveal that 10 s is already generous. Measuring it needs a **packaged** bundle
         * (`open -b` finds nothing in a dev tree), which is why this ships as a copied number with
         * its provenance stated rather than a number pretending to be evidence.
         */
        const val CONNECT_TIMEOUT_MS: Long = 10_000

        /** ⚠ Matches the tray's 0.25 s cadence, for the same reason: 40 polls over the bound. */
        const val POLL_INTERVAL_MS: Long = 250
    }
}

/** Desktop's [TargetDelivery], for Control's per-platform proxy wiring. */
fun desktopTargetDelivery(
    connectionFor: (clientId: String) -> ClientConnection?,
    launch: (appId: String) -> Boolean,
): TargetDelivery = DesktopTargetDelivery(connectionFor = connectionFor, launch = launch)
