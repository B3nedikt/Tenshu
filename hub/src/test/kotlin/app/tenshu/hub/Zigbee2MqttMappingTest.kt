package app.tenshu.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.HomeDevice

/**
 * HD-2's translation layer replayed against **real zigbee2mqtt payload
 * shapes** ([Zigbee2MqttFixtures]): inventory mapping, capability derivation,
 * state mapping, `extras` capture, unit round-trips, and the sanitisation of
 * air-sourced text (threat model T-HD-04).
 *
 * Everything here is pure — no broker, no coroutines, no hardware — which is
 * the point of keeping [Zigbee2Mqtt] free of I/O.
 */
class Zigbee2MqttMappingTest {

    // ---- inventory -------------------------------------------------------------------

    @Test
    fun bridgeDevicesPayloadMapsToInventoryKeyedByIeeeAddress() {
        val entries = assertNotNull(Zigbee2Mqtt.parseInventory(Zigbee2MqttFixtures.BRIDGE_DEVICES))

        // Three household devices; the coordinator is NOT one of them (it
        // rides as the bridge's own row).
        assertEquals(3, entries.size)
        assertTrue(entries.none { it.ieeeAddress == "0x00124b00120144ae" })

        val lamp = entries.first { it.ieeeAddress == Zigbee2MqttFixtures.LAMP_IEEE }
        assertEquals(Zigbee2MqttFixtures.LAMP_ID, lamp.device.deviceId)
        assertEquals("zigbee2mqtt:${Zigbee2MqttFixtures.LAMP_IEEE}", lamp.device.deviceId)
        assertEquals(HomeDevice.KIND_LIGHT, lamp.device.kind)
        assertEquals("IKEA", lamp.device.manufacturer)
        assertEquals("LED1545G12", lamp.device.model)
        assertEquals("Living room lamp", lamp.device.defaultName)
        assertEquals("Living room lamp", lamp.friendlyName)
        assertEquals(
            listOf(HomeDevice.CAP_POWER, HomeDevice.CAP_LEVEL, HomeDevice.CAP_COLOR_TEMP),
            lamp.device.capabilityIds,
        )
        // Identity extras carry what the typed fields have no slot for.
        assertTrue(lamp.device.extras.contains(Zigbee2MqttFixtures.LAMP_IEEE))
        assertTrue(lamp.device.extras.contains("Mains"))
    }

    @Test
    fun sensorExposesMapToTemperatureHumidityAndBatteryCapabilities() {
        val entries = assertNotNull(Zigbee2Mqtt.parseInventory(Zigbee2MqttFixtures.BRIDGE_DEVICES))
        val sensor = entries.first { it.ieeeAddress == Zigbee2MqttFixtures.SENSOR_IEEE }

        assertEquals(HomeDevice.KIND_SENSOR, sensor.device.kind)
        assertEquals("Aqara", sensor.device.manufacturer)
        assertEquals(
            setOf(
                HomeDevice.CAP_TEMPERATURE,
                HomeDevice.CAP_HUMIDITY,
                HomeDevice.CAP_BATTERY,
            ),
            sensor.device.capabilityIds.toSet(),
        )
        // `pressure` and `linkquality` have no capability of ours — and that
        // is fine: they are not silently *dropped*, they simply have no
        // capability id (their readings ride the state row's extras).
        assertFalse(sensor.device.capabilityIds.any { it == "pressure" })
    }

    @Test
    fun aDeviceZigbee2MqttHasNoDefinitionForStillBecomesADevice() {
        val entries = assertNotNull(Zigbee2Mqtt.parseInventory(Zigbee2MqttFixtures.BRIDGE_DEVICES))
        val mystery = entries.first { it.ieeeAddress == Zigbee2MqttFixtures.MYSTERY_IEEE }

        assertEquals(HomeDevice.KIND_OTHER, mystery.device.kind)
        assertEquals(emptyList(), mystery.device.capabilityIds)
        assertEquals("", mystery.device.manufacturer)
        // Its unsupported-ness is recorded rather than hidden.
        assertTrue(mystery.device.extras.contains("supported"))
    }

    @Test
    fun renamingInZigbee2MqttMovesTheTopicButNotTheIdentity() {
        val before = assertNotNull(Zigbee2Mqtt.parseInventory(Zigbee2MqttFixtures.BRIDGE_DEVICES))
            .first { it.ieeeAddress == Zigbee2MqttFixtures.LAMP_IEEE }
        val after =
            assertNotNull(Zigbee2Mqtt.parseInventory(Zigbee2MqttFixtures.BRIDGE_DEVICES_AFTER_RENAME))
                .first { it.ieeeAddress == Zigbee2MqttFixtures.LAMP_IEEE }

        assertEquals(before.device.deviceId, after.device.deviceId, "identity is the IEEE address")
        assertEquals("Living room lamp", before.friendlyName)
        assertEquals("Reading lamp", after.friendlyName, "addressing follows the friendly name")
    }

    @Test
    fun theBridgeAnnouncesItselfAsThePermitJoinTarget() {
        val self = Zigbee2Mqtt.bridgeSelfDevice(
            Zigbee2Mqtt.coordinatorModel(Zigbee2MqttFixtures.BRIDGE_DEVICES),
        )
        assertEquals(HomeDevice.BRIDGE_ZIGBEE2MQTT, self.deviceId)
        assertEquals(HomeDevice.KIND_BRIDGE, self.kind)
        assertEquals(listOf(HomeDevice.CAP_PERMIT_JOIN), self.capabilityIds)
    }

    @Test
    fun malformedInventoryLeavesTheCallerFreeToKeepThePreviousOne() {
        assertNull(Zigbee2Mqtt.parseInventory("not json at all"))
        assertNull(Zigbee2Mqtt.parseInventory("""{"not":"an array"}"""))
        // An array of junk parses to *nothing*, not to garbage rows.
        assertEquals(emptyList(), Zigbee2Mqtt.parseInventory("""[1, 2, {"no":"ieee"}]"""))
    }

    // ---- state -----------------------------------------------------------------------

    @Test
    fun lightStatePayloadMapsToTypedFieldsWithTheRestInExtras() {
        val state = assertNotNull(
            Zigbee2Mqtt.mergeState(
                Zigbee2MqttFixtures.LAMP_ID,
                Zigbee2MqttFixtures.LAMP_STATE,
                previous = null,
            ),
        )
        assertEquals(true, state.powerOn)
        assertEquals(50, state.levelPercent, "brightness 128 of 254")
        assertEquals(370, state.colorTempMireds)
        assertTrue(state.extras.contains("linkquality"), "unmapped attributes are captured")
        assertTrue(state.extras.contains("102"))
        // The nested `update` object is not a scalar — bounded out, not crashed on.
        assertFalse(state.extras.contains("installed_version"))
    }

    @Test
    fun sensorStatePayloadMapsDecimalsToTheSchemasUnits() {
        val state = assertNotNull(
            Zigbee2Mqtt.mergeState(
                Zigbee2MqttFixtures.SENSOR_ID,
                Zigbee2MqttFixtures.SENSOR_STATE,
                previous = null,
            ),
        )
        assertEquals(215, state.temperatureDeciC, "21.53 °C → tenths, rounded")
        assertEquals(42, state.humidityPercent)
        assertEquals(87, state.batteryPercent)
        assertNull(state.powerOn, "a sensor has no power dimension")
        assertTrue(state.extras.contains("pressure"))
        assertTrue(state.extras.contains("voltage"))
    }

    @Test
    fun aDeviceWithOnlyUnmodelledAttributesStillProducesAState() {
        val state = assertNotNull(
            Zigbee2Mqtt.mergeState(
                Zigbee2MqttFixtures.MYSTERY_ID,
                Zigbee2MqttFixtures.MYSTERY_STATE,
                previous = null,
            ),
        )
        assertEquals(Zigbee2MqttFixtures.MYSTERY_ID, state.deviceId)
        assertTrue(state.extras.contains("unknown_1"))
        assertTrue(state.extras.contains("last_seen"))
    }

    @Test
    fun aPartialPublishMergesRatherThanErasingDimensions() {
        val full = assertNotNull(
            Zigbee2Mqtt.mergeState(
                Zigbee2MqttFixtures.LAMP_ID,
                Zigbee2MqttFixtures.LAMP_STATE,
                previous = null,
            ),
        )
        val partial = assertNotNull(
            Zigbee2Mqtt.mergeState(Zigbee2MqttFixtures.LAMP_ID, """{"state":"OFF"}""", full),
        )
        assertEquals(false, partial.powerOn)
        assertEquals(50, partial.levelPercent, "brightness survives a state-only publish")
        assertEquals(370, partial.colorTempMireds)
    }

    @Test
    fun malformedStateIsSkippedRatherThanCrashing() {
        assertNull(Zigbee2Mqtt.mergeState(Zigbee2MqttFixtures.LAMP_ID, "<<not json>>", null))
        assertNull(Zigbee2Mqtt.mergeState(Zigbee2MqttFixtures.LAMP_ID, "[1,2,3]", null))
        // A well-formed object with a hostile type for a field we do model:
        // the field is ignored, the rest of the payload still lands.
        val state = assertNotNull(
            Zigbee2Mqtt.mergeState(
                Zigbee2MqttFixtures.LAMP_ID,
                """{"brightness":"very bright","state":"ON"}""",
                null,
            ),
        )
        assertEquals(true, state.powerOn)
        assertNull(state.levelPercent)
    }

    @Test
    fun availabilityAndBridgeStateAcceptBothPublishedForms() {
        assertEquals(true, Zigbee2Mqtt.parseAvailability("""{"state":"online"}"""))
        assertEquals(false, Zigbee2Mqtt.parseAvailability("""{"state":"offline"}"""))
        assertEquals(true, Zigbee2Mqtt.parseAvailability("online"))
        assertEquals(false, Zigbee2Mqtt.parseAvailability("offline"))
        assertNull(Zigbee2Mqtt.parseAvailability("perhaps"))
        assertEquals(false, Zigbee2Mqtt.parseBridgeState(Zigbee2MqttFixtures.BRIDGE_OFFLINE))
    }

    @Test
    fun bridgeEventsDescribeThemselvesForTheLog() {
        val described = assertNotNull(Zigbee2Mqtt.describeEvent(Zigbee2MqttFixtures.DEVICE_JOINED_EVENT))
        assertTrue(described.startsWith("device_joined"))
        assertNull(Zigbee2Mqtt.describeEvent("nonsense"))
    }

    // ---- units -----------------------------------------------------------------------

    @Test
    fun brightnessAndPercentRoundTripStablyForEveryPercentValue() {
        for (percent in 0..100) {
            val brightness = Zigbee2Mqtt.percentToBrightness(percent)
            assertTrue(brightness in 0..Zigbee2Mqtt.MAX_BRIGHTNESS, "brightness in range for $percent")
            assertEquals(
                percent,
                Zigbee2Mqtt.brightnessToPercent(brightness),
                "percent → brightness → percent must be the identity ($percent)",
            )
        }
        // The scale's endpoints and the classic off-by-one (254, not 255).
        assertEquals(0, Zigbee2Mqtt.percentToBrightness(0))
        assertEquals(254, Zigbee2Mqtt.percentToBrightness(100))
        assertEquals(100, Zigbee2Mqtt.brightnessToPercent(254))
        assertEquals(100, Zigbee2Mqtt.brightnessToPercent(9_999), "out-of-range input is clamped")
    }

    // ---- commands --------------------------------------------------------------------

    @Test
    fun ourVerbsProduceZigbee2MqttSetPayloads() {
        assertEquals(
            """{"state":"ON"}""",
            Zigbee2Mqtt.setPayload(DeviceCommand.VERB_SET_POWER, DeviceCommand.ARG_ON),
        )
        assertEquals(
            """{"state":"OFF"}""",
            Zigbee2Mqtt.setPayload(DeviceCommand.VERB_SET_POWER, DeviceCommand.ARG_OFF),
        )
        assertEquals(
            """{"state":"TOGGLE"}""",
            Zigbee2Mqtt.setPayload(DeviceCommand.VERB_TOGGLE, ""),
        )
        assertEquals(
            """{"state":"ON","brightness":127}""",
            Zigbee2Mqtt.setPayload(DeviceCommand.VERB_SET_LEVEL, "50"),
        )
        // Level 0 is an explicit off — brightness 0 is not a reliable off.
        assertEquals(
            """{"state":"OFF"}""",
            Zigbee2Mqtt.setPayload(DeviceCommand.VERB_SET_LEVEL, "0"),
        )
        // Never guess: an unknown verb or unparseable argument has no payload.
        assertNull(Zigbee2Mqtt.setPayload("danceTheJig", ""))
        assertNull(Zigbee2Mqtt.setPayload(DeviceCommand.VERB_SET_LEVEL, "brightly"))
        assertNull(Zigbee2Mqtt.setPayload(DeviceCommand.VERB_SET_POWER, "maybe"))
    }

    @Test
    fun permitJoinPayloadIsTimeBounded() {
        assertEquals("""{"time":60}""", Zigbee2Mqtt.permitJoinPayload(60))
        assertEquals("""{"time":0}""", Zigbee2Mqtt.permitJoinPayload(0))
        assertEquals("""{"time":0}""", Zigbee2Mqtt.permitJoinPayload(-30))
    }

    // ---- sanitisation (threat model T-HD-04) ----------------------------------------

    @Test
    fun anAdversarialDeviceNameIsBoundedBeforeItCanBecomeARow() {
        val entries = assertNotNull(Zigbee2Mqtt.parseInventory(Zigbee2MqttFixtures.HOSTILE_DEVICES))
        val device = entries.single().device

        val name = device.defaultName
        assertTrue(
            name.length <= Zigbee2Mqtt.MAX_TEXT_LENGTH,
            "name capped at ${Zigbee2Mqtt.MAX_TEXT_LENGTH}, was ${name.length}",
        )
        assertFalse(name.any { it.isISOControl() }, "no control characters survive: $name")
        assertFalse(name.contains('\n'))
        assertFalse(name.contains('\r'))
        assertFalse(name.contains('\u200B'), "zero-width characters are stripped")
        assertTrue(name.startsWith("Kitchen"), "the legible part survives: $name")

        assertFalse(device.model.any { it.isISOControl() })
        assertFalse(device.manufacturer.any { it.isISOControl() })
        assertTrue(device.manufacturer.contains("EVIL"), "text is bounded, not censored")
        assertTrue(device.extras.length <= Zigbee2Mqtt.MAX_EXTRAS_LENGTH)
    }

    @Test
    fun sanitisationCollapsesRatherThanConcatenates() {
        // "a\nb" must not become "ab" — that would let a hostile name forge a
        // *different* legible word than the one an operator saw in z2m.
        assertEquals("a b", Zigbee2Mqtt.sanitiseText("a\nb"))
        assertEquals("", Zigbee2Mqtt.sanitiseText(null))
        assertEquals("", Zigbee2Mqtt.sanitiseText("   "))
        assertEquals("x".repeat(8), Zigbee2Mqtt.sanitiseText("x".repeat(50), maxLength = 8))
    }

    @Test
    fun stateExtrasAreBoundedInCountAndSize() {
        val huge = (1..200).joinToString(prefix = "{", postfix = "}") { """"k$it":"${"v".repeat(40)}"""" }
        val state = assertNotNull(Zigbee2Mqtt.mergeState("zigbee2mqtt:0xdead", huge, previous = null))
        assertTrue(
            state.extras.length <= Zigbee2Mqtt.MAX_EXTRAS_LENGTH,
            "extras capped, was ${state.extras.length}",
        )
    }

    // ---- topics ----------------------------------------------------------------------

    @Test
    fun topicsClassifyTheWholeContractAndIgnoreOurOwnEchoes() {
        val topics = Zigbee2Mqtt.Topics("zigbee2mqtt")
        assertEquals(Zigbee2Mqtt.Inbound.Devices, topics.classify("zigbee2mqtt/bridge/devices"))
        assertEquals(Zigbee2Mqtt.Inbound.BridgeState, topics.classify("zigbee2mqtt/bridge/state"))
        assertEquals(Zigbee2Mqtt.Inbound.Event, topics.classify("zigbee2mqtt/bridge/event"))
        assertEquals(
            Zigbee2Mqtt.Inbound.State("Living room lamp"),
            topics.classify("zigbee2mqtt/Living room lamp"),
        )
        // ⚠ Friendly names may contain slashes — the remainder is taken whole.
        assertEquals(
            Zigbee2Mqtt.Inbound.State("kitchen/lamp"),
            topics.classify("zigbee2mqtt/kitchen/lamp"),
        )
        assertEquals(
            Zigbee2Mqtt.Inbound.Availability("Living room lamp"),
            topics.classify("zigbee2mqtt/Living room lamp/availability"),
        )
        // Our own publishes come back to us; bridge chatter and foreign
        // topics are none of our business.
        assertEquals(Zigbee2Mqtt.Inbound.Ignored, topics.classify("zigbee2mqtt/Living room lamp/set"))
        assertEquals(Zigbee2Mqtt.Inbound.Ignored, topics.classify("zigbee2mqtt/bridge/logging"))
        assertEquals(Zigbee2Mqtt.Inbound.Ignored, topics.classify("zigbee2mqtt/bridge/request/permit_join"))
        assertEquals(Zigbee2Mqtt.Inbound.Ignored, topics.classify("homeassistant/light/config"))
    }

    @Test
    fun aCustomBaseTopicIsHonouredEverywhere() {
        val topics = Zigbee2Mqtt.Topics("upstairs-zigbee")
        assertEquals("upstairs-zigbee/bridge/devices", topics.devices)
        assertEquals("upstairs-zigbee/#", topics.subscription)
        assertEquals("upstairs-zigbee/Lamp/set", topics.set("Lamp"))
        assertEquals("upstairs-zigbee/bridge/request/permit_join", topics.permitJoinRequest)
        assertEquals(Zigbee2Mqtt.Inbound.Ignored, topics.classify("zigbee2mqtt/bridge/devices"))
    }
}
