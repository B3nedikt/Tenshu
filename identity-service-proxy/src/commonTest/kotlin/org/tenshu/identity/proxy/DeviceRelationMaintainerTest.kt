package org.tenshu.identity.proxy

import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins down the `DeviceRelationMaintainer` invariant: after any `Device`
 * save, `IdentityService.Relations.DEVICES` contains **exactly one** entry
 * per logical `deviceId`, regardless of prior contents.
 *
 * The "post-pairing the joiner sees its phone Device twice" UI bug from
 * 2026-05-10 surfaced through pre-polluted relation state — a clean-slate
 * pairing test couldn't reproduce it because the dedup check would skip
 * the second add. The maintainer now self-heals: it removes any existing
 * entries with the saved deviceId before appending, so even an array
 * left dirty by a previous bug converges to one entry on the next save.
 */
class DeviceRelationMaintainerTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = createIdentityProxy()
        testProxy.executionContext.initIdentityService()
    }

    @Test
    fun saveDevice_appendsOnce_onCleanArray() {
        val device = Device.create(
            context = testProxy.tContext,
            deviceId = "dev-1",
            name = "Phone",
            publicKey = byteArrayOf(0x01),
            isAuthorized = true,
            admittedAtEpoch = 0L
        )
        testProxy.executionContext.saveData(device)

        assertDeviceIds(listOf("dev-1"))
    }

    @Test
    fun saveDevice_secondSameId_doesNotDuplicate() {
        val device = Device.create(
            context = testProxy.tContext,
            deviceId = "dev-1",
            name = "Phone",
            publicKey = byteArrayOf(0x01),
            isAuthorized = false,
            admittedAtEpoch = 0L
        )
        val updated = Device.create(
            context = testProxy.tContext,
            deviceId = "dev-1",
            name = "Phone Renamed",
            publicKey = byteArrayOf(0x01),
            isAuthorized = true,
            admittedAtEpoch = 1L
        )
        testProxy.executionContext.saveData(device)
        testProxy.executionContext.saveData(updated)

        assertDeviceIds(listOf("dev-1"))
    }

    @Test
    fun saveDevice_selfHealsPrePollutedDuplicates() {
        // Simulate the "phone × 2" production state: bypass the observer
        // and stuff two entries with the same deviceId directly into the
        // relation array, the way an old buggy build would have left it.
        val pollutedDevice = Device.create(
            context = testProxy.tContext,
            deviceId = "dev-1",
            name = "Phone",
            publicKey = byteArrayOf(0x01),
            isAuthorized = false,
            admittedAtEpoch = 0L
        )
        repeat(2) {
            testProxy.executionContext.database.addToArray(devicesRef(), pollutedDevice)
        }
        assertDeviceIds(listOf("dev-1", "dev-1"), "Array should be polluted by setup")

        // A subsequent observer-driven save must collapse the pollution.
        val freshDevice = Device.create(
            context = testProxy.tContext,
            deviceId = "dev-1",
            name = "Phone",
            publicKey = byteArrayOf(0x01),
            isAuthorized = true,
            admittedAtEpoch = 1L
        )
        testProxy.executionContext.saveData(freshDevice)

        assertDeviceIds(listOf("dev-1"))
    }

    private fun devicesRef(): ArrayDatabaseReference = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = testProxy.tContext,
            type = TList.ID,
            relationId = IdentityService.Relations.DEVICES
        ),
        isEntity = true,
        elementSchemaId = Device.ID
    )

    private fun assertDeviceIds(expected: List<String>, message: String? = null) {
        val actual = testProxy.executionContext.getArrayData(devicesRef())
            ?.filterIsInstance<Entity<*>>()
            ?.map { it.getFieldValue<TString>(Device.Fields.DEVICE_ID).get() }
            ?.toList()
            ?: emptyList()
        assertEquals(expected, actual, message ?: "DEVICES relation should match")
    }
}
