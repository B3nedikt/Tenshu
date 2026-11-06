package org.tenshu.mapregion.proxy

/**
 * Whether the device is currently on a **metered** connection, for the region-download gate
 * (`maps-region-delivery.md` D5 / §7.1 track 3g).
 *
 * A seam rather than a direct dependency: `:map-region-service-proxy` has no business knowing about
 * `ConnectivityManager`, and Control already owns a `NetworkStateProvider` for the sync constraint gate
 * — this is the one-line adapter onto it, mirroring how `SyncGate` is implemented by
 * `ConstraintSyncGate` in `:control-shared-logic`.
 *
 * ⚠ [Unknown] is the default and **fails open**: a proxy with no signal wired (desktop, iOS, every
 * test) downloads exactly as it did before. A gate that blocked whenever it could not tell would turn
 * "we don't know the network" into "you may not download", which is a worse failure than the one it
 * guards — and silently, since the caller cannot distinguish the two.
 */
fun interface MeteredNetworkSignal {

    fun isMetered(): Boolean

    companion object {
        /** No signal available — never refuses. */
        val Unknown = MeteredNetworkSignal { false }
    }
}
