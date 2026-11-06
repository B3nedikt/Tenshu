package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SR-3b.2: a `SharedRealmMembership` landing locally auto-registers the shared realm
 * (`SharedRealmJoinObserver`). Exercises the joiner path in-process — the cross-device
 * sync that delivers the membership is the deferred dev-relay system test (SR-3b.3);
 * here we save the membership directly (as the joiner's `SyncProcessor` would on
 * ingest) and assert the observer joins. Also asserts the bootstrapper's OWNER
 * registration survives the observer, and that a membership wrapped to a *different*
 * member is not joined.
 */
class SharedRealmAutoJoinTest {

    private val phrase = "personal phrase placeholder words go here now"

    private class Fixture(
        val proxy: TestProxy,
        val syncManager: SyncManagerImpl,
        val crypto: SyncCryptoManager,
        val components: SyncProxyComponents,
        /** ownerIds the relay factory was asked to build a relay for. */
        val relayRequests: MutableList<String>,
    )

    private suspend fun CoroutineScope.fixture(): Fixture {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(identityServiceCommonModule)
        proxy.additionalImplementations[GetChanges.ID] = emptyChanges()
        proxy.additionalImplementations[GetTombstones.ID] = emptyTombstones()

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize(phrase)

        // A per-realm relay factory — records which realms it's asked for, returns a
        // MockSyncRelay so the registered realm has a (test) relay wired.
        val relayRequests = mutableListOf<String>()
        val components = proxy.executionContext.initSyncService(
            syncManager = syncManager,
            cryptoManager = crypto,
            sharedRealmRelayFactory = { ownerId ->
                relayRequests += ownerId
                MockSyncRelay(proxy.tContext)
            },
            // Nothing here steps or waits on the loops, so they are never started — a loop that
            // never starts has no first tick to race (`SyncHealthReportingTest.fixture`).
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )
        return Fixture(proxy, syncManager, crypto, components, relayRequests)
    }

    @Test
    fun ingestedMembership_autoJoins_underThisDevicesA13() = runTest {
        val f = fixture()
        val registry = f.components.realmRegistry

        // Build a membership for a realm this device is a member of: Kr wrapped to
        // THIS device's own A13 (as the owner device would have wrapped it for us).
        val kr = f.crypto.generateSharedRealmMasterKey()
        val myA13 = f.crypto.deriveRealmEncryptionKeyPair(phrase)
        val sharedRealmId = "shared-auto-1"
        val membership = SharedRealmMembership.create(
            context = f.proxy.tContext,
            sharedRealmId = sharedRealmId,
            wrappedMasterKey = f.crypto.wrapKeyForIdentity(kr, myA13.publicKey),
            role = SharedRealmMembership.Role.MEMBER,
        )

        assertFalse(registry.isRegistered(sharedRealmId), "precondition: not yet joined")

        // Saving it (what the joiner's SyncProcessor does on ingest) fires the observer.
        f.proxy.executionContext.saveData(membership)

        assertTrue(registry.isRegistered(sharedRealmId), "membership save auto-joined the realm")
        val joined = registry.sharedRealms().single { it.ownerId == sharedRealmId }
        assertEquals(
            f.crypto.deriveSharedRealmKeys(kr),
            joined.derivedKeys,
            "joined under the realm's keys (Kr unwrapped with this device's A13)",
        )
        assertNull(joined.masterKeyPair, "a joiner does not hold the realm's Ed25519 identity")
        assertTrue(
            registry.sharedRuntimesWithRelay().any { it.context.ownerId == sharedRealmId },
            "the joined realm got a relay from the factory",
        )
        assertTrue(f.relayRequests.contains(sharedRealmId), "the relay factory was consulted")
    }

    @Test
    fun membershipWrappedToAnotherMember_isNotJoined() = runTest {
        val f = fixture()
        val registry = f.components.realmRegistry

        // Kr wrapped to a DIFFERENT member's A13 (derived from a different phrase) —
        // this device's A13 can't unwrap it, so the observer must skip.
        val kr = f.crypto.generateSharedRealmMasterKey()
        val otherMemberA13 = f.crypto.deriveRealmEncryptionKeyPair("a different member's recovery phrase")
        val membership = SharedRealmMembership.create(
            context = f.proxy.tContext,
            sharedRealmId = "not-for-us",
            wrappedMasterKey = f.crypto.wrapKeyForIdentity(kr, otherMemberA13.publicKey),
            role = SharedRealmMembership.Role.MEMBER,
        )

        f.proxy.executionContext.saveData(membership)

        assertFalse(
            registry.isRegistered("not-for-us"),
            "a membership wrapped to another member's A13 must not be joined",
        )
    }

    @Test
    fun bootstrapOwnerRegistration_isNotClobberedByTheObserver() = runTest {
        val f = fixture()
        val registry = f.components.realmRegistry

        val bootstrapper = SharedRealmBootstrapper(
            executionContext = f.proxy.executionContext,
            syncManager = f.syncManager,
            cryptoManager = f.crypto,
            relay = MockSyncRelay(f.proxy.tContext),
            realmRegistry = registry,
        )

        val realm = bootstrapper.createSharedRealm()

        // The owner registration (masterKeyPair = the realm Ed25519 identity) survives
        // the auto-join observer that fires on the self-join membership save — the
        // observer skips because the realm is already registered.
        val registered = registry.sharedRealms().single { it.ownerId == realm.ownerId }
        assertNotNull(registered.masterKeyPair, "OWNER keeps the realm's Ed25519 identity")
        assertEquals(
            realm.masterKeyPair?.publicKey?.toList(),
            registered.masterKeyPair?.publicKey?.toList(),
            "still the bootstrapper's keypair, not a null-keypair joiner registration",
        )
        // And it has a relay from the factory.
        assertTrue(registry.sharedRuntimesWithRelay().any { it.context.ownerId == realm.ownerId })
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
