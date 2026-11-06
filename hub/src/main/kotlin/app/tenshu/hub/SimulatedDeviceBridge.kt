package app.tenshu.hub

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice

/**
 * Bridge #0 (`home-device-service.md` HD-D4): two fake devices with real
 * little state machines, proving the entire spine — bridge → rows → sync →
 * Home tab → command row → hub executor → bridge → state row — with zero
 * hardware. OFF by default in production; enabled via `--simulated-devices`
 * (see `Main`). HD-2 swaps in the real radio with no protocol change.
 *
 *  - **Dimmable light** (`simulated:light-1`): on/off + level 0–100; the
 *    `setPower`/`toggle`/`setLevel` verbs actually mutate it, so command
 *    round trips are honest.
 *  - **Climate sensor** (`simulated:sensor-1`): temperature + humidity +
 *    battery. The temperature *drifts* on a timer — a bounded random walk in
 *    small (sub-quantum) steps, exactly the chatty-sensor shape the
 *    publisher's change-gate/rate-limit exists for. Battery sags very slowly.
 *
 * Determinism: pass a seeded [random] (and drive [driftSensorNow] directly)
 * in tests; the wall-clock timer only runs between [start] and [stop].
 */
class SimulatedDeviceBridge(
    private val sensorTickMs: Long = DEFAULT_SENSOR_TICK_MS,
    private val random: Random = Random(System.nanoTime()),
    /**
     * HD-2 demo mode (OFF by default, so HD-1's fixtures are untouched):
     * announce a bridge row that advertises
     * [HomeDevice.CAP_PERMIT_JOIN] and let a fake bulb "join" a few seconds
     * into an open window. That makes Home's Add-device flow — permit-join →
     * countdown → a device appears — clickable end to end on a desktop with
     * no Zigbee radio in the building.
     */
    private val pairable: Boolean = false,
    private val joinDelayMs: Long = DEFAULT_JOIN_DELAY_MS,
    /**
     * Display names by role ([NAME_KEYS]: `light`, `sensor`, `bridge`), from
     * `--simulated-device-name` — for demo footage, where "Simulated light" reads as a
     * fake (store-screenshots.md §8.2). Only the name a row is announced with changes:
     * same ids, kinds and capabilities, so no row or wire shape moves. Absent ⇒ the
     * default names.
     */
    private val names: Map<String, String> = emptyMap(),
) : DeviceBridge {

    override val bridgeId: String = HomeDevice.BRIDGE_SIMULATED

    /** The dimmable light's state machine. */
    data class LightState(val on: Boolean = false, val levelPercent: Int = 100)

    /** The climate sensor's state machine. */
    data class SensorState(
        val temperatureDeciC: Int = 215, // 21.5 °C
        val humidityPercent: Int = 45,
        val batteryPercent: Int = 93,
        /**
         * Motion, for the RE-2 routines demo. ⚠ Reported through the row's
         * **extras**, not a typed field — which is exactly where zigbee2mqtt puts
         * `occupancy`, so a routine keyed on it exercises the same
         * extras-flattening path a real Zigbee sensor does.
         */
        val occupancy: Boolean = false,
    )

    private val light = MutableStateFlow(LightState())
    private val sensor = MutableStateFlow(SensorState())

    /** Seconds granted by the last [setPermitJoin] — test/CLI probe. */
    @Volatile
    var permitJoinSeconds: Int = 0
        private set

    private var driftJob: Job? = null
    private var joinJob: Job? = null
    private var bridgeScope: CoroutineScope? = null
    private var joinedCount = 0

    /** Ticks the light has been commanded — test probe for exactly-once. */
    @Volatile
    var executedCommandCount: Long = 0L
        private set

    private val deviceList = listOf(
        BridgeDevice(
            deviceId = LIGHT_ID,
            kind = HomeDevice.KIND_LIGHT,
            manufacturer = "Tenshu",
            model = "SimLight",
            capabilityIds = listOf(HomeDevice.CAP_POWER, HomeDevice.CAP_LEVEL),
            defaultName = names["light"] ?: "Simulated light",
        ),
        BridgeDevice(
            deviceId = SENSOR_ID,
            kind = HomeDevice.KIND_SENSOR,
            manufacturer = "Tenshu",
            model = "SimClimate",
            capabilityIds = listOf(
                HomeDevice.CAP_TEMPERATURE,
                HomeDevice.CAP_HUMIDITY,
                HomeDevice.CAP_BATTERY,
            ),
            defaultName = names["sensor"] ?: "Simulated climate sensor",
        ),
    )

    /** The bridge's own row (HD-2 shape) — present only in [pairable] mode. */
    private val selfDevice = BridgeDevice(
        deviceId = HomeDevice.BRIDGE_SIMULATED,
        kind = HomeDevice.KIND_BRIDGE,
        manufacturer = "Tenshu",
        model = "SimBridge",
        capabilityIds = listOf(HomeDevice.CAP_PERMIT_JOIN),
        defaultName = names["bridge"] ?: "Simulated bridge",
    )

    /** Bulbs that "joined" during an open pairing window (demo mode). */
    private val joined = MutableStateFlow<Map<String, LightState>>(emptyMap())

    private val devicesFlow = MutableStateFlow(
        if (pairable) listOf(selfDevice) + deviceList else deviceList,
    )

    override fun devices(): Flow<List<BridgeDevice>> = devicesFlow

    override fun states(): Flow<List<BridgeDeviceState>> =
        combineStates()

    private fun combineStates(): Flow<List<BridgeDeviceState>> =
        kotlinx.coroutines.flow.combine(light, sensor, joined) { l, s, j ->
            buildList {
                if (pairable) add(BridgeDeviceState(deviceId = selfDevice.deviceId, online = true))
                add(l.toBridgeState())
                add(s.toBridgeState())
                j.forEach { (id, state) ->
                    add(
                        BridgeDeviceState(
                            deviceId = id,
                            online = true,
                            powerOn = state.on,
                            levelPercent = state.levelPercent,
                        ),
                    )
                }
            }
        }

    override suspend fun execute(
        deviceId: String,
        verb: String,
        argValue: String,
    ): BridgeCommandResult {
        // The sensor is read-only; unknown devices can't be actuated.
        val isJoined = deviceId in joined.value
        if (deviceId != LIGHT_ID && !isJoined) {
            return BridgeCommandResult(DeviceState.OUTCOME_UNSUPPORTED)
        }
        val current = if (isJoined) joined.value.getValue(deviceId) else light.value
        val next = applyVerb(current, verb, argValue)
            ?: return BridgeCommandResult(
                if (verb in KNOWN_VERBS) DeviceState.OUTCOME_FAILED
                else DeviceState.OUTCOME_UNSUPPORTED,
            )
        if (isJoined) joined.update { it + (deviceId to next) } else light.value = next
        executedCommandCount += 1
        return BridgeCommandResult(
            DeviceState.OUTCOME_OK,
            BridgeDeviceState(
                deviceId = deviceId,
                online = true,
                powerOn = next.on,
                levelPercent = next.levelPercent,
            ),
        )
    }

    /** One verb applied to a light state; `null` = not applicable / bad argument. */
    private fun applyVerb(state: LightState, verb: String, argValue: String): LightState? =
        when (verb) {
            DeviceCommand.VERB_SET_POWER -> when (argValue) {
                DeviceCommand.ARG_ON -> state.copy(on = true)
                DeviceCommand.ARG_OFF -> state.copy(on = false)
                else -> null
            }

            DeviceCommand.VERB_TOGGLE -> state.copy(on = !state.on)

            DeviceCommand.VERB_SET_LEVEL -> argValue.toIntOrNull()?.let { level ->
                val clamped = level.coerceIn(0, 100)
                state.copy(levelPercent = clamped, on = clamped > 0)
            }

            else -> null
        }

    /**
     * HD-2's admission gate in miniature: records the granted window and, in
     * [pairable] demo mode, lets exactly one fake bulb join it — the desktop
     * stand-in for holding a real bulb's reset button.
     */
    override suspend fun setPermitJoin(seconds: Int): BridgeCommandResult {
        val bounded = seconds.coerceIn(0, DeviceCommand.PERMIT_JOIN_MAX_SECONDS)
        permitJoinSeconds = bounded
        if (!pairable || bounded <= 0) return BridgeCommandResult(DeviceState.OUTCOME_OK)
        val scope = bridgeScope ?: return BridgeCommandResult(DeviceState.OUTCOME_OK)
        joinJob?.cancel()
        joinJob = scope.launch {
            delay(joinDelayMs.coerceAtMost(bounded * 1_000L))
            joinSimulatedDevice()
        }
        return BridgeCommandResult(DeviceState.OUTCOME_OK)
    }

    /** Announce one more fake bulb (the demo-mode "a device joined" moment). */
    fun joinSimulatedDevice() {
        joinedCount += 1
        val id = "${HomeDevice.BRIDGE_SIMULATED}:joined-$joinedCount"
        joined.update { it + (id to LightState(on = true, levelPercent = 100)) }
        devicesFlow.update { devices ->
            devices + BridgeDevice(
                deviceId = id,
                kind = HomeDevice.KIND_LIGHT,
                manufacturer = "Tenshu",
                model = "SimBulb",
                capabilityIds = listOf(HomeDevice.CAP_POWER, HomeDevice.CAP_LEVEL),
                defaultName = "Simulated bulb $joinedCount",
            )
        }
    }

    override fun start(scope: CoroutineScope) {
        bridgeScope = scope
        if (driftJob?.isActive == true) return
        driftJob = scope.launch {
            var tick = 0L
            while (isActive) {
                try {
                    delay(sensorTickMs)
                    tick += 1
                    driftSensorNow(
                        // Bounded random walk in sub-quantum steps (±0.1 °C):
                        // most ticks stay inside the publisher's 0.5 °C
                        // quantum — the rate-limit's honest workload.
                        deltaDeciC = random.nextInt(-1, 2),
                        deltaHumidity = if (tick % 7 == 0L) random.nextInt(-1, 2) else 0,
                        batterySag = if (tick % BATTERY_SAG_EVERY_TICKS == 0L) 1 else 0,
                    )
                } catch (e: CancellationException) {
                    throw e
                }
            }
        }
    }

    override fun stop() {
        driftJob?.cancel()
        driftJob = null
        joinJob?.cancel()
        joinJob = null
        bridgeScope = null
    }

    /** Applies one drift step directly — the deterministic test hook. */
    fun driftSensorNow(deltaDeciC: Int, deltaHumidity: Int = 0, batterySag: Int = 0) {
        sensor.update {
            it.copy(
                temperatureDeciC = (it.temperatureDeciC + deltaDeciC).coerceIn(100, 350),
                humidityPercent = (it.humidityPercent + deltaHumidity).coerceIn(20, 80),
                batteryPercent = (it.batteryPercent - batterySag).coerceIn(0, 100),
            )
        }
    }

    /**
     * Reports motion (or its end) immediately — the RE-2 demo hook, symmetric
     * with [driftSensorNow].
     *
     * ⚠ Deliberately NOT on the drift timer: motion is an event, and a routine
     * that reacts to it must be provable without waiting on a cadence.
     */
    fun triggerMotion(occupied: Boolean) {
        sensor.value = sensor.value.copy(occupancy = occupied)
    }

    /** Current light state — test probe. */
    fun currentLight(): LightState = light.value

    /** Current sensor state — test probe. */
    fun currentSensor(): SensorState = sensor.value

    private fun LightState.toBridgeState() = BridgeDeviceState(
        deviceId = LIGHT_ID,
        online = true,
        powerOn = on,
        levelPercent = levelPercent,
    )

    private fun SensorState.toBridgeState() = BridgeDeviceState(
        deviceId = SENSOR_ID,
        online = true,
        temperatureDeciC = temperatureDeciC,
        humidityPercent = humidityPercent,
        batteryPercent = batteryPercent,
        // The z2m shape: unmapped attributes ride as a compact JSON object.
        extras = """{"occupancy":$occupancy}""",
    )

    companion object {
        /** The roles `--simulated-device-name` may name. */
        val NAME_KEYS = setOf("light", "sensor", "bridge")

        const val LIGHT_ID = "${HomeDevice.BRIDGE_SIMULATED}:light-1"
        const val SENSOR_ID = "${HomeDevice.BRIDGE_SIMULATED}:sensor-1"

        /** Sensor drift cadence (5 s) — chatty on purpose (the rate limit's workload). */
        const val DEFAULT_SENSOR_TICK_MS = 5_000L

        /** One battery percent every ~500 ticks (~40 min at the default cadence). */
        const val BATTERY_SAG_EVERY_TICKS = 500L

        /** How long a demo-mode "bulb" takes to join an open window (3 s). */
        const val DEFAULT_JOIN_DELAY_MS = 3_000L

        private val KNOWN_VERBS = setOf(
            DeviceCommand.VERB_SET_POWER,
            DeviceCommand.VERB_TOGGLE,
            DeviceCommand.VERB_SET_LEVEL,
        )
    }
}
