package app.tenshu.control.data

import org.tenshu.storage.proxy.StorageFigures
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Local-first decision logic (ADR 0019): the pure [storageAllowsSync] truth
 * table the [StorageSyncGate] delegates to. `SyncSchedulerGateTest`
 * (`:sync-service-proxy`) covers the scheduler honouring the personal-realm
 * `storageGate`; together they cover the local-first gate end-to-end.
 */
class StorageSyncGateTest {

    @Test
    fun blocksWhenFiguresUnknown() {
        // Fail closed during the startup window before the provider has emitted:
        // no relay contact until a subscription is confirmed.
        assertFalse(storageAllowsSync(null))
    }

    @Test
    fun blocksWhenNoSubscription() {
        assertFalse(
            storageAllowsSync(
                StorageFigures(hasStorage = false, usedBytes = 0L, limitBytes = 0L)
            )
        )
    }

    @Test
    fun allowsWhenSubscribed() {
        assertTrue(
            storageAllowsSync(
                StorageFigures(hasStorage = true, usedBytes = 0L, limitBytes = 1_000L)
            )
        )
    }

    @Test
    fun allowsWhenSubscribedEvenWhenOverQuota() {
        // The storage gate only decides *whether to contact the relay at all*
        // (subscription present). Quota enforcement is the op-side Tier-2 guard's
        // job, not the sync gate's — a subscribed-but-full user still syncs
        // (e.g. tombstones / deletes that free space).
        assertTrue(
            storageAllowsSync(
                StorageFigures(hasStorage = true, usedBytes = 2_000L, limitBytes = 1_000L)
            )
        )
    }

    // --- Joiner bootstrap (storage-entitlement-sync.md §3.3) ---

    @Test
    fun joinerBootstrapAllowsSyncEvenWithoutLocalSubscription() {
        // A freshly-paired device has no device-local subscription yet (figures
        // null / hasStorage false) but joined an owner-funded realm — it must be
        // allowed to run its bootstrap pull, which delivers the entitlement that
        // would otherwise never arrive (the cross-device deadlock).
        assertTrue(personalRealmSyncAllowed(figures = null, joinedFundedRealm = true))
        assertTrue(
            personalRealmSyncAllowed(
                figures = StorageFigures(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
                joinedFundedRealm = true,
            )
        )
    }

    @Test
    fun nonJoinerStillBlockedWithoutSubscription() {
        // First device (never joined a funded realm) keeps the original behaviour:
        // no subscription ⇒ local-only, unchanged from before this fix.
        assertFalse(personalRealmSyncAllowed(figures = null, joinedFundedRealm = false))
        assertFalse(
            personalRealmSyncAllowed(
                figures = StorageFigures(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
                joinedFundedRealm = false,
            )
        )
    }

    @Test
    fun subscribedAllowsRegardlessOfJoinerFlag() {
        assertTrue(
            personalRealmSyncAllowed(
                figures = StorageFigures(hasStorage = true, usedBytes = 0L, limitBytes = 1_000L),
                joinedFundedRealm = false,
            )
        )
    }
}
