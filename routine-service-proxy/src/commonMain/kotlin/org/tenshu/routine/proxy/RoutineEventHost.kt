package org.tenshu.routine.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.routine.common.engine.RosterDevice

/**
 * Subscribes the engine to the bridge-local device-event stream (RE-2).
 *
 * ⚠⚠ **Bound only in a process that hosts a `DeviceBridge`.** Everywhere else
 * there is no [LocalDeviceEventSource] to collect and device-event routines
 * simply never arm — which is RE-D1 working as designed, not a gap.
 *
 * ⭐ Nothing about the engine is hub-specific: this class is the entire
 * difference between a proxy that can serve device-event triggers and one that
 * cannot. The engine is constructed exactly as the scheduled path constructs
 * it, so it shares the process-scoped safety gate — which is what makes a
 * flapping sensor hit the same runaway rail a flapping schedule would.
 */
class RoutineEventHost(
    private val scope: CoroutineScope,
    private val executionContext: ExecutionContext,
    private val implementationFactory: OperationImplementationFactory,
    private val source: LocalDeviceEventSource,
    private val nowEpochMs: () -> Long,
    private val thisDeviceId: () -> String?,
    private val roster: () -> List<RosterDevice> = { emptyList() },
    private val onResults: (List<FiringResult>) -> Unit = {},
) {

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            source.events().collect { change ->
                try {
                    onResults(engine().onDeviceEvent(change))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // ⚠ One bad routine must never take the event stream down
                    // with it — the next motion event has to still fire the
                    // lights. The run row already records per-firing failure;
                    // this is the backstop for a failure outside a firing.
                    println("tenshu-routines: device-event evaluation failed: $t")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun engine() = RoutineEngineFactory.create(
        executionContext = executionContext,
        implementationFactory = implementationFactory,
        nowEpochMs = nowEpochMs,
        thisDeviceId = thisDeviceId,
        roster = roster.takeIf { it().isNotEmpty() }
            ?: RoutineEngineFactory.singleDeviceRoster(thisDeviceId),
        // The whole point of this host existing.
        hostsDeviceBridge = true,
    )
}
