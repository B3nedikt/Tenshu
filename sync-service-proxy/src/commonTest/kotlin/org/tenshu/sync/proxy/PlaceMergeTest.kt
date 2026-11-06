package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.number.TInteger
import org.tenshu.core.place.FingerprintProvenance
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.PlaceNetworkFingerprint
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.proxy.PlaceMergeMerger
import org.tenshu.place.proxy.PlaceMerger
import org.tenshu.place.proxy.PlaceNetworkFingerprintMerger
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.core.Data
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The **place merge plane** (`timeline-recognition-and-place-naming.md` §9.2/§9.3/§9.4, P4d) over the
 * real encrypt → [SyncBlock] → `processSyncBlock` path — the same harness as `MomentMergeTest` /
 * `TripMergeTest`, which is the only way to prove a merger actually fires rather than merely
 * compiles.
 *
 * The first test is the **bug this slice exists for**: under last-writer-wins, a peer that captured a
 * visit before it had pulled the user's rename wrote a legitimately blank label, and that newer whole
 * row erased the name on every device.
 */
class PlaceMergeTest {

    @Test
    fun aPeersBlankCaptureCannotEraseARenamedPlace() = runTest {
        val h = harness()

        // This device: the user renamed the place.
        val local = Place.create(
            context = h.tContext,
            placeId = "cap:490320000,209030000",
            latE7 = 490_320_000L,
            lngE7 = 209_030_000L,
            capturedAt = UnixInstantDto(1_000L),
            label = "Office",
            labelSource = LabelSource.USER,
            accuracyM = 90,
        )
        h.testProxy.database.saveData(local)

        // A peer that had not yet pulled that rename captures a visit to the same place. Its local
        // guard sees a blank row, so it legitimately writes a blank label — with a newer HLC.
        val incoming = Place.create(
            context = h.tContext,
            placeId = "cap:490320000,209030000",
            latE7 = 490_320_000L,
            lngE7 = 209_030_000L,
            capturedAt = UnixInstantDto(2_000L),
            label = "",
            labelSource = LabelSource.GEOCODED,
            accuracyM = 40,
        )
        h.deliver(incoming, "block-place-1", physicalTime = 2000L)

        val merged = h.reload(local) as Place
        assertEquals("Office", merged.getField<TString>(Place.Fields.LABEL).get())
        assertEquals(LabelSource.USER, merged.getField<TString>(Place.Fields.LABEL_SOURCE).get())
        // …and the peer's genuinely better facts still land.
        assertEquals(40L, merged.getField<TInteger>(Place.Fields.ACCURACY_M).get())
        // capturedAt keeps meaning "when we first saw this place".
        assertEquals(1_000L, merged.getField<org.tenshu.core.time.UnixInstant>(Place.Fields.CAPTURED_AT).toEpochMilliseconds())
    }

    @Test
    fun aGeocodedLabelIsStillImprovedByAPeer() = runTest {
        val h = harness()
        val local = Place.create(
            context = h.tContext,
            placeId = "cap:1,2",
            latE7 = 1, lngE7 = 2,
            capturedAt = UnixInstantDto(1_000L),
            label = "Street",
            labelSource = LabelSource.GEOCODED,
        )
        h.testProxy.database.saveData(local)

        h.deliver(
            Place.create(
                context = h.tContext,
                placeId = "cap:1,2",
                latE7 = 1, lngE7 = 2,
                capturedAt = UnixInstantDto(1_000L),
                label = "Office",
                labelSource = LabelSource.GEOCODED,
            ),
            "block-place-2", physicalTime = 2000L,
        )

        assertEquals("Office", (h.reload(local) as Place).getField<TString>(Place.Fields.LABEL).get())
    }

    @Test
    fun aUserNetworkBindingBeatsALearnedOneWhicheverSideItIsOn() = runTest {
        val h = harness()
        // This device learned the AP at the drifted cell…
        val local = PlaceNetworkFingerprint.create(
            context = h.tContext,
            networkTag = "tag-a",
            placeId = "cap:490320000,209040000",
            observedAt = UnixInstantDto(5_000L),
            provenance = FingerprintProvenance.LEARNED,
        )
        h.testProxy.database.saveData(local)

        // …the phone bound it to the place the user actually named. Even though this arrives with a
        // newer HLC either way, the rule is provenance, not arrival.
        h.deliver(
            PlaceNetworkFingerprint.create(
                context = h.tContext,
                networkTag = "tag-a",
                placeId = "cap:490320000,209030000",
                observedAt = UnixInstantDto(1_000L), // deliberately OLDER observation
                provenance = FingerprintProvenance.USER,
            ),
            "block-fp-1", physicalTime = 2000L,
        )

        val merged = h.reload(local) as PlaceNetworkFingerprint
        assertEquals("cap:490320000,209030000", merged.getField<TString>(PlaceNetworkFingerprint.Fields.PLACE_ID).get())
        assertEquals(FingerprintProvenance.USER, merged.getField<TString>(PlaceNetworkFingerprint.Fields.PROVENANCE).get())
    }

    @Test
    fun twoDevicesDisagreeingOnAMergeTargetResolveToTheSmallerId() = runTest {
        val h = harness()
        val local = PlaceMerge.create(
            context = h.tContext,
            placeId = "cap:9",
            mergedInto = "cap:5",
            mergedAt = UnixInstantDto(1_000L),
        )
        h.testProxy.database.saveData(local)

        h.deliver(
            PlaceMerge.create(
                context = h.tContext,
                placeId = "cap:9",
                mergedInto = "cap:3",
                mergedAt = UnixInstantDto(2_000L),
            ),
            "block-merge-1", physicalTime = 2000L,
        )

        val merged = h.reload(local) as PlaceMerge
        // min, not "the newer one" — so the peer resolving the same pair lands here too.
        assertEquals("cap:3", merged.getField<TString>(PlaceMerge.Fields.MERGED_INTO).get())
        assertEquals(1_000L, merged.getField<org.tenshu.core.time.UnixInstant>(PlaceMerge.Fields.MERGED_AT).toEpochMilliseconds())
    }

    // ── harness ────────────────────────────────────────────────────────────────

    private class Harness(
        val testProxy: TestProxy,
        val processor: SyncProcessor,
        val crypto: org.tenshu.core.security.SyncCryptoManager,
        val sharedKey: ByteArray,
    ) {
        val tContext get() = testProxy.tContext

        fun deliver(data: Data, blockId: String, physicalTime: Long) {
            val payload = DataBlockPayload.create(context = tContext, propertyId = null, value = data)
            processor.processSyncBlock(
                org.tenshu.sync.common.EnvelopeAuth.sealSyncBlock(
                    crypto = crypto,
                    context = tContext,
                    blockId = blockId,
                    ownerId = "user-1",
                    hlc = HybridLogicalClock.create(tContext, physicalTime, 0, "device-remote"),
                    targetEntityIdHash = null,
                    plaintext = BlockPayloadEnvelope.seal(
                        BinarySerializer().serialize(payload),
                        testProxy.versionStampBytes(),
                    ),
                    identityKey = sharedKey,
                )
            )
        }

        fun reload(local: Data): Data =
            testProxy.database.getData(testProxy.executionContext.getDataId(local)!!)!!
    }

    private suspend fun harness(): Harness {
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        testProxy.schemaRegistry.registerConfig(placeServiceCommonModule)

        val crypto = testProxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        syncManager.initialize("mock phrase")

        val registry = SemanticMergerRegistry().apply {
            register(PlaceMerger())
            register(PlaceNetworkFingerprintMerger())
            register(PlaceMergeMerger())
        }
        return Harness(
            testProxy = testProxy,
            processor = SyncProcessor(
                wireIdResolver = WireIdResolver(syncManager),
                executionContext = testProxy.executionContext,
                syncManager = syncManager,
                cryptoManager = crypto,
                mergerRegistry = registry,
            ),
            crypto = crypto,
            sharedKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
    }
}
