package app.tenshu.hub

import app.tenshu.home.data.ClientDeviceSource
import io.ktor.client.HttpClient
import java.io.File as JavaFile
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.getOrNull
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.client.getDeviceState
import org.tenshu.home.device.client.getHomeDevices
import org.tenshu.home.device.client.homeDeviceService
import org.tenshu.home.device.client.openPairingWindow
import org.tenshu.home.device.client.setDeviceLevel
import org.tenshu.sync.approvePairingOffer
import org.tenshu.sync.client.syncService
import org.tenshu.sync.respondToPairingOffer
import org.tenshu.systemtest.TestClient
import org.tenshu.systemtest.TestSystem
import org.tenshu.systemtest.TestClientRole
import org.tenshu.systemtest.bootstrapAsHost

/**
 * HD-2 end to end with a **fake broker but a real everything-else**: recorded
 * zigbee2mqtt payloads → [Zigbee2MqttBridge] → [DeviceBridgeHost] → synced
 * `HomeDevice`/`DeviceState` rows → a manager client (Home's own
 * [ClientDeviceSource] seam) → a command row → an MQTT `/set` publish →
 * the sidecar's state echo → the updated row back on the manager.
 *
 * This is HD-1's [HomeDeviceSystemTest] with the radio half swapped in, and
 * it is the closest thing to the Pi that runs without a Pi: everything but
 * the mosquitto socket and the 2.4 GHz airspace is production code.
 */
class Zigbee2MqttSystemTest {

    private val topics = Zigbee2Mqtt.Topics()

    @Test
    fun zigbeeDeviceSyncsCommandsRoundTripAndPermitJoinIsBounded() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-hd2-zigbee-test").toFile()
        var runtime: HubRuntime? = null
        var host: DeviceBridgeHost? = null
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            runtime = assembleHub(system, dataRoot)
            runtime.start()
            pairViaOfferFlow(phone, runtime)

            // The sidecar has been up for a while: the broker holds its
            // retained view, which is all the hub needs to derive the world.
            val broker = FakeMqttClient()
            broker.retained[topics.devices] = Zigbee2MqttFixtures.BRIDGE_DEVICES
            broker.retained[topics.bridgeState] = Zigbee2MqttFixtures.BRIDGE_ONLINE
            val bridge = Zigbee2MqttBridge(
                client = broker,
                backoff = Zigbee2MqttBridge.Backoff(initialMs = 20, maxMs = 50),
                log = {},
            )
            host = DeviceBridgeHost(
                runtime = runtime,
                bridge = bridge,
                publishGate = DeviceStatePublishGate(minIntervalMs = 400),
            )
            host.start()

            val source = ClientDeviceSource(phone.tenshuClient)

            // 1. The Zigbee lamp becomes a realm row the manager can see —
            //    identified by its IEEE address, named by its friendly name.
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                source.devices().first().any { it.deviceId == Zigbee2MqttFixtures.LAMP_ID }
            }
            val lamp = source.devices().first()
                .first { it.deviceId == Zigbee2MqttFixtures.LAMP_ID }
            assertEquals(HomeDevice.KIND_LIGHT, lamp.kind)
            assertEquals("Living room lamp", lamp.name)

            // The bridge itself is a row too — that is what "Add device" aims at.
            val bridgeRow = phone.tenshuClient.homeDeviceService.getHomeDevices()
                .first { it.deviceId.get() == HomeDevice.BRIDGE_ZIGBEE2MQTT }
            assertEquals(HomeDevice.KIND_BRIDGE, bridgeRow.kind.get())
            assertTrue(bridgeRow.capabilities().contains(HomeDevice.CAP_PERMIT_JOIN))

            // 2. The sidecar reports the lamp's state; it lands on the row.
            broker.deliver("zigbee2mqtt/Living room lamp", Zigbee2MqttFixtures.LAMP_STATE)
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                source.devices().first()
                    .first { it.deviceId == Zigbee2MqttFixtures.LAMP_ID }
                    .levelPercent == 50
            }

            // 3. A command from the manager becomes an MQTT publish, in
            //    zigbee2mqtt's units.
            assertTrue(
                phone.tenshuClient.homeDeviceService.setDeviceLevel(
                    Zigbee2MqttFixtures.LAMP_ID,
                    percent = 65,
                    commandId = "hd2-level-1",
                ) is TResult.Success,
            )
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                broker.publishedTo("zigbee2mqtt/Living room lamp/set").isNotEmpty()
            }
            assertEquals(
                """{"state":"ON","brightness":165}""",
                broker.publishedTo("zigbee2mqtt/Living room lamp/set").single(),
                "65% of the 0..254 Zigbee scale",
            )
            assertEquals(1L, host.executedCommandCount)

            // 4. The radio confirms asynchronously (as a real one does) and
            //    the confirmed state syncs back with the command's outcome.
            broker.deliver(
                "zigbee2mqtt/Living room lamp",
                """{"state":"ON","brightness":165,"linkquality":97}""",
            )
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                val state = phone.tenshuClient.homeDeviceService
                    .getDeviceState(Zigbee2MqttFixtures.LAMP_ID)
                state?.lastCommandId?.get() == "hd2-level-1" &&
                    state.lastCommandOutcome.get() == DeviceState.OUTCOME_OK &&
                    state.levelPercent.get() == 65L
            }

            // 5. Re-delivery of the same command is a no-op — the physical
            //    actuation guarantee HD-1 pinned, now over a real radio path.
            assertTrue(
                phone.tenshuClient.homeDeviceService.setDeviceLevel(
                    Zigbee2MqttFixtures.LAMP_ID,
                    percent = 65,
                    commandId = "hd2-level-1",
                ) is TResult.Success,
            )
            delay(2_000)
            assertEquals(1L, host.executedCommandCount, "a re-delivered command must not re-publish")
            assertEquals(1, broker.publishedTo("zigbee2mqtt/Living room lamp/set").size)

            // 6. "Add device": permit-join rides the ordinary command channel
            //    and is clamped hub-side however much is asked for.
            assertTrue(
                phone.tenshuClient.homeDeviceService.openPairingWindow(
                    bridgeId = HomeDevice.BRIDGE_ZIGBEE2MQTT,
                    seconds = DeviceCommand.PERMIT_JOIN_MAX_SECONDS * 10,
                    commandId = "hd2-permit-1",
                ) is TResult.Success,
            )
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                broker.publishedTo(topics.permitJoinRequest).isNotEmpty()
            }
            assertEquals(
                """{"time":${DeviceCommand.PERMIT_JOIN_MAX_SECONDS}}""",
                broker.publishedTo(topics.permitJoinRequest).single(),
                "the radio is never opened for longer than the hub's ceiling",
            )

            // 7. A device joining during the window appears without any
            //    further action (z2m republishes the retained device list).
            broker.deliver(topics.devices, Zigbee2MqttFixtures.BRIDGE_DEVICES_AFTER_RENAME)
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                phone.tenshuClient.homeDeviceService.getHomeDevices()
                    .any { it.deviceId.get() == Zigbee2MqttFixtures.LAMP_ID }
            }
            // ⚠ …and the rename that came with it did NOT mint a second lamp.
            assertEquals(
                1,
                phone.tenshuClient.homeDeviceService.getHomeDevices()
                    .count { it.deviceId.get() == Zigbee2MqttFixtures.LAMP_ID },
            )
        } finally {
            host?.stop()
            runtime?.shutdown()
            system.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    @Test
    fun stalePermitJoinNeverOpensTheRadio() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-hd2-stale-permit-test").toFile()
        var runtime: HubRuntime? = null
        var host: DeviceBridgeHost? = null
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            runtime = assembleHub(system, dataRoot)
            runtime.start()
            pairViaOfferFlow(phone, runtime)

            val broker = FakeMqttClient()
            broker.retained[topics.devices] = Zigbee2MqttFixtures.BRIDGE_DEVICES
            val bridge = Zigbee2MqttBridge(
                client = broker,
                backoff = Zigbee2MqttBridge.Backoff(initialMs = 20, maxMs = 50),
                log = {},
            )
            // The hub's clock sits 3 minutes ahead of the issuer's, so every
            // command looks older than the 2-minute window: the backlog-replay
            // shape. For permit-join that is the sharp case — a replayed
            // "open the network" must never quietly admit the neighbourhood.
            host = DeviceBridgeHost(
                runtime = runtime,
                bridge = bridge,
                nowMs = { System.currentTimeMillis() + 3L * 60 * 1000 },
            )
            host.start()

            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                phone.tenshuClient.homeDeviceService.getHomeDevices()
                    .any { it.deviceId.get() == HomeDevice.BRIDGE_ZIGBEE2MQTT }
            }
            assertTrue(
                phone.tenshuClient.homeDeviceService.openPairingWindow(
                    bridgeId = HomeDevice.BRIDGE_ZIGBEE2MQTT,
                    seconds = 60,
                    commandId = "hd2-stale-permit",
                ) is TResult.Success,
            )
            delay(3_000) // many sync cycles — the row definitely reached the hub

            assertEquals(0L, host.executedCommandCount, "a stale command must never execute")
            assertTrue(
                broker.publishedTo(topics.permitJoinRequest).isEmpty(),
                "no permit-join request may reach the radio",
            )
            assertEquals(0L, bridge.permitJoinUntilMs)
        } finally {
            host?.stop()
            runtime?.shutdown()
            system.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    // ---- harness glue (HD-1's shapes) --------------------------------------------------

    private fun assembleHub(system: TestSystem, dataRoot: JavaFile): HubRuntime =
        HubRuntime.assemble(
            dataRoot = dataRoot,
            syncIntervalMs = 250,
            overrides = listOf(
                module {
                    single<HttpClient> { system.relayHttpClient }
                    single(named("syncRelayUrl")) { "" }
                },
            ),
        )

    private suspend fun pairViaOfferFlow(phone: TestClient, runtime: HubRuntime) = coroutineScope {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val cli = HubPairingCli(
            client = runtime.client,
            out = { lines += it },
            pollInterval = 200.milliseconds,
            timeout = 30.seconds,
        )
        val outcome = async(Dispatchers.Default) { cli.runOnce("Backup Hub") }
        val offerUri = try {
            withTimeout(10.seconds) {
                while (true) {
                    lines.toList().firstOrNull { it.startsWith("Offer URI: ") }
                        ?.let { return@withTimeout it.removePrefix("Offer URI: ") }
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: TimeoutCancellationException) {
            // ⚠⚠ Where an arming stall lands — NOT the terminal wait below. `offerPairing` runs
            // BEFORE the CLI prints anything, so a hang there expires THIS 10 s wait long
            // before the CLI's own (now whole-session) budget, and never reaches that one.
            // Forced pair, 2026-09-15: bare, it reports `Timed out waiting for 10000 ms`
            // and names nothing.
            // `docs/tech-debt.md` § "Two 30-second budgets".
            // The "[...]" markers are the CLI's state machine; the rest is QR and the URI.
            val armed = lines.toList().filter { it.startsWith("[") }
            throw AssertionError("CLI never printed an offer URI within 10s; armed=$armed", e)
        }
        val promptResult = phone.tenshuClient.syncService.respondToPairingOffer(offerUri)
        assertTrue(promptResult is TResult.Success, "respondToPairingOffer failed: $promptResult")
        val approve = phone.tenshuClient.syncService
            .approvePairingOffer(promptResult.getOrNull()!!.pairingSecretHash.get())
        assertTrue(approve is TResult.Success, "approvePairingOffer failed: $approve")
        // ⚠⚠ 45 s against the CLI's own 30 s, and NOT the same number. `runOnce`'s budget
        // now covers the WHOLE session, arming included; it used to cover
        // `pollForOutcome` only and to test `elapsedNow() < timeout` at the TOP of each
        // poll, so one slow `getOfferStatus` overran it. With equal budgets the overrun
        // expires
        // this wait first and the failure is a bare `TimeoutCancellationException: Timed out
        // waiting for 30000 ms` naming nothing. Forced pair, 2026-09-15: equal budgets give exactly
        // that; a larger outer budget gives `expected approval, got TimedOut`. `docs/tech-debt.md`
        // § "Two 30-second budgets".
        val terminal = try {
            withTimeout(45.seconds) { outcome.await() }
        } catch (e: TimeoutCancellationException) {
            // Armed, but no terminal outcome: the poll stalled. An arming stall cannot reach
            // here — the wait above catches that one.
            // The "[...]" markers are the CLI's state machine; the rest is QR and the URI.
            val armed = lines.toList().filter { it.startsWith("[") }
            throw AssertionError("CLI did not return within 45s; armed=$armed", e)
        }
        assertTrue(terminal is PairingOutcome.Approved, "expected approval, got $terminal")
        assertEquals(phone.syncManager.currentOwnerId, runtime.syncManager.currentOwnerId)
    }
}
