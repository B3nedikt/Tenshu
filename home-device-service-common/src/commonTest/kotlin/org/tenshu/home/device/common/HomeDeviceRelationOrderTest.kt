package org.tenshu.home.device.common

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.tenshu.core.number.TInteger
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.HomeDeviceService

/**
 * The **user-visible** half of R2b's before/after
 * (`core-relation-system.md` §10.4), on the sharpest of the eleven relations the
 * §10.3 audit found rendering raw array order.
 *
 * `HomeDeviceService.HOME_DEVICES` does not merely *display* in array order —
 * `SyncedDeviceAwareHomeDevicesRepository.devices()` maps the array's index
 * straight into `Device.sortIndex`, so array position **is** the model's sort
 * key. Until R2b, every relation re-saved its member by removing and re-adding
 * it, which means:
 *
 *  - **before:** renaming the hallway lamp (`UpdateHomeDevice` → one `HomeDevice`
 *    save) moved it to the bottom of the Home screen, and the hub re-publishing
 *    its inventory reshuffled the whole list in publish order;
 *  - **after:** every device keeps its slot; only a genuinely new device appends.
 *
 * The second test covers the other half, which the ordering audit did not look
 * at and which nearly went out as a silent regression: `DeviceState` is one row
 * per device, re-saved by the hub on *every* state change (a lamp turning on).
 * Its membership never changes, so probe-first membership writes nothing — and
 * the array listener that a re-save used to fire was the only thing that
 * re-evaluated Home's reactive `deviceStatesFlow()`. A state change has to still
 * reach a reader.
 */
class HomeDeviceRelationOrderTest {

    private lateinit var proxy: TestProxy

    @BeforeTest
    fun setup() {
        val registry = createCoreTestRegistry().apply { registerConfig(homeDeviceServiceCommonModule) }
        proxy = TestProxy(schemaRegistry = registry)
        proxy.executionContext.installRelationMaintainer()
    }

    @Test
    fun reSavingADevice_leavesTheHomeScreenOrderAlone() {
        saveDevice("light-1", "Hallway lamp")
        saveDevice("light-2", "Desk lamp")
        saveDevice("thermo-1", "Thermostat")

        // A rename — one `HomeDevice` save with the same deviceId.
        saveDevice("light-1", "Entrance lamp")

        assertEquals(
            listOf("light-1", "light-2", "thermo-1"),
            deviceIds(),
            "⚠ BEFORE R2b this asserted [light-2, thermo-1, light-1]: the re-saved device " +
                "was removed and re-appended, and the array index is Device.sortIndex",
        )
        assertEquals(
            listOf("Entrance lamp", "Desk lamp", "Thermostat"),
            deviceNames(),
            "the row itself is current — it is the position that stopped moving",
        )
    }

    @Test
    fun aNewDeviceStillAppends() {
        saveDevice("light-1", "Hallway lamp")
        saveDevice("light-2", "Desk lamp")

        assertEquals(listOf("light-1", "light-2"), deviceIds())
    }

    @Test
    fun aReSavedDeviceStateStillReachesAReactiveReader() {
        val statesRef = relationRef(HomeDeviceService.Relations.DEVICE_STATES, DeviceState.ID)
        var notifications = 0
        proxy.executionContext.database.setArrayListener(
            reference = statesRef,
            onAdded = { _, _ -> notifications++ },
            onRemoved = { _, _ -> notifications++ },
        )

        saveState("light-1", online = true, power = DeviceState.POWER_OFF)
        notifications = 0

        // The lamp turns on: same row, new value, membership unchanged.
        saveState("light-1", online = true, power = DeviceState.POWER_ON)

        assertEquals(
            1,
            notifications,
            "a state change must still re-evaluate Home's reactive read — probe-first " +
                "membership writes nothing here, so the notification is explicit " +
                "(Database.notifyArrayMemberUpdated) rather than a side effect of the write",
        )
        assertEquals(
            listOf(DeviceState.POWER_ON),
            statesIn(statesRef).map { it.getFieldValue<TInteger>(DeviceState.Fields.POWER).get() },
        )
    }

    // --- fixtures ---------------------------------------------------------

    private fun saveDevice(deviceId: String, name: String) =
        proxy.executionContext.saveData(
            HomeDevice.create(
                context = proxy.tContext,
                deviceId = deviceId,
                bridgeId = "bridge-1",
                kind = HomeDevice.KIND_LIGHT,
                name = name,
                room = "Hallway",
                joinedAtEpochMs = 1L,
            ),
        )

    private fun saveState(deviceId: String, online: Boolean, power: Long) =
        proxy.executionContext.saveData(
            DeviceState.create(
                context = proxy.tContext,
                deviceId = deviceId,
                online = online,
                power = power,
                freshAsOf = 1L,
            ),
        )

    private fun devices(): List<Entity<*>> =
        statesIn(relationRef(HomeDeviceService.Relations.HOME_DEVICES, HomeDevice.ID))

    private fun deviceIds(): List<String> =
        devices().map { it.getFieldValue<TString>(HomeDevice.Fields.DEVICE_ID).get() }

    private fun deviceNames(): List<String> =
        devices().map { it.getFieldValue<TString>(HomeDevice.Fields.NAME).get() }

    private fun statesIn(reference: ArrayDatabaseReference): List<Entity<*>> =
        proxy.executionContext.database.getArrayData(reference)
            ?.map { it as Entity<*> }
            ?.toList()
            ?: emptyList()

    private fun relationRef(relationId: String, elementSchemaId: String) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = proxy.tContext,
            type = TList.ID,
            relationId = relationId,
        ),
        isEntity = true,
        elementSchemaId = elementSchemaId,
    )
}
