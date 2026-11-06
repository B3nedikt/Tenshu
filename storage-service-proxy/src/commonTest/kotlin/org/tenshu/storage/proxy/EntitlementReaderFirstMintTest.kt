package org.tenshu.storage.proxy

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.identity.Entitlement
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The **ordinary sign-up sequence**, which [EntitlementReaderOwnerChangeTest] does not cover:
 * the owner is already resolved (the realm is unlocked), and then the very first
 * [Entitlement] is minted and saved.
 *
 * ⚠ That file's three cases are all about the *owner transition* — locked start, owner
 * appears, lock→unlock mid-session. Every one of them arranges for the entitlement to
 * ALREADY be persisted and then moves the owner. None of them saves an entitlement while
 * the owner is steady, which is exactly what happens the first time a user signs up:
 * unlock, then `/mint`, then `saveData(Entitlement)`.
 *
 * Observed on a real desktop install (2026-08-07): after sign-up the Services card sat on
 * "Setting up your storage" for ~1h50m. The store held a valid, owner-matching
 * `Entitlement` the whole time while `StorageStatus` read
 * `hasStorage=false, limitBytes=0` — and a restart fixed it instantly, because the
 * proxy-init seed reads the persisted row. So the data was never the problem; the
 * re-derivation on the save was. Once missed, nothing re-triggers it: the owner never
 * changes again, so the `scope` backstop stays quiet and the entitlement-save observer
 * has already had its one chance.
 */
class EntitlementReaderFirstMintTest {

    @Test
    fun ownerAlreadyResolved_thenFirstEntitlementSaved_figuresFlip() = runTest {
        val registry = createSyntheticTestRegistry().also {
            it.registerConfig(identityServiceCommonModule)
        }
        val proxy = TestProxy(schemaRegistry = registry)
        val provider = EntitlementStorageProvider()
        val now = 5_000L

        // Unlocked from the start: the owner is known before the reader seeds, and there is
        // no entitlement yet (a brand-new account, pre-mint).
        val owner = "owner-x"
        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { owner },
            now = { now },
        )
        assertFalse(
            provider.observe().first().hasStorage,
            "no entitlement yet — the seed must report no funded storage",
        )

        // The mint lands: commerce signs the grant and the writer saves it. This is the ONLY
        // trigger available — the owner does not change, so the backstop never fires.
        proxy.executionContext.saveData(
            Entitlement.create(
                context = proxy.tContext,
                ownerId = owner,
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.FREE,
                validFrom = 0L,
                validUntil = 10_000L,
                publisherSignature = ByteArray(0),
            ),
        )

        val figures = provider.observe().first()
        assertTrue(
            figures.hasStorage,
            "saving the first entitlement while the owner is steady must flip the figures — " +
                "this is the sign-up path, and if it misses, nothing re-triggers it",
        )
        assertTrue(
            figures.limitBytes > 0L,
            "a funded entitlement must carry a real limit, not a 0 B of 0 B gauge",
        )
    }
}
