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
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.client.getDeviceState
import org.tenshu.home.device.client.getHomeDevices
import org.tenshu.home.device.client.homeDeviceService
import org.tenshu.home.device.client.renameDevice
import org.tenshu.home.device.client.setDeviceLevel
import org.tenshu.home.device.client.toggleDevice
import org.tenshu.sync.approvePairingOffer
import org.tenshu.sync.client.syncService
import org.tenshu.sync.respondToPairingOffer
import org.tenshu.systemtest.TestClient
import org.tenshu.systemtest.TestClientRole
import org.tenshu.systemtest.TestSystem
import org.tenshu.systemtest.bootstrapAsHost

/**
 * HD-1 (`home-device-service.md` HD-D4): the full device spine over the
 * simulated bridge with zero hardware — the hub publishes [HomeDevice] +
 * [DeviceState] rows → a manager client (home-desktop-shaped: a real
 * `TenshuClient` under Home's [ClientDeviceSource] seam) sees the light →
 * issues `toggle`/`setLevel` → the hub executes **exactly once** (journal
 * pins re-delivery as a no-op) → the simulated light actually changes →
 * the refreshed state row (outcome + new level) syncs back → a manager
 * rename merges cleanly against concurrent hub state publishes and survives
 * a hub inventory re-announce. Sensor chatter inside the quantisation
 * proves the rate limit end to end.
 *
 * The staleness window (HD-D7, 2 min) gets its own test: a command the hub
 * sees as older than the window must never actuate.
 */
class HomeDeviceSystemTest {

    @Test
    fun lightRoundTrip_exactlyOnce_rateLimit_renameSurvives() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-hd1-devices-test").toFile()
        var runtime: HubRuntime? = null
        var host: DeviceBridgeHost? = null
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            runtime = assembleHub(system, dataRoot)
            runtime.start()
            pairViaOfferFlow(phone, runtime)

            // Wall-clock drift disabled (huge tick) — the test drives the
            // sensor deterministically; a tight rate-limit floor keeps the
            // held-flush path inside the test's wall-clock budget.
            val bridge = SimulatedDeviceBridge(sensorTickMs = 60L * 60 * 1000)
            host = DeviceBridgeHost(
                runtime = runtime,
                bridge = bridge,
                publishGate = DeviceStatePublishGate(minIntervalMs = 400),
            )
            host.start()

            // Home's seam, exactly as home-desktop assembles it: the client
            // source over a real TenshuClient on the MANAGER device.
            val source = ClientDeviceSource(phone.tenshuClient)

            // 1. Hub publishes inventory + state → the manager sees the light
            //    (with live state) through the DeviceSource seam.
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                source.devices().first()
                    .any { it.deviceId == SimulatedDeviceBridge.LIGHT_ID && it.stateFresh }
            }
            val lightInfo = source.devices().first()
                .first { it.deviceId == SimulatedDeviceBridge.LIGHT_ID }
            assertEquals(HomeDevice.KIND_LIGHT, lightInfo.kind)
            assertEquals("Simulated light", lightInfo.name)
            assertEquals(false, lightInfo.powerOn, "the simulated light starts off")
            assertEquals(true, lightInfo.online)

            // 2. toggle → the hub executes it and the simulated light REALLY
            //    turns on; the outcome + new state sync back to the manager.
            val toggleId = "hd1-toggle-1"
            assertTrue(
                phone.tenshuClient.homeDeviceService
                    .toggleDevice(SimulatedDeviceBridge.LIGHT_ID, commandId = toggleId)
                        is TResult.Success,
            )
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                bridge.currentLight().on
            }
            assertEquals(1L, host.executedCommandCount)
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                val s = phone.tenshuClient.homeDeviceService
                    .getDeviceState(SimulatedDeviceBridge.LIGHT_ID)
                s?.lastCommandId?.get() == toggleId &&
                    s.lastCommandOutcome.get() == DeviceState.OUTCOME_OK &&
                    s.powerOnOrNull() == true
            }

            // 3. setLevel(65) → the light's level machine moves; the manager
            //    sees the new level through the seam.
            assertTrue(
                phone.tenshuClient.homeDeviceService.setDeviceLevel(
                    SimulatedDeviceBridge.LIGHT_ID,
                    percent = 65,
                    commandId = "hd1-level-1",
                ) is TResult.Success,
            )
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                bridge.currentLight().levelPercent == 65
            }
            assertEquals(2L, host.executedCommandCount)
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                source.devices().first()
                    .first { it.deviceId == SimulatedDeviceBridge.LIGHT_ID }
                    .levelPercent == 65
            }

            // 4. Exactly-once: re-delivering the SAME toggle row is a no-op —
            //    the issuer re-writes the identical commandId (same row key,
            //    fresh sync emission) and the hub's consumed journal drops it.
            assertTrue(
                phone.tenshuClient.homeDeviceService
                    .toggleDevice(SimulatedDeviceBridge.LIGHT_ID, commandId = toggleId)
                        is TResult.Success,
            )
            delay(2_000) // several sync cycles at the 200–250 ms test cadence
            assertEquals(2L, host.executedCommandCount, "re-delivered command must not re-execute")
            assertEquals(true, bridge.currentLight().on, "the light must not have re-toggled")

            // 5. Rate limit: sensor chatter INSIDE the 0.5 °C quantum writes
            //    no state row; crossing the quantum writes one.
            delay(1_000) // let any in-flight publishes settle
            val publishedBefore = host.publishedStateCount
            val tempBefore = bridge.currentSensor().temperatureDeciC // 21.5 °C seed
            repeat(4) {
                bridge.driftSensorNow(deltaDeciC = 1) // 21.6..21.9 — same quantum step
                delay(150)
            }
            delay(1_000)
            assertEquals(
                publishedBefore, host.publishedStateCount,
                "sub-quantum sensor drift must not produce state rows",
            )
            bridge.driftSensorNow(deltaDeciC = 5) // crosses into the next 0.5 °C step
            val tempAfter = tempBefore + 9
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                source.devices().first()
                    .firstOrNull { it.deviceId == SimulatedDeviceBridge.SENSOR_ID }
                    ?.temperatureDeciC == tempAfter
            }

            // 6. Rename from the manager merges cleanly with concurrent hub
            //    publishes: state rows keep flowing (separate row — no
            //    collision by design) and an inventory re-announce preserves
            //    the user's name (change-gated + user-field-preserving).
            assertTrue(
                phone.tenshuClient.homeDeviceService
                    .renameDevice(SimulatedDeviceBridge.LIGHT_ID, "Reading lamp")
                        is TResult.Success,
            )
            bridge.driftSensorNow(deltaDeciC = 5) // concurrent hub-authored state publish
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                source.devices().first()
                    .first { it.deviceId == SimulatedDeviceBridge.LIGHT_ID }
                    .name == "Reading lamp"
            }
            // Wait for the rename to reach the HUB's replica, then re-announce.
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                runtime!!.client.homeDeviceService.getHomeDevices()
                    .firstOrNull { it.deviceId.get() == SimulatedDeviceBridge.LIGHT_ID }
                    ?.name?.get() == "Reading lamp"
            }
            host.publishInventory(bridge.devices().first())
            delay(2_000)
            val renamed = source.devices().first()
                .first { it.deviceId == SimulatedDeviceBridge.LIGHT_ID }
            assertEquals("Reading lamp", renamed.name, "a re-announce must not clobber the rename")
            assertEquals(65, renamed.levelPercent, "hub-authored state stays live after the rename")

            // The command channel still works post-rename (fresh id).
            assertTrue(
                phone.tenshuClient.homeDeviceService.setDeviceLevel(
                    SimulatedDeviceBridge.LIGHT_ID,
                    percent = 20,
                    commandId = "hd1-level-2",
                ) is TResult.Success,
            )
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                bridge.currentLight().levelPercent == 20
            }
            assertEquals(3L, host.executedCommandCount)
        } finally {
            host?.stop()
            runtime?.shutdown()
            system.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    @Test
    fun staleCommand_neverActuates() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-hd1-stale-test").toFile()
        var runtime: HubRuntime? = null
        var host: DeviceBridgeHost? = null
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            runtime = assembleHub(system, dataRoot)
            runtime.start()
            pairViaOfferFlow(phone, runtime)

            val bridge = SimulatedDeviceBridge(sensorTickMs = 60L * 60 * 1000)
            // The hub's clock sits 3 minutes ahead of the issuer's, so every
            // command arrives looking older than the 2-minute window — the
            // backlog-replay shape (HD-D7: must never actuate).
            host = DeviceBridgeHost(
                runtime = runtime,
                bridge = bridge,
                nowMs = { System.currentTimeMillis() + 3L * 60 * 1000 },
            )
            host.start()

            // Device known realm-wide before issuing (the ownership leg).
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                phone.tenshuClient.homeDeviceService.getHomeDevices()
                    .any { it.deviceId.get() == SimulatedDeviceBridge.LIGHT_ID }
            }

            assertTrue(
                phone.tenshuClient.homeDeviceService
                    .toggleDevice(SimulatedDeviceBridge.LIGHT_ID, commandId = "hd1-stale-1")
                        is TResult.Success,
            )
            delay(3_000) // many sync cycles — the command row definitely reached the hub
            assertEquals(0L, host.executedCommandCount, "a stale command must never execute")
            assertEquals(false, bridge.currentLight().on, "the light must not have moved")
        } finally {
            host?.stop()
            runtime?.shutdown()
            system.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    // ---- harness glue (H1/H2/H4 shapes) -----------------------------------------------

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

    /** The H1 offer-flow pairing dance: hub CLI arms, the phone responds + approves. */
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
