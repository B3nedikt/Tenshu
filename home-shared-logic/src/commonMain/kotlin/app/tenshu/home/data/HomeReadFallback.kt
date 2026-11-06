package app.tenshu.home.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector

/**
 * The shared failure fold for Home's client-backed [DeviceSource] / [HubStatusSource] reads.
 *
 * A dead dispatch (no proxy connection, an older Control without the home wiring) does not
 * always fold into a result — a reactive read can *throw* into its collector instead, which
 * would kill the screen's collection outright. Both sources therefore degrade to an empty
 * emission, which the repository reads as its fall-back-to-mock signal.
 *
 * ⚠ That empty is indistinguishable — to the screen and to a test — from "the realm really has
 * no devices", and here it is worse than blank: it renders *plausible mock devices*, so nothing
 * looks wrong. The rendering is deliberate and unchanged; the diagnosis is what was missing, so
 * the throwable is named before it is folded away. A whole class of iOS route-1 failures hid
 * behind this silence — every void async op threw `NullPointerException` (see
 * `ExecutionContext`'s `execute<Data?>` note), the writes never landed, and the reads that
 * followed were *correctly* empty, which reads exactly like a sync bug and is not one.
 *
 * ⚠ [CancellationException] is rethrown rather than folded: Flow's `catch` operator does **not**
 * exempt it, so swallowing it here would break structured concurrency — a cancelled screen would
 * report "no devices" on its way out instead of simply stopping.
 */
internal suspend fun <T> FlowCollector<List<T>>.emptyOnUnreachable(what: String, e: Throwable) {
    if (e is CancellationException) throw e
    println("[home] $what read failed, falling back to empty: ${e::class.simpleName}: ${e.message}")
    emit(emptyList())
}
