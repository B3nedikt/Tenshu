package app.tenshu.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice

/**
 * [Zigbee2MqttBridge] against the in-memory [FakeMqttClient]: the behaviours
 * that only exist once payloads meet a live (if fake) broker — retained
 * inventory replay, rename-without-identity-loss, reconnect + resubscribe,
 * malformed-payload tolerance, unknown devices, and the permit-join clamp.
 *
 * Mapping itself is covered purely in [Zigbee2MqttMappingTest]; the message
 * routing here uses the internal `onMessage` seam where a deterministic
 * assertion is worth more than a coroutine race.
 */
class Zigbee2MqttBridgeTest {

    private val topics = Zigbee2Mqtt.Topics()

    // ---- routing ---------------------------------------------------------------------

    @Test
    fun inventoryThenStateProducesDevicesAndStates() = runBlocking {
        val bridge = Zigbee2MqttBridge(FakeMqttClient(), log = {})

        bridge.onMessage(MqttMessage(topics.devices, Zigbee2MqttFixtures.BRIDGE_DEVICES))
        val devices = bridge.devices().first()

        // The bridge's own row leads, then the three household devices.
        assertEquals(HomeDevice.BRIDGE_ZIGBEE2MQTT, devices.first().deviceId)
        assertEquals(4, devices.size)
        assertTrue(devices.any { it.deviceId == Zigbee2MqttFixtures.LAMP_ID })

        bridge.onMessage(MqttMessage("zigbee2mqtt/Living room lamp", Zigbee2MqttFixtures.LAMP_STATE))
        val lampState = bridge.states().first().first { it.deviceId == Zigbee2MqttFixtures.LAMP_ID }
        assertEquals(true, lampState.powerOn)
        assertEquals(50, lampState.levelPercent)
        assertTrue(lampState.extras.contains("linkquality"))
    }

    @Test
    fun everyKnownDeviceGetsAStateRowEvenBeforeItReports() = runBlocking {
        val bridge = Zigbee2MqttBridge(FakeMqttClient(), log = {})
        bridge.onMessage(MqttMessage(topics.devices, Zigbee2MqttFixtures.BRIDGE_DEVICES))

        val states = bridge.states().first()
        assertEquals(4, states.size, "3 devices + the bridge row")
        assertNull(states.first { it.deviceId == Zigbee2MqttFixtures.SENSOR_ID }.temperatureDeciC)
    }

    @Test
    fun aRenameKeepsIdentityAndMovesTheCommandTopic() = runBlocking {
        val client = FakeMqttClient()
        val bridge = Zigbee2MqttBridge(client, log = {})

        bridge.onMessage(MqttMessage(topics.devices, Zigbee2MqttFixtures.BRIDGE_DEVICES))
        val idsBefore = bridge.devices().first().map { it.deviceId }.toSet()
        assertEquals(
            DeviceState.OUTCOME_OK,
            bridge.execute(Zigbee2MqttFixtures.LAMP_ID, DeviceCommand.VERB_TOGGLE, "").outcome,
        )
        assertEquals("zigbee2mqtt/Living room lamp/set", client.published.last().first)

        // The user renames the lamp in zigbee2mqtt; z2m republishes the list.
        bridge.onMessage(
            MqttMessage(topics.devices, Zigbee2MqttFixtures.BRIDGE_DEVICES_AFTER_RENAME),
        )
        assertEquals(
            idsBefore,
            bridge.devices().first().map { it.deviceId }.toSet(),
            "identity is the IEEE address — a rename must not mint a new device",
        )
        bridge.execute(Zigbee2MqttFixtures.LAMP_ID, DeviceCommand.VERB_TOGGLE, "")
        assertEquals(
            "zigbee2mqtt/Reading lamp/set",
            client.published.last().first,
            "commands follow the new friendly name",
        )
        // The state topic follows too.
        bridge.onMessage(MqttMessage("zigbee2mqtt/Reading lamp", """{"state":"OFF"}"""))
        assertEquals(
            false,
            bridge.states().first().first { it.deviceId == Zigbee2MqttFixtures.LAMP_ID }.powerOn,
        )
    }

    @Test
    fun malformedAndUnknownTrafficNeverKillsTheBridge() = runBlocking {
        val bridge = Zigbee2MqttBridge(FakeMqttClient(), log = {})
        bridge.onMessage(MqttMessage(topics.devices, Zigbee2MqttFixtures.BRIDGE_DEVICES))
        val before = bridge.devices().first()

        // Garbage on every topic we listen to.
        bridge.onMessage(MqttMessage(topics.devices, "}{ not json"))
        bridge.onMessage(MqttMessage(topics.bridgeState, "¯\\_(ツ)_/¯"))
        bridge.onMessage(MqttMessage(topics.event, "[]"))
        bridge.onMessage(MqttMessage("zigbee2mqtt/Living room lamp", "<html>nope</html>"))
        // A device that is not in the inventory (yet).
        bridge.onMessage(MqttMessage("zigbee2mqtt/Someone elses lamp", """{"state":"ON"}"""))
        // Foreign topics and our own echoes.
        bridge.onMessage(MqttMessage("zigbee2mqtt/Living room lamp/set", """{"state":"ON"}"""))
        bridge.onMessage(MqttMessage("homeassistant/light/config", "{}"))

        assertEquals(before, bridge.devices().first(), "a malformed inventory keeps the previous one")
        // …and the bridge still works afterwards.
        bridge.onMessage(MqttMessage("zigbee2mqtt/Living room lamp", Zigbee2MqttFixtures.LAMP_STATE))
        assertEquals(
            true,
            bridge.states().first().first { it.deviceId == Zigbee2MqttFixtures.LAMP_ID }.powerOn,
        )
    }

    @Test
    fun aBridgeGoingOfflineMarksEveryDeviceUnreachable() = runBlocking {
        val bridge = Zigbee2MqttBridge(FakeMqttClient(), log = {})
        bridge.onMessage(MqttMessage(topics.devices, Zigbee2MqttFixtures.BRIDGE_DEVICES))
        bridge.onMessage(MqttMessage(topics.bridgeState, Zigbee2MqttFixtures.BRIDGE_ONLINE))
        assertTrue(bridge.states().first().all { it.online })

        bridge.onMessage(MqttMessage(topics.bridgeState, Zigbee2MqttFixtures.BRIDGE_OFFLINE))
        assertTrue(
            bridge.states().first().none { it.online },
            "the radio being gone means every device behind it is unreachable",
        )
    }

    @Test
    fun perDeviceAvailabilityMarksOneDevice() = runBlocking {
        val bridge = Zigbee2MqttBridge(FakeMqttClient(), log = {})
        bridge.onMessage(MqttMessage(topics.devices, Zigbee2MqttFixtures.BRIDGE_DEVICES))

        bridge.onMessage(
            MqttMessage("zigbee2mqtt/Bedroom sensor/availability", """{"state":"offline"}"""),
        )
        val states = bridge.states().first()
        assertEquals(false, states.first { it.deviceId == Zigbee2MqttFixtures.SENSOR_ID }.online)
        assertEquals(true, states.first { it.deviceId == Zigbee2MqttFixtures.LAMP_ID }.online)
    }

    // ---- commands --------------------------------------------------------------------

    @Test
    fun commandsForUnknownDevicesAndUnknownVerbsAreUnsupportedNotFatal() = runBlocking {
        val client = FakeMqttClient()
        val bridge = Zigbee2MqttBridge(client, log = {})
        bridge.onMessage(MqttMessage(topics.devices, Zigbee2MqttFixtures.BRIDGE_DEVICES))

        assertEquals(
            DeviceState.OUTCOME_UNSUPPORTED,
            bridge.execute("zigbee2mqtt:0xnothere", DeviceCommand.VERB_TOGGLE, "").outcome,
        )
        assertEquals(
            DeviceState.OUTCOME_UNSUPPORTED,
            bridge.execute(Zigbee2MqttFixtures.LAMP_ID, "danceTheJig", "").outcome,
        )
        assertTrue(client.published.isEmpty(), "nothing is published for a command we cannot express")
    }

    @Test
    fun setLevelPublishesTheBrightnessScaledToZigbee() = runBlocking {
        val client = FakeMqttClient()
        val bridge = Zigbee2MqttBridge(client, log = {})
        bridge.onMessage(MqttMessage(topics.devices, Zigbee2MqttFixtures.BRIDGE_DEVICES))

        val result = bridge.execute(Zigbee2MqttFixtures.LAMP_ID, DeviceCommand.VERB_SET_LEVEL, "65")
        assertEquals(DeviceState.OUTCOME_OK, result.outcome)
        assertNull(result.newState, "the radio confirms asynchronously — no synchronous state")
        assertEquals(
            """{"state":"ON","brightness":165}""",
            client.publishedTo("zigbee2mqtt/Living room lamp/set").single(),
        )
    }

    @Test
    fun permitJoinIsClampedAndPublishedAsATimeBoundedRequest() = runBlocking {
        val client = FakeMqttClient()
        val bridge = Zigbee2MqttBridge(client, log = {})

        assertEquals(DeviceState.OUTCOME_OK, bridge.setPermitJoin(60).outcome)
        assertTrue(bridge.permitJoinUntilMs > System.currentTimeMillis())

        // However much is asked for, the radio is never opened for longer
        // than the hub's ceiling (HD-D7).
        bridge.setPermitJoin(99_999)
        bridge.setPermitJoin(0)
        assertEquals(
            listOf(
                """{"time":60}""",
                """{"time":${DeviceCommand.PERMIT_JOIN_MAX_SECONDS}}""",
                """{"time":0}""",
            ),
            client.publishedTo(topics.permitJoinRequest),
        )
        assertEquals(0L, bridge.permitJoinUntilMs, "closing the window clears it")
    }

    // ---- lifecycle -------------------------------------------------------------------

    @Test
    fun retainedInventoryReplaysOnSubscribeSoAHubMayStartLast() = runBlocking {
        val client = FakeMqttClient()
        // The sidecar has been running for hours; the broker holds its
        // retained view of the world.
        client.retained[topics.devices] = Zigbee2MqttFixtures.BRIDGE_DEVICES
        client.retained[topics.bridgeState] = Zigbee2MqttFixtures.BRIDGE_ONLINE
        val bridge = Zigbee2MqttBridge(client, backoff = fastBackoff, log = {})

        withScope { scope ->
            bridge.start(scope)
            withTimeout(5.seconds) {
                bridge.devices().first { devices ->
                    devices.any { it.deviceId == Zigbee2MqttFixtures.LAMP_ID }
                }
                // ⚠⚠ Await this one too. `bridge/state` is a *second* retained message, routed
                // by a *different* handler (`onBridgeState`, not `onInventory`) on the reader
                // coroutine — so arrival of the inventory says nothing about it. Asserting
                // `bridgeOnline` off the back of the devices wait passed only because the two
                // replays normally land in the same scheduling slice; under full-gate load they
                // do not, and it failed as `Expected value to be true`. Waiting on the state you
                // are about to assert is the fix, and it is the general shape: this test replays
                // two independent messages and may only synchronise on one of them by waiting
                // for both.
                bridge.bridgeOnline().first { it }
            }
            assertEquals(listOf(topics.subscription), client.subscriptions)
            assertTrue(bridge.bridgeOnline().value)
            bridge.stop()
        }
    }

    @Test
    fun connectFailuresBackOffAndTheBridgeStillComesUp() = runBlocking {
        val client = FakeMqttClient(failConnectTimes = 3)
        client.retained[topics.devices] = Zigbee2MqttFixtures.BRIDGE_DEVICES
        val bridge = Zigbee2MqttBridge(client, backoff = fastBackoff, log = {})

        withScope { scope ->
            bridge.start(scope)
            withTimeout(10.seconds) {
                bridge.devices().first { it.isNotEmpty() }
            }
            assertTrue(client.connectCount >= 4, "it kept trying: ${client.connectCount}")
            bridge.stop()
        }
    }

    @Test
    fun aBrokerDropReconnectsAndResubscribes() = runBlocking {
        val client = FakeMqttClient()
        client.retained[topics.devices] = Zigbee2MqttFixtures.BRIDGE_DEVICES
        val bridge = Zigbee2MqttBridge(client, backoff = fastBackoff, log = {})

        withScope { scope ->
            bridge.start(scope)
            withTimeout(5.seconds) { bridge.devices().first { it.isNotEmpty() } }
            assertEquals(1, client.subscriptions.size)

            // The sidecar's host restarts mosquitto underneath us.
            client.dropConnection()
            withTimeout(5.seconds) {
                while (client.subscriptions.size < 2) delay(20)
            }
            // …and the retained topics rebuild the world.
            withTimeout(5.seconds) {
                bridge.devices().first { devices ->
                    devices.any { it.deviceId == Zigbee2MqttFixtures.LAMP_ID }
                }
            }
            assertTrue(bridge.connectAttempts >= 2)
            bridge.stop()
        }
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        val backoff = Zigbee2MqttBridge.Backoff(initialMs = 1_000, maxMs = 60_000, factor = 2)
        assertEquals(1_000, backoff.delayMsFor(1))
        assertEquals(2_000, backoff.delayMsFor(2))
        assertEquals(4_000, backoff.delayMsFor(3))
        assertEquals(60_000, backoff.delayMsFor(20), "a broker down for an hour is not hammered")
    }

    // ---- harness ---------------------------------------------------------------------

    private val fastBackoff = Zigbee2MqttBridge.Backoff(initialMs = 20, maxMs = 60, factor = 2)

    private suspend fun withScope(block: suspend (CoroutineScope) -> Unit) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job)
        try {
            block(scope)
        } finally {
            job.cancelAndJoin()
        }
    }
}
