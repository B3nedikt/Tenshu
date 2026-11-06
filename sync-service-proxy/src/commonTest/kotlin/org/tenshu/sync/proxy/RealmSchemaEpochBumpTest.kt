package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.reference
import org.tenshu.core.schema.service
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.core.trait.Syncable
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.RealmSchemaEpoch
import org.tenshu.sync.common.decodeVersions
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.tenshu.core.common.module.reference as registerReference
import org.tenshu.core.common.module.service as registerService

/*
 * Two synthetic single-service modules, so `SchemaRegistry.owningServiceOf`
 * attributes unambiguously (the documented rule: the service registered in the
 * same ModuleConfiguration as the type):
 *
 *  - ALPHA at version 3, type `AlphaThing` (Syncable, key = its `name`);
 *  - BETA  at version 5, type `BetaThing`  (Syncable, key = its `name`) which
 *    EMBEDS an `AlphaThing` as a composite field — the §4.1.3 case: writing a
 *    beta row puts alpha bytes on the wire too, so both entries must move.
 *  - LOCAL: a NON-Syncable type in the alpha module, to prove a purely local
 *    row never moves the realm's epoch (§7.2 — only what leaves the device can
 *    fence a peer).
 *
 * Neither service registers a migration chain, which is fine: `verify` only
 * checks contiguity for services that HAVE registered steps.
 */

/** A registered shared realm's ownerId — also the `"$realmId::"` DataId key prefix. */
private const val SHARED_OWNER = "shared-owner-1"

private const val ALPHA_SERVICE_ID = "test/epoch/alphaService"
private const val BETA_SERVICE_ID = "test/epoch/betaService"
private const val ALPHA_ID = "test/epoch/alphaThing"
private const val BETA_ID = "test/epoch/betaThing"
private const val LOCAL_ID = "test/epoch/localThing"

private const val ALPHA_NAME = "$ALPHA_ID/name"
private const val BETA_NAME = "$BETA_ID/name"
private const val BETA_ALPHA = "$BETA_ID/alpha"
private const val LOCAL_NAME = "$LOCAL_ID/name"

private val ALPHA_DATA_ID = stringDataId("$ALPHA_ID/dataIdProvider", ALPHA_NAME)
private val BETA_DATA_ID = stringDataId("$BETA_ID/dataIdProvider", BETA_NAME)
private val LOCAL_DATA_ID = stringDataId("$LOCAL_ID/dataIdProvider", LOCAL_NAME)

private val ALPHA_SCHEMA: CompositeSchema = composite(
    id = ALPHA_ID,
    traits = listOf(Syncable(), ALPHA_DATA_ID),
    fields = { string(ALPHA_NAME) },
)

private val BETA_SCHEMA: CompositeSchema = composite(
    id = BETA_ID,
    traits = listOf(Syncable(), BETA_DATA_ID),
    fields = {
        string(BETA_NAME)
        composite(BETA_ALPHA, type = ALPHA_SCHEMA)
    },
)

private val LOCAL_SCHEMA: CompositeSchema = composite(
    id = LOCAL_ID,
    traits = listOf(LOCAL_DATA_ID),
    fields = { string(LOCAL_NAME) },
)

private class AlphaDto(fields: List<Data?>) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = ALPHA_SCHEMA
}

private class BetaDto(fields: List<Data?>) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = BETA_SCHEMA
}

private class LocalDto(fields: List<Data?>) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = LOCAL_SCHEMA
}

private val alphaTestModule = tModule {
    composite(interfaceClass = AlphaDto::class, dataSchema = ALPHA_SCHEMA, dtoFactory = ::AlphaDto)
    composite(interfaceClass = LocalDto::class, dataSchema = LOCAL_SCHEMA, dtoFactory = ::LocalDto)
    registerReference(reference(ALPHA_NAME))
    registerReference(reference(LOCAL_NAME))
    trait(ALPHA_SCHEMA, ALPHA_DATA_ID) { context, schema ->
        StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
    }
    trait(LOCAL_SCHEMA, LOCAL_DATA_ID) { context, schema ->
        StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
    }
    registerService(service(id = ALPHA_SERVICE_ID, version = 3))
}

private val betaTestModule = tModule {
    composite(interfaceClass = BetaDto::class, dataSchema = BETA_SCHEMA, dtoFactory = ::BetaDto)
    registerReference(reference(BETA_NAME))
    registerReference(reference(BETA_ALPHA))
    trait(BETA_SCHEMA, BETA_DATA_ID) { context, schema ->
        StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
    }
    registerService(service(id = BETA_SERVICE_ID, version = 5))
}

private fun alpha(context: TContext, name: String): Data =
    context.create(type = ALPHA_ID, fields = listOf(name.toTString(context)))

private fun beta(context: TContext, name: String, alpha: Data): Data =
    context.create(type = BETA_ID, fields = listOf(name.toTString(context), alpha))

private fun local(context: TContext, name: String): Data =
    context.create(type = LOCAL_ID, fields = listOf(name.toTString(context)))

/**
 * Write-triggered realm epoch bump (`core-schema-evolution.md` §7.2 + §4.1.3),
 * driven through the real `SyncObserver` emit consumer that hosts it.
 *
 * `SyncObserver` is constructed directly (no `initSyncService`) so the scheduler
 * loop cannot race the assertions — the `SyncObserverChannelTest` posture.
 */
class RealmSchemaEpochBumpTest {

    private class Fixture(val proxy: TestProxy, val observer: SyncObserver) {

        private fun pendingRef(realmId: String?) = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = proxy.tContext,
                type = SyncBlock.ID,
                relationId = if (realmId == null) SyncService.Collections.PENDING_BLOCKS
                else RealmRegistry.pendingBlocksRelationId(
                    SyncService.Collections.PENDING_BLOCKS,
                    realmId,
                ),
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID,
        )

        /** Queued blocks for the personal realm, or [realmId]'s shared queue. */
        fun pendingBlocks(realmId: String? = null): Int =
            proxy.executionContext.database.getArrayData(pendingRef(realmId))?.toList()?.size ?: 0

        /** The personal realm's epoch row, or [realmId]'s. */
        fun epoch(realmId: String? = null): RealmSchemaEpoch? = proxy.executionContext.database.getData(
            DataId.StringDataId.create(
                context = proxy.tContext,
                type = RealmSchemaEpoch.ID,
                keyValue = RealmRegistry.epochKeyFor(realmId),
            ),
        ) as? RealmSchemaEpoch

        suspend fun save(data: Data, origin: WriteOrigin = WriteOrigin.USER) {
            proxy.executionContext.saveData(data, origin)
            // Two drains: the write's own emit, then the epoch row's re-entrant
            // save (it is Syncable, so it comes back through the same channel).
            observer.awaitEmitIdle()
            observer.awaitEmitIdle()
        }
    }

    private fun TestProxy.fixture(withSharedRealm: String? = null): Fixture {
        schemaRegistry.registerConfig(syncServiceCommonModule)
        schemaRegistry.registerConfig(alphaTestModule)
        schemaRegistry.registerConfig(betaTestModule)

        val crypto = tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = tContext,
        )
        syncManager.initialize("mock phrase")

        // `null` registry = the single-realm posture every pre-existing case here runs
        // under, so those stay byte-identical.
        val registry = withSharedRealm?.let { ownerId ->
            RealmRegistry(syncManager).apply {
                register(
                    RealmContext(
                        ownerId = ownerId,
                        deviceId = syncManager.currentDeviceId,
                        derivedKeys = SyncCryptoManager.DerivedKeys(
                            sharedIdentityKey = ByteArray(32) { 0x11 },
                            sharedVolumeKey = ByteArray(32) { 0x22 },
                        ),
                        deviceSigningKeyPair = SyncCryptoManager.KeyPair(
                            ByteArray(32) { 1 },
                            ByteArray(32) { 1 },
                        ),
                        masterKeyPair = null,
                    )
                )
            }
        }

        val observer = SyncObserver(executionContext, syncManager, crypto, realmRegistry = registry)
        executionContext.addDataObserver(observer)
        return Fixture(this, observer)
    }

    @Test
    fun firstSyncableWrite_recordsTheWritingServicesDeclaredVersion() = runTest {
        val f = TestProxy(createSyntheticTestRegistry(), coroutineScope = this).fixture()

        f.save(alpha(f.proxy.tContext, "a1"))

        val epoch = assertNotNull(f.epoch(), "the first Syncable write must create the epoch row")
        assertEquals(
            mapOf(ALPHA_SERVICE_ID to 3),
            epoch.decodeVersions(f.proxy.tContext).toMap(),
            "only the service whose types the write contained gets an entry (§4 self-scoping)",
        )
        assertEquals(
            TENSHU_STORAGE_FORMAT_VERSION,
            epoch.decodeVersions(f.proxy.tContext).coreVersion.get().toInt(),
            "core is the global floor and rides every write",
        )
    }

    /**
     * The epoch row is itself `Syncable`, so its own save re-enters the emit
     * consumer. The writer excludes its own type; without that the realm would
     * emit epoch blocks forever. Counting blocks is the honest assertion: one
     * data block + exactly one epoch block.
     */
    @Test
    fun epochRowOwnSave_doesNotLoop() = runTest {
        val f = TestProxy(createSyntheticTestRegistry(), coroutineScope = this).fixture()

        f.save(alpha(f.proxy.tContext, "a1"))

        assertEquals(2, f.pendingBlocks(), "1 data block + 1 epoch block, and no more")

        // Extra drains must not produce anything further.
        f.observer.awaitEmitIdle()
        f.observer.awaitEmitIdle()
        assertEquals(2, f.pendingBlocks(), "the epoch save must not re-trigger a bump")
    }

    @Test
    fun secondWriteAtTheSameVersion_doesNotRewriteTheEpoch() = runTest {
        val f = TestProxy(createSyntheticTestRegistry(), coroutineScope = this).fixture()

        f.save(alpha(f.proxy.tContext, "a1"))
        val blocksAfterFirst = f.pendingBlocks()

        f.save(alpha(f.proxy.tContext, "a2"))

        assertEquals(
            blocksAfterFirst + 1,
            f.pendingBlocks(),
            "the second write emits ONLY its own block — nothing is behind, so the no-op fast " +
                "path must not rewrite the epoch row",
        )
        assertEquals(mapOf(ALPHA_SERVICE_ID to 3), f.epoch()!!.decodeVersions(f.proxy.tContext).toMap())
    }

    /**
     * §4.1.3: the bump follows CONTAINED types, not the written entity's nominal
     * owner. A beta row inlines alpha's bytes, so a reader lagging on *alpha*
     * would garble it — and must therefore be fenced for alpha too.
     */
    @Test
    fun embeddedForeignServiceComposite_bumpsThatServiceToo() = runTest {
        val f = TestProxy(createSyntheticTestRegistry(), coroutineScope = this).fixture()

        f.save(beta(f.proxy.tContext, "b1", alpha(f.proxy.tContext, "inner")))

        assertEquals(
            mapOf(ALPHA_SERVICE_ID to 3, BETA_SERVICE_ID to 5),
            f.epoch()!!.decodeVersions(f.proxy.tContext).toMap(),
            "the embedded alpha composite must raise ALPHA's entry as well as BETA's",
        )
    }

    @Test
    fun nonSyncableLocalWrite_doesNotBumpTheEpoch() = runTest {
        val f = TestProxy(createSyntheticTestRegistry(), coroutineScope = this).fixture()

        f.save(local(f.proxy.tContext, "l1"))

        assertNull(
            f.epoch(),
            "a row that never leaves the device cannot fence a peer, so it must not move the epoch",
        )
        assertEquals(0, f.pendingBlocks(), "and it emits no block at all")
    }

    /**
     * Ingested peer writes must never bump: `onDataSaved` early-returns on
     * [WriteOrigin.SYNC_INGEST] before the emit channel, so the writer is
     * unreachable from the ingest path. Without that, one device's bump would
     * ping-pong across the realm.
     */
    @Test
    fun ingestedWrite_doesNotBumpTheEpoch() = runTest {
        val f = TestProxy(createSyntheticTestRegistry(), coroutineScope = this).fixture()

        f.save(alpha(f.proxy.tContext, "a1"), origin = WriteOrigin.SYNC_INGEST)

        assertNull(f.epoch(), "an ingested write is a peer's, not this device's, emission")
        assertEquals(0, f.pendingBlocks())
    }

    // --- core floor (§7.11) ---

    /**
     * A core rise must reach an **existing** row, or `SchemaPullFence` — which fences on
     * core as of §7.11 — goes blind to exactly the skew it was just taught to catch. The
     * gap is real: House Rule #9 bumps `TENSHU_STORAGE_FORMAT_VERSION` for a reshape of a
     * core-common type, which belongs to no service, so no *service* entry would move.
     */
    @Test
    fun coreRise_updatesAnExistingEpochRow_evenWithNoServiceBehind() = runTest {
        val f = TestProxy(createSyntheticTestRegistry(), coroutineScope = this).fixture()

        // A row already exists, recorded at a LOWER core than this build's.
        f.proxy.executionContext.database.saveData(
            RealmSchemaEpoch.create(
                context = f.proxy.tContext,
                versions = VersionVector.create(
                    context = f.proxy.tContext,
                    coreVersion = TENSHU_STORAGE_FORMAT_VERSION - 1,
                    services = listOf(
                        ServiceVersion.create(f.proxy.tContext, ALPHA_SERVICE_ID, 3),
                    ),
                ),
            ),
        )

        // Alpha is already at its declared version, so NO service entry is behind.
        f.save(alpha(f.proxy.tContext, "a1"))

        val epoch = assertNotNull(f.epoch())
        assertEquals(
            TENSHU_STORAGE_FORMAT_VERSION,
            epoch.decodeVersions(f.proxy.tContext).coreVersion.get().toInt(),
            "the realm's recorded store/wire floor must track this device's",
        )
    }

    /**
     * ⚠ The narrow half. E2d-1 suppressed core-only writes on purpose: creating a row for
     * core alone makes the very first write in every realm emit an epoch block (it broke
     * ~10 sync tests) and puts the row in realms whose writes carry no attributable
     * service at all. §7.11 only added *update*-if-present.
     */
    @Test
    fun coreRise_aloneStillDoesNotCreateTheEpochRow() = runTest {
        val f = TestProxy(createSyntheticTestRegistry(), coroutineScope = this).fixture()

        // A purely local (non-Syncable) write: nothing emits, no service is attributable.
        f.save(local(f.proxy.tContext, "l1"))

        assertNull(f.epoch(), "core alone must not conjure an epoch row into a fresh realm")
        assertEquals(0, f.pendingBlocks())
    }

    // --- per-realm routing of the bump (§7.1, shared-realm fencing) ---

    /**
     * A write routed to a shared realm must bump **that realm's** epoch row, not the
     * personal one. Before per-realm keys every bump wrote the one constant-keyed row,
     * so a shared-folder write raised the personal epoch: it would fence a
     * personal-realm peer over a shared folder's schema, and never fence the shared
     * realm's actual members.
     */
    @Test
    fun sharedRealmWrite_bumpsThatRealmsEpoch_notThePersonalOne() = runTest {
        val f = TestProxy(createSyntheticTestRegistry(), coroutineScope = this)
            .fixture(withSharedRealm = SHARED_OWNER)

        // The "$realmId::" DataId prefix is what routes the entity — and therefore the
        // bump — to the shared realm (`RealmRegistry.realmForDataId`).
        f.save(alpha(f.proxy.tContext, "$SHARED_OWNER::a1"))

        assertNull(f.epoch(), "a shared-realm write must not touch the personal realm's epoch")
        val shared = assertNotNull(
            f.epoch(SHARED_OWNER),
            "the shared realm's own epoch row must record the write",
        )
        assertEquals(
            mapOf(ALPHA_SERVICE_ID to 3),
            shared.decodeVersions(f.proxy.tContext).toMap(),
        )
    }

    /**
     * The bump row carries the shared realm's namespaced key, which is exactly what
     * makes the ordinary emit path route its own block to that realm's queue — no
     * special case anywhere. If it landed in the personal queue it would be encrypted
     * under the wrong realm's key and pushed to the wrong relay stream.
     */
    @Test
    fun sharedRealmEpochBlock_ridesTheSharedRealmsQueue() = runTest {
        val f = TestProxy(createSyntheticTestRegistry(), coroutineScope = this)
            .fixture(withSharedRealm = SHARED_OWNER)

        f.save(alpha(f.proxy.tContext, "$SHARED_OWNER::a1"))

        assertEquals(
            0,
            f.pendingBlocks(),
            "neither the data block nor its epoch bump belongs in the personal queue",
        )
        assertEquals(
            2,
            f.pendingBlocks(SHARED_OWNER),
            "the shared realm's queue holds the write's block AND its epoch bump",
        )
    }

    /** Personal writes are unchanged by the registry's presence — the no-regression half. */
    @Test
    fun personalWrite_stillBumpsThePersonalEpoch_withASharedRealmRegistered() = runTest {
        val f = TestProxy(createSyntheticTestRegistry(), coroutineScope = this)
            .fixture(withSharedRealm = SHARED_OWNER)

        f.save(alpha(f.proxy.tContext, "a1"))

        assertNotNull(f.epoch(), "an unprefixed key is personal, registry or not")
        assertNull(f.epoch(SHARED_OWNER), "and it must not leak into a shared realm's epoch")
    }
}
