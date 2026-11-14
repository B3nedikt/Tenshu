package app.tenshu.hub

import app.tenshu.home.data.ClientDeviceControlSource
import app.tenshu.home.data.ClientDeviceSource
import app.tenshu.home.data.MockHomeDevicesRepository
import app.tenshu.home.ui.screens.devices.DeviceCommandStatus
import app.tenshu.home.ui.screens.devices.DevicesViewModel
import io.ktor.client.HttpClient
import java.io.File as JavaFile
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.getOrNull
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.client.getDeviceState
import org.tenshu.home.device.client.homeDeviceService
import org.tenshu.home.device.client.setDevicePower
import org.tenshu.sync.approvePairingOffer
import org.tenshu.sync.client.syncService
import org.tenshu.sync.respondToPairingOffer
import org.tenshu.systemtest.TestClient
import org.tenshu.systemtest.TestClientRole
import org.tenshu.systemtest.TestSystem
import org.tenshu.systemtest.bootstrapAsHost

/**
 * Actuation from Home's Devices tab, end to end (the HD-3 follow-up). Where
 * [HomeDeviceSystemTest] drives the *client ops* directly, this drives the real
 * [DevicesViewModel] over the seams `home-desktop`/`home-android` assemble — so
 * the whole tap-to-bulb path is covered: control appears → power/level command
 * issued → the hub executes it → the simulated light really moves → the outcome
 * echoed on the state row resolves the view model's pending status.
 *
 * That last link is the one nothing else pins: the view model correlates on the
 * command id the hub echoes back, and [ClientDeviceSource] is what has to carry
 * it out of the schema row. A fake source cannot prove that mapping.
 */
class HomeDeviceControlSystemTest {

    /**
     * Runs on real time and real dispatchers, and is deterministic on one condition:
     * every wait is on something that stays true once it is — the light's own state,
     * and a status the view model resolves from the command id the hub echoes on the
     * state row. The echo only stays because [DeviceBridgeHost] writes a device's state
     * row under one lock. Without it the bridge's own report of the change the command
     * caused can land after the outcome and erase the id, and step 4 waits out its 30 s
     * for an echo nobody sends again — the order
     * [aStateReportRacingItsOwnCommandDoesNotEraseTheEchoedOutcome] forces.
     */
    @Test
    fun tappingTheSwitchMovesTheLightAndTheOutcomeResolvesTheRow() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-home-control-test").toFile()
        // Set as each is started, so teardown stops exactly what this run got to.
        var startedRuntime: HubRuntime? = null
        var startedHost: DeviceBridgeHost? = null
        withBoundedTeardown({
            step("stopping the device bridge host") { startedHost?.stop() }
            step("shutting the hub runtime down") { startedRuntime?.shutdown() }
            step("shutting the test system down") { system.shutdown() }
            step("deleting the hub's data root") { dataRoot.deleteRecursively() }
        }) {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            bounded("bootstrapping the phone as realm host") {
                assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)
            }

            val runtime = assembleHub(system, dataRoot)
            startedRuntime = runtime
            bounded("starting the hub runtime") { runtime.start() }
            pairViaOfferFlow(phone, runtime)

            // Drift disabled: the sensor is not this test's subject.
            val bridge = SimulatedDeviceBridge(sensorTickMs = 60L * 60 * 1000)
            val host = DeviceBridgeHost(
                runtime = runtime,
                bridge = bridge,
                publishGate = DeviceStatePublishGate(minIntervalMs = 400),
            )
            startedHost = host
            host.start()

            // Home's Devices tab as the apps assemble it: the mock household
            // underneath (it is replaced wholesale once real devices arrive) with
            // the read + actuation seams over the manager's real client.
            val model = DevicesViewModel(
                repository = MockHomeDevicesRepository(),
                deviceSource = ClientDeviceSource(phone.tenshuClient),
                controlSource = ClientDeviceControlSource(phone.tenshuClient),
            )

            val hubRows = runtime.client.homeDeviceService
            val describe: suspend () -> String = {
                val row = hubRows.getDeviceState(SimulatedDeviceBridge.LIGHT_ID)
                "tab=${model.uiState.value.controls[SimulatedDeviceBridge.LIGHT_ID]}, " +
                    "light=${bridge.currentLight()}, executed=${host.executedCommandCount}, " +
                    "hub row echoes '${row?.lastCommandId?.get()}'/'${row?.lastCommandOutcome?.get()}'"
            }

            // 1. The light shows up with controls; the mock rows never do.
            // Wait for the *state* too, not just the row: the control row is
            // built from `info.capabilities` while `on` reads `info.powerOn`,
            // and those are separate publishes — so a wait on the key alone
            // races the state and reads a null `on` (nullable by design, the
            // "not reported yet" case the screen tolerates).
            system.awaitStep("the light's controls and first state reach the tab", describe) {
                model.uiState.value.controls[SimulatedDeviceBridge.LIGHT_ID]?.on != null
            }
            val controls = model.uiState.value.controls.getValue(SimulatedDeviceBridge.LIGHT_ID)
            assertTrue(controls.canSetPower)
            assertTrue(controls.canSetLevel)
            assertEquals(false, controls.on, "the simulated light starts off")
            assertEquals(
                setOf(SimulatedDeviceBridge.LIGHT_ID, SimulatedDeviceBridge.SENSOR_ID)
                    .intersect(model.uiState.value.controls.keys),
                setOf(SimulatedDeviceBridge.LIGHT_ID),
                "a climate sensor advertises nothing actuable",
            )

            // 2. Flip the switch: an absolute setPower, straight from the VM.
            model.setDevicePower(SimulatedDeviceBridge.LIGHT_ID, on = true)
            assertEquals(
                DeviceCommandStatus.Pending,
                model.uiState.value.controls.getValue(SimulatedDeviceBridge.LIGHT_ID).status,
            )

            // 3. The hub executes it and the simulated light REALLY turns on.
            system.awaitStep("the hub turns the simulated light on", describe) {
                bridge.currentLight().on
            }
            assertEquals(1L, host.executedCommandCount)

            // 4. …and the echoed outcome resolves the row — the link a fake
            //    source cannot prove.
            system.awaitStep("the echoed outcome resolves the switch", describe) {
                model.uiState.value.controls[SimulatedDeviceBridge.LIGHT_ID]?.status ==
                    DeviceCommandStatus.Idle
            }
            assertEquals(
                true,
                model.uiState.value.controls.getValue(SimulatedDeviceBridge.LIGHT_ID).on,
            )

            // 5. Dim it: the level command travels the same path and the
            //    authoritative level comes back through the seam.
            model.setDeviceLevel(SimulatedDeviceBridge.LIGHT_ID, 45)
            system.awaitStep("the hub dims the simulated light to 45", describe) {
                bridge.currentLight().levelPercent == 45
            }
            assertEquals(2L, host.executedCommandCount)
            system.awaitStep("the echoed outcome resolves the dimmer", describe) {
                val c = model.uiState.value.controls[SimulatedDeviceBridge.LIGHT_ID]
                c?.status == DeviceCommandStatus.Idle && c.levelPercent == 45
            }
        }
    }

    /**
     * The interleaving behind the 30 s timeouts of the test above, forced: a bridge
     * reports the state a command caused while the executor is still recording that
     * command's outcome, and the report's write lands last.
     *
     * Deterministic because the report is parked on `nowMs` — the one injectable call
     * between its snapshot of "the last command" and its save — until the command has
     * either finished or is blocked on the host's publish lock. No ordering here depends
     * on time: [HANG_BOUND] only turns a wait that can no longer end into a failure that
     * names it. The host is never started: this test *is* its two streams.
     */
    @Test
    fun aStateReportRacingItsOwnCommandDoesNotEraseTheEchoedOutcome() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-home-control-race-test").toFile()
        var startedRuntime: HubRuntime? = null
        // Releases a parked report whatever way this test ends, so it never outlives it.
        val abandoned = AtomicBoolean(false)
        withBoundedTeardown({
            step("releasing a parked report") { abandoned.set(true) }
            step("shutting the hub runtime down") { startedRuntime?.shutdown() }
            step("shutting the test system down") { system.shutdown() }
            step("deleting the hub's data root") { dataRoot.deleteRecursively() }
        }) {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            bounded("bootstrapping the phone as realm host") {
                assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)
            }
            val runtime = assembleHub(system, dataRoot)
            startedRuntime = runtime
            bounded("starting the hub runtime") { runtime.start() }
            pairViaOfferFlow(phone, runtime)

            val bridge = SimulatedDeviceBridge(sensorTickMs = 60L * 60 * 1000)
            val reporter = AtomicReference<Thread?>(null)
            val commander = AtomicReference<Thread?>(null)
            val commandDone = AtomicBoolean(false)
            val reportParked = AtomicBoolean(false)
            val reportParkedOrOver = CountDownLatch(1)
            val reporterClockReads = AtomicInteger(0)
            val host = DeviceBridgeHost(
                runtime = runtime,
                bridge = bridge,
                publishGate = DeviceStatePublishGate(minIntervalMs = 0),
                nowMs = {
                    // The report reads the clock twice: for the gate, then for the
                    // row's `freshAsOf` — after it has read the last command.
                    if (Thread.currentThread() === reporter.get() &&
                        reporterClockReads.incrementAndGet() == 2
                    ) {
                        reportParked.set(true)
                        reportParkedOrOver.countDown()
                        while (!commandDone.get() &&
                            commander.get()?.state != Thread.State.BLOCKED
                        ) {
                            check(!abandoned.get()) {
                                "the test gave up while the report was parked before its save"
                            }
                            Thread.onSpinWait()
                        }
                    }
                    System.currentTimeMillis()
                },
            )
            val lightId = SimulatedDeviceBridge.LIGHT_ID
            host.publishInventory(bridge.devices().first())
            host.onBridgeState(BridgeDeviceState(deviceId = lightId, powerOn = false, levelPercent = 100))

            val commandId = "race-power-on"
            val hubDevices = runtime.client.homeDeviceService
            val command = bounded("issuing the command on the hub and reading its row back") {
                assertTrue(
                    hubDevices.setDevicePower(lightId, on = true, commandId = commandId) is TResult.Success,
                )
                hubDevices.deviceCommands.getAll().getOrNull()?.toList().orEmpty()
                    .single { it.commandId.get() == commandId }
            }

            val reportError = AtomicReference<Throwable?>(null)
            val commandError = AtomicReference<Throwable?>(null)
            val report = thread(name = "bridge-report", start = false, isDaemon = true) {
                try {
                    host.onBridgeState(BridgeDeviceState(deviceId = lightId, powerOn = true, levelPercent = 100))
                } catch (t: Throwable) {
                    reportError.set(t)
                } finally {
                    reportParkedOrOver.countDown()
                }
            }
            val execute = thread(name = "command-executor", start = false, isDaemon = true) {
                try {
                    runBlocking { host.handleCommand(command) }
                } catch (t: Throwable) {
                    commandError.set(t)
                } finally {
                    commandDone.set(true)
                }
            }
            // What each worker threw, or where it stands if it is still running.
            fun workers(): String = listOf(report to reportError, execute to commandError)
                .joinToString("\n") { (worker, error) ->
                    val thrown = error.get()
                    when {
                        thrown != null -> "${worker.name} threw $thrown"
                        worker.isAlive -> "${worker.name} is ${worker.state} at\n" +
                            worker.stackTrace.take(15).joinToString("\n") { "    at $it" }
                        worker.state == Thread.State.NEW -> "${worker.name} was never started"
                        else -> "${worker.name} finished"
                    }
                }
            fun raceFailed(what: String): Nothing = throw AssertionError(
                "$what\n${workers()}",
                reportError.get() ?: commandError.get(),
            )

            reporter.set(report)
            commander.set(execute)
            report.start()
            if (!reportParkedOrOver.await(HANG_BOUND.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                raceFailed("the bridge report neither reached its save nor returned within $HANG_BOUND")
            }
            if (reportError.get() != null) {
                raceFailed("the bridge report threw before reaching its save, so there is no race to force")
            }
            if (!reportParked.get()) {
                raceFailed(
                    "the bridge report returned without reaching its save, so there is no race to " +
                        "force — it read the clock ${reporterClockReads.get()} time(s); 1 means the " +
                        "gate did not publish it",
                )
            }
            execute.start()
            val giveUpAt = System.nanoTime() + HANG_BOUND.inWholeNanoseconds
            for (worker in listOf(report, execute)) {
                worker.join(((giveUpAt - System.nanoTime()) / 1_000_000).coerceAtLeast(1))
            }
            if (report.isAlive || execute.isAlive) {
                raceFailed("the report and the command did not both finish within $HANG_BOUND")
            }
            if (reportError.get() != null || commandError.get() != null) {
                raceFailed("a writer of the state row failed")
            }

            assertEquals(1L, host.executedCommandCount)
            val row = bounded("reading the light's state row on the hub") {
                hubDevices.getDeviceState(lightId)
            }
            assertEquals(true, row?.powerOnOrNull())
            assertEquals(
                commandId to DeviceState.OUTCOME_OK,
                row?.lastCommandId?.get() to row?.lastCommandOutcome?.get(),
                "the state report erased the outcome the issuer is waiting for",
            )
        }
    }

    /**
     * Runs [body], then every teardown step in order, each under [bounded] — so a teardown that
     * cannot finish fails the test naming that step, never hangs it.
     *
     * ⚠⚠ Teardown is where a wedged process shows: `shutdownAndJoin` joins every session's work, and
     * a coroutine stuck where cancellation cannot reach (a native call) never lets go. Unbounded, that
     * parked this class in `runBlocking` for 17 minutes holding the gate lock (`docs/tech-debt.md`,
     * "A Mac idle probe that never returned"). A teardown failure never replaces the body's own: it
     * rides along as suppressed.
     */
    private suspend fun withBoundedTeardown(
        teardown: Teardown.() -> Unit,
        body: suspend () -> Unit,
    ) {
        val failure = runCatching { body() }.exceptionOrNull()
        var teardownFailure: Throwable? = null
        for ((what, step) in Teardown().apply(teardown).steps) {
            try {
                bounded(what) { step() }
            } catch (t: Throwable) {
                when {
                    failure != null -> failure.addSuppressed(t)
                    teardownFailure == null -> teardownFailure = t
                    else -> teardownFailure.addSuppressed(t)
                }
            }
        }
        (failure ?: teardownFailure)?.let { throw it }
    }

    /** The teardown steps of [withBoundedTeardown], run in the order they are listed. */
    private class Teardown {
        val steps = mutableListOf<Pair<String, suspend () -> Unit>>()

        fun step(what: String, action: suspend () -> Unit) {
            steps += what to action
        }
    }

    /** A client call that says which one it was if it never returns. */
    private suspend fun <T> bounded(what: String, block: suspend () -> T): T =
        try {
            withTimeout(HANG_BOUND) { block() }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("'$what' did not return within $HANG_BOUND", e)
        }

    /**
     * [TestSystem.advanceUntil] that says what it was waiting for: a bare 30 s
     * cancellation names neither the link that broke nor how far the command got.
     */
    private suspend fun TestSystem.awaitStep(
        step: String,
        describe: suspend () -> String,
        predicate: suspend () -> Boolean,
    ) {
        try {
            advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds, predicate = predicate)
        } catch (e: TimeoutCancellationException) {
            // Bounded too: the description reads a row through the client, and a
            // failure report that hangs is worse than the bare timeout it replaces.
            val state = withTimeoutOrNull(HANG_BOUND) { describe() }
                ?: "(the state could not be read within $HANG_BOUND either)"
            throw AssertionError("'$step' did not hold within 30 s: $state", e)
        }
    }

    private companion object {
        /**
         * How long a wait that should take milliseconds may stand before it is reported
         * as a hang. Never the mechanism of an ordering, and one budget per wait: the
         * parked report has no clock of its own, it is released by whoever gives up.
         */
        val HANG_BOUND = 30.seconds
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
        val promptResult = bounded("the phone responding to the hub's offer") {
            phone.tenshuClient.syncService.respondToPairingOffer(offerUri)
        }
        assertTrue(promptResult is TResult.Success, "respondToPairingOffer failed: $promptResult")
        val approve = bounded("the phone approving the hub's offer") {
            phone.tenshuClient.syncService
                .approvePairingOffer(promptResult.getOrNull()!!.pairingSecretHash.get())
        }
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
