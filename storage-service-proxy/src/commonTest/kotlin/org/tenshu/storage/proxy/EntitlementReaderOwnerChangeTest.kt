package org.tenshu.storage.proxy

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.identity.Entitlement
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for the locked-start re-derive gap (storage-entitlement-sync.md slice 3).
 *
 * [initEntitlementStorageReader] seeds figures once at proxy-init time, then only
 * recomputes on an [Entitlement] save/delete. On a locked-start device the seed runs
 * *before* unlock, so `ownerId()` is null and the lookup finds nothing — and an
 * entitlement already persisted from a prior session produces no fresh save to trip
 * the observer. Without re-deriving on the owner null→known transition, `hasStorage`
 * stays stuck false and the personal push gate (`StorageSyncGate`) never opens, so the
 * device stores locally but never uploads. This pins the re-derive.
 */
class EntitlementReaderOwnerChangeTest {

    @Test
    fun entitlementPersistedAtLockedStart_reDerivesWhenOwnerAppears() = runTest {
        val registry = createSyntheticTestRegistry().also {
            it.registerConfig(identityServiceCommonModule)
        }
        val proxy = TestProxy(schemaRegistry = registry)
        val provider = EntitlementStorageProvider()
        val now = 5_000L

        // An entitlement for owner-x is already in the DB (minted/synced last session) —
        // saved before the reader exists, so its observer never sees this write.
        proxy.executionContext.saveData(
            Entitlement.create(
                context = proxy.tContext,
                ownerId = "owner-x",
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.FREE,
                validFrom = 0L,
                validUntil = 10_000L,
                publisherSignature = ByteArray(0),
            ),
        )

        // Locked start: the owner is not yet known when the reader seeds.
        var owner: String? = null
        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { owner },
            now = { now },
        )
        assertFalse(
            provider.observe().first().hasStorage,
            "a locked-start seed (null owner) must not yet see the persisted entitlement",
        )

        // Unlock: the owner becomes known. The next save — a *non-entitlement* one, so
        // the old observer (entitlement-only) would have ignored it — must re-derive the
        // figures from the already-persisted entitlement.
        owner = "owner-x"
        proxy.executionContext.saveData(
            SyntheticTestData.Factory.create(proxy.tContext, "post-unlock", 1L),
        )

        assertTrue(
            provider.observe().first().hasStorage,
            "figures must re-derive on the owner null→known transition",
        )
    }

    /**
     * The `scope` backstop: on an **idle** 2nd device the owner can resolve (unlock) with **no**
     * subsequent save to trip the observer, so the save-driven re-derive above never fires. When a
     * session [scope] is provided, the reader polls until the owner appears and re-derives on its own —
     * closing the window where Services shows "sign up" though the synced entitlement is present.
     */
    @Test
    fun ownerResolvesWithNoSave_scopeBackstopReDerives() = runTest {
        val registry = createSyntheticTestRegistry().also {
            it.registerConfig(identityServiceCommonModule)
        }
        val proxy = TestProxy(schemaRegistry = registry)
        val provider = EntitlementStorageProvider()
        val now = 5_000L

        proxy.executionContext.saveData(
            Entitlement.create(
                context = proxy.tContext,
                ownerId = "owner-x",
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.FREE,
                validFrom = 0L,
                validUntil = 10_000L,
                publisherSignature = ByteArray(0),
            ),
        )

        var owner: String? = null
        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { owner },
            now = { now },
            // The backstop runs here; auto-cancelled at test end.
            scope = backgroundScope,
        )
        assertFalse(
            provider.observe().first().hasStorage,
            "a locked-start seed (null owner) must not yet see the persisted entitlement",
        )

        // Unlock: the owner resolves — but nothing is saved (idle device). Only the poll can catch it.
        owner = "owner-x"
        advanceTimeBy(1_500) // past OWNER_RESOLVE_POLL_MS
        runCurrent()

        assertTrue(
            provider.observe().first().hasStorage,
            "the scope backstop must re-derive when the owner resolves with no save",
        )
    }

    /**
     * Regression (2026-07-21, found on-device): Drive showed "Local only" while Control was
     * **unlocked**.
     *
     * The backstop above was armed *only* when the initial derivation saw a null owner, and it
     * exited after the first resolution — so it covered a locked **start**, but not a lock that
     * happens later in the life of the process. Locking nulls the owner (zeroing the figures on the
     * next incidental save); unlocking restores it, but by then nothing was left to notice: the
     * one-shot poll was gone, and the observer's `ownerId() != lastDerivedOwner` test only runs when
     * *some other* write happens to occur. An idle Control after a biometric unlock produces no such
     * write, so `hasStorage` stayed false indefinitely and the banner mis-read an unlocked, entitled
     * realm as unsubscribed — intermittently, since any unrelated save silently repaired it.
     */
    @Test
    fun lockThenUnlockMidSession_reDerivesWithNoSave() = runTest {
        val registry = createSyntheticTestRegistry().also {
            it.registerConfig(identityServiceCommonModule)
        }
        val proxy = TestProxy(schemaRegistry = registry)
        val provider = EntitlementStorageProvider()
        val now = 5_000L

        proxy.executionContext.saveData(
            Entitlement.create(
                context = proxy.tContext,
                ownerId = "owner-x",
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.FREE,
                validFrom = 0L,
                validUntil = 10_000L,
                publisherSignature = ByteArray(0),
            ),
        )

        // Start UNLOCKED — so the one-shot startup backstop is never armed. This is the real-world
        // shape: the user already unlocked once, and the process keeps running.
        var owner: String? = "owner-x"
        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { owner },
            now = { now },
            scope = backgroundScope,
        )
        assertTrue(
            provider.observe().first().hasStorage,
            "precondition: an unlocked, entitled realm has storage",
        )

        // Lock mid-session: `SyncManager.lock()` purges the keys, so the owner stops resolving.
        owner = null
        advanceTimeBy(1_500)
        runCurrent()
        assertFalse(
            provider.observe().first().hasStorage,
            "a sealed realm cannot read its entitlement",
        )

        // Unlock again — and nothing is saved afterwards (idle Control post-biometric).
        owner = "owner-x"
        advanceTimeBy(1_500)
        runCurrent()

        assertTrue(
            provider.observe().first().hasStorage,
            "unlocking mid-session must restore the figures without an incidental save",
        )
    }
}
