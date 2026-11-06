package app.tenshu.control.data

import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.StorageFigures
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.SyncManager

/**
 * Local-first storage gate (ADR 0019, local-first reframe): the [SyncGate] the
 * production wiring passes to `SyncScheduler` (via
 * `initSyncService(storageGate = …)`) to gate the **personal** realm's relay
 * traffic on a bound storage subscription.
 *
 * Reads the live [StorageCapacityHolder.figures] — the same proxy-side latch the
 * Tier-2 upload guard (`ensureCapacity`) reads — so the scheduler and the op
 * guards agree on one source of truth. Until the figures load, and whenever no
 * subscription is bound, the personal realm stays local-only. Shared realms are
 * unaffected — their drain/pull runs outside this gate (owner-funded storage).
 *
 * ## ⚠⚠ "Local-only" gates the PUSH, not the pull — corrected 2026-08-24
 *
 * This KDoc used to claim "nothing is pushed to or pulled from the relay, so an
 * unsubscribed user makes no relay request at all". That is **not what the scheduler
 * does**, and the difference is load-bearing:
 *
 * ```kotlin
 * val pushAllowed = storageGate.shouldSync() && !entitlementBackoff… && !registrationBackoff…
 * val pullAllowed = (pushAllowed || syncManager.realmRelayRegistered) && …
 * ```
 *
 * A realm that is **registered with the relay** keeps pulling even while this gate is
 * shut. That disjunct is not incidental — it is the only reason an unfunded realm can
 * ever become funded: the entitlement that opens this gate arrives *by pull*.
 *
 * ⭐ Demonstrated 2026-08-24. A realm created on iOS sat at **0 blocks** on the relay
 * because its host had no subscription and `joinedFundedRealm` is false for a host.
 * Signing in from its *desktop* peer (same `ownerId`, since a joiner shares the realm
 * owner) bound the account and minted the Free entitlement; the realm went to 11 blocks
 * and the host picked the entitlement up **through this very pull path**, opening its own
 * gate. Read literally, the old wording says that rescue is impossible.
 *
 * ⚠ So do not "restore" the stronger claim by gating pulls here to match the prose. A
 * host that cannot pull cannot be funded from anywhere, which is the deadlock the joiner
 * carve-out below exists to avoid — one step further out.
 *
 * Composed with [ConstraintSyncGate] at the DI layer: the network-policy gate
 * stays the whole-tick `syncGate`; this one is the personal-only `storageGate`.
 *
 * **Joiner bootstrap (storage-entitlement-sync.md §3.3).** A freshly-paired device
 * has no device-local subscription of its own yet, but it joined an *owner-funded*
 * realm — so it must be allowed to run its first pull, which is what delivers the
 * synced entitlement. The gate therefore opens on either a bound local
 * subscription **or** [SyncManager.joinedFundedRealm]; otherwise the joiner would
 * deadlock (blocked from the pull that would unblock it).
 */
class StorageSyncGate(
    private val capacityHolder: StorageCapacityHolder,
    private val syncManager: SyncManager,
) : SyncGate {
    override suspend fun shouldSync(): Boolean =
        personalRealmSyncAllowed(capacityHolder.figures, syncManager.joinedFundedRealm)
}

/**
 * Pure decision: do the latest [figures] permit personal-realm relay traffic?
 *
 * `null` (figures not yet loaded) and `hasStorage == false` (no subscription)
 * both **block**. The local-first default is *no* relay contact until a
 * subscription is confirmed — deliberately the inverse of the Tier-1/Tier-2
 * optimistic-on-unknown convention, which is about not over-blocking a *local*
 * action. Here "unknown" must fail closed so we don't spam the relay during the
 * startup window before the [StorageProvider][org.tenshu.storage.proxy.StorageProvider]
 * has emitted.
 */
internal fun storageAllowsSync(figures: StorageFigures?): Boolean =
    figures?.hasStorage == true

/**
 * Pure decision for the personal-realm gate: a bound local subscription
 * ([storageAllowsSync]) **or** having joined an owner-funded realm via pairing
 * ([joinedFundedRealm], storage-entitlement-sync.md §3.3). The latter is what lets
 * a fresh joiner run the bootstrap pull that delivers its entitlement — without
 * it the joiner is blocked from the very pull that would flip its `hasStorage`.
 */
internal fun personalRealmSyncAllowed(
    figures: StorageFigures?,
    joinedFundedRealm: Boolean,
): Boolean = joinedFundedRealm || storageAllowsSync(figures)
