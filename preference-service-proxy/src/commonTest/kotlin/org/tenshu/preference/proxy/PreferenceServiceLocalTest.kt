package org.tenshu.preference.proxy

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.search
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.Composite
import org.tenshu.core.equalTo
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.Setting
import org.tenshu.preference.client.read
import org.tenshu.preference.client.readOnceForDevice
import org.tenshu.preference.client.readOnceForDeviceOrNull
import org.tenshu.preference.client.set
import org.tenshu.preference.client.setForDevice
import org.tenshu.preference.test.createPreferenceProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Local (single-proxy) round-trip via the SDK chain — proves Phase 2 wiring
 * end-to-end without involving sync: `set` / `setForDevice` write a
 * [RealmSetting] / [DeviceSetting], the proxy-side
 * generic `RelationMaintainer` adds it to the right relation, and the
 * chain DSL surfaces it on the same client.
 *
 * The cross-device SystemTest variant in `:system-test-utils` is the
 * canonical Phase 2 DoD; this test is the cheap local smoke that catches
 * schema/DSL issues without the pairing-and-sync overhead.
 */
class PreferenceServiceLocalTest {

    private val testWifiOnly = Preference(
        id = "test.wifiOnly",
        scope = PreferenceScope.REALM,
        default = false,
        adapter = BooleanAdapter,
    )

    private val testDevicePinned = Preference(
        id = "test.devicePinned",
        scope = PreferenceScope.DEVICE,
        default = false,
        adapter = BooleanAdapter,
    )

    @Test
    fun realmSetting_roundTripsThroughSdkChain() = runTest {
        val proxy = createPreferenceProxy(backgroundScope)
        val service = environmentFor(proxy).getService<PreferenceService>(PreferenceService.ID)

        val writeResult = service.set(testWifiOnly, value = true)
        assertTrue(writeResult is TResult.Success<*>, "set failed: $writeResult")

        val searchResult = service.realmSettings
            .search<RealmSetting> { this.preferenceId equalTo testWifiOnly.id }
            .resolve()

        if (searchResult is TResult.Failure<*>) error("search failed: ${searchResult.error}")
        val rawEntity = (searchResult as TResult.Success<*>).value
        assertNotNull(rawEntity, "RealmSetting should be reachable via the SDK chain after write")
        val composite = rawEntity as Composite

        // Adapter handles the bytes → consumer-type round-trip.
        val decoded = BooleanAdapter.fromData(composite.getField(Setting.Field.VALUE))
        assertEquals(true, decoded, "stored boolean should round-trip true")

        val storedId = composite.getField<TString>(Setting.Field.PREFERENCE_ID)
        assertEquals(testWifiOnly.id, storedId.get())
    }

    @Test
    fun read_reflectsRealmWriteThroughAdapter() = runTest {
        val proxy = createPreferenceProxy(backgroundScope)
        val service = environmentFor(proxy).getService<PreferenceService>(PreferenceService.ID)

        val writeResult = service.set(testWifiOnly, value = true)
        assertTrue(writeResult is TResult.Success<*>, "set failed: $writeResult")

        // The reactive read replays the now-present realm row and resolves it
        // through the adapter to the typed value — no manual chain/cast.
        assertEquals(true, service.read(testWifiOnly).first())
    }

    @Test
    fun readOnceForDevice_answersImmediately_forAPresentAndAnAbsentRow() = runTest {
        val proxy = createPreferenceProxy(backgroundScope)
        val service = environmentFor(proxy).getService<PreferenceService>(PreferenceService.ID)

        // Absent — including on a realm whose device relation holds no rows at all, where
        // a `readForDevice(...).first()` would simply never emit.
        assertEquals(false, service.readOnceForDevice(testDevicePinned, "device-A"))

        service.setForDevice(testDevicePinned, value = true, deviceId = "device-A")

        assertEquals(true, service.readOnceForDevice(testDevicePinned, "device-A"))
        // Rows are per (device, preference) — another device still reads the default.
        assertEquals(false, service.readOnceForDevice(testDevicePinned, "device-B"))
    }

    @Test
    fun readOnceForDeviceOrNull_answersLikeReadOnceForDevice_whenTheRelationCanBeRead() = runTest {
        // ⚠ The tri-state read is only allowed to differ on a FAILED read. An absent row is an
        // answer (the default), not "unknown" — else a device that never enabled backup would retry
        // its jobs forever instead of reading "off".
        val proxy = createPreferenceProxy(backgroundScope)
        val service = environmentFor(proxy).getService<PreferenceService>(PreferenceService.ID)

        assertEquals(false, service.readOnceForDeviceOrNull(testDevicePinned, "device-A"))
        service.setForDevice(testDevicePinned, value = true, deviceId = "device-A")
        assertEquals(true, service.readOnceForDeviceOrNull(testDevicePinned, "device-A"))
        service.setForDevice(testDevicePinned, value = false, deviceId = "device-A")
        assertEquals(false, service.readOnceForDeviceOrNull(testDevicePinned, "device-A"))
    }

    @Test
    fun deviceSetting_roundTripsThroughSdkChain_withCompositeKey() = runTest {
        val proxy = createPreferenceProxy(backgroundScope)
        val service = environmentFor(proxy).getService<PreferenceService>(PreferenceService.ID)

        val device = "device-A"
        val writeResult = service.setForDevice(
            pref = testDevicePinned,
            value = true,
            deviceId = device,
        )
        assertTrue(writeResult is TResult.Success<*>, "setForDevice failed: $writeResult")

        // Same SDK chain shape as realm, just rooted on `deviceSettings` and
        // matching on the derived `compositeKey` field that
        // DeviceSettingDataIdProvider keys on.
        val searchResult = service.deviceSettings
            .search<DeviceSetting> {
                this.compositeKey equalTo DeviceSetting.compositeKey(device, testDevicePinned.id)
            }
            .resolve()

        if (searchResult is TResult.Failure<*>) error("search failed: ${searchResult.error}")
        val rawEntity = (searchResult as TResult.Success<*>).value
        assertNotNull(rawEntity, "DeviceSetting round-trip via SDK chain should land")
        val composite = rawEntity as Composite

        val decoded = BooleanAdapter.fromData(composite.getField(Setting.Field.VALUE))
        assertEquals(true, decoded)

        val storedDeviceId = composite.getField<TString>(DeviceSetting.Field.DEVICE_ID)
        assertEquals(device, storedDeviceId.get())
    }

    private fun environmentFor(proxy: TestProxy): TenshuEnvironment {
        val executor = LocalTaskExecutor(proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = proxy.tContext,
        )
    }
}
