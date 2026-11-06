package app.tenshu.control.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The edge logic behind Control's "Update Control" OS alerts: turns the schema pull
 * fence's verdict stream into **one** alert per engagement and one clear when it lifts.
 *
 * The fence is a durable condition — it lasts until the user updates Control — so a per-tick
 * alert would be noise, and with no alert a fenced Control can sit for days with no surface
 * anywhere. Pure, like [PendingRequestAlerts], so the diff is
 * testable without a platform; the effect lives behind [SchemaFenceAlertHandle].
 *
 * Verdicts: `null` = not yet known (a cold process before its first tick) and is **never** a
 * transition in either direction; an empty list = clear; anything else = fenced.
 *
 * ⚠ "Once per engagement" survives a process restart only if [memory] does. On Android the
 * process is killed and re-created by every background wake, and a fenced device stays fenced
 * across all of them, so a per-process memory would re-alert hourly; there the memory is
 * persisted. A per-process memory is right where the alert is a log line (desktop).
 */
class SchemaFenceAlerts(
    private val handle: SchemaFenceAlertHandle,
    private val memory: SchemaFenceAlertMemory = SchemaFenceAlertMemory.InProcess(),
) {
    /** Collect [verdicts] for the lifetime of [scope], driving [handle]. */
    fun launchIn(scope: CoroutineScope, verdicts: Flow<List<String>?>) {
        scope.launch { verdicts.collect(::onVerdict) }
    }

    fun onVerdict(fencedServiceIds: List<String>?) {
        if (fencedServiceIds == null) return
        val fenced = fencedServiceIds.isNotEmpty()
        if (fenced == memory.alerted) return
        memory.alerted = fenced
        if (fenced) handle.onFenceEngaged(fencedServiceIds) else handle.onFenceCleared()
    }
}

/** The platform effect of a fence transition. */
interface SchemaFenceAlertHandle {
    /** The fence engaged: this device stopped pulling until Control is updated. */
    fun onFenceEngaged(fencedServiceIds: List<String>)

    /** The fence lifted (Control was updated): withdraw the alert. */
    fun onFenceCleared()
}

/** Whether the current engagement has already been alerted. */
interface SchemaFenceAlertMemory {
    var alerted: Boolean

    class InProcess : SchemaFenceAlertMemory {
        override var alerted: Boolean = false
    }
}
