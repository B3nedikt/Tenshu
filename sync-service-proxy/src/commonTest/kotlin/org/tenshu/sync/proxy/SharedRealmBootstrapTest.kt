package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.RealmRegistry.Companion.NAMESPACE_SEP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SR-3b: the host bootstraps an empty shared realm and self-joins. Asserts the
 * three steps end-to-end in-process — relay bootstrap, self-join membership (Kr
 * recoverable under the host's own A13), and registration into [RealmRegistry] —
 * plus the routing invariant that the `Syncable(Eager)` membership stays in the
 * **personal** realm (the auto-join channel) and is NOT misrouted to the freshly-
 * registered shared realm despite being keyed by the shared realm's id.
 */
class SharedRealmBootstrapTest {

    private val phrase = "personal phrase placeholder words go here now"

    @Test
    fun createSharedRealm_bootstrapsRelay_selfJoins_andRegistersRealm() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(identityServiceCommonModule)

        // Personal inbound stream empty (wired for completeness; the loops below are never started).
        proxy.additionalImplementations[GetChanges.ID] = emptyChanges()
        proxy.additionalImplementations[GetTombstones.ID] = emptyTombstones()

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize(phrase)
        val personalOwner = syncManager.currentOwnerId!!

        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            // Nothing here steps or waits on the loops, so they are never started — a loop that
            // never starts has no first tick to race (`SyncHealthReportingTest.fixture`).
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )

        // The bootstrapper reuses this (personal) relay client for the unauthenticated
        // `createRealm` — a recording MockSyncRelay so we can assert the bootstrap call.
        val relay = MockSyncRelay(proxy.tContext)

        val bootstrapper = SharedRealmBootstrapper(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            relay = relay,
            realmRegistry = components.realmRegistry,
        )

        val realm = bootstrapper.createSharedRealm()

        // 1. Bootstrapped at the relay under the shared realm's own ownerId.
        assertEquals(listOf(realm.ownerId), relay.createdRealms(), "POST /sync/realm hit with the shared ownerId")
        assertTrue(realm.ownerId != personalOwner, "shared realm has a distinct ownerId")

        // 2. Self-join: the membership is persisted with role OWNER, and Kr is
        // recoverable from it under the host's own A13 — and unwraps to the realm's
        // actual master key (deriving its DerivedKeys back to the registered ones).
        val membershipId = DataId.StringDataId.create(
            context = proxy.tContext,
            type = SharedRealmMembership.ID,
            keyValue = realm.ownerId,
        )
        val membership = proxy.executionContext.database.getData(membershipId) as? SharedRealmMembership
        assertNotNull(membership, "self-join saved a SharedRealmMembership row")
        assertEquals(SharedRealmMembership.Role.OWNER, membership.role.get())

        val hostA13 = crypto.deriveRealmEncryptionKeyPair(phrase)
        val recoveredKr = crypto.unwrapKeyForIdentity(membership.wrappedMasterKey.get(), hostA13.privateKey)
        assertEquals(
            realm.derivedKeys,
            crypto.deriveSharedRealmKeys(recoveredKr),
            "Kr unwrapped from the membership derives the realm's registered DerivedKeys",
        )

        // 3. Registered into the registry — the emit path now routes this realm's
        // namespaced entities to it.
        assertTrue(
            components.realmRegistry.sharedRealms().any { it.ownerId == realm.ownerId },
            "the shared realm is registered",
        )
        val sharedKeyedId = DataId.StringDataId.create(
            context = proxy.tContext,
            type = "test/entity",
            keyValue = realm.ownerId + NAMESPACE_SEP + "some/file",
        )
        assertEquals(
            realm.ownerId,
            components.realmRegistry.realmForDataId(sharedKeyedId)?.ownerId,
            "a \"\$ownerId::…\"-keyed entity routes to the shared realm",
        )

        // 4. Routing invariant: the membership (keyed by the shared ownerId but with
        // NO namespace separator) stays in the PERSONAL realm — it must not be
        // misrouted to the shared realm's keys just because its key equals an ownerId.
        assertEquals(
            personalOwner,
            components.realmRegistry.realmForDataId(membershipId)?.ownerId,
            "the Eager membership stays in the personal realm (the auto-join channel)",
        )

        // Sanity: the recovered Kr is a full 32-byte key.
        assertEquals(32, recoveredKr.size)
    }

    private fun emptyChanges() = object : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return ChangesResponse.create(
                executionContext.context, emptyList(), emptyList(), 0L
            ) as T
        }
    }

    private fun emptyTombstones() = object : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return TombstonesResponse.create(
                executionContext.context, emptyList(), 0L
            ) as T
        }
    }
}
