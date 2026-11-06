@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.promise
import org.tenshu.identity.Entitlement
import org.tenshu.identity.EntitlementRecordCredential
import org.tenshu.storage.proxy.EntitlementProducts
import org.tenshu.storage.proxy.EntitlementStorageProvider
import org.tenshu.storage.proxy.StorageFigures
import org.tenshu.storage.proxy.StorageProvider
import org.tenshu.storage.proxy.initEntitlementStorageReader
import org.tenshu.storage.proxy.initStorageService
import org.tenshu.storage.proxy.mintEntitlement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Verifies web-storage-status.md storage-on-web wiring:
//  1. (Bucket 1) the full derive → relation → client → JS-surface path:
//     `Storage.status()` reflects a figure driven through `initStorageService`.
//  2. (Bucket 2) the relocated entitlement reader on JS: a saved `Entitlement`
//     recomputes the provider's figures (the cross-device subscription payoff,
//     no local account).
//
// Both drive their state *in-body* on purpose: the suite shares one singleton
// `JsRuntime` across classes, and sibling suites' `Session.clear` →
// `purgeRealmStorage` (`clearAll`) run between tests and would wipe a row written
// only at boot. The boot-time wiring itself is covered cross-platform by
// `:storage-service-proxy` (`EntitlementToFiguresTest`) + control-shared's
// `EntitlementStorageInitializationTest`.
class StorageStatusTest : SdkRuntimeTest() {

    private class FixedProvider(figures: StorageFigures) : StorageProvider {
        private val state = MutableStateFlow(figures)
        override fun observe(): Flow<StorageFigures> = state.asStateFlow()
    }

    @Test
    fun status_surfacesDerivedFigures() = GlobalScope.promise {
        TenshuSdk.awaitReady().await()
        JsRuntime.tenshuClient // build the runtime → StoragePlugin.initialize runs

        // The boot-time storage plugin fed the Tier-2 latch (poll: its collect is
        // launched async on the runtime scope).
        var i = 0
        while (JsRuntime.storageCapacityHolder.figures == null && i < 50) {
            delay(20); i++
        }
        assertTrue(
            JsRuntime.storageCapacityHolder.figures != null,
            "StoragePlugin.initialize should feed the StorageCapacityHolder",
        )

        // Drive a known figure through the production derivation and read it back
        // through the public SDK surface. This in-body write is the last one, so
        // it wins the single-row `StorageStatus` over the boot provider's default.
        JsRuntime.executionContext.initStorageService(
            scope = CoroutineScope(Dispatchers.Default),
            provider = FixedProvider(
                StorageFigures(hasStorage = true, usedBytes = 5L, limitBytes = 100L),
            ),
        )

        var status: dynamic = null
        var attempts = 0
        while ((status == null || status.hasStorage != true) && attempts < 50) {
            status = Storage.status().await()
            if (status == null || status.hasStorage != true) delay(20)
            attempts++
        }
        assertNotNull(status, "Storage.status() should resolve a non-null StorageStatus")
        assertEquals(true, status.hasStorage, "derived figure: storage is available")
        assertEquals(5.0, status.usedBytes, "used bytes surface through toJs")
        assertEquals(100.0, status.limitBytes, "limit bytes surface through toJs")
    }

    @Test
    fun entitlementReader_flipsFiguresOnSyncedEntitlement() = GlobalScope.promise {
        TenshuSdk.awaitReady().await()
        val ec = JsRuntime.executionContext

        // The relocated reader, wired against a known owner. Asserts on the
        // provider directly (not the shared single-row `StorageStatus`) so the
        // boot provider's parallel derivation can't race the assertion.
        val provider = EntitlementStorageProvider()
        ec.initEntitlementStorageReader(
            provider = provider,
            ownerId = { "owner-test" },
            now = { 5_000L },
        )

        // No entitlement yet → no storage.
        assertEquals(false, provider.observe().first().hasStorage)

        // The owner's Entitlement arrives (modelled as a direct save, the
        // sync-merge path on a web joiner). The reader's observer recomputes.
        ec.saveData(
            Entitlement.create(
                context = ec.context,
                ownerId = "owner-test",
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.BASIC,
                validFrom = 0L,
                validUntil = 10_000L,
                publisherSignature = ByteArray(0),
            ),
        )

        var figures = provider.observe().first()
        var attempts = 0
        while (!figures.hasStorage && attempts < 50) {
            delay(20)
            figures = provider.observe().first()
            attempts++
        }
        assertTrue(figures.hasStorage, "a valid synced Entitlement grants storage")
        assertEquals(EntitlementProducts.BASIC_LIMIT_BYTES, figures.limitBytes)
    }

    @Test
    fun genesisMint_grantsDefaultFreeStorage() = GlobalScope.promise {
        TenshuSdk.awaitReady().await()
        val ec = JsRuntime.executionContext

        val provider = EntitlementStorageProvider()
        ec.initEntitlementStorageReader(
            provider = provider,
            ownerId = { "owner-genesis" },
            now = { JsRuntime.clock.nowEpochMillis() },
        )
        assertEquals(false, provider.observe().first().hasStorage)

        // The exact mint `Realm.create` runs on web genesis: the in-process
        // dev-key minter signs a default-FREE entitlement and it's saved into the
        // realm. The reader's observer recomputes the figures.
        ec.mintEntitlement(
            ownerId = "owner-genesis",
            productId = EntitlementProducts.FREE,
            email = null,
            mintClient = JsRuntime.entitlementMintClient,
            nowMs = 0L,
        )

        var figures = provider.observe().first()
        var attempts = 0
        while (!figures.hasStorage && attempts < 50) {
            delay(20)
            figures = provider.observe().first()
            attempts++
        }
        assertTrue(figures.hasStorage, "web genesis mints a default-free entitlement")
        assertEquals(EntitlementProducts.FREE_LIMIT_BYTES, figures.limitBytes)
    }

    // Track B3 regression guard. The web is ALWAYS a session-less device — a commerce bearer
    // token is device-local PII that cannot sync (House Rule #1) — so the signed-record proof is
    // the only write credential a browser can present. It used to present none: the reader ran
    // with `recordSink = null` and `RestSyncRelay` was built without an `entitlementProofProvider`
    // (which defaults to `{ null }` and omits the header silently), so every `POST /sync/push`
    // was `402 entitlement_required` under `ENTITLEMENT_ENFORCE` while reads stayed ungated —
    // silent one-way sync, under a Services card that showed the owner's real plan.
    //
    // ⚠ Scope, stated because the gap here is what let the bug live: this covers
    // record → `recordSink` → provider → encoded proof. It does NOT assert that the runtime's
    // relay puts that value in an `X-Entitlement-Proof` header — that needs an HTTP seam the JS
    // runtime does not expose, and it was verified instead against the production relay
    // (`POST /sync/push` 402 → 200). If you are adding that seam, assert the header here too.
    @Test
    fun entitlementReader_feedsTheRecordCredentialProvider() = GlobalScope.promise {
        TenshuSdk.awaitReady().await()
        val ec = JsRuntime.executionContext
        val credential = JsRuntime.entitlementCredentialProvider

        // The production sink, wired exactly as `StoragePlugin.initialize` wires it.
        ec.initEntitlementStorageReader(
            provider = EntitlementStorageProvider(),
            ownerId = { "owner-proof-test" },
            now = { 5_000L },
            recordSink = { signed -> credential.update(signed) },
        )

        // Nothing synced yet → nothing to present. The relay answers this `entitlement_required`,
        // which is the CORRECT transient state on a fresh pairing, not the bug.
        assertEquals(
            null,
            credential.proofHeader(5_000L),
            "no synced Entitlement yet → no proof to present",
        )

        ec.saveData(
            Entitlement.create(
                context = ec.context,
                ownerId = "owner-proof-test",
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.BASIC,
                validFrom = 0L,
                validUntil = 10_000L,
                publisherSignature = ByteArray(0),
            ),
        )

        var proof = credential.proofHeader(5_000L)
        var attempts = 0
        while (proof == null && attempts < 50) {
            delay(20)
            proof = credential.proofHeader(5_000L)
            attempts++
        }
        assertNotNull(proof, "a synced Entitlement must become a presentable proof")
        assertTrue(
            proof.startsWith(EntitlementRecordCredential.SIGNED_RECORD_PROOF_TYPE),
            "the proof is the `signed-record.v1` form the relay's verifier registry dispatches on",
        )

        // ⚠⚠ START-gated only. The old rule — "presenting a record outside its validity is a
        // guaranteed 402, so withhold it" — was circular: the write was doomed BECAUSE the record
        // was withheld, and withholding made an expiry indistinguishable from having no record at
        // all. The relay floors an expired record to the free tier, so it is still presented.
        assertNotNull(
            credential.proofHeader(20_000L),
            "an expired record is still presented — the relay floors it to Free",
        )
    }
}
