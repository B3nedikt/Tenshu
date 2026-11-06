package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.RealmRegistry.Companion.NAMESPACE_SEP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SR-M.1b safety net (`sync-multi-realm-runtime.md`): emit routing keeps two
 * realms cryptographically isolated. A saved entity's block goes to **its**
 * realm's keys + **its** realm's outbound queue — a shared realm's block is
 * never encrypted under the personal key, and never lands in the personal
 * queue. This is the highest-risk correctness property of multi-realm; proving
 * it in-process gates the riskier per-realm drain/relay wiring that follows.
 */
class MultiRealmEmitIsolationTest {

    private val crypto = BootstrapSyncCryptoManager()

    private fun TestProxy.queue(relationId: String): List<SyncBlock> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = tContext,
                type = SyncBlock.ID,
                relationId = relationId,
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID,
        )
        return executionContext.database.getArrayData(ref)?.toList()?.map { it as SyncBlock }
            ?: emptyList()
    }

    @Test
    fun personalAndSharedEmits_routeToSeparateQueuesUnderSeparateKeys() {
        val testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
        }
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
        )
        syncManager.initialize("personal recovery phrase placeholder words here")
        val personalKeys = syncManager.getDerivedKeys()!!

        val registry = RealmRegistry(syncManager)
        val sharedRealm = RealmContext(
            ownerId = "shared-1",
            deviceId = syncManager.currentDeviceId,
            derivedKeys = SyncCryptoManager.DerivedKeys(
                sharedIdentityKey = ByteArray(32) { 0x11 },
                sharedVolumeKey = ByteArray(32) { 0x22 },
            ),
            deviceSigningKeyPair = SyncCryptoManager.KeyPair(ByteArray(32) { 1 }, ByteArray(32) { 1 }),
            masterKeyPair = null,
        )
        registry.register(sharedRealm)
        val observer = SyncObserver(
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            realmRegistry = registry,
        )

        val base = SyncService.Collections.PENDING_BLOCKS
        val sharedQueue = base + NAMESPACE_SEP + "shared-1"

        // 1. A personal-realm entity (unprefixed key) → personal queue, personal owner.
        observer.emitPropertyBlock(
            targetEntityId = DataId.StringDataId.create(testProxy.tContext, "test/entity", "/doc"),
            propertyId = "test/prop",
            value = "personal-payload".toTString(testProxy.tContext),
        )
        var personal = testProxy.queue(base)
        assertEquals(1, personal.size, "personal block lands in the base queue")
        assertEquals(syncManager.currentOwnerId, personal.single().ownerId.get())
        assertTrue(testProxy.queue(sharedQueue).isEmpty(), "personal save must not touch the shared queue")

        // 2. A shared-realm entity ("shared-1::…" key) → shared queue, shared owner.
        observer.emitPropertyBlock(
            targetEntityId = DataId.StringDataId.create(testProxy.tContext, "test/entity", "shared-1$NAMESPACE_SEP/doc"),
            propertyId = "test/prop",
            value = "shared-payload".toTString(testProxy.tContext),
        )
        val shared = testProxy.queue(sharedQueue)
        assertEquals(1, shared.size, "shared block lands in the per-realm queue")
        assertEquals("shared-1", shared.single().ownerId.get())

        // No cross-contamination: the personal queue is untouched by the shared save.
        personal = testProxy.queue(base)
        assertEquals(1, personal.size, "shared save must not leak into the personal queue")

        // 3. The shared block was encrypted under the SHARED key, not the personal key:
        // decrypting its payload with the two realms' identity keys yields different bytes.
        val sharedPayload = shared.single().encryptedPayload.get()
        val underShared = crypto.decrypt(sharedPayload, sharedRealm.derivedKeys.sharedIdentityKey)
        val underPersonal = crypto.decrypt(sharedPayload, personalKeys.sharedIdentityKey)
        assertFalse(
            underShared.contentEquals(underPersonal),
            "the shared block must not decrypt the same under the personal key — wrong-realm key isolation",
        )
    }
}
