package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.EntityDto
import org.tenshu.core.common.impl.reference.CompositeTypeReferenceDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.schemaMigration
import org.tenshu.core.common.module.service as registerService
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.migration.PayloadUpcaster
import org.tenshu.core.common.schema.migration.SchemaMigration
import org.tenshu.core.common.schema.migration.schemaMigration
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.reference
import org.tenshu.core.schema.service
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTString
import org.tenshu.core.trait.Syncable
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.DeltaBatchPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncDelta
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.core.common.module.reference as registerReference

/*
 * Synthetic upcast fixtures: a "note" reshaped v1 → v2 → v3 (live), plus a
 * type retired at v1→v2 via dropType. The service declares version 3, so a
 * block stamped v1 needs the full two-step chain, v2 one step, v3 none, and
 * v0 sits below the derived floor. LEGACY's field reference is deliberately
 * NOT registered in the live module (it retired with v1) — old entity blocks
 * carrying it prove the decode overlay's reference fallback.
 */

private const val UPCAST_SERVICE_ID = "test/upcast/service"
private const val NOTE_ID = "test/upcast/note"
private const val RETIRED_ID = "test/upcast/retired"

private object NoteFields {
    const val TITLE = "$NOTE_ID/title" // all versions — the DataId key
    const val LEGACY = "$NOTE_ID/legacy" // v1 only — dropped at v2
    const val KIND = "$NOTE_ID/kind" // v2+ — new REQUIRED, derived from TITLE
    const val SCORE = "$NOTE_ID/score" // v3 — new REQUIRED, derived from TITLE
}

private val NOTE_AT_V1: CompositeSchema = composite(
    id = NOTE_ID,
    fields = {
        string(NoteFields.TITLE)
        string(NoteFields.LEGACY)
    }
)

private val NOTE_AT_V2: CompositeSchema = composite(
    id = NOTE_ID,
    fields = {
        string(NoteFields.TITLE)
        string(NoteFields.KIND)
    }
)

private val NOTE_DATA_ID_TRAIT = stringDataId("$NOTE_ID/dataIdProvider", NoteFields.TITLE)

private val NOTE_LIVE: CompositeSchema = composite(
    id = NOTE_ID,
    traits = listOf(Syncable(), NOTE_DATA_ID_TRAIT),
    fields = {
        string(NoteFields.TITLE)
        string(NoteFields.KIND)
        int(NoteFields.SCORE)
    }
)

private val RETIRED_AT_V1: CompositeSchema = composite(
    id = RETIRED_ID,
    fields = { string("$RETIRED_ID/x") }
)

private class NoteDto(fields: List<Data?>) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = NOTE_LIVE
}

/** A composite bound to an explicit (old) snapshot — the emit-side twin of the internal SnapshotComposite. */
private class LegacyShaped(
    private val boundSchema: CompositeSchema,
    fields: List<Data?>,
) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = boundSchema
}

private fun noteStep1(): SchemaMigration =
    schemaMigration(UPCAST_SERVICE_ID, fromVersion = 1) {
        migration(typeId = NOTE_ID, from = NOTE_AT_V1, to = NOTE_AT_V2) {
            drop(NoteFields.LEGACY)
            derive(NoteFields.KIND) { old, ctx ->
                ("kind:" + (old as Composite).getField<TString>(NoteFields.TITLE).get())
                    .toTString(ctx.context)
            }
        }
        dropType(typeId = RETIRED_ID, from = RETIRED_AT_V1)
    }

private fun noteStep2(): SchemaMigration =
    schemaMigration(UPCAST_SERVICE_ID, fromVersion = 2) {
        migration(typeId = NOTE_ID, from = NOTE_AT_V2, to = NOTE_LIVE) {
            derive(NoteFields.SCORE) { old, ctx ->
                (old as Composite).getField<TString>(NoteFields.TITLE).get()
                    .length.toLong().toTInteger(ctx.context)
            }
        }
    }

private val upcastTestModule = tModule {
    composite(
        interfaceClass = NoteDto::class,
        dataSchema = NOTE_LIVE,
        dtoFactory = ::NoteDto,
    )
    registerReference(reference(NoteFields.TITLE))
    registerReference(reference(NoteFields.KIND))
    registerReference(reference(NoteFields.SCORE))
    // NoteFields.LEGACY deliberately unregistered — see file header.
    trait(
        dataSchema = NOTE_LIVE,
        traitSchema = NOTE_DATA_ID_TRAIT,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
    registerService(service(id = UPCAST_SERVICE_ID, version = 3))
    schemaMigration(noteStep1())
    schemaMigration(noteStep2())
}

/**
 * E2c history upcast (`core-schema-evolution.md` §8.2): a block whose stamp
 * names a service version behind the receiver's declared version decodes
 * under the *producing* schemas and upcasts to the current shape before
 * materialization; below-floor stamps and dropType'd values drop BY
 * DECISION. Current-stamped blocks ride the fast path untouched (the E0/E1
 * behaviour `BlockVersionStampTest` pins for chain-less registries).
 */
class HistoryUpcastTest {

    private lateinit var testProxy: TestProxy
    private lateinit var processor: SyncProcessor
    private lateinit var syncManager: SyncManagerImpl

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        testProxy.schemaRegistry.registerConfig(upcastTestModule)

        val crypto = testProxy.tContext.syncCryptoManager()
        syncManager = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate()
        )
        syncManager.initialize("mock phrase")

        processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto
        )
    }

    // --- emit-side helpers: old-shaped bytes exactly as an old device wrote them ---

    private fun serialize(data: Data): ByteArray =
        BinarySerializer(testProxy.schemaRegistry).serialize(data)

    /** The emitter's full-vector stamp (§4.1.2), naming [versions]' services. */
    private fun stampBytes(vararg versions: Pair<String, Int>): ByteArray = serialize(
        VersionVector.create(
            context = testProxy.tContext,
            coreVersion = TENSHU_STORAGE_FORMAT_VERSION,
            services = versions.map { (serviceId, version) ->
                ServiceVersion.create(testProxy.tContext, serviceId, version)
            },
        )
    )

    private fun entityPayloadBytes(value: Data): ByteArray = serialize(
        DataBlockPayload.create(testProxy.tContext, propertyId = null, value = value)
    )

    private fun v1Note(title: String, legacy: String): Data = LegacyShaped(
        NOTE_AT_V1,
        listOf(title.toTString(testProxy.tContext), legacy.toTString(testProxy.tContext)),
    )

    private fun blockCarrying(plaintext: ByteArray, blockId: String): SyncBlock {
        val crypto = testProxy.tContext.syncCryptoManager()
        // Envelope bound as AAD (sync-envelope-authentication §3.2).
        return EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(
                context = testProxy.tContext,
                physicalTime = 2000L,
                logicalCounter = 0L,
                deviceId = "device-remote"
            ),
            targetEntityIdHash = null,
            plaintext = plaintext,
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
    }

    private fun storedNote(title: String): Data? = testProxy.database.getData(
        DataId.StringDataId.create(testProxy.tContext, NOTE_ID, title)
    )

    // --- the ingest round-trips ---

    @Test
    fun multiStepV1CompositeBlockMaterializesAtTheLiveShape() {
        val sealed = BlockPayloadEnvelope.seal(
            entityPayloadBytes(v1Note(title = "alpha", legacy = "junk")),
            stampBytes(UPCAST_SERVICE_ID to 1),
        )

        processor.processSyncBlock(blockCarrying(sealed, blockId = "v1-composite"))

        val stored = storedNote("alpha")
        assertIs<NoteDto>(stored, "the migrated value must be a registered LIVE dto")
        assertEquals("kind:alpha", stored.getField<TString>(NoteFields.KIND).get(), "v1→v2 derive")
        assertEquals(5L, stored.getField<TInteger>(NoteFields.SCORE).get(), "v2→v3 derive")
        assertEquals("alpha", stored.getField<TString>(NoteFields.TITLE).get(), "auto-copy by id")
    }

    @Test
    fun singleStepV2BlockRunsOnlyTheRemainingChain() {
        val v2Note = LegacyShaped(
            NOTE_AT_V2,
            listOf(
                "beta".toTString(testProxy.tContext),
                "custom-kind".toTString(testProxy.tContext),
            ),
        )
        val sealed = BlockPayloadEnvelope.seal(
            entityPayloadBytes(v2Note),
            stampBytes(UPCAST_SERVICE_ID to 2),
        )

        processor.processSyncBlock(blockCarrying(sealed, blockId = "v2-composite"))

        val stored = storedNote("beta")
        assertIs<NoteDto>(stored)
        // The v1→v2 derive must NOT re-run — the emitter's own KIND survives.
        assertEquals("custom-kind", stored.getField<TString>(NoteFields.KIND).get())
        assertEquals(4L, stored.getField<TInteger>(NoteFields.SCORE).get())
    }

    @Test
    fun v1EntityBlockMigratesThroughTheFieldMapIncludingTheRetiredFieldReference() {
        // An old device's entity: field map keyed by v1 field ids, including
        // LEGACY, whose reference registration no longer exists live.
        val oldEntity = EntityDto<Composite>(
            type = CompositeTypeReferenceDto(NOTE_ID),
            fields = mapOf(
                NoteFields.TITLE to "gamma".toTString(testProxy.tContext),
                NoteFields.LEGACY to "junk".toTString(testProxy.tContext),
            ),
            properties = emptyMap(),
        )
        val sealed = BlockPayloadEnvelope.seal(
            entityPayloadBytes(oldEntity),
            stampBytes(UPCAST_SERVICE_ID to 1),
        )

        processor.processSyncBlock(blockCarrying(sealed, blockId = "v1-entity"))

        // The store materializes rows back as the registered composite dto
        // (properties ride separately) — the LIVE shape is what matters here.
        val stored = storedNote("gamma")
        assertIs<NoteDto>(stored, "the migrated entity's composite must be the registered LIVE dto")
        assertEquals("kind:gamma", stored.getField<TString>(NoteFields.KIND).get())
        assertEquals(5L, stored.getField<TInteger>(NoteFields.SCORE).get())
    }

    @Test
    fun belowFloorBlockIsDroppedByDecision() {
        val sealed = BlockPayloadEnvelope.seal(
            entityPayloadBytes(v1Note(title = "delta", legacy = "x")),
            stampBytes(UPCAST_SERVICE_ID to 0), // floor is 1 (steps from v1, v2)
        )

        processor.processSyncBlock(blockCarrying(sealed, blockId = "below-floor"))

        assertNull(storedNote("delta"), "a below-floor block must be dropped, never applied")
    }

    @Test
    fun currentStampTakesTheFastPathUntouched() {
        val liveNote = NoteDto(
            listOf(
                "epsilon".toTString(testProxy.tContext),
                "kind:live".toTString(testProxy.tContext),
                99L.toTInteger(testProxy.tContext),
            )
        )
        val sealed = BlockPayloadEnvelope.seal(
            entityPayloadBytes(liveNote),
            stampBytes(UPCAST_SERVICE_ID to 3),
        )

        processor.processSyncBlock(blockCarrying(sealed, blockId = "current"))

        val stored = storedNote("epsilon")
        assertIs<NoteDto>(stored)
        assertEquals(99L, stored.getField<TInteger>(NoteFields.SCORE).get())
    }

    @Test
    fun deltaBatchUpcastsPerValueAndAppliesTheRest() {
        fun hlc(at: Long) = HybridLogicalClock.create(
            context = testProxy.tContext,
            physicalTime = at,
            logicalCounter = 0L,
            deviceId = "device-remote"
        )
        // One old-shaped note, one retired-type value (dropped BY DECISION),
        // one untouched-type entity — all under the same v1 stamp.
        val batch = DeltaBatchPayload.create(
            context = testProxy.tContext,
            deltas = listOf(
                SyncDelta.create(
                    context = testProxy.tContext,
                    hlc = hlc(2100L),
                    value = v1Note(title = "zeta", legacy = "old"),
                    deltaId = "d1",
                ),
                SyncDelta.create(
                    context = testProxy.tContext,
                    hlc = hlc(2200L),
                    value = LegacyShaped(RETIRED_AT_V1, listOf("gone".toTString(testProxy.tContext))),
                    deltaId = "d2",
                ),
                SyncDelta.create(
                    context = testProxy.tContext,
                    hlc = hlc(2300L),
                    value = Entity.create<SyntheticTestData>(
                        context = testProxy.tContext,
                        composite = SyntheticTestData.Factory.create(testProxy.tContext, "eta", 7L),
                        properties = emptyMap()
                    ),
                    deltaId = "d3",
                ),
            ),
        )
        val sealed = BlockPayloadEnvelope.seal(serialize(batch), stampBytes(UPCAST_SERVICE_ID to 1))

        processor.processSyncBlock(blockCarrying(sealed, blockId = "batch-v1"))

        val note = storedNote("zeta")
        assertIs<NoteDto>(note, "the old-shaped delta must upcast and apply")
        assertEquals(4L, note.getField<TInteger>(NoteFields.SCORE).get())
        val synthetic = testProxy.database.getData(
            DataId.StringDataId.create(testProxy.tContext, SyntheticTestData.ID, "eta")
        ) as? SyntheticTestData
        assertEquals(7L, synthetic?.value?.get(), "the untouched-type delta must apply as-is")
        assertNull(
            testProxy.database.getData(
                DataId.StringDataId.create(testProxy.tContext, RETIRED_ID, "gone")
            ),
            "the retired-type delta must be dropped"
        )
    }

    // --- verdict-level assertions straight on the upcaster ---

    private fun upcaster() = PayloadUpcaster(
        live = testProxy.schemaRegistry,
        context = testProxy.tContext,
        deviceIdProvider = { "device-under-test" },
    )

    @Test
    fun aRepeatedStampIsParsedOnceAndKeyedByContent() {
        // ⚠⚠ THE REGRESSION THIS GUARDS. Parsing the stamp cost 80 ms per block on a real iPhone
        // — TEN TIMES the payload it accompanies (meanStampUs=80462 vs meanParseUs=8438) — and it
        // happened on the fast path, where there is no migration work at all. Catch-up ran ~5x
        // slower than necessary: of a ~55 s / 500-block tick, ~40 s was re-parsing the same
        // handful of version vectors.
        //
        // ⚠ This cannot be a behavioural test: re-parsing every stamp is still *correct*. Only
        // the parse count can tell the difference, hence the seam.
        val upcaster = upcaster()
        val payload = entityPayloadBytes(v1Note("a", "b"))

        // ⚠ Distinct ARRAY INSTANCES with equal content — a `Map<ByteArray, _>` would miss on the
        // second call, because ByteArray equality is reference-based. That mistake would be
        // silent: same verdicts, cache never hits, 80 ms/block quietly back.
        val first = stampBytes(UPCAST_SERVICE_ID to 1)
        val second = stampBytes(UPCAST_SERVICE_ID to 1)
        assertTrue(first !== second, "sanity: the helper must build a fresh array each call")
        assertTrue(first.contentEquals(second), "sanity: and equal content")

        upcaster.upcast(payload, first)
        assertEquals(1, upcaster.stampParses, "the first stamp is parsed")
        upcaster.upcast(payload, second)
        assertEquals(
            1, upcaster.stampParses,
            "a content-equal stamp must hit the cache — keying by the array itself would miss " +
                "every time and silently restore the 80 ms/block cost"
        )

        // A genuinely different stamp is a real miss, so the cache cannot be answering blindly.
        upcaster.upcast(payload, stampBytes(UPCAST_SERVICE_ID to 2))
        assertEquals(2, upcaster.stampParses, "a different stamp must still be parsed")
    }

    @Test
    fun belowFloorVerdictNamesServiceAndVersions() {
        val result = upcaster().upcast(
            entityPayloadBytes(v1Note("a", "b")),
            stampBytes(UPCAST_SERVICE_ID to 0),
        )
        assertIs<PayloadUpcaster.UpcastResult.Dropped>(result)
        assertTrue(
            result.reason.contains(UPCAST_SERVICE_ID) &&
                result.reason.contains("below the readable floor"),
            "got: ${result.reason}"
        )
    }

    @Test
    fun coreOnlyStampIsDroppedByDecisionWhileChainsAreRegistered() {
        // E0-era test-convenience stamps name no per-service versions; with
        // chains registered the producing shape cannot be proven (KDoc rule).
        val result = upcaster().upcast(entityPayloadBytes(v1Note("a", "b")), ByteArray(0))
        assertIs<PayloadUpcaster.UpcastResult.Dropped>(result)
        assertTrue(result.reason.contains("core-only stamp"), "got: ${result.reason}")
    }

    @Test
    fun droppedTypeValueDropsItsBlockByDecision() {
        // A DataBlockPayload's value is a required field — a dropType'd value
        // takes the whole block with it, with the migration named.
        val result = upcaster().upcast(
            entityPayloadBytes(LegacyShaped(RETIRED_AT_V1, listOf("gone".toTString(testProxy.tContext)))),
            stampBytes(UPCAST_SERVICE_ID to 1),
        )
        assertIs<PayloadUpcaster.UpcastResult.Dropped>(result)
        assertTrue(
            result.reason.contains(RETIRED_ID) && result.reason.contains("v1→v2"),
            "got: ${result.reason}"
        )
    }
}

/**
 * The **outbound** half of the same seam (`core-schema-evolution.md` §8.3, E2f):
 * [PendingBlockRestamper] runs the identical [PayloadUpcaster] over blocks this device
 * already queued, so a device whose schema moved while writes were waiting pushes them
 * at the current version instead of the one they were sealed at. Shares
 * [HistoryUpcastTest]'s fixture deliberately — same synthetic service, same chain, the
 * other direction.
 */
class PendingBlockRestamperTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: org.tenshu.core.security.SyncCryptoManager

    /** With the upcast module registered ⇒ chains exist ⇒ the restamper is live. */
    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        testProxy.schemaRegistry.registerConfig(upcastTestModule)
        crypto = testProxy.tContext.syncCryptoManager()
        syncManager = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        // Derives the keys AND the ownerId, so `currentRealmContext()` is non-null —
        // the restamper's "realm unlocked" gate.
        syncManager.initialize("mock phrase")
    }

    private val queueRef: ArrayDatabaseReference by lazy {
        ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS,
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID,
        )
    }

    private fun restamper(manager: SyncManagerImpl = syncManager) = PendingBlockRestamper(
        executionContext = testProxy.executionContext,
        syncManager = manager,
        cryptoManager = crypto,
        queues = listOf(queueRef),
    )

    private fun serialize(data: Data): ByteArray = BinarySerializer(testProxy.schemaRegistry).serialize(data)

    /** A hand-built partial vector, exactly as an older build of this device would have stamped. */
    private fun stampBytes(vararg versions: Pair<String, Int>): ByteArray = serialize(
        VersionVector.create(
            context = testProxy.tContext,
            coreVersion = TENSHU_STORAGE_FORMAT_VERSION,
            services = versions.map { (serviceId, version) ->
                ServiceVersion.create(testProxy.tContext, serviceId, version)
            },
        )
    )

    /** What `SyncObserver` seals with in this runtime — the full registered-module vector. */
    private fun currentStamp(): ByteArray = BlockPayloadEnvelope.stampOf(
        testProxy.executionContext.schemaProvider,
        testProxy.tContext,
    )

    private fun enqueue(payloadPlaintext: ByteArray, blockId: String): SyncBlock {
        val block = EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(
                context = testProxy.tContext,
                physicalTime = 2000L,
                logicalCounter = 0L,
                deviceId = "device-local",
            ),
            targetEntityIdHash = "hash-$blockId",
            plaintext = payloadPlaintext,
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
        testProxy.executionContext.database.addToArray(queueRef, block)
        return block
    }

    private fun queued(): List<SyncBlock> =
        testProxy.executionContext.database.getArrayData(queueRef)?.toList()
            ?.map { it as SyncBlock } ?: emptyList()

    private fun openQueued(block: SyncBlock): BlockPayloadEnvelope.Opened = BlockPayloadEnvelope.open(
        EnvelopeAuth.openSyncBlock(crypto, block, syncManager.getDerivedKeys()!!.sharedIdentityKey)!!
    )!!

    private fun v1Note(title: String, legacy: String): Data = LegacyShaped(
        NOTE_AT_V1,
        listOf(title.toTString(testProxy.tContext), legacy.toTString(testProxy.tContext)),
    )

    private fun payloadBytes(value: Data): ByteArray =
        serialize(DataBlockPayload.create(testProxy.tContext, propertyId = null, value = value))

    @Test
    fun staleStampedQueuedBlockIsResealedAtTheCurrentStamp() = runTest {
        val original = enqueue(
            BlockPayloadEnvelope.seal(
                payloadBytes(v1Note(title = "alpha", legacy = "junk")),
                stampBytes(UPCAST_SERVICE_ID to 1),
            ),
            blockId = "queued-v1",
        )

        val restamper = restamper()
        restamper.restampOnce()

        assertEquals(1, restamper.restampedCount)
        val repaired = queued().single()
        assertEquals(
            original.blockId.get(),
            repaired.blockId.get(),
            "a restamp is a re-encoding of the same event — the relay dedupes on blockId",
        )
        assertEquals("hash-queued-v1", repaired.targetEntityIdHash?.get(), "routing hash preserved")
        assertEquals(
            original.hlc.physicalTime.get(),
            repaired.hlc.physicalTime.get(),
            "the HLC is this write's position in the realm's causal order, not a new event",
        )

        val opened = openQueued(repaired)
        assertTrue(
            opened.stampBytes.contentEquals(currentStamp()),
            "the queued block must now carry THIS runtime's stamp, not the one it was sealed at",
        )
        // ...and the payload under it really is the live shape, not old bytes relabelled.
        val payload = BinaryParser(testProxy.schemaRegistry, testProxy.schemaRegistry)
            .parse<DataBlockPayload>(opened.payloadBytes)
        val note = payload.value
        assertIs<NoteDto>(note, "the payload must be rebuilt as a registered LIVE dto")
        assertEquals("kind:alpha", note.getField<TString>(NoteFields.KIND).get(), "v1→v2 derive ran")
        assertEquals(5L, note.getField<TInteger>(NoteFields.SCORE).get(), "v2→v3 derive ran")
    }

    @Test
    fun currentStampedQueuedBlockIsLeftByteForByteAlone() = runTest {
        val sealed = BlockPayloadEnvelope.seal(
            payloadBytes(
                NoteDto(
                    listOf(
                        "beta".toTString(testProxy.tContext),
                        "kind:live".toTString(testProxy.tContext),
                        4L.toTInteger(testProxy.tContext),
                    )
                )
            ),
            currentStamp(),
        )
        val original = enqueue(sealed, blockId = "queued-current")

        val restamper = restamper()
        restamper.restampOnce()

        assertEquals(0, restamper.restampedCount, "nothing to repair ⇒ no rewrite, no re-encryption")
        assertTrue(
            queued().single().encryptedPayload.get().contentEquals(original.encryptedPayload.get()),
            "a block already at the current stamp must not even be re-encrypted",
        )
    }

    @Test
    fun belowFloorQueuedBlockIsDroppedRatherThanPushed() = runTest {
        enqueue(
            BlockPayloadEnvelope.seal(
                payloadBytes(v1Note(title = "gamma", legacy = "x")),
                stampBytes(UPCAST_SERVICE_ID to 0), // the derived floor is 1
            ),
            blockId = "queued-below-floor",
        )

        val restamper = restamper()
        restamper.restampOnce()

        assertEquals(1, restamper.droppedCount)
        assertTrue(
            queued().isEmpty(),
            "the chain no longer reaches this shape, so the block could only ever be pushed as " +
                "bytes no peer can decode — drop BY DECISION instead of emitting garbage",
        )
    }

    @Test
    fun undecryptableQueuedBlockIsLeftQueuedForTheDrainToJudge() = runTest {
        // The classic case is an orphaned-owner block from a realm this device left; the
        // drain quarantines those on its own terms. Restamping must never be the thing
        // that destroys a block it merely failed to READ. Sealed under a key this realm
        // does not hold, so every decrypt candidate fails.
        val foreign = SyncBlock.create(
            context = testProxy.tContext,
            blockId = "queued-foreign",
            ownerId = "someone-else",
            hlc = HybridLogicalClock.create(testProxy.tContext, 2000L, 0L, "device-local"),
            encryptedPayload = crypto.encrypt(byteArrayOf(7, 7, 7), ByteArray(32) { 3 }),
        )
        testProxy.executionContext.database.addToArray(queueRef, foreign)

        val restamper = restamper()
        restamper.restampOnce()

        assertEquals(0, restamper.droppedCount)
        assertEquals(0, restamper.restampedCount)
        assertEquals(1, queued().size, "an unreadable block stays queued")
    }

    /**
     * ⚠⚠ The outage this class caused, and the reason it now reads its queue member-wise.
     *
     * A queue member whose object row is gone makes `getArrayData(...).toList()` throw
     * `IllegalStateException: Entry not found` — it resolves every member eagerly. Thrown out of
     * `restampOnce`, which `SyncScheduler.tickOnce` calls **before** the slice bootstrap and both
     * drains, one dangling ref would cost the device push and pull entirely, on every tick,
     * forever (`done` is set at the end of the pass, so the throw guarantees the one-shot is never
     * consumed).
     *
     * ⚠ Revert `readQueue` to the one-shot `getArrayData(...).toList()` and this test fails with
     * that exception rather than an assertion — which is the point.
     */
    @Test
    fun aDanglingQueueMemberIsSkippedAndTheRestOfTheQueueIsStillRepaired() = runTest {
        val doomed = enqueue(
            BlockPayloadEnvelope.seal(
                payloadBytes(v1Note(title = "vanishing", legacy = "x")),
                stampBytes(UPCAST_SERVICE_ID to 1),
            ),
            blockId = "queued-dangling",
        )
        enqueue(
            BlockPayloadEnvelope.seal(
                payloadBytes(v1Note(title = "survivor", legacy = "y")),
                stampBytes(UPCAST_SERVICE_ID to 1),
            ),
            blockId = "queued-intact",
        )

        // Delete the row while leaving the queue's reference to it — exactly the shape a sweep
        // that deletes a still-referenced block leaves behind, and what the device showed.
        val doomedId = testProxy.executionContext.getDataId(doomed)!!
        testProxy.executionContext.database.deleteData(doomedId)

        val restamper = restamper()
        restamper.restampOnce() // must not throw — that it did is the whole outage

        assertEquals(
            1,
            restamper.restampedCount,
            "the readable block must still be repaired — degrade means skip that member, not " +
                "abandon the pass",
        )

        // The *forever* half. `done` is set at the end of the pass, so a throw would also leave the
        // one-shot unconsumed and every subsequent tick would re-throw. Consuming it is what
        // turns a permanent outage into one skipped repair.
        restamper.restampOnce()
        assertEquals(
            1,
            restamper.restampedCount,
            "the pass is a one-shot even when a member was skipped — otherwise every tick retries",
        )

        // ⚠⚠ A whole-queue read returns the survivors rather than throwing: `getArrayData` skips a
        // dangling member itself (`DanglingArrayMemberTest`), because every whole-queue reader —
        // the drain included — would otherwise throw on the same members, and hardening readers
        // one at a time cannot keep up. `readQueue` stays as the layer below — it is what makes
        // the restamper correct against a store without that skip, or a future reader that is
        // stricter.
        assertEquals(
            1,
            queued().size,
            "a whole-queue read returns the survivors instead of throwing",
        )
    }

    /**
     * The declared vector is fixed for the process, so a queue that is clean once stays
     * clean — everything enqueued afterwards is sealed at the current stamp by
     * construction. Pinning it keeps the pass off the hot tick path.
     */
    @Test
    fun theRepairIsOneShot() = runTest {
        enqueue(
            BlockPayloadEnvelope.seal(
                payloadBytes(v1Note("delta", "x")),
                stampBytes(UPCAST_SERVICE_ID to 1),
            ),
            blockId = "queued-first",
        )
        val restamper = restamper()
        restamper.restampOnce()
        assertEquals(1, restamper.restampedCount)

        // A second stale block could only appear from an emitter that no longer exists
        // in this process; the pass does not walk the queue again.
        enqueue(
            BlockPayloadEnvelope.seal(
                payloadBytes(v1Note("epsilon", "x")),
                stampBytes(UPCAST_SERVICE_ID to 1),
            ),
            blockId = "queued-second",
        )
        restamper.restampOnce()
        assertEquals(1, restamper.restampedCount, "one-shot per process")
    }

    /**
     * The pass needs the realm's key to read a payload at all. A locked / not-yet-
     * rehydrated realm must therefore DEFER rather than burn the one-shot — otherwise
     * the very first tick of a cold start would silently skip the repair forever.
     */
    @Test
    fun lockedRealmDefersInsteadOfConsumingTheOneShot() = runTest {
        val sealed = BlockPayloadEnvelope.seal(
            payloadBytes(v1Note("zeta", "x")),
            stampBytes(UPCAST_SERVICE_ID to 1),
        )
        enqueue(sealed, blockId = "queued-locked")
        // A manager that has not been initialized yet — `currentRealmContext()` is null,
        // exactly the cold-start window before the realm is unlocked. Same recovery
        // phrase, so once it initializes it derives the very keys the block is under.
        val sealedRealm = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        val restamper = restamper(sealedRealm)

        restamper.restampOnce()
        assertEquals(0, restamper.restampedCount, "nothing can be read while locked")

        sealedRealm.initialize("mock phrase")
        restamper.restampOnce()
        assertEquals(1, restamper.restampedCount, "the deferred pass runs on a later tick")
    }

    /**
     * Production today: no service has registered a chain yet, so there is nothing a
     * stale stamp could be upcast *from*. The pass must then be completely inert — the
     * outbound queue is byte-for-byte what it was before this slice.
     */
    @Test
    fun withNoRegisteredChainsThePassIsInert() = runTest {
        val chainless = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        chainless.schemaRegistry.registerConfig(syncServiceCommonModule)
        val block = EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = chainless.tContext,
            blockId = "chainless",
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(chainless.tContext, 2000L, 0L, "device-local"),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(byteArrayOf(1, 2, 3), stampBytes(UPCAST_SERVICE_ID to 1)),
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = chainless.tContext,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS,
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID,
        )
        chainless.executionContext.database.addToArray(ref, block)

        val restamper = PendingBlockRestamper(
            executionContext = chainless.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            queues = listOf(ref),
        )
        restamper.restampOnce()

        assertEquals(0, restamper.restampedCount)
        assertEquals(0, restamper.droppedCount)
        val stored = chainless.executionContext.database.getArrayData(ref)?.toList()
            ?.map { it as SyncBlock }?.single()
        assertTrue(
            stored!!.encryptedPayload.get().contentEquals(block.encryptedPayload.get()),
            "with no chains registered nothing may be touched",
        )
    }
}
