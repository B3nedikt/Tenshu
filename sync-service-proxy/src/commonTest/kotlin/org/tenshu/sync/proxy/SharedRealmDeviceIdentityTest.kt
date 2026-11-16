package org.tenshu.sync.proxy

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.util.HLCManager
import org.tenshu.core.common.util.compare
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTString
import org.tenshu.identity.OwnedRealmMaster
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.deviceIdForPublicKey
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.RealmRegistry.Companion.NAMESPACE_SEP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Threat model T-GR-05 / `shared-realm-read-only-members.md` D10: **no device identity crosses a share.**
 *
 *  1. A shared-realm block's clear HLC names the **realm-local** device id, never the personal one —
 *     the personal id appears on every personal-realm push too, so it linked the two. Personal-realm
 *     blocks are unchanged.
 *  2. Emit and ingest tick **one** clock per realm: what the realm's processor has seen, the next
 *     emit is stamped above (it used to tick the personal clock, which never saw the realm's ingest).
 *  3. An owner's other device joins under its **own per-realm key** and admits that key itself with
 *     the synced realm master — never under its personal A4.
 */
class SharedRealmDeviceIdentityTest {

    private val crypto = BootstrapSyncCryptoManager()
    private val realmLocalId = "realm-local-device"

    private fun TestProxy.queue(relationId: String): List<SyncBlock> =
        executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(tContext, SyncBlock.ID, relationId),
                isEntity = false,
                elementSchemaId = SyncBlock.ID,
            ),
        )?.toList()?.map { it as SyncBlock }.orEmpty()

    private class Emit(
        val proxy: TestProxy,
        val syncManager: SyncManagerImpl,
        val registry: RealmRegistry,
        val observer: SyncObserver,
        /** The clock the registry's factory built, when [emitFixture] was asked for one. */
        val factoryClocks: List<HLCManager>,
    )

    private fun emitFixture(withClockFactory: Boolean = false): Emit {
        val proxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
        }
        val syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
        syncManager.initialize("personal recovery phrase placeholder words here")
        val built = mutableListOf<HLCManager>()
        val clockFactory: ((RealmContext) -> HLCManager)? = if (withClockFactory) {
            { realm -> HLCManager(realm.deviceId, proxy.tContext).also { built += it } }
        } else null
        val registry = RealmRegistry(syncManager, clockFactory = clockFactory)
        registry.register(
            RealmContext(
                ownerId = "shared-1",
                deviceId = realmLocalId,
                derivedKeys = SyncCryptoManager.DerivedKeys(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }),
                deviceSigningKeyPair = SyncCryptoManager.KeyPair(ByteArray(32) { 1 }, ByteArray(32) { 1 }),
                masterKeyPair = null,
            ),
        )
        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            realmRegistry = registry,
        )
        return Emit(proxy, syncManager, registry, observer, built)
    }

    private fun Emit.emitShared(value: String) = observer.emitPropertyBlock(
        targetEntityId = DataId.StringDataId.create(proxy.tContext, "test/entity", "shared-1$NAMESPACE_SEP/doc"),
        propertyId = "test/prop",
        value = value.toTString(proxy.tContext),
    )

    private fun Emit.sharedQueue() = proxy.queue(SyncService.Collections.PENDING_BLOCKS + NAMESPACE_SEP + "shared-1")

    @Test
    fun aSharedRealmBlock_carriesTheRealmLocalId_andAPersonalBlockThePersonalOne() {
        val f = emitFixture()
        val personalId = f.syncManager.currentDeviceId
        assertNotEquals(personalId, realmLocalId, "precondition")

        f.emitShared("shared-payload")
        f.observer.emitPropertyBlock(
            targetEntityId = DataId.StringDataId.create(f.proxy.tContext, "test/entity", "/doc"),
            propertyId = "test/prop",
            value = "personal-payload".toTString(f.proxy.tContext),
        )

        val shared = f.sharedQueue().single()
        assertEquals(realmLocalId, shared.hlc.deviceId.get(), "⚠⚠ a share must never see the personal device id")
        val personal = f.proxy.queue(SyncService.Collections.PENDING_BLOCKS).single()
        assertEquals(personalId, personal.hlc.deviceId.get(), "personal-realm blocks are unchanged")
    }

    @Test
    fun theEmitClock_isTheRealmsOwn_soItStampsAboveWhatIngestSaw() {
        val f = emitFixture(withClockFactory = true)
        val built = f.factoryClocks.single()
        val realm = f.registry.sharedRealms().single()
        assertSame(built, f.registry.runtimeFor("shared-1")!!.clock, "the runtime holds the factory's clock")
        assertSame(built, f.registry.clockFor(realm, f.proxy.tContext), "and that is the clock emits use")

        // What the realm's processor does on ingest: a peer block far in the future.
        val peer = HybridLogicalClock.create(f.proxy.tContext, physicalTime = built.current().physicalTime.get() + 3_600_000L, logicalCounter = 3, deviceId = "peer")
        built.update(peer)

        f.emitShared("after-ingest")

        val stamped = f.sharedQueue().single().hlc
        assertTrue(stamped.compare(peer) > 0, "the next emit is ordered after the realm write it has seen")
        assertEquals(realmLocalId, stamped.deviceId.get())
        assertTrue(
            f.syncManager.hlcManager.current().physicalTime.get() < peer.physicalTime.get(),
            "the personal clock is not dragged along by a share's ingest",
        )
    }

    @Test
    fun aSecondOwnerDevice_joinsUnderItsOwnPerRealmKey_andAdmitsItself() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(identityServiceCommonModule)
        proxy.additionalImplementations[GetChanges.ID] = emptyChanges()
        proxy.additionalImplementations[GetTombstones.ID] = emptyTombstones()
        val crypto = proxy.tContext.syncCryptoManager()
        val phrase = "personal phrase placeholder words go here now"
        val syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
        syncManager.initialize(phrase)

        val relays = mutableListOf<MockSyncRelay>()
        val components = proxy.executionContext.initSyncService(
            syncManager = syncManager,
            cryptoManager = crypto,
            grantedRelayFactory = { MockSyncRelay(proxy.tContext).also { relays += it } },
            // The self-admission runs here, so `advanceUntilIdle` waits for it.
            coroutineScope = backgroundScope,
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )
        val registry = components.realmRegistry

        // What another owner device created and synced here: the realm master, then the membership.
        val master = crypto.generateDeviceSigningKeyPair()
        val realmId = "shared-owned-1"
        val kr = crypto.generateSharedRealmMasterKey()
        proxy.executionContext.saveData(
            OwnedRealmMaster.create(proxy.tContext, realmId, master.publicKey, master.privateKey),
        )
        assertFalse(registry.isRegistered(realmId), "the master alone does not join — no Kr yet")
        proxy.executionContext.saveData(
            SharedRealmMembership.create(
                context = proxy.tContext,
                sharedRealmId = realmId,
                wrappedMasterKey = crypto.wrapKeyForIdentity(kr, crypto.deriveRealmEncryptionKeyPair(phrase).publicKey),
                role = SharedRealmMembership.Role.OWNER,
            ),
        )
        settleBackground()

        val joined = assertNotNull(registry.runtimeFor(realmId)).context
        assertNotEquals(syncManager.currentDeviceId, joined.deviceId, "⚠⚠ not the personal device id")
        assertFalse(joined.deviceSigningKeyPair.publicKey.contentEquals(syncManager.getDeviceSigningKeyPair().publicKey), "nor the personal A4")
        assertEquals(deviceIdForPublicKey(joined.deviceSigningKeyPair.publicKey), joined.deviceId)
        assertNotNull(joined.masterKeyPair, "it owns the realm, so it is an OWNER")

        val admitted = relays.flatMap { it.admittedDevices() }.single()
        assertEquals(realmId, admitted.first)
        assertEquals(joined.deviceId, admitted.second, "it admitted its own per-realm id")
        assertTrue(admitted.third.contentEquals(joined.deviceSigningKeyPair.publicKey))

        // §5b R3: the admission is stamped, from the realm's own clock — so it names the realm-local id.
        val stamp = relays.firstNotNullOf { it.admittedStamp(realmId, joined.deviceId) }
        assertEquals(joined.deviceId, org.tenshu.core.common.util.decodeHlcStamp(stamp)?.deviceId, "stamped as the realm-local device")
    }

    @Test
    fun aDeviceRegisteredBeforeTheMasterArrived_isUpgraded_keepingItsPerRealmId() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(identityServiceCommonModule)
        proxy.additionalImplementations[GetChanges.ID] = emptyChanges()
        proxy.additionalImplementations[GetTombstones.ID] = emptyTombstones()
        val crypto = proxy.tContext.syncCryptoManager()
        val phrase = "personal phrase placeholder words go here now"
        val syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
        syncManager.initialize(phrase)
        val relays = mutableListOf<MockSyncRelay>()
        val components = proxy.executionContext.initSyncService(
            syncManager = syncManager,
            cryptoManager = crypto,
            grantedRelayFactory = { MockSyncRelay(proxy.tContext).also { relays += it } },
            // The self-admission runs here, so `advanceUntilIdle` waits for it.
            coroutineScope = backgroundScope,
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )
        val registry = components.realmRegistry
        val realmId = "shared-owned-2"
        val master = crypto.generateDeviceSigningKeyPair()

        // Membership first: registered locally, under a per-realm key, not admitted (no master yet).
        proxy.executionContext.saveData(
            SharedRealmMembership.create(
                context = proxy.tContext,
                sharedRealmId = realmId,
                wrappedMasterKey = crypto.wrapKeyForIdentity(
                    crypto.generateSharedRealmMasterKey(),
                    crypto.deriveRealmEncryptionKeyPair(phrase).publicKey,
                ),
                role = SharedRealmMembership.Role.OWNER,
            ),
        )
        settleBackground()
        val before = assertNotNull(registry.runtimeFor(realmId)).context
        assertNotEquals(syncManager.currentDeviceId, before.deviceId)
        assertTrue(relays.all { it.admittedDevices().isEmpty() }, "no master, no admission")

        // Then the master syncs in.
        proxy.executionContext.saveData(OwnedRealmMaster.create(proxy.tContext, realmId, master.publicKey, master.privateKey))
        settleBackground()

        val after = assertNotNull(registry.runtimeFor(realmId)).context
        assertNotNull(after.masterKeyPair)
        assertEquals(before.deviceId, after.deviceId, "the realm-local id is kept, not re-minted")
        assertEquals(after.deviceId, relays.flatMap { it.admittedDevices() }.single().second)
    }

    /** ⚠ `advanceUntilIdle` ignores background work, which is where the self-admission runs. */
    private fun TestScope.settleBackground() {
        advanceTimeBy(5_000)
        runCurrent()
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
            return ChangesResponse.create(executionContext.context, emptyList(), emptyList(), 0L) as T
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
            return TombstonesResponse.create(executionContext.context, emptyList(), 0L) as T
        }
    }
}
