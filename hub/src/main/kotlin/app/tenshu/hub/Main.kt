package app.tenshu.hub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.routine.proxy.MutableLocalDeviceEventSource
import org.tenshu.routine.proxy.ObservedStateChange
import org.tenshu.routine.proxy.RoutineEventHost
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/*
 * `tenshu-hub` — the headless always-on Tenshu proxy for the Hub prototype
 * (hub proposal §13 H1 runtime + §11 H2 headless pairing). Runs `control-desktop`'s
 * production proxy assembly with no UI, pairs into a realm via the printed-static-key
 * hub offer (or the legacy terminal-QR offer as fallback), and then syncs until
 * terminated. Designed to run under systemd (`hub/deploy/tenshu-hub.service`,
 * `docs/how-to/hub-pi-deploy.md`).
 *
 * ```
 * tenshu-hub [run|pair|provision] [--data-dir DIR] [--relay-url URL] [--name NAME]
 *            [--sync-interval-ms N] [--invert-qr] [--legacy-offer-qr] [--rotate]
 * ```
 *
 *  - `run` (default): start headless. If unpaired, arm pairing first (re-arming with a
 *    fresh signed offer on timeout/denial) and continue once approved.
 *  - `pair`: interactive first-run pairing; exits 0 once paired (then start the service),
 *    1 on denial/timeout. No-ops if already paired.
 *  - `provision`: H2 factory-provisioning stand-in — mint (or re-print) the device
 *    identity, static signing key, and pairing secret, and emit the printable label
 *    (`pairing-uri.txt` + `pairing-qr.png` + ASCII QR to the terminal). Idempotent;
 *    `--rotate` regenerates the SECRET only (identity/signing key never rotate silently).
 *
 * Pairing mode: `run`/`pair` use the H2 printed-label arm when the data dir is
 * provisioned; `--legacy-offer-qr` (or an unprovisioned data dir, with a hint) falls
 * back to the H1 terminal-QR offer.
 *
 * Config resolution (flag → env → default):
 *  - data dir: `--data-dir` → `TENSHU_HUB_DATA_DIR` → `~/.local/share/tenshu-hub`
 *  - relay:    `--relay-url` → the inherited desktop seam (`TENSHU_RELAY_URL` /
 *              `-Dtenshu.relayUrl` → production relay), same as `control-desktop`.
 */
/**
 * Deliberate-restart exit code (H4 `RestartHub(mode="proxy")`): distinct from
 * crash exits so the systemd unit's comments can name it. Under
 * `Restart=always` (see `hub/deploy/tenshu-hub.service`) any exit re-spawns
 * the service; the distinct code makes the journal read as an intentional,
 * management-initiated restart.
 */
const val EXIT_RESTART = 10

fun main(args: Array<String>) {
    val options = HubOptions.parse(args) ?: run {
        System.err.println(HubOptions.USAGE)
        exitProcess(2)
    }

    // The relay flag rides the existing desktop seam: the platform module reads
    // TENSHU_RELAY_URL / -Dtenshu.relayUrl (PlatformProxyModule "syncRelayUrl").
    options.relayUrl?.let { System.setProperty("tenshu.relayUrl", it) }

    // HD-D2 v1: with bridged devices present, default the sync cadence to the
    // fast poll so a phone-issued DeviceCommand lands within ~one interval
    // (explicit --sync-interval-ms always wins; relay long-poll = HD-4).
    val effectiveSyncIntervalMs = options.syncIntervalMs
        ?: DeviceBridgeHost.DEVICE_FAST_POLL_INTERVAL_MS.takeIf { options.hasBridges }

    val runtime = HubRuntime.assemble(
        dataRoot = options.dataDir,
        syncIntervalMs = effectiveSyncIntervalMs,
    )

    if (options.command == HubCliCommand.PROVISION) {
        runProvision(runtime, options)
        return
    }

    val keepRunning = runBlocking {
        runtime.start()
        println("tenshu-hub: data dir ${options.dataDir.absolutePath}")
        println("tenshu-hub: relay ${describeRelay()}")
        println("tenshu-hub: ${if (runtime.isPaired()) "paired (owner ${runtime.syncManager.currentOwnerId})" else "not paired"}")

        if (options.command == HubCliCommand.PAIR && runtime.isPaired()) {
            println("Already paired — nothing to do. (To re-pair, wipe the data dir and restart.)")
            runtime.shutdown()
            return@runBlocking false
        }

        if (!runtime.isPaired()) {
            // v1 arming = process start / the `pair` subcommand (bounded window per
            // session, re-armed below). A physical GPIO arming button + status LED
            // (Hue-button model, hub proposal §11.3) is a later Pi concern and would
            // gate this block instead of process start.
            val printedUri = if (options.legacyOfferQr) null
            else HubProvisioning.printedUri(options.dataDir, runtime.syncManager)
            if (printedUri == null && !options.legacyOfferQr) {
                println("[hint] This data dir is not provisioned (no printed label); using the legacy")
                println("[hint] terminal-QR offer. Run `tenshu-hub provision` for printed-label pairing.")
            }
            val cli = HubPairingCli(runtime.client, invertQr = options.invertQr)
            pairing@ while (true) {
                val outcome =
                    if (printedUri != null) cli.runHubOnce(printedUri, options.deviceName)
                    else cli.runOnce(options.deviceName)
                when (outcome) {
                    is PairingOutcome.Approved -> break@pairing
                    is PairingOutcome.Denied, is PairingOutcome.TimedOut -> {
                        // ⚠⚠ The CLI's deadline bounds its WAIT, not the operation
                        // (`HubPairingCli.withSessionDeadline`), so "timed out" and "paired"
                        // are not exclusive: an orphaned late `getOfferStatus` installs keys
                        // on approval whether or not anyone is still awaiting it, and an
                        // approval landing as the deadline expires does the same. Without this
                        // check a paired hub re-arms forever and never starts its runtime, and
                        // `pair` exits 1 on a pairing that actually succeeded.
                        if (runtime.isPaired()) {
                            println("[approved] Keys were installed after the wait expired.")
                            break@pairing
                        }
                        if (options.command == HubCliCommand.PAIR) {
                            runtime.shutdown()
                            exitProcess(1)
                        }
                        println("[re-arming] Minting a fresh pairing offer...")
                    }
                    is PairingOutcome.Failed -> {
                        System.err.println("[error] ${outcome.reason}")
                        if (options.command == HubCliCommand.PAIR) {
                            runtime.shutdown()
                            exitProcess(1)
                        }
                        println("[re-arming] Retrying in 10s (is the relay reachable?)...")
                        delay(10.seconds)
                    }
                }
            }
            if (options.command == HubCliCommand.PAIR) {
                println("Pairing complete. Start the hub service now (systemctl start tenshu-hub).")
                runtime.shutdown()
                return@runBlocking false
            }
        }

        // H3 (hub proposal §12): the paired hub is the realm's keep-everything
        // replica — pin the device-scoped KEEP_OFFLINE policy and drain the
        // offline keeper periodically so every file's bytes land on local disk.
        runtime.startBackupLoop(options.backupIntervalMs ?: HubRuntime.DEFAULT_BACKUP_INTERVAL_MS)
        // H4 (hub proposal §8, as-built): publish the HubStatus row + execute
        // synced HubCommands. A `RestartHub(mode="proxy")` exits with
        // EXIT_RESTART for the supervisor to re-spawn (tenshu-hub.service).
        val agent = HubManagementAgent(
            runtime = runtime,
            deviceName = options.deviceName,
            restartHook = {
                runBlocking { withTimeoutOrNull(10_000) { runtime.shutdown() } }
                exitProcess(EXIT_RESTART)
            },
        )
        agent.start()
        agent.publishStatus(force = true)
        // HD-1 (home-device-service.md HD-D4): the simulated device bridge —
        // OFF by default in production, enabled via --simulated-devices. Its
        // devices are REAL synced rows (Home's mock stays the client-side
        // fallback for realms with no bridged devices).
        // RE-2 (routines-engine.md RE-D11): the hub is the one process that can
        // observe a device event in milliseconds, so it is the one process where
        // device-event routines arm. The source is shared by every bridge host —
        // one engine, one process-scoped safety gate, regardless of how many
        // radios are attached.
        val routineEvents = MutableLocalDeviceEventSource()
        routineEventHost = RoutineEventHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            executionContext = hubExecutionContext(runtime),
            implementationFactory = hubExecutionContext(runtime).implementationFactory,
            source = routineEvents,
            nowEpochMs = { System.currentTimeMillis() },
            thisDeviceId = { runtime.syncManager.currentDeviceId },
        ).also { it.start() }

        fun emitRoutineEvent(state: BridgeDeviceState, causedByCommandId: String) {
            routineEvents.emit(
                ObservedStateChange(
                    deviceId = state.deviceId,
                    fields = buildMap {
                        put("online", state.online.toString())
                        state.powerOn?.let { put("power", if (it) "on" else "off") }
                        state.levelPercent?.let { put("levelPercent", it.toString()) }
                        state.temperatureDeciC?.let { put("temperatureDeciC", it.toString()) }
                        state.humidityPercent?.let { put("humidityPercent", it.toString()) }
                        state.batteryPercent?.let { put("batteryPercent", it.toString()) }
                        // ⚠ Sensor semantics the typed fields do not model
                        // (occupancy, contact) ride the bridge's extras; a
                        // motion routine reads them by name.
                        // zigbee2mqtt reports occupancy/contact/action in the
                        // extras object rather than a typed field, and those are
                        // exactly what a motion routine keys on — so the fast
                        // path reads them by name.
                        putAll(flattenExtras(state.extras))
                    },
                    freshAsOfEpochMs = System.currentTimeMillis(),
                    lastCommandId = causedByCommandId,
                ),
            )
        }

        if (options.simulatedDevices) {
            // `pairable`: the demo bridge advertises permit-join and lets a
            // fake bulb join an open window, so Home's HD-2 Add-device flow is
            // clickable end to end with no radio present.
            val host = DeviceBridgeHost(
                runtime = runtime,
                bridge = SimulatedDeviceBridge(pairable = true, names = options.simulatedDeviceNames),
                journalFile = File(options.dataDir, "consumed-device-commands.log"),
                onDeviceEvent = ::emitRoutineEvent,
            )
            host.start()
            deviceHosts += host
            println("[devices] Simulated device bridge up (fast poll ${effectiveSyncIntervalMs ?: "default"} ms).")
        }
        // HD-2 (home-device-service.md HD-D3): the real Zigbee bridge — an MQTT
        // client against the local mosquitto that fronts the zigbee2mqtt
        // sidecar. Outbound-only, no listener; see docs/how-to/hub-pi-deploy.md.
        if (options.zigbee2mqtt) {
            val mqtt = PahoMqttClient(
                serverUri = options.mqttUrl,
                clientId = PahoMqttClient.clientIdFor(options.deviceName),
                // Credentials ride the environment, never argv: /proc/<pid>/cmdline
                // is world-readable on Linux.
                username = System.getenv("TENSHU_MQTT_USERNAME")?.takeIf { it.isNotBlank() },
                password = System.getenv("TENSHU_MQTT_PASSWORD")?.takeIf { it.isNotBlank() },
            )
            val host = DeviceBridgeHost(
                runtime = runtime,
                bridge = Zigbee2MqttBridge(client = mqtt, baseTopic = options.mqttBaseTopic),
                journalFile = File(options.dataDir, "consumed-zigbee-commands.log"),
                onDeviceEvent = ::emitRoutineEvent,
            )
            host.start()
            deviceHosts += host
            println(
                "[devices] zigbee2mqtt bridge up (broker ${options.mqttUrl}, " +
                    "base topic ${options.mqttBaseTopic}, fast poll " +
                    "${effectiveSyncIntervalMs ?: "default"} ms).",
            )
        }
        println("[running] Headless proxy up; owner ${runtime.syncManager.currentOwnerId}. Send SIGTERM/Ctrl+C to stop.")
        true
    }

    if (keepRunning) {
        awaitTermination(runtime, deviceHosts)
    }
}

/** Filled inside the runBlocking bootstrap; stopped before runtime shutdown. */
private val deviceHosts = mutableListOf<DeviceBridgeHost>()

/** RE-2's fast path; stopped alongside the bridge hosts on SIGTERM. */
private var routineEventHost: RoutineEventHost? = null

/** `provision` subcommand: mint/re-print the H2 label material, then exit 0. */
private fun runProvision(runtime: HubRuntime, options: HubOptions) {
    val label = HubProvisioning.provision(
        dataRoot = options.dataDir,
        syncManager = runtime.syncManager,
        rotate = options.rotate,
    )
    println("tenshu-hub: data dir ${options.dataDir.absolutePath}")
    println(
        when {
            options.rotate -> "[provision] Pairing secret ROTATED — previously printed labels are now void."
            label.generatedSecret -> "[provision] New pairing secret generated."
            else -> "[provision] Already provisioned — re-printing the existing label (use --rotate to mint a new secret)."
        }
    )
    println()
    println(AsciiQr.render(label.uri, invert = options.invertQr))
    println("Label URI:    ${label.uri}")
    println("Device id:    ${label.deviceId}")
    // Matter-style manual-entry fallback for a damaged code (§11.3).
    println("Manual code:  ${label.secretBase32.chunked(4).joinToString("-")}")
    println("Artifacts:    ${label.uriFile.absolutePath}")
    println("              ${label.qrFile.absolutePath}")
    println()
    println("Print pairing-qr.png and attach it to the device. Scanning it with a paired")
    println("Tenshu device pairs this hub whenever the hub is armed (run/pair while unpaired).")
    runBlocking { runtime.shutdown() }
}

/** Blocks the main thread until SIGTERM/SIGINT, then quiesces the proxy (bounded). */
private fun awaitTermination(runtime: HubRuntime, deviceHosts: List<DeviceBridgeHost> = emptyList()) {
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("tenshu-hub: shutting down...")
            runBlocking {
                withTimeoutOrNull(10_000) {
                    deviceHosts.forEach { it.stop() }
                    routineEventHost?.stop()
                    runtime.shutdown()
                }
            }
            stopped.countDown()
        },
    )
    stopped.await()
}

private fun describeRelay(): String =
    System.getenv("TENSHU_RELAY_URL")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("tenshu.relayUrl")?.takeIf { it.isNotBlank() }
        ?: "https://relay.tenshu.app (default)"

internal enum class HubCliCommand { RUN, PAIR, PROVISION }

internal data class HubOptions(
    val command: HubCliCommand,
    val dataDir: File,
    val relayUrl: String?,
    val deviceName: String,
    val syncIntervalMs: Long?,
    val backupIntervalMs: Long?,
    val invertQr: Boolean,
    val legacyOfferQr: Boolean,
    val rotate: Boolean,
    val simulatedDevices: Boolean,
    val zigbee2mqtt: Boolean,
    val mqttUrl: String,
    val mqttBaseTopic: String,
    /** `--simulated-device-name role=Name`, by role (SimulatedDeviceBridge.NAME_KEYS). */
    val simulatedDeviceNames: Map<String, String> = emptyMap(),
) {
    /** Whether any [DeviceBridge] is enabled (drives the HD-D2 fast-poll default). */
    val hasBridges: Boolean get() = simulatedDevices || zigbee2mqtt

    companion object {
        val USAGE = """
            usage: tenshu-hub [run|pair|provision] [options]
              --data-dir DIR          durable state directory
                                      (default: ${'$'}TENSHU_HUB_DATA_DIR or ~/.local/share/tenshu-hub)
              --relay-url URL         sync relay origin (default: ${'$'}TENSHU_RELAY_URL or production)
              --name NAME             device name shown in the approval prompt (default: "Tenshu Hub")
              --sync-interval-ms N    sync scheduler cadence, N > 0 (default: production 30000)
              --backup-interval-ms N  keep-everything backup (offline keeper) cadence, N > 0
                                      (default: 900000 = 15 min)
              --invert-qr             flip QR polarity for light-themed terminals
              --legacy-offer-qr       pair via the H1 terminal-QR offer instead of the printed label
              --rotate                (provision) regenerate the pairing secret, voiding printed labels
              --simulated-devices     bridge the simulated devices (HD-1 dev/demo; OFF by default)
              --simulated-device-name ROLE=NAME
                                      the name a simulated device is announced with; ROLE is
                                      light, sensor or bridge (repeatable; needs --simulated-devices)
              --zigbee2mqtt           bridge Zigbee devices via a local zigbee2mqtt sidecar
                                      (HD-2; OFF by default — see docs/how-to/hub-pi-deploy.md)
              --mqtt-url URL          MQTT broker (default: ${'$'}TENSHU_MQTT_URL or tcp://127.0.0.1:1883)
              --mqtt-base-topic T     zigbee2mqtt base topic (default: ${'$'}TENSHU_MQTT_BASE_TOPIC or zigbee2mqtt)
                                      Broker credentials, if any, ride ${'$'}TENSHU_MQTT_USERNAME /
                                      ${'$'}TENSHU_MQTT_PASSWORD — never argv (/proc is world-readable).
        """.trimIndent()

        /**
         * A cadence flag's value, or `null` (⇒ usage) when it is missing, non-numeric, or not > 0.
         *
         * ⚠ Zero and negatives are rejected on purpose, not merely "malformed": they reach
         * `SyncScheduler`'s `awaitOrTimeout`, where `withTimeoutOrNull` returns at once for a
         * non-positive timeout, and the loop spins — on a paired hub, polling the relay as fast as
         * requests complete. They were harmless only while the knob was silently dropped
         * (docs/tech-debt.md, "The hub's sync cadence override never reached its scheduler").
         * `--backup-interval-ms` feeds a bare `delay(…)` and gets the same rule.
         */
        private fun positiveMillis(flag: String, raw: String?): Long? {
            val value = raw?.toLongOrNull()
            if (value == null || value <= 0) {
                System.err.println("$flag expects a positive number of milliseconds, got: ${raw ?: "<missing>"}")
                return null
            }
            return value
        }

        /** Returns `null` on any malformed argument (caller prints [USAGE]). */
        fun parse(args: Array<String>): HubOptions? {
            var command = HubCliCommand.RUN
            var dataDir: String? = null
            var relayUrl: String? = null
            var deviceName = "Tenshu Hub"
            var syncIntervalMs: Long? = null
            var backupIntervalMs: Long? = null
            var invertQr = false
            var legacyOfferQr = false
            var rotate = false
            var simulatedDevices = false
            var zigbee2mqtt = false
            var mqttUrl: String? = null
            var mqttBaseTopic: String? = null
            val simulatedDeviceNames = mutableMapOf<String, String>()

            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "run" -> command = HubCliCommand.RUN
                    "pair" -> command = HubCliCommand.PAIR
                    "provision" -> command = HubCliCommand.PROVISION
                    "--data-dir" -> dataDir = args.getOrNull(++i) ?: return null
                    "--relay-url" -> relayUrl = args.getOrNull(++i) ?: return null
                    "--name" -> deviceName = args.getOrNull(++i) ?: return null
                    "--sync-interval-ms" ->
                        syncIntervalMs = positiveMillis(arg, args.getOrNull(++i)) ?: return null
                    "--backup-interval-ms" ->
                        backupIntervalMs = positiveMillis(arg, args.getOrNull(++i)) ?: return null
                    "--invert-qr" -> invertQr = true
                    "--legacy-offer-qr" -> legacyOfferQr = true
                    "--rotate" -> rotate = true
                    "--simulated-devices" -> simulatedDevices = true
                    "--zigbee2mqtt" -> zigbee2mqtt = true
                    "--simulated-device-name" -> {
                        val raw = args.getOrNull(++i) ?: return null
                        val role = raw.substringBefore('=', "")
                        val name = raw.substringAfter('=', "").trim()
                        if (role !in SimulatedDeviceBridge.NAME_KEYS || name.isEmpty()) {
                            System.err.println(
                                "--simulated-device-name expects ROLE=NAME with ROLE one of " +
                                    "${SimulatedDeviceBridge.NAME_KEYS.sorted()}, got: $raw",
                            )
                            return null
                        }
                        simulatedDeviceNames[role] = name
                    }
                    "--mqtt-url" -> mqttUrl = args.getOrNull(++i) ?: return null
                    "--mqtt-base-topic" -> mqttBaseTopic = args.getOrNull(++i) ?: return null
                    "--help", "-h" -> return null
                    else -> {
                        System.err.println("unknown argument: $arg")
                        return null
                    }
                }
                i++
            }
            if (simulatedDeviceNames.isNotEmpty() && !simulatedDevices) {
                System.err.println("--simulated-device-name needs --simulated-devices")
                return null
            }
            if (rotate && command != HubCliCommand.PROVISION) {
                System.err.println("--rotate only applies to the provision subcommand")
                return null
            }

            val resolvedDataDir = dataDir
                ?: System.getenv("TENSHU_HUB_DATA_DIR")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share/tenshu-hub")

            return HubOptions(
                command = command,
                dataDir = File(resolvedDataDir),
                relayUrl = relayUrl,
                deviceName = deviceName,
                syncIntervalMs = syncIntervalMs,
                backupIntervalMs = backupIntervalMs,
                invertQr = invertQr,
                legacyOfferQr = legacyOfferQr,
                rotate = rotate,
                simulatedDevices = simulatedDevices,
                zigbee2mqtt = zigbee2mqtt,
                mqttUrl = mqttUrl
                    ?: System.getenv("TENSHU_MQTT_URL")?.takeIf { it.isNotBlank() }
                    ?: PahoMqttClient.DEFAULT_BROKER_URL,
                mqttBaseTopic = mqttBaseTopic
                    ?: System.getenv("TENSHU_MQTT_BASE_TOPIC")?.takeIf { it.isNotBlank() }
                    ?: Zigbee2Mqtt.DEFAULT_BASE_TOPIC,
                simulatedDeviceNames = simulatedDeviceNames.toMap(),
            )
        }
    }
}

/** The hub's own execution context — the same accessor `DeviceBridgeHost` uses. */
private fun hubExecutionContext(runtime: HubRuntime) =
    runtime.koin.get<ClientRegistry>().getExecutionContext(HubRuntime.PACKAGE_NAME)

/**
 * Flattens a bridge's compact extras object into the flat string map the engine
 * matches against. ⚠ Values are stringified as they appear (`true`, `42`), which
 * is what `StateMatch` compares — a quoted JSON string would never equal `true`.
 */
private fun flattenExtras(extras: String): Map<String, String> {
    if (extras.isBlank()) return emptyMap()
    return runCatching {
        Json.parseToJsonElement(extras).jsonObject.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.content ?: value.toString()
        }
    }.getOrElse { emptyMap() }
}
