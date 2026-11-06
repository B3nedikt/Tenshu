package org.tenshu.storage.proxy

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.identity.Entitlement
import org.tenshu.identity.EntitlementSignature
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The read-path signature rule (`entitlement-trust-anchor-distribution.md` D1).
 *
 * ⚠⚠ This is the half that makes **retention** safe. `SyncProcessor` no longer drops an
 * unverifiable `Entitlement` — it keeps it so the record can still be forwarded to the relay as
 * `X-Entitlement-Proof`. That is only defensible because the reader refuses to let such a record
 * decide how much storage the user has. If this rule regresses, the sync-side tests still pass
 * while the client silently starts trusting unverified records, so neither side's test is
 * sufficient alone.
 */
class EntitlementReaderVerificationTest {

    private fun proxyWithEntitlement(owner: String): TestProxy {
        val registry = createSyntheticTestRegistry().also { it.registerConfig(identityServiceCommonModule) }
        val proxy = TestProxy(schemaRegistry = registry)
        proxy.executionContext.saveData(
            Entitlement.create(
                context = proxy.tContext,
                ownerId = owner,
                publisherId = EntitlementSignature.COMMERCE_PUBLISHER_ID,
                productId = EntitlementProducts.FREE,
                validFrom = 0L,
                validUntil = 10_000L,
                publisherSignature = ByteArray(0),
            ),
        )
        return proxy
    }

    @Test
    fun unverifiableRecord_doesNotDriveFigures_butIsStillForwardedAsCredential() = runTest {
        val owner = "owner-x"
        val proxy = proxyWithEntitlement(owner)
        val provider = EntitlementStorageProvider()
        var forwarded: EntitlementSignature.SignedEntitlement? = null
        val reported = mutableListOf<String>()

        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { owner },
            now = { 5_000L },
            recordSink = { forwarded = it },
            verifyRecord = { false }, // this client cannot confirm the signature
            reportUnverified = { reported += it },
        )

        assertFalse(
            provider.observe().first().hasStorage,
            "a record this client cannot verify must not decide how much storage the user has",
        )
        assertNotNull(
            forwarded,
            "it must STILL be forwarded as the credential — the relay re-verifies and is the " +
                "authority; withholding it strands session-less paired devices on 402",
        )
        assertEquals(
            listOf(EntitlementSignature.COMMERCE_PUBLISHER_ID),
            reported,
            "the failure must be reported, and must name the publisher",
        )
    }

    @Test
    fun verifiableRecord_drivesFiguresAndReportsNothing() = runTest {
        val owner = "owner-y"
        val proxy = proxyWithEntitlement(owner)
        val provider = EntitlementStorageProvider()
        val reported = mutableListOf<String>()

        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { owner },
            now = { 5_000L },
            verifyRecord = { true },
            reportUnverified = { reported += it },
        )

        assertTrue(provider.observe().first().hasStorage)
        assertTrue(reported.isEmpty(), "a verifiable record must not raise the error")
    }

    /**
     * ⚠ `refreshFigures` runs on a 1s poll, so reporting per derivation would emit one error row
     * per second — burying the very failure it exists to surface. Report on transition only.
     */
    @Test
    fun repeatedDerivations_reportOnceNotPerPoll() = runTest {
        val owner = "owner-z"
        val proxy = proxyWithEntitlement(owner)
        val provider = EntitlementStorageProvider()
        val reported = mutableListOf<String>()

        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { owner },
            now = { 5_000L },
            verifyRecord = { false },
            reportUnverified = { reported += it },
        )
        // Every save trips the observer, which re-derives.
        repeat(5) {
            proxy.executionContext.saveData(
                Entitlement.create(
                    context = proxy.tContext,
                    ownerId = owner,
                    publisherId = EntitlementSignature.COMMERCE_PUBLISHER_ID,
                    productId = EntitlementProducts.FREE,
                    validFrom = 0L,
                    validUntil = 10_000L + it,
                    publisherSignature = ByteArray(0),
                ),
            )
        }

        assertEquals(1, reported.size, "the unverified state must be reported once, not per derivation")
    }

    /**
     * No verifier wired (the web SDK and every pre-existing caller) must behave exactly as before —
     * verification is opt-in, so this change cannot quietly break a consumer that never asked for it.
     */
    @Test
    fun noVerifierWired_behavesAsBefore() = runTest {
        val owner = "owner-legacy"
        val proxy = proxyWithEntitlement(owner)
        val provider = EntitlementStorageProvider()

        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { owner },
            now = { 5_000L },
        )

        assertTrue(
            provider.observe().first().hasStorage,
            "with no verifier the reader must accept the record, as it always did",
        )
    }
}
