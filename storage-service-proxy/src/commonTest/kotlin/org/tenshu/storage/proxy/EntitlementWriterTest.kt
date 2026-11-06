package org.tenshu.storage.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.identity.Entitlement
import org.tenshu.identity.EntitlementSignature
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertTrue

/**
 * The relocated entitlement **writer** (web-storage-status.md Bucket 3):
 * [mintEntitlement] upserts a signed [Entitlement] into the realm, idempotently,
 * and no-ops on a `null` mint. The reactive [initEntitlementWriter] form over a
 * `PublisherAccount` flow is covered by control-shared's
 * `EntitlementStorageInitializationTest`.
 */
class EntitlementWriterTest {

    private fun newProxy(): TestProxy =
        TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }

    private fun TestProxy.entitlementId(owner: String): DataId.StringDataId =
        DataId.StringDataId.create(context = tContext, type = Entitlement.ID, keyValue = owner)

    private class FixedMint(private val productId: String) : EntitlementMintClient {
        override suspend fun mint(ownerId: String, email: String?, productId: String) =
            EntitlementSignature.SignedEntitlement(
                ownerId = ownerId,
                publisherId = "tenshu.dev",
                productId = this.productId,
                validFrom = 0L,
                validUntil = 10_000L,
                signature = ByteArray(0),
            )
    }

    private val nullMint = object : EntitlementMintClient {
        override suspend fun mint(ownerId: String, email: String?, productId: String) = null
    }

    /** Models commerce stamping a fresh, now-relative window on each mint (validUntil = [now] + 10_000). */
    private class ClockMint(private val now: () -> Long) : EntitlementMintClient {
        override suspend fun mint(ownerId: String, email: String?, productId: String) =
            EntitlementSignature.SignedEntitlement(
                ownerId = ownerId,
                publisherId = "tenshu.dev",
                productId = productId,
                validFrom = now(),
                validUntil = now() + 10_000L,
                signature = ByteArray(0),
            )
    }

    @Test
    fun mintEntitlement_writesThenIsIdempotent() = runTest {
        val proxy = newProxy()

        val wrote = proxy.executionContext.mintEntitlement(
            ownerId = "owner-1",
            productId = EntitlementProducts.FREE,
            email = null,
            mintClient = FixedMint(EntitlementProducts.FREE),
            nowMs = 0L,
        )
        assertTrue(wrote, "first mint writes the entitlement")

        val saved = proxy.database.getData(proxy.entitlementId("owner-1")) as? Entitlement
        assertNotNull(saved, "the owner's entitlement is in the realm")
        assertEquals(EntitlementProducts.FREE, saved.productId.get())

        // Same product, still well within its window (t=0 of a [0,10_000] record) → no churn.
        val again = proxy.executionContext.mintEntitlement(
            ownerId = "owner-1",
            productId = EntitlementProducts.FREE,
            email = null,
            mintClient = FixedMint(EntitlementProducts.FREE),
            nowMs = 0L,
        )
        assertFalse(again, "an unchanged product not yet due for renewal is a no-op")
    }

    @Test
    fun mintEntitlement_nullMint_neverWrites() = runTest {
        val proxy = newProxy()

        val wrote = proxy.executionContext.mintEntitlement(
            ownerId = "owner-2",
            productId = EntitlementProducts.FREE,
            email = null,
            mintClient = nullMint,
            nowMs = 0L,
        )
        assertFalse(wrote, "a null mint is a deliberate no-op")
        assertNull(proxy.database.getData(proxy.entitlementId("owner-2")))
    }

    @Test
    fun mintEntitlement_renewsPastHalfway_notBefore() = runTest {
        val proxy = newProxy()
        var serverNow = 0L
        val mint = ClockMint(now = { serverNow })

        // First mint at t=0 → window [0, 10_000].
        assertTrue(
            proxy.executionContext.mintEntitlement("owner-r", EntitlementProducts.FREE, null, mint, nowMs = 0L),
        )
        assertEquals(10_000L, (proxy.database.getData(proxy.entitlementId("owner-r")) as Entitlement).validUntil.get())

        // t=3_000 (7_000 remaining > half) → not yet due → no-op, window unchanged.
        serverNow = 3_000L
        assertFalse(
            proxy.executionContext.mintEntitlement("owner-r", EntitlementProducts.FREE, null, mint, nowMs = 3_000L),
            "still in the first half of the window → no renewal",
        )
        assertEquals(10_000L, (proxy.database.getData(proxy.entitlementId("owner-r")) as Entitlement).validUntil.get())

        // t=6_000 (4_000 remaining < half) → renew → commerce stamps [6_000, 16_000] → writes.
        serverNow = 6_000L
        assertTrue(
            proxy.executionContext.mintEntitlement("owner-r", EntitlementProducts.FREE, null, mint, nowMs = 6_000L),
            "past the halfway point → renew",
        )
        assertEquals(16_000L, (proxy.database.getData(proxy.entitlementId("owner-r")) as Entitlement).validUntil.get())
    }

    /**
     * Regression (iOS Control genesis→relay-registration bug): a **persisted** account
     * (`MockPublisherClient` / a restored session) makes the intent flow emit its bound value at
     * startup — *before* genesis/unlock resolves the owner. The `distinctUntilChanged` collect skips
     * it (owner null) and then never re-fires for the identical intent once the owner appears, so
     * without the owner-resolve backstop the owner's entitlement is never minted (→ `hasStorage`
     * stuck false → the ADR-0019 relay-registration gate never opens → EPC provision 401s).
     * The backstop must mint the latest intent once the owner resolves.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun initEntitlementWriter_intentBoundBeforeOwnerResolves_mintsWhenOwnerAppears() = runTest {
        val proxy = newProxy()
        var owner: String? = null
        // The intent is already bound at wiring time — the persisted-account-at-startup shape.
        val intents = MutableStateFlow<EntitlementMintIntent?>(
            EntitlementMintIntent(productId = EntitlementProducts.FREE, email = null),
        )

        proxy.executionContext.initEntitlementWriter(
            intents = intents,
            ownerId = { owner },
            scope = backgroundScope,
            mintClient = FixedMint(EntitlementProducts.FREE),
            now = { 0L },
        )

        // The collect fires with the bound intent, but the owner is unresolved → no write.
        runCurrent()
        assertNull(
            proxy.database.getData(proxy.entitlementId("owner-x")),
            "owner unresolved → nothing minted yet",
        )

        // Genesis/unlock resolves the owner. The re-bind of the SAME intent would be suppressed by
        // distinctUntilChanged, so only the backstop can rescue this — it polls, sees the owner, mints.
        owner = "owner-x"
        advanceTimeBy(1_100)
        runCurrent()

        val saved = proxy.database.getData(proxy.entitlementId("owner-x")) as? Entitlement
        assertNotNull(saved, "the owner-resolve backstop mints the latest intent once the owner appears")
        assertEquals(EntitlementProducts.FREE, saved.productId.get())
    }

    @Test
    fun saveSignedEntitlement_persistsTheRedeemGrant_idempotently() = runTest {
        val proxy = newProxy()
        val grant = EntitlementSignature.SignedEntitlement(
            ownerId = "owner-3",
            publisherId = "tenshu.commerce",
            productId = EntitlementProducts.PREMIUM,
            validFrom = 0L,
            validUntil = 99L,
            signature = byteArrayOf(1, 2, 3),
        )

        proxy.executionContext.saveSignedEntitlement(grant)

        val saved = proxy.database.getData(proxy.entitlementId("owner-3")) as? Entitlement
        assertNotNull(saved, "the redeemed entitlement is persisted directly (no minting)")
        assertEquals(EntitlementProducts.PREMIUM, saved.productId.get())

        // Re-applying the same product is a no-op (idempotent against sync churn).
        proxy.executionContext.saveSignedEntitlement(grant)
        val still = proxy.database.getData(proxy.entitlementId("owner-3")) as? Entitlement
        assertEquals(EntitlementProducts.PREMIUM, still?.productId?.get())
    }

    @Test
    fun saveSignedEntitlement_writesLaterWindow_skipsSameOrOlder() = runTest {
        val proxy = newProxy()
        fun grant(validUntil: Long) = EntitlementSignature.SignedEntitlement(
            ownerId = "owner-s",
            publisherId = "tenshu.commerce",
            productId = EntitlementProducts.FREE,
            validFrom = 0L,
            validUntil = validUntil,
            signature = byteArrayOf(1),
        )

        assertTrue(proxy.executionContext.saveSignedEntitlement(grant(100L)), "first write")
        assertFalse(proxy.executionContext.saveSignedEntitlement(grant(100L)), "same validUntil → no churn")
        assertFalse(proxy.executionContext.saveSignedEntitlement(grant(50L)), "older validUntil → skip")
        assertTrue(proxy.executionContext.saveSignedEntitlement(grant(200L)), "later validUntil → renewal writes")
        assertEquals(
            200L,
            (proxy.database.getData(proxy.entitlementId("owner-s")) as Entitlement).validUntil.get(),
            "the extended window is persisted",
        )
    }

    /**
     * ⚠⚠ Regression for a bug found on the live deploy (2026-08-08): a `FOUNDER-1` redemption for
     * `tenshu.drive.premium` was consumed at commerce, yet the device sat on Free with no Premium
     * anywhere.
     *
     * Two writers author this one row and have no notion of each other. `productIdForPlan` maps the
     * ACCOUNT plan, whose enum is only Free|Rei and cannot express Premium, so the account-driven
     * mint asks for Free; `saveSignedEntitlement` writes on any product change; and a renewal tick
     * re-drives the mint on a timer. The automatic path therefore always won eventually, silently
     * undoing an explicit user action.
     */
    @Test
    fun automaticMint_doesNotDowngradeAStillValidHigherGrant() = runTest {
        val proxy = newProxy()
        val owner = "owner-redeemed"

        // The user redeems Premium — the identity-light path, written verbatim.
        proxy.executionContext.saveSignedEntitlement(
            EntitlementSignature.SignedEntitlement(
                ownerId = owner,
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.PREMIUM,
                validFrom = 0L,
                validUntil = 10_000L,
                signature = ByteArray(0),
            ),
        )

        // The account-driven writer then re-drives its mint. The account's plan is Free — it has no
        // way to express Premium at all — so without the guard this silently overwrote the grant.
        val wrote = proxy.executionContext.mintEntitlement(
            ownerId = owner,
            productId = EntitlementProducts.FREE,
            email = null,
            mintClient = FixedMint(EntitlementProducts.FREE),
            nowMs = 5_000L,
        )

        assertFalse(wrote, "an automatic re-mint must not replace a still-valid higher grant")
        val row = proxy.database.getData(proxy.entitlementId(owner)) as Entitlement
        assertEquals(
            EntitlementProducts.PREMIUM,
            row.productId.get(),
            "the redeemed tier must survive the account-driven mint",
        )
    }

    /**
     * ⚠ The downgrade is DEFERRED, not cancelled. Once the higher record leaves its window the
     * automatic mint takes over again — the revocation model this system already states: "revocation
     * is handled by the record's TTL (it stops being re-minted)".
     */
    @Test
    fun onceTheHigherGrantExpires_theAutomaticMintTakesOver() = runTest {
        val proxy = newProxy()
        val owner = "owner-expired"
        proxy.executionContext.saveSignedEntitlement(
            EntitlementSignature.SignedEntitlement(
                ownerId = owner,
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.PREMIUM,
                validFrom = 0L,
                validUntil = 10_000L,
                signature = ByteArray(0),
            ),
        )

        val wrote = proxy.executionContext.mintEntitlement(
            ownerId = owner,
            productId = EntitlementProducts.FREE,
            email = null,
            mintClient = FixedMint(EntitlementProducts.FREE),
            nowMs = 20_000L, // past validUntil
        )

        assertTrue(wrote, "an expired higher grant must not block the account's own tier forever")
        val row = proxy.database.getData(proxy.entitlementId(owner)) as Entitlement
        assertEquals(EntitlementProducts.FREE, row.productId.get())
    }

    /**
     * ⚠ Scoped to the AUTOMATIC path. An explicit redeem still writes whatever commerce signed,
     * including a lower tier — a user action being silently ignored is the failure being fixed, and
     * reproducing it in the other direction would be no better.
     */
    @Test
    fun anExplicitRedeemMayStillLowerTheTier() = runTest {
        val proxy = newProxy()
        val owner = "owner-explicit"
        proxy.executionContext.saveSignedEntitlement(
            EntitlementSignature.SignedEntitlement(
                ownerId = owner, publisherId = "tenshu.dev",
                productId = EntitlementProducts.PREMIUM,
                validFrom = 0L, validUntil = 10_000L, signature = ByteArray(0),
            ),
        )

        proxy.executionContext.saveSignedEntitlement(
            EntitlementSignature.SignedEntitlement(
                ownerId = owner, publisherId = "tenshu.dev",
                productId = EntitlementProducts.BASIC,
                validFrom = 0L, validUntil = 10_000L, signature = ByteArray(0),
            ),
        )

        val row = proxy.database.getData(proxy.entitlementId(owner)) as Entitlement
        assertEquals(
            EntitlementProducts.BASIC,
            row.productId.get(),
            "the explicit path is deliberately unguarded",
        )
    }

    /** Answers a fixed [productId] / [validUntil] regardless of what is asked, recording each call. */
    private class RecordingMint(
        private val productId: String,
        private val validUntil: Long,
    ) : EntitlementMintClient {
        val requested = mutableListOf<String>()
        override suspend fun mint(ownerId: String, email: String?, productId: String): EntitlementSignature.SignedEntitlement {
            requested += productId
            return EntitlementSignature.SignedEntitlement(
                ownerId = ownerId,
                publisherId = "tenshu.commerce",
                productId = this.productId,
                validFrom = 6_000L,
                validUntil = validUntil,
                signature = ByteArray(0),
            )
        }
    }

    /** A paid Basic record over [0, 10_000]: its renewal point is t=5_000. */
    private suspend fun TestProxy.seedBasic(owner: String) {
        executionContext.saveSignedEntitlement(
            EntitlementSignature.SignedEntitlement(
                ownerId = owner, publisherId = "tenshu.commerce",
                productId = EntitlementProducts.BASIC,
                validFrom = 0L, validUntil = 10_000L, signature = ByteArray(0),
            ),
        )
    }

    @Test
    fun paidRecord_beforeItsRenewalPoint_isLeftAlone_withoutAskingTheMinter() = runTest {
        val proxy = newProxy()
        val owner = "owner-paid-early"
        proxy.seedBasic(owner)
        val mint = RecordingMint(EntitlementProducts.BASIC, validUntil = 16_000L)

        val wrote = proxy.executionContext.mintEntitlement(owner, EntitlementProducts.FREE, null, mint, nowMs = 3_000L)

        assertFalse(wrote)
        assertTrue(mint.requested.isEmpty(), "a fresh higher record never reaches the minter")
        val row = proxy.database.getData(proxy.entitlementId(owner)) as Entitlement
        assertEquals(EntitlementProducts.BASIC, row.productId.get())
        assertEquals(10_000L, row.validUntil.get())
    }

    /**
     * The production minter ignores the requested product and answers from the ledger, so a paying
     * account asking for Free gets its paid tier back — which must be written, or the record expires.
     */
    @Test
    fun paidRecord_pastItsRenewalPoint_isRenewed_whenTheMinterAnswersTheSameTierLater() = runTest {
        val proxy = newProxy()
        val owner = "owner-paid-renew"
        proxy.seedBasic(owner)
        val mint = RecordingMint(EntitlementProducts.BASIC, validUntil = 16_000L)

        val wrote = proxy.executionContext.mintEntitlement(owner, EntitlementProducts.FREE, null, mint, nowMs = 6_000L)

        assertTrue(wrote, "a paid record past its renewal point is renewed")
        assertEquals(listOf(EntitlementProducts.FREE), mint.requested)
        val row = proxy.database.getData(proxy.entitlementId(owner)) as Entitlement
        assertEquals(EntitlementProducts.BASIC, row.productId.get())
        assertEquals(16_000L, row.validUntil.get())
    }

    /** The dev minter echoes the requested Free: past the renewal point that answer still must not land. */
    @Test
    fun paidRecord_pastItsRenewalPoint_isNotDowngraded_whenTheMinterAnswersLower() = runTest {
        val proxy = newProxy()
        val owner = "owner-paid-lower"
        proxy.seedBasic(owner)
        val mint = RecordingMint(EntitlementProducts.FREE, validUntil = 16_000L)

        val wrote = proxy.executionContext.mintEntitlement(owner, EntitlementProducts.FREE, null, mint, nowMs = 6_000L)

        assertFalse(wrote, "a lower answer never replaces a still-valid higher record")
        assertEquals(1, mint.requested.size)
        val row = proxy.database.getData(proxy.entitlementId(owner)) as Entitlement
        assertEquals(EntitlementProducts.BASIC, row.productId.get())
        assertEquals(10_000L, row.validUntil.get())
    }

    /**
     * ⚠ The same-tier cases are also refused by [saveSignedEntitlement]'s own staleness check; the
     * higher-tier one is not, so it is what pins the writer's `validUntil` clause.
     */
    @Test
    fun paidRecord_pastItsRenewalPoint_isNotShortened_whenTheMinterAnswersAnEarlierOrEqualWindow() = runTest {
        val answers = listOf(
            EntitlementProducts.BASIC to 8_000L,
            EntitlementProducts.BASIC to 10_000L,
            EntitlementProducts.PREMIUM to 8_000L,
        )
        for ((answeredProduct, answeredUntil) in answers) {
            val proxy = newProxy()
            val owner = "owner-paid-short-$answeredProduct-$answeredUntil"
            proxy.seedBasic(owner)
            val mint = RecordingMint(answeredProduct, validUntil = answeredUntil)

            val wrote = proxy.executionContext.mintEntitlement(owner, EntitlementProducts.FREE, null, mint, nowMs = 6_000L)

            assertFalse(wrote, "$answeredProduct until $answeredUntil does not extend the record")
            assertEquals(1, mint.requested.size)
            val row = proxy.database.getData(proxy.entitlementId(owner)) as Entitlement
            assertEquals(EntitlementProducts.BASIC, row.productId.get())
            assertEquals(10_000L, row.validUntil.get())
        }
    }
}
