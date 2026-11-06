package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.EncodedSlice
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.SliceSnapshotProducer
import org.tenshu.core.common.proxy.SliceSnapshotProducerRegistry
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.util.generateId
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetSyncLog
import org.tenshu.sync.GetSyncLogBefore
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.PushBlock
import org.tenshu.sync.RelayRouteUnsupportedError
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncCatchUpStatus
import org.tenshu.sync.SyncLogResponse
import org.tenshu.sync.SyncService
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule

/**
 * A real [SyncScheduler], built by the production [initSyncService], over a relay whose every
 * answer is scripted — for the catch-up signal's tests (`ios-sync-activity-indicator.md` §6).
 *
 * Each lane has a queue of [Answer]s, consumed one per request. An empty queue answers an empty
 * page that leaves the cursor where it was asked, which is what a relay with nothing new says.
 *
 * ⚠ The loops are never started (`startBackgroundLoops = false`): every test steps `tickOnce` by
 * hand, for the reason `SyncHealthReportingTest.fixture` records.
 */
internal class CatchUpFixture private constructor(
    val proxy: TestProxy,
    val scheduler: SyncScheduler,
    val syncManager: SyncManagerImpl,
    val components: SyncProxyComponents,
    val writerId: String,
    private val crypto: SyncCryptoManager,
) {

    /** What one request is answered with. */
    sealed interface Answer {
        /** An answered page. [nextCursor] `null` leaves the cursor where the request asked. */
        data class Page(
            val blocks: List<SyncBlock> = emptyList(),
            val tombstones: List<DeleteBlock> = emptyList(),
            val nextCursor: Long? = null,
            val hasMore: Boolean = false,
        ) : Answer

        /** The request fails the way an unreachable relay does. */
        data object Fails : Answer
    }

    val forward = ArrayDeque<Answer>()
    val backward = ArrayDeque<Answer>()
    val blockLane = ArrayDeque<Answer>()
    val tombstoneLane = ArrayDeque<Answer>()

    /** Run at the moment each forward request (merged, or the block lane) reaches the relay. */
    var onForwardRequest: () -> Unit = {}

    /** This writer's row as a reader in this process would get it; `null` = no row. */
    fun row(): Boolean? = rowOf(writerId)

    fun rowOf(writer: String): Boolean? = proxy.catchUpRow(writer)

    /** Blocks no key on this device decrypts. Ingest drops them, but they are rows in a page. */
    fun junkBlocks(count: Int): List<SyncBlock> = List(count) {
        SyncBlock.create(
            context = proxy.tContext,
            blockId = generateId(),
            ownerId = "owner",
            hlc = syncManager.hlcManager.tick(),
            encryptedPayload = ByteArray(64) { i -> (i * 7 + it).toByte() },
        )
    }

    fun junkTombstones(count: Int): List<DeleteBlock> = List(count) {
        DeleteBlock.create(
            context = proxy.tContext,
            blockId = generateId(),
            ownerId = "owner",
            hlc = syncManager.hlcManager.tick(),
            targetEntityIdHash = "no-such-entity-$it",
            targetEntityType = SyntheticTestData.ID,
        )
    }

    /**
     * A block this device decrypts and applies, so a [org.tenshu.core.common.proxy.DataObserver]
     * fires **while the page is being applied**.
     */
    fun realBlock(name: String = "arrived.txt"): SyncBlock {
        val entity = Entity.create<SyntheticTestData>(
            context = proxy.tContext,
            composite = SyntheticTestData.Factory.create(proxy.tContext, name = name, value = 1L),
            properties = emptyMap(),
        )
        val payload = DataBlockPayload.create(proxy.tContext, propertyId = null, value = entity)
        val sealed = BlockPayloadEnvelope.seal(BinarySerializer().serialize(payload), proxy.versionStampBytes())
        // Envelope bound as AAD (sync-envelope-authentication §3.2).
        return EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = proxy.tContext,
            blockId = generateId(),
            ownerId = "owner",
            hlc = syncManager.hlcManager.tick(),
            targetEntityIdHash = null,
            plaintext = sealed,
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
    }

    /** Registers a shared realm whose relay answers from [pages], one per pull. */
    fun registerSharedRealm(pages: ArrayDeque<List<SyncBlock>>, onRequest: suspend () -> Unit = {}) {
        val realm = RealmContext(
            ownerId = "shared-1",
            deviceId = syncManager.currentDeviceId,
            derivedKeys = SyncCryptoManager.DerivedKeys(
                sharedIdentityKey = ByteArray(32) { 0x33 },
                sharedVolumeKey = ByteArray(32) { 0x44 },
            ),
            deviceSigningKeyPair = crypto.generateDeviceSigningKeyPair(),
            masterKeyPair = null,
        )
        components.realmRegistry.register(
            realm,
            ScriptedSharedRelay(proxy.tContext, MockSyncRelay(proxy.tContext), pages, onRequest),
        )
    }

    companion object {

        const val WRITER = "app.tenshu.test"

        /**
         * @param wireMergedLane `false` models a proxy whose graph never included `GetSyncLog`,
         *   so every pull goes through the two fallback lanes.
         * @param contentBlobStore wires the slice bootstrap; with [withSliceProducer] it will fetch.
         * @param enabled `false` skips `initialize`, leaving the realm locked.
         */
        suspend fun create(
            scope: CoroutineScope,
            wireMergedLane: Boolean = true,
            relaySupportsMergedLane: Boolean = true,
            contentBlobStore: ((TContext) -> ContentBlobStore)? = null,
            withSliceProducer: Boolean = false,
            /** Registered instead of the fixture's seed producer when [withSliceProducer] is set. */
            sliceProducer: SliceSnapshotProducer = SeedProducer,
            storageGate: SyncGate = SyncGate.AlwaysAllow,
            enabled: Boolean = true,
            writerId: String = WRITER,
            objectStore: ObjectStore = InMemoryObjectStore(),
            /** `null` leaves `initSyncService`'s own default in force, which is what a test of it needs. */
            catchUpHeartbeat: Boolean? = null,
        ): CatchUpFixture {
            val proxy = TestProxy(
                schemaRegistry = createSyntheticTestRegistry(),
                coroutineScope = scope,
                objectStore = objectStore,
                additionalComponents = if (withSliceProducer) {
                    mapOf(SliceSnapshotProducer.ID to SliceSnapshotProducerRegistry(listOf(sliceProducer)))
                } else {
                    emptyMap()
                },
            )
            proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
            proxy.schemaRegistry.registerConfig(syncServiceClientModule)

            val crypto = proxy.tContext.syncCryptoManager()
            val syncManager = SyncManagerImpl(
                cryptoManager = crypto,
                storageDelegate = InMemorySecureStorageDelegate(),
                context = proxy.tContext,
            )
            if (enabled) {
                syncManager.initialize("mock phrase")
                syncManager.currentRealmContext()?.deviceId?.takeIf { it.isNotEmpty() }?.let { deviceId ->
                    proxy.executionContext.saveData(
                        DeviceSchemaVersion.create(
                            context = proxy.tContext,
                            deviceId = deviceId,
                            versions = proxy.schemaRegistry.currentVersionVector(proxy.tContext),
                        ),
                        WriteOrigin.SYNC_INGEST,
                    )
                }
            }

            // The fixture exists before the ops so they can read its queues.
            lateinit var fixture: CatchUpFixture

            proxy.additionalImplementations[PushBlock.ID] = op { _, _ -> null }
            if (wireMergedLane) {
                proxy.additionalImplementations[GetSyncLog.ID] = op { ctx, params ->
                    fixture.onForwardRequest()
                    if (!relaySupportsMergedLane) {
                        throw TenshuException(RelayRouteUnsupportedError.create(ctx.context, "/sync/log"))
                    }
                    val since = (params?.get(GetSyncLog.Params.SINCE_TIMESTAMP) as TInteger).get()
                    logResponse(ctx, fixture.forward.removeFirstOrNull(), since)
                }
                proxy.additionalImplementations[GetSyncLogBefore.ID] = op { ctx, params ->
                    val before = (params?.get(GetSyncLogBefore.Params.BEFORE_SEQ) as TInteger).get()
                    logResponse(ctx, fixture.backward.removeFirstOrNull(), before)
                }
            }
            proxy.additionalImplementations[GetChanges.ID] = op { ctx, params ->
                if (!wireMergedLane || !relaySupportsMergedLane) fixture.onForwardRequest()
                val since = (params?.get(GetChanges.Params.SINCE_TIMESTAMP) as TInteger).get()
                val page = fixture.blockLane.removeFirstOrNull().pageOrThrow()
                ChangesResponse.create(
                    context = ctx.context,
                    blocks = page.blocks,
                    peerTips = emptyList(),
                    nextCursor = page.nextCursor ?: since,
                )
            }
            proxy.additionalImplementations[GetTombstones.ID] = op { ctx, params ->
                val since = (params?.get(GetTombstones.Params.SINCE_TIMESTAMP) as TInteger).get()
                val page = fixture.tombstoneLane.removeFirstOrNull().pageOrThrow()
                TombstonesResponse.create(
                    context = ctx.context,
                    tombstones = page.tombstones,
                    nextCursor = page.nextCursor ?: since,
                )
            }

            val components = if (catchUpHeartbeat == null) {
                proxy.executionContext.initSyncService(
                    syncManager,
                    crypto,
                    contentBlobStore = contentBlobStore?.invoke(proxy.tContext),
                    storageGate = storageGate,
                    hostAppId = writerId,
                    startBackgroundLoops = false,
                    platformScheduler = NoOpPlatformScheduler,
                )
            } else {
                proxy.executionContext.initSyncService(
                    syncManager,
                    crypto,
                    contentBlobStore = contentBlobStore?.invoke(proxy.tContext),
                    storageGate = storageGate,
                    hostAppId = writerId,
                    catchUpHeartbeat = catchUpHeartbeat,
                    startBackgroundLoops = false,
                    platformScheduler = NoOpPlatformScheduler,
                )
            }
            fixture = CatchUpFixture(proxy, components.scheduler, syncManager, components, writerId, crypto)
            return fixture
        }

        private fun Answer?.pageOrThrow(): Answer.Page = when (this) {
            null -> Answer.Page()
            is Answer.Page -> this
            Answer.Fails -> throw IllegalStateException("scripted: relay unreachable")
        }

        private fun logResponse(ctx: ExecutionContext, answer: Answer?, asked: Long): SyncLogResponse {
            val page = answer.pageOrThrow()
            return SyncLogResponse.create(
                context = ctx.context,
                blocks = page.blocks,
                tombstones = page.tombstones,
                peerTips = emptyList(),
                nextCursor = page.nextCursor ?: asked,
                hasMore = page.hasMore,
            )
        }

        private fun op(body: (ExecutionContext, Map<String, Data>?) -> Data?) =
            object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?,
                ): T {
                    @Suppress("UNCHECKED_CAST")
                    return body(executionContext, params) as T
                }
            }
    }

    /** One seed slice, so `SliceSnapshotReader.bootstrapOnce` has something to GET. */
    private object SeedProducer : SliceSnapshotProducer {
        override val relationIds: Set<String> = setOf("tenshu/file/folder/files")

        override fun sliceKeys(executionContext: ExecutionContext, relationId: String) = listOf("/")

        override fun bootstrapSliceKeys(executionContext: ExecutionContext, relationId: String) =
            if (relationId in relationIds) listOf("/") else emptyList()

        override fun encodeSlice(
            executionContext: ExecutionContext,
            relationId: String,
            sliceKey: String,
        ): EncodedSlice? = null

        override fun applySlice(
            executionContext: ExecutionContext,
            relationId: String,
            sliceKey: String,
            entries: ByteArray,
            isDeleted: (DataId) -> Boolean,
        ): Int = 0
    }
}

/** A shared realm's relay answering `getChanges` from a script; everything else is the mock's. */
private class ScriptedSharedRelay(
    private val context: TContext,
    delegate: MockSyncRelay,
    private val pages: ArrayDeque<List<SyncBlock>>,
    private val onRequest: suspend () -> Unit,
) : SyncRelay by delegate {

    private var seq = 0L

    override suspend fun getChanges(
        sinceTimestamp: Long,
        tip: SignedHlcTip,
    ): TResult<ChangesResponse> {
        onRequest()
        val blocks = pages.removeFirstOrNull() ?: emptyList()
        seq += blocks.size
        return TResult.Success.create(
            context,
            value = ChangesResponse.create(
                context = context,
                blocks = blocks,
                peerTips = emptyList(),
                nextCursor = if (blocks.isEmpty()) sinceTimestamp else seq,
            ),
        )
    }
}

/** [writer]'s `SyncCatchUpStatus` row as a reader of the relation would get it; `null` = no row. */
internal fun TestProxy.catchUpRow(writer: String): Boolean? {
    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = tContext,
            type = TList.ID,
            relationId = SyncService.Relations.CATCH_UP,
        ),
        isEntity = false,
        elementSchemaId = SyncCatchUpStatus.ID,
    )
    return executionContext.database.getArrayData(ref)
        ?.mapNotNull { it as? SyncCatchUpStatus }
        ?.firstOrNull { it.key.get() == writer }
        ?.catchingUp?.get()
}
