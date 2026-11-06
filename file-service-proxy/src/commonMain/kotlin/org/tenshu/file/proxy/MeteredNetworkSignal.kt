package org.tenshu.file.proxy

/**
 * Whether the device is currently on a **metered** connection, for the view-mint gate
 * (`drive-xxlarge-thumbnail-rung.md` §4.6 / D-18).
 *
 * A seam rather than a direct dependency: `:file-service-proxy` has no business knowing about
 * `ConnectivityManager`, and Control already owns a `NetworkStateProvider` for the sync constraint
 * gate — this is the one-line adapter onto it, the same shape `:map-region-service-proxy` uses for
 * its region-download gate. ⚠ Deliberately a second copy of that one-method interface rather than a
 * shared type: two modules with no dependency between them each own their gate, as `SyncGate` and
 * `ConstraintSyncGate` do. Promote it to `:core-common` when a third consumer appears, not before.
 *
 * ⚠⚠ **Why this exists at all, when `canPublishContentNow()` is already reachable here.** That
 * answers a different question: it is the user's *sync policy* (`WifiOnly`) plus a relay-quota latch,
 * so with `WifiOnly` off it returns `true` on cellular. A view mint downloads a whole **original**
 * (6–18 MB, far more for a DSLR frame) on a foreground tap, which is not something a user who
 * allowed background sync on mobile data has agreed to.
 *
 * ⚠ [Unknown] is the default and **fails open**, matching the region-download gate: a proxy with no
 * signal wired downloads exactly as it did before, because turning "we cannot tell" into "you may
 * not" is a worse failure than the one it guards, and a silent one. Desktop is unmetered by
 * construction; ⚠⚠ **iOS wires no signal today**, so a view mint there is not metered-gated — the
 * one platform where this contract is aspirational rather than enforced.
 */
fun interface MeteredNetworkSignal {

    fun isMetered(): Boolean

    companion object {
        /** No signal available — never refuses. */
        val Unknown = MeteredNetworkSignal { false }
    }
}
