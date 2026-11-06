package app.tenshu.hub

import io.ktor.client.HttpClient
import java.io.File as JavaFile
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.getOrNull
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.routine.client.routineService
import org.tenshu.routine.client.save
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.MatchOp
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.StateMatch
import org.tenshu.routine.common.program.Trigger
import org.tenshu.routine.proxy.MutableLocalDeviceEventSource
import org.tenshu.routine.proxy.ObservedStateChange
import org.tenshu.routine.proxy.RoutineEventHost
import org.tenshu.sync.approvePairingOffer
import org.tenshu.sync.client.syncService
import org.tenshu.sync.respondToPairingOffer
import org.tenshu.systemtest.TestClient
import org.tenshu.systemtest.TestClientRole
import org.tenshu.systemtest.TestSystem
import org.tenshu.systemtest.bootstrapAsHost

/**
 * RE-2's headline claim, end to end and in its strongest form: **motion turns on
 * the light with the relay dead**.
 *
 * ⭐ This is the one test that pins "routines execute locally on the proxy — no
 * cloud round-trip" as a *property of the assembled hub process* rather than of
 * the engine in isolation. The engine-level tests
 * (`RoutineDeviceEventTest`) prove the logic; only this proves the wiring —
 * bridge event → `MutableLocalDeviceEventSource` → `RoutineEventHost` → engine →
 * `DeviceCommand` row → `DeviceBridgeHost` → the simulated bulb — with nothing
 * on the other end of the network.
 *
 * ⚠ The routine is authored by the phone **while the relay is up** (that is a
 * sync operation, and pretending otherwise would be dishonest); the relay is
 * then killed, and the *firing* happens with no network at all.
 */
class RoutineDeviceEventSystemTest {

    @Test
    fun motionTurnsOnTheLightWithTheRelayDead() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-routine-event-test").toFile()
        var runtime: HubRuntime? = null
        var host: DeviceBridgeHost? = null
        var eventHost: RoutineEventHost? = null
        var relayAlive = true
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            runtime = assembleHub(system, dataRoot)
            runtime.start()
            pairViaOfferFlow(phone, runtime)

            // 1. The phone authors "when the sensor sees motion, turn the light
            //    on". A sync write — the relay is necessarily up for this part.
            val encoded = JsonRoutineProgramCodec().encode(
                RoutineProgram(
                    trigger = Trigger.DeviceEvent(
                        deviceId = SimulatedDeviceBridge.SENSOR_ID,
                        // ⚠ `occupancy` lives in the bridge row's EXTRAS, exactly
                        // as zigbee2mqtt reports it — so this exercises the same
                        // flattening the real Zigbee path uses.
                        match = StateMatch(field = "occupancy", op = MatchOp.EQ, value = "true"),
                    ),
                    actions = listOf(Action.SetPower(SimulatedDeviceBridge.LIGHT_ID, on = true)),
                )
            )
            phone.tenshuClient.routineService.save(
                routineId = "motion-lights",
                name = "Motion lights",
                timeZoneId = "Europe/Berlin",
                programJson = encoded.programJson,
                programVersion = encoded.programVersion,
            )

            // 2. Bring the bridge up and wait for the routine to reach the hub.
            val bridge = SimulatedDeviceBridge(sensorTickMs = 60L * 60 * 1000)
            val events = MutableLocalDeviceEventSource()
            host = DeviceBridgeHost(
                runtime = runtime,
                bridge = bridge,
                publishGate = DeviceStatePublishGate(minIntervalMs = 400),
                onDeviceEvent = { state, causedBy -> events.emit(state.toChange(causedBy)) },
            )
            host.start()

            val hubContext = runtime.koin.get<ClientRegistry>()
                .getExecutionContext(HubRuntime.PACKAGE_NAME)
            eventHost = RoutineEventHost(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                executionContext = hubContext,
                implementationFactory = hubContext.implementationFactory,
                source = events,
                nowEpochMs = { System.currentTimeMillis() },
                thisDeviceId = { runtime.syncManager.currentDeviceId },
            ).also { it.start() }

            // The routine has to REACH the hub before the relay dies — that is
            // the one thing this test still needs a network for.
            val hub = runtime
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                hubHasRoutine(hub)
            }
            assertEquals(false, bridge.currentLight().on, "the light starts off")

            // 3. ⭐ Kill the relay. Everything after this happens with no network.
            system.shutdown()
            relayAlive = false

            // 4. Motion. Nothing here may touch a socket.
            bridge.triggerMotion(occupied = true)

            withTimeout(15.seconds) {
                while (!bridge.currentLight().on) delay(50)
            }

            assertTrue(bridge.currentLight().on, "motion must turn the light on with the relay dead")
            assertTrue(
                host.executedCommandCount >= 1,
                "the command travelled the ordinary hub execution path",
            )
        } finally {
            eventHost?.stop()
            host?.stop()
            runtime?.shutdown()
            if (relayAlive) runCatching { system.shutdown() }
            dataRoot.deleteRecursively()
        }
    }

    /** Mirrors `Main.kt`'s emitter so the test covers the production mapping. */
    private fun BridgeDeviceState.toChange(causedByCommandId: String) = ObservedStateChange(
        deviceId = deviceId,
        fields = buildMap {
            put("online", online.toString())
            powerOn?.let { put("power", if (it) "on" else "off") }
            levelPercent?.let { put("levelPercent", it.toString()) }
            temperatureDeciC?.let { put("temperatureDeciC", it.toString()) }
            putAll(flattenExtras(extras))
        },
        freshAsOfEpochMs = System.currentTimeMillis(),
        lastCommandId = causedByCommandId,
    )

    private fun flattenExtras(extras: String): Map<String, String> {
        if (extras.isBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(extras).jsonObject.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.content ?: value.toString()
            }
        }.getOrElse { emptyMap() }
    }

    private fun hubHasRoutine(runtime: HubRuntime): Boolean = runCatching {
        val context = runtime.koin.get<ClientRegistry>()
            .getExecutionContext(HubRuntime.PACKAGE_NAME)
        context.database.getArrayData(
            org.tenshu.core.common.proxy.ArrayDatabaseReference(
                arrayId = org.tenshu.core.entity.DataId.RelationDataId.create(
                    context = context.context,
                    type = org.tenshu.core.collection.list.TList.ID,
                    relationId = org.tenshu.routine.RoutineService.Relations.ROUTINES,
                ),
                isEntity = false,
                elementSchemaId = org.tenshu.routine.Routine.ID,
            )
        )?.toList().orEmpty().isNotEmpty()
    }.getOrDefault(false)

    // ---- harness glue (mirrors HomeDeviceControlSystemTest) -----------------------------

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
        val outcome = async(Dispatchers.Default) { cli.runOnce("Routines Hub") }
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
        val prompt = phone.tenshuClient.syncService.respondToPairingOffer(offerUri)
        assertTrue(prompt is TResult.Success, "respondToPairingOffer failed: $prompt")
        val approve = phone.tenshuClient.syncService
            .approvePairingOffer(prompt.getOrNull()!!.pairingSecretHash.get())
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
    }
}
