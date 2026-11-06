package app.tenshu.control.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.identity.Entitlement
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.storage.proxy.DevSeedEntitlementMintClient
import org.tenshu.storage.proxy.EntitlementProducts
import org.tenshu.storage.proxy.EntitlementStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reactive wiring of [initEntitlementStorage] (storage-entitlement-sync.md
 * slice 3): the mock-Publisher writer (subscribe → write Entitlement into the
 * realm) and the reader (any Entitlement save → recompute figures), end-to-end
 * over a [TestProxy].
 */
class EntitlementStorageInitializationTest {

    private fun newProxy(): TestProxy =
        TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }

    private fun freeAccount() = PublisherAccount(
        plan = PublisherAccount.Plan.Free,
        identityMethod = PublisherAccount.IdentityMethod.Email("a@b.c"),
        displayName = null,
    )

    private fun TestProxy.entitlementId(owner: String): DataId.StringDataId =
        DataId.StringDataId.create(context = tContext, type = Entitlement.ID, keyValue = owner)

    @Test
    fun writerWritesEntitlementOnSubscribe_andReaderFlipsFigures() = runTest {
        val proxy = newProxy()
        val provider = EntitlementStorageProvider()
        val account = MutableStateFlow<PublisherAccount?>(null)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        proxy.executionContext.initEntitlementStorage(
            provider = provider,
            accountFlow = account,
            ownerId = { "owner-x" },
            isRelease = false,
            scope = scope,
            now = { 1_000_000L },
            // Dev-key minter; signature value is irrelevant to these reader/writer figure assertions.
            mintClient = DevSeedEntitlementMintClient(sign = { it }, now = { 1_000_000L }),
        )

        // No entitlement yet → no storage.
        assertFalse(provider.observe().first().hasStorage)

        // Subscribe — the unconfined collector writes eagerly on this emission.
        account.value = freeAccount()

        // Writer wrote the owner's entitlement into the realm (so it can sync out).
        val saved = proxy.database.getData(proxy.entitlementId("owner-x")) as? Entitlement
        assertNotNull(saved, "subscribing must write an Entitlement into the realm")
        assertEquals(EntitlementProducts.FREE, saved.productId.get())

        // Reader (the writer's saveData fired onDataSaved) flipped the figures.
        val figures = provider.observe().first()
        assertTrue(figures.hasStorage)
        assertEquals(EntitlementProducts.FREE_LIMIT_BYTES, figures.limitBytes)

        scope.cancel()
    }

    @Test
    fun nullAccount_neverWritesNorDeletes() = runTest {
        // Device B: never subscribed locally. The writer must not touch the realm —
        // a never-subscribed device must not wipe the owner's synced grant.
        val proxy = newProxy()
        val provider = EntitlementStorageProvider()
        val account = MutableStateFlow<PublisherAccount?>(null)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        proxy.executionContext.initEntitlementStorage(
            provider = provider,
            accountFlow = account,
            ownerId = { "owner-x" },
            isRelease = false,
            scope = scope,
            now = { 1_000_000L },
            // Dev-key minter; signature value is irrelevant to these reader/writer figure assertions.
            mintClient = DevSeedEntitlementMintClient(sign = { it }, now = { 1_000_000L }),
        )

        assertFalse(provider.observe().first().hasStorage)
        assertNull(proxy.database.getData(proxy.entitlementId("owner-x")))

        scope.cancel()
    }

    @Test
    fun syncMergedEntitlement_flipsFigures_withoutLocalAccount() = runTest {
        // Device B payoff: no local account, but the owner's Entitlement arrives via
        // sync-merge (modelled as a direct saveData). The reader must reflect it.
        val proxy = newProxy()
        val provider = EntitlementStorageProvider()
        val account = MutableStateFlow<PublisherAccount?>(null)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        proxy.executionContext.initEntitlementStorage(
            provider = provider,
            accountFlow = account,
            ownerId = { "owner-x" },
            isRelease = false,
            scope = scope,
            now = { 5_000L },
            // Dev-key minter; signature value is irrelevant to these reader/writer figure assertions.
            mintClient = DevSeedEntitlementMintClient(sign = { it }, now = { 5_000L }),
        )

        proxy.executionContext.saveData(
            Entitlement.create(
                context = proxy.tContext,
                ownerId = "owner-x",
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.BASIC,
                validFrom = 0L,
                validUntil = 10_000L,
                publisherSignature = ByteArray(0),
            ),
        )

        val figures = provider.observe().first()
        assertTrue(figures.hasStorage)
        assertEquals(EntitlementProducts.BASIC_LIMIT_BYTES, figures.limitBytes)

        scope.cancel()
    }
}
