package org.tenshu.storage.proxy

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.identity.Entitlement
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression for the **sticky-false derivation** (2026-08-08, found while testing the paid tier).
 *
 * A denial that was only ever momentary used to last for the life of the process. Figures move on
 * an [Entitlement] save or an owner transition, and on an idle device neither recurs — so a record
 * that was not yet valid at the instant it landed read as "no storage" until Control was restarted.
 * It cost two paid-tier test runs: a real 2 TiB grant rendered as nothing, twice, and only a
 * relaunch revealed the figures had been correct all along.
 *
 * ⚠ The sibling half of the fix — tolerating a *small* issuer skew outright, which is what made the
 * denial happen in the first place — lives in `EntitlementToFiguresTest`. This pins the backstop
 * for skew larger than that tolerance, so the two together mean a valid grant always arrives.
 */
class EntitlementReaderSkewSelfHealTest {

    @Test
    fun recordThatWasNotYetValidWhenItLanded_healsWithoutASaveOrARestart() = runTest {
        val registry = createSyntheticTestRegistry().also {
            it.registerConfig(identityServiceCommonModule)
        }
        val proxy = TestProxy(schemaRegistry = registry)
        val provider = EntitlementStorageProvider()

        // Far enough ahead to clear the skew leeway — this is a genuinely future-dated record, not
        // the sub-second issuer drift the tolerance already absorbs.
        var nowMs = 5_000L
        val validFrom = 500_000L

        proxy.executionContext.saveData(
            Entitlement.create(
                context = proxy.tContext,
                ownerId = "owner-x",
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.PREMIUM,
                validFrom = validFrom,
                validUntil = 9_000_000L,
                publisherSignature = ByteArray(0),
            ),
        )

        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { "owner-x" },
            now = { nowMs },
            scope = backgroundScope,
        )
        assertFalse(
            provider.observe().first().hasStorage,
            "precondition: a record dated past the leeway confers nothing yet",
        )

        // Time passes and the record becomes valid. ⚠ Nothing is saved and the owner never changes
        // — the two triggers the reader had before this fix. An idle Control produces neither.
        nowMs = validFrom + 1
        advanceTimeBy(1_500) // past OWNER_RESOLVE_POLL_MS
        runCurrent()

        val figures = provider.observe().first()
        assertTrue(
            figures.hasStorage,
            "a record that has since become valid must re-derive on its own, not wait for a restart",
        )
        assertTrue(
            figures.limitBytes == EntitlementProducts.PREMIUM_LIMIT_BYTES,
            "and it must heal to the record's real tier, not to some floor",
        )
    }

    /**
     * ⚠ The retry is deliberately armed only for the not-yet-valid case, the one denial that
     * resolves at a *known* instant. Expiry is terminal — retrying it would re-read the store every
     * second, forever, and never change the answer.
     *
     * ⚠⚠ **This asserts the tier, not `hasStorage`, and the difference is the point.** An expired
     * record now confers the free floor (the relay grants it Free rather than refusing it), so
     * `hasStorage` is true and can no longer stand in for "the backstop did not fire". What
     * resurrection would look like *is* the record's own tier coming back: this record is PREMIUM,
     * and healing it into validity would show 2 TiB. The floor is not resurrection.
     */
    @Test
    fun expiredRecord_isNotRetriedIntoItsOwnTier() = runTest {
        val registry = createSyntheticTestRegistry().also {
            it.registerConfig(identityServiceCommonModule)
        }
        val proxy = TestProxy(schemaRegistry = registry)
        val provider = EntitlementStorageProvider()
        var nowMs = 50_000L

        proxy.executionContext.saveData(
            Entitlement.create(
                context = proxy.tContext,
                ownerId = "owner-x",
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.PREMIUM,
                validFrom = 0L,
                validUntil = 10_000L,
                publisherSignature = ByteArray(0),
            ),
        )

        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { "owner-x" },
            now = { nowMs },
            scope = backgroundScope,
        )

        nowMs = 100_000L
        advanceTimeBy(5_000)
        runCurrent()

        val afterFirstAdvance = provider.observe().first()
        assertEquals(
            EntitlementProducts.FREE_LIMIT_BYTES,
            afterFirstAdvance.limitBytes,
            "an expired record stays on the free floor — the backstop must not heal it to its tier",
        )
        assertNotEquals(
            EntitlementProducts.PREMIUM_LIMIT_BYTES,
            afterFirstAdvance.limitBytes,
            "2 TiB here would mean the terminal verdict had been retried into validity",
        )

        // And it is genuinely terminal, not merely slow: more time changes nothing.
        nowMs = 500_000L
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(
            afterFirstAdvance,
            provider.observe().first(),
            "figures for an expired record must be stable — a retry would be re-deriving forever",
        )
    }
}
