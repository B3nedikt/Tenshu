package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.NotFoundError
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.DeviceHardware
import org.tenshu.identity.DeviceLastSeen
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.identity.IdentityService
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.RevokeDevice
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.RevokeDeviceImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RevokeDeviceImplementationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var emitter: RecordingTombstoneEmitter
    private lateinit var impl: RevokeDeviceImplementation

    /**
     * ⚠ A `CoroutineScope` extension, not an `@BeforeTest`, so the [TestProxy] rides the caller's
     * `TestScope` and `runTest` cancels the loops `initSyncService` starts. `@AfterTest` cannot do
     * it: commonTest requires `Unit` there and has no `runBlocking` (`docs/tech-debt.md` fix-5).
     */
    private fun CoroutineScope.setup() {
        testProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry(),
            coroutineScope = this,
        ).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        emitter = RecordingTombstoneEmitter()
        impl = RevokeDeviceImplementation(emitter)
    }

    /**
     * `runTest`, with [setup] already run. Every test in this class needs the fixture, so the
     * call belongs here once rather than as the first line of each body.
     *
     * ⚠⚠ It cannot be an `@BeforeTest`, and that is the whole point: [setup] takes a
     * `CoroutineScope` receiver so the [TestProxy] rides the **`TestScope`**, and `@BeforeTest`
     * runs outside `runTest` where no such scope exists. Nor can the teardown move to
     * `@AfterTest` — this is `commonTest`, where `@AfterTest` returns `Unit` and cannot suspend,
     * and `runBlocking` does not exist. Letting `runTest` own the proxy is what cancels the
     * `SyncScheduler` and 1-second `ScheduleDrainLoop` that `initSyncService` starts
     * (`docs/tech-debt.md` fix-item 5). Same shape as `:maps-shared-logic`'s `vmTest`.
     */
    private fun syncTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        setup()
        body()
    }

    private fun devicesRelationRef(): ArrayDatabaseReference = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = testProxy.tContext,
            type = TList.ID,
            relationId = IdentityService.Relations.DEVICES
        ),
        isEntity = true,
        elementSchemaId = Device.ID
    )

    private fun seedDevice(deviceId: String, name: String = "Tablet"): DataId.StringDataId {
        val device = Device.create(
            context = testProxy.tContext,
            deviceId = deviceId,
            name = name,
            publicKey = byteArrayOf(0x01),
            isAuthorized = true
        )
        testProxy.executionContext.saveData(device)
        testProxy.executionContext.database.addToArray(devicesRelationRef(), device)
        return DataId.StringDataId.create(testProxy.tContext, Device.ID, deviceId)
    }

    @Test
    fun success_removesEntity_andEmitsTombstone() = syncTest {
        val deviceId = "victim-device"
        val dataId = seedDevice(deviceId)

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(RevokeDevice.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
        )


        // The entity is gone from local DB.
        assertNull(
            testProxy.database.getData(dataId),
            "Local Device entity should be deleted"
        )

        // It also disappears from the DEVICES relation array — the same path UI observers
        // (SyncDashboardViewModel) read from.
        val remaining = testProxy.executionContext.getArrayData(devicesRelationRef())
            ?.toList()
            .orEmpty()
            .filterIsInstance<Entity<*>>()
            .map { it.getFieldValue<org.tenshu.core.TString>(Device.Fields.DEVICE_ID).get() }
        assertFalse(
            remaining.contains(deviceId),
            "Revoked device should be removed from DEVICES relation; remaining=$remaining"
        )

        // A tombstone was emitted with the right id-hash + entity type AND the
        // revokedDeviceId — the relay extracts that field on /sync/push-tombstone to
        // revoke the device's KnownDevicesStore entry (T-K-02 enforcement per
        // threat-model §8.5).
        val emitted = assertNotNull(emitter.lastEmit, "Tombstone should have been emitted")
        assertEquals(dataId.getDataIdHash().toString(), emitted.first)
        assertEquals(Device.ID, emitted.second)
        assertEquals(deviceId, emitted.third)
    }

    /**
     * The device's self-published descriptors ([DeviceHardware], [DeviceSchemaVersion]) have no
     * other writer, so revocation is the only thing that can retire them — otherwise every replica
     * keeps a revoked device's rows for ever. `ForgetThisDevice` reuses this implementation.
     */
    @Test
    fun revocation_retiresTheDevicesSelfPublishedRows() = syncTest {
        val deviceId = "victim-device"
        seedDevice(deviceId)
        val ctx = testProxy.tContext
        testProxy.executionContext.saveData(DeviceHardware.create(ctx, deviceId, "Google Pixel 8"))
        testProxy.executionContext.saveData(
            DeviceSchemaVersion.create(ctx, deviceId, testProxy.schemaRegistry.currentVersionVector(ctx))
        )
        val hardwareId = DataId.StringDataId.create(ctx, DeviceHardware.ID, deviceId)
        val versionId = DataId.StringDataId.create(ctx, DeviceSchemaVersion.ID, deviceId)

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(RevokeDevice.Params.DEVICE_ID to deviceId.toTString(ctx))
        )

        assertNull(testProxy.database.getData(hardwareId), "the hardware row must go with the device")
        assertNull(testProxy.database.getData(versionId), "so must the schema-version statement")
        val byType = emitter.all.associateBy { it.second }
        assertEquals(hardwareId.getDataIdHash().toString(), byType[DeviceHardware.ID]?.first)
        assertEquals(versionId.getDataIdHash().toString(), byType[DeviceSchemaVersion.ID]?.first)
        assertNull(byType[DeviceHardware.ID]?.third, "a descriptor tombstone is not a revocation")
        assertNull(byType[DeviceSchemaVersion.ID]?.third)
        assertEquals(Device.ID, emitter.lastEmit?.second, "the Device tombstone stays the last block")
    }

    @Test
    fun unknownDevice_withNoMembershipEvidence_throwsNotFound_andDoesNotEmit() = syncTest {
        // An id nothing in the realm has ever vouched for. A `Device` tombstone raises
        // `currentRealmEpoch` for EVERY device, so this must stay a hard failure — it is the one
        // case the original precondition was right about.
        val ex = assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = { null },
                caller = null,
                params = mapOf(
                    RevokeDevice.Params.DEVICE_ID to "ghost".toTString(testProxy.tContext)
                )
            )
        }
        // ⚠ Typed, not `GenericError`: only the error's *type id* reaches the recorded
        // `ErrorEvent` (freetext survives for `InternalError` alone), so a `revokeDevice` failure
        // used to log as the contentless `tenshu/result/error/generic`.
        assertEquals(
            NotFoundError.ID,
            ex.error.schema.id.get(),
            "a failed revoke must name its reason in the type the ErrorEvent records"
        )
        assertNull(emitter.lastEmit, "No tombstone should be emitted for an unknown id")
    }

    @Test
    fun missingRow_butDeviceWasARealmMember_stillRevokes() = syncTest {
        // The regression this fixes: the `Device` row is the DESCRIPTIVE half of membership, and
        // the tombstone — the actual revocation, and the thing that strips the relay's
        // KnownDevicesStore entry — is keyed by the id hash and needs no row. A degraded roster
        // must not make a lost device un-evictable.
        val deviceId = "row-less-but-real"
        // Evidence a real member left behind: this replica ingested a block authored by it.
        testProxy.executionContext.saveData(
            DeviceLastSeen.create(
                context = testProxy.tContext,
                deviceId = deviceId,
                lastSeenMs = 1_787_000_000_000L,
            )
        )
        // Precondition: there is genuinely no Device row, so this exercises the new branch and
        // not the ordinary path.
        assertNull(
            testProxy.database.getData(
                DataId.StringDataId.create(testProxy.tContext, Device.ID, deviceId)
            ),
            "precondition: no local Device row"
        )

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(RevokeDevice.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
        )

        val emitted = assertNotNull(
            emitter.lastEmit,
            "a device with membership evidence must still be revocable without its row"
        )
        assertEquals(
            DataId.StringDataId.create(testProxy.tContext, Device.ID, deviceId)
                .getDataIdHash().toString(),
            emitted.first
        )
        assertEquals(Device.ID, emitted.second)
        assertEquals(
            deviceId,
            emitted.third,
            "revokedDeviceId must still travel — it is what the relay reads to drop the registration"
        )
    }

    @Test
    fun nullEmitter_stillDeletesLocally() = syncTest {
        // Pre-`initSyncService` wiring (or sync-disabled context) — the emitter is null and
        // tombstone emission becomes a local-only no-op. The local DB delete still happens
        // so the UI immediately reflects the revoke; the tombstone will not propagate.
        val implWithoutEmitter = RevokeDeviceImplementation(tombstoneEmitter = null)
        val deviceId = "no-emitter"
        val dataId = seedDevice(deviceId)

        implWithoutEmitter.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(RevokeDevice.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
        )

        assertNull(testProxy.database.getData(dataId))
    }
}

private class RecordingTombstoneEmitter : TombstoneEmitter {
        /** ⚠ A fake with no realm: it names locally and therefore does NOT cover the wire (EI-1). */
        override fun emit(
            targetEntityId: org.tenshu.core.entity.DataId,
            targetEntityType: String,
            revokedDeviceId: String?,
        ): String? = emit(
            targetEntityIdHash = targetEntityId.getDataIdHash().toString(),
            targetEntityType = targetEntityType,
            revokedDeviceId = revokedDeviceId,
        )

    /** Every emit, in order. */
    val all = mutableListOf<Triple<String, String, String?>>()

    /** (targetEntityIdHash, targetEntityType, revokedDeviceId). */
    var lastEmit: Triple<String, String, String?>? = null

    override fun emit(
        targetEntityIdHash: String,
        targetEntityType: String,
        revokedDeviceId: String?
    ): String {
        lastEmit = Triple(targetEntityIdHash, targetEntityType, revokedDeviceId)
        all += lastEmit!!
        return "block-${++emitCount}"
    }

    private var emitCount = 0
}
