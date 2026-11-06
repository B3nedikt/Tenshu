package app.tenshu.reachability

/**
 * How a [ControlReachability] learns whether Control's device has a realm (`ADR 0025` §Decision 8).
 *
 * ⚠⚠ **Required, never defaulted, and never a bare nullable.** An engine with no probe reads as
 * writable (`RealmVerdict.UNKNOWN`), so an Android or desktop app that silently forgot to wire one
 * would open its whole realm gate — the inert-seam trap. Every construction
 * says which it is, and an engine without a probe says *why* at the call site.
 * `RealmProbeWiringTest` fails if any Android/desktop wiring module passes [None].
 */
sealed interface RealmProbe {

    /**
     * A real read of Control's realm — `true`/`false`, or `null` for *cannot tell*. Android and
     * desktop wire it to `getUnlockStatus` + `getSyncStatus` through `realmPresence`.
     */
    class Wired(val read: suspend () -> Boolean?) : RealmProbe

    /**
     * Deliberately no probe, with the [reason] stated: iOS route-1 (its provisioning is
     * `ControlProvisioning`'s), the web runtime, a fallback engine with no probe loop, tests. The
     * verdict is then `UNKNOWN` — writable.
     */
    class None(val reason: String) : RealmProbe
}
