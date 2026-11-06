package app.tenshu.sdk.internal

import app.tenshu.diagnostics.Diagnostics
import org.tenshu.sync.proxy.SyncScheduler

/**
 * W8 (`drive-web-parity.md` §1.2b). On web the app window **is** the proxy: the sync loop runs
 * on the tab's main thread, so a backgrounded tab does not merely slow down — Chrome throttles
 * its timers and then freezes it outright. Measured gaps between polls were 2 s → 4.5 s → 9.6 s
 * → 25 s → 5 m 45 s → 11 m 37 s → nothing for 55 minutes. `SyncScheduler`'s own ceiling is 30 s,
 * so the app cannot produce the last three; the browser did.
 *
 * ⚠⚠ **This does not make a hidden tab sync, and nothing in a page can.** A frozen tab runs no
 * code — no timer, no coroutine, no `visibilitychange` handler — so "keep draining while
 * hidden" is not a thing to implement more cleverly. What it removes is the *second* half of the
 * failure: on returning, the loop would otherwise wait out the remainder of its 30 s tick (or
 * longer, having been throttled), so the user stares at a stale list and concludes sync is
 * broken. See the doc for why a Service Worker is not the missing piece.
 *
 * ⚠ Also logs the transition. §1.2c is the standing complaint that web bugs cost a day each
 * because nothing is observable; a quiet relay log means "the tab was not allowed to run", not
 * "nothing happened", and there was previously no way to tell those apart after the fact.
 */
internal fun installForegroundWake(scheduler: SyncScheduler) {
    val doc: dynamic = js("(typeof document !== 'undefined' ? document : null)")
    if (doc == null) {
        // No DOM (a worker, or a non-browser test host). Nothing to listen to.
        return
    }

    var hiddenSinceMs: Double = 0.0
    val handler: (dynamic) -> Unit = handler@{ _ ->
        val nowMs: Double = js("Date.now()") as Double
        if (doc.visibilityState == "visible") {
            val awayMs = if (hiddenSinceMs > 0.0) (nowMs - hiddenSinceMs).toLong() else 0L
            hiddenSinceMs = 0.0
            Diagnostics.i("Sync", "foreground after ${awayMs}ms hidden — waking the sync loop")
            scheduler.notifyForegrounded()
        } else {
            hiddenSinceMs = nowMs
            Diagnostics.i("Sync", "backgrounded — this tab's timers throttle and may freeze")
        }
    }
    doc.addEventListener("visibilitychange", handler)
}
