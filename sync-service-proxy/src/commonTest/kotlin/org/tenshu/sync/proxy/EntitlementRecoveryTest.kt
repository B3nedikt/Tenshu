package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.identity.Entitlement
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The C1 targeted entitlement fetch (`sync-device-catch-up.md` §5.3).
 *
 * The behaviour worth pinning is **when it declines to run**. Fetching is cheap and idempotent, so
 * the risk is not a missed fetch — it is a device that asks the relay for a row it already has, on
 * every probing boundary, forever. Three of the five tests below are about not doing that.
 */
class EntitlementRecoveryTest {

    /** Counts fetches so "did it even ask?" is directly assertable. */
    private class CountingRelay(
        private val delegate: MockSyncRelay,
        private val onFetch: (String) -> Unit,
    ) : org.tenshu.sync.common.SyncRelay by delegate {
        override suspend fun fetchEntityBlocks(entityWireId: String) =
            delegate.fetchEntityBlocks(entityWireId).also { onFetch(entityWireId) }
    }

    /**
     * ⚠ The entitlement is keyed by the SyncManager's **real** owner id, not a literal — the first
     * draft of this test keyed it by a made-up string and both "already usable" assertions failed,
     * which is the recovery correctly refusing to find a row that is not the owner's.
     */
    private class Fixture(val proxy: TestProxy) {
        val syncManager: SyncManagerImpl
        val owner: String

        init {
            proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
            proxy.schemaRegistry.registerConfig(identityServiceCommonModule)
            syncManager = SyncManagerImpl(
                cryptoManager = proxy.tContext.syncCryptoManager(),
                storageDelegate = InMemorySecureStorageDelegate(),
                context = proxy.tContext,
            )
            syncManager.initialize("mock phrase")
            owner = requireNotNull(syncManager.currentOwnerId) { "no owner after initialize" }
        }

        val entitlementId: DataId.StringDataId
            get() = DataId.StringDataId.create(
                context = proxy.tContext,
                type = Entitlement.ID,
                keyValue = owner,
            )

        fun saveEntitlement(validFrom: Long, validUntil: Long) {
            proxy.executionContext.saveData(
                Entitlement.create(
                    context = proxy.tContext,
                    ownerId = owner,
                    publisherId = "tenshu.commerce",
                    productId = "tenshu.drive.free",
                    validFrom = validFrom,
                    validUntil = validUntil,
                    publisherSignature = byteArrayOf(1, 2, 3, 4),
                ),
                WriteOrigin.SYNC_INGEST,
            )
        }

        fun recovery(relay: org.tenshu.sync.common.SyncRelay?, nowMs: Long = 5_000L) =
            EntitlementRecovery(
                executionContext = proxy.executionContext,
                syncManager = syncManager,
                syncProcessor = SyncProcessor(
                    wireIdResolver = WireIdResolver(syncManager),
                    executionContext = proxy.executionContext,
                    syncManager = syncManager,
                    cryptoManager = proxy.tContext.syncCryptoManager(),
                ),
                relay = relay,
                nowMs = { nowMs },
            )
    }

    private fun fixture() = Fixture(TestProxy())

    @Test
    fun aValidLocalRecordIsNotRefetched() = runTest {
        // ⚠ The main cost guard. If the row is present and inside its window, the relay cannot hold
        // anything better — and a 402 in that state is `entitlement_invalid` (a different bug), not
        // a stale record. Asking again would be a request per probing boundary, forever.
        val f = fixture()
        f.saveEntitlement(validFrom = 1_000L, validUntil = 9_000L)
        var fetches = 0
        val relay = CountingRelay(MockSyncRelay(f.proxy.tContext)) { fetches++ }

        val outcome = f.recovery(relay).recoverIfStale()

        assertIs<EntitlementRecovery.Outcome.AlreadyUsable>(outcome)
        assertEquals(0, fetches, "a usable record must not be re-fetched")
    }

    @Test
    fun anExpiredLocalRecordIsRefetched() = runTest {
        // The incident's exact state: a record is present, so every "is it there?" check passes —
        // but it expired, so nothing is presentable and the device is write-dead.
        val f = fixture()
        f.saveEntitlement(validFrom = 1_000L, validUntil = 2_000L)
        var fetched: String? = null
        val relay = CountingRelay(MockSyncRelay(f.proxy.tContext)) { fetched = it }

        val outcome = f.recovery(relay).recoverIfStale()

        assertIs<EntitlementRecovery.Outcome.Fetched>(outcome)
        assertEquals(
            WireIdResolver(f.syncManager).of(f.entitlementId), fetched,
            "it must ask for the entitlement row specifically, not sweep the log — by its WIRE id"
        )
        // ⚠⚠ B3 wire spec §11.3: the relay is asked by the keyed name, so the plaintext key (the
        // owner id, here; a path for a File) never crosses.
        assertTrue(!fetched!!.contains(f.owner), "the request must not carry the plaintext key: $fetched")
    }

    @Test
    fun anAbsentLocalRecordIsRefetched() = runTest {
        val f = fixture()
        var fetches = 0
        val relay = CountingRelay(MockSyncRelay(f.proxy.tContext)) { fetches++ }

        val outcome = f.recovery(relay).recoverIfStale()

        assertIs<EntitlementRecovery.Outcome.Fetched>(outcome)
        assertEquals(1, fetches)
    }

    @Test
    fun withoutARelayItDeclinesInsteadOfThrowing() = runTest {
        // Test/legacy wirings pass no relay. Declining keeps them on today's behaviour (recover the
        // slow way) rather than turning an optional optimisation into a crash.
        val f = fixture()
        f.saveEntitlement(validFrom = 1_000L, validUntil = 2_000L)

        assertIs<EntitlementRecovery.Outcome.NotAttempted>(f.recovery(relay = null).recoverIfStale())
    }

    @Test
    fun aRelayFailureIsReportedNotThrown() = runTest {
        // The probing boundary repeats; a transient failure must not break the tick that hosts it.
        val f = fixture()
        val throwing = object : org.tenshu.sync.common.SyncRelay by MockSyncRelay(f.proxy.tContext) {
            override suspend fun fetchEntityBlocks(entityWireId: String) = error("relay unreachable")
        }

        val outcome = f.recovery(throwing).recoverIfStale()

        assertIs<EntitlementRecovery.Outcome.Failed>(outcome)
        assertTrue(true, "and it returned rather than propagating")
    }
}
