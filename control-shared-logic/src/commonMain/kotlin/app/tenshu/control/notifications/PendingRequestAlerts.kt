package app.tenshu.control.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Phase 3 OS elevation (`docs/proposals/control-central-notifications.md` §4.3.4):
 * the edge logic that turns the [PendingRequestsRepository] `requests` stream into
 * *arrived* / *resolved* transitions, so a platform can alert on a newly-inbound
 * actionable request and clear it once resolved.
 *
 * Pure (no platform imports; mirrors `ForegroundTaskLiveness`) so the arrival/removal
 * diff is unit-testable on the JVM; the platform effect — on Android a
 * HIGH-importance channel + `notify`/`cancel` — lives behind [PendingRequestAlertHandle].
 *
 * Semantics: an **id** seen for the first time fires [PendingRequestAlertHandle.onRequestArrived];
 * an id that drops out of the feed (resolved by acting, or dismissed) fires
 * [PendingRequestAlertHandle.onRequestResolved]. State is per-instance/per-process, so a
 * request still pending after a process restart re-alerts once — acceptable because the
 * notification id is stable (the shade replaces, not stacks).
 */
class PendingRequestAlerts(
    private val handle: PendingRequestAlertHandle,
) {
    private var known: Set<String> = emptySet()

    /** Collect [requests] for the lifetime of [scope], driving [handle]. */
    fun launchIn(scope: CoroutineScope, requests: StateFlow<List<PendingRequest>>) {
        scope.launch {
            requests.collect(::onRequests)
        }
    }

    /**
     * Diff one observed [requests] snapshot against the known set: alert the
     * newly-arrived, cancel the now-gone. Idempotent for ids that persist across
     * emissions (a request that stays pending is neither re-alerted nor cancelled).
     */
    internal fun onRequests(requests: List<PendingRequest>) {
        val currentIds = requests.mapTo(mutableSetOf()) { it.id }
        requests.filter { it.id !in known }.forEach(handle::onRequestArrived)
        (known - currentIds).forEach(handle::onRequestResolved)
        known = currentIds
    }
}

/**
 * The platform effect of a pending-request transition. An interface so
 * [PendingRequestAlerts]'s diff can be exercised without a platform runtime.
 */
interface PendingRequestAlertHandle {
    /** A request id appeared in the feed — post (or refresh) its notification. */
    fun onRequestArrived(request: PendingRequest)

    /** A request id left the feed (acted on / dismissed) — cancel its notification. */
    fun onRequestResolved(id: String)
}
