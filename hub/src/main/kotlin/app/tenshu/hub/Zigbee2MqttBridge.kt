package app.tenshu.hub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice

/**
 * Bridge #1 (`home-device-service.md` HD-D3 / HD-2): **Zigbee via a
 * zigbee2mqtt sidecar**. The hub is an MQTT *client* against a local
 * mosquitto — outbound-only even on the LAN, no listener, no coordinator
 * protocol of our own (rejected as pure liability). Radio work happens in two
 * proven upstream services deployed beside `tenshu-hub`; this class is the
 * translation layer between their MQTT contract and the [DeviceBridge] seam.
 *
 * All wire semantics live in the pure [Zigbee2Mqtt] object (parsing, mapping,
 * units, sanitisation); this class owns only I/O and lifecycle:
 *
 *  - **Inventory** from the *retained* `bridge/devices` topic, so a hub that
 *    starts long after the sidecar still derives the full device list on
 *    subscribe. Devices are keyed by **IEEE address**, never by friendly
 *    name — a user renaming a device in z2m moves its topics but keeps its
 *    realm identity (and its history, its room, its user-given name).
 *  - **State** from `<base>/<friendly_name>` publishes, resolved back to the
 *    IEEE address through the mapping the inventory maintains. States merge
 *    onto the last snapshot, so a partial publish never erases a dimension.
 *  - **Liveness**: `bridge/state` offline marks every device offline (the
 *    honest signal — the radio is gone); per-device `availability` topics
 *    (when the sidecar is configured to publish them) mark single devices.
 *  - **Reconnect**: the sidecar and the broker restart independently of the
 *    hub, so a supervisor loop reconnects with exponential backoff and
 *    **re-subscribes**; the retained topics then replay the world. Backoff is
 *    bridge behaviour, not library behaviour, precisely so it can be tested.
 *  - **Tolerance**: a malformed or unknown payload is logged and skipped — a
 *    single bad publish (or a device we do not model at all) must never kill
 *    the flow. Devices whose `exposes` we cannot map still appear, with their
 *    attributes in `extras`.
 *
 * **Permit-join** (HD-D7's admission gate) is addressed to the bridge's own
 * inventory row — `deviceId == bridgeId == "zigbee2mqtt"` — which advertises
 * [HomeDevice.CAP_PERMIT_JOIN]. It is always explicit, always time-bounded,
 * and clamped to [DeviceCommand.PERMIT_JOIN_MAX_SECONDS] here as well as in
 * the host: the network is never left open by default, and a request for an
 * unreasonable window is served a reasonable one.
 */
class Zigbee2MqttBridge(
    private val client: MqttClient,
    baseTopic: String = Zigbee2Mqtt.DEFAULT_BASE_TOPIC,
    private val backoff: Backoff = Backoff(),
    private val log: (String) -> Unit = { println("tenshu-hub: $it") },
) : DeviceBridge {

    override val bridgeId: String = Zigbee2Mqtt.BRIDGE_ID

    private val topics = Zigbee2Mqtt.Topics(baseTopic)

    /** IEEE address → the friendly name its topics currently use. */
    private val friendlyByIeee = LinkedHashMap<String, String>()

    /** Friendly name → IEEE address (rebuilt on every inventory publish). */
    private val ieeeByFriendly = LinkedHashMap<String, String>()

    private val devicesFlow = MutableStateFlow<List<BridgeDevice>>(emptyList())
    private val statesFlow = MutableStateFlow<List<BridgeDeviceState>>(emptyList())

    /** Latest state per realm device id — the merge base. */
    private val states = LinkedHashMap<String, BridgeDeviceState>()

    private val bridgeOnlineState = MutableStateFlow(false)

    private val lock = Any()

    private var supervisorJob: Job? = null
    private var readerJob: Job? = null

    /** Wall-clock ms at which the current permit-join window closes; 0 = closed. */
    @Volatile
    var permitJoinUntilMs: Long = 0L
        private set

    /** Reconnect attempts made — the backoff test's probe. */
    @Volatile
    var connectAttempts: Long = 0L
        private set

    override fun devices(): Flow<List<BridgeDevice>> = devicesFlow

    override fun states(): Flow<List<BridgeDeviceState>> = statesFlow

    /** The bridge's own liveness (`bridge/state`) — surfaced for the CLI banner. */
    fun bridgeOnline(): StateFlow<Boolean> = bridgeOnlineState.asStateFlow()

    override fun start(scope: CoroutineScope) {
        if (supervisorJob?.isActive == true) return
        // The reader starts FIRST: `messages()` is hot, and retained publishes
        // land the instant the subscription is acknowledged.
        readerJob = scope.launch {
            client.messages().collect { message ->
                try {
                    onMessage(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    log("zigbee2mqtt: dropped a message on ${message.topic}: $t")
                }
            }
        }
        supervisorJob = scope.launch {
            var attempt = 0
            while (isActive) {
                val connected = try {
                    connectAttempts += 1
                    client.connect()
                    client.subscribe(topics.subscription)
                    log("zigbee2mqtt: connected; subscribed to ${topics.subscription}")
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    log("zigbee2mqtt: connect failed ($t); retrying")
                    false
                }
                if (connected) {
                    attempt = 0
                    // Park until the broker drops us, then rebuild the world
                    // from the retained topics on the next pass.
                    client.connected().first { !it }
                    markAllOffline()
                    log("zigbee2mqtt: broker connection lost; reconnecting")
                }
                attempt += 1
                delay(backoff.delayMsFor(attempt))
            }
        }
    }

    override fun stop() {
        supervisorJob?.cancel()
        supervisorJob = null
        readerJob?.cancel()
        readerJob = null
    }

    override suspend fun execute(
        deviceId: String,
        verb: String,
        argValue: String,
    ): BridgeCommandResult {
        val friendlyName = synchronized(lock) {
            friendlyByIeee[deviceId.removePrefix("$bridgeId:")]
        } ?: return BridgeCommandResult(DeviceState.OUTCOME_UNSUPPORTED)
        val payload = Zigbee2Mqtt.setPayload(verb, argValue)
            ?: return BridgeCommandResult(DeviceState.OUTCOME_UNSUPPORTED)
        return try {
            client.publish(topics.set(friendlyName), payload)
            // The radio confirms asynchronously: z2m echoes the applied state
            // on the device topic, which flows back through `states()`. No
            // synchronous newState — that is exactly the shape the seam kept
            // open for this bridge.
            BridgeCommandResult(DeviceState.OUTCOME_OK, newState = null)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("zigbee2mqtt: publishing $verb to $friendlyName failed: $t")
            BridgeCommandResult(DeviceState.OUTCOME_FAILED)
        }
    }

    /**
     * Open (or close, at `seconds <= 0`) the radio's admission window.
     * Clamped to [DeviceCommand.PERMIT_JOIN_MAX_SECONDS] regardless of what
     * was asked (HD-D7: never leave the network open).
     */
    override suspend fun setPermitJoin(seconds: Int): BridgeCommandResult {
        val bounded = seconds.coerceIn(0, DeviceCommand.PERMIT_JOIN_MAX_SECONDS)
        return try {
            client.publish(topics.permitJoinRequest, Zigbee2Mqtt.permitJoinPayload(bounded))
            permitJoinUntilMs =
                if (bounded <= 0) 0L else System.currentTimeMillis() + bounded * 1_000L
            log(
                if (bounded <= 0) "zigbee2mqtt: permit-join closed"
                else "zigbee2mqtt: permit-join open for ${bounded}s",
            )
            BridgeCommandResult(DeviceState.OUTCOME_OK)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("zigbee2mqtt: permit-join request failed: $t")
            BridgeCommandResult(DeviceState.OUTCOME_FAILED)
        }
    }

    // ---- inbound ---------------------------------------------------------------------

    /** Route one inbound message. Never throws for content reasons. */
    internal fun onMessage(message: MqttMessage) {
        when (val inbound = topics.classify(message.topic)) {
            Zigbee2Mqtt.Inbound.Devices -> onInventory(message.payload)
            Zigbee2Mqtt.Inbound.BridgeState -> onBridgeState(message.payload)
            Zigbee2Mqtt.Inbound.Event ->
                Zigbee2Mqtt.describeEvent(message.payload)?.let { log("zigbee2mqtt: $it") }

            is Zigbee2Mqtt.Inbound.State -> onDeviceState(inbound.friendlyName, message.payload)
            is Zigbee2Mqtt.Inbound.Availability ->
                onAvailability(inbound.friendlyName, message.payload)

            Zigbee2Mqtt.Inbound.Ignored -> Unit
        }
    }

    private fun onInventory(payload: String) {
        val entries = Zigbee2Mqtt.parseInventory(payload)
        if (entries == null) {
            log("zigbee2mqtt: malformed bridge/devices payload ignored (inventory unchanged)")
            return
        }
        val self = Zigbee2Mqtt.bridgeSelfDevice(Zigbee2Mqtt.coordinatorModel(payload))
        synchronized(lock) {
            friendlyByIeee.clear()
            ieeeByFriendly.clear()
            for (entry in entries) {
                friendlyByIeee[entry.ieeeAddress] = entry.friendlyName
                ieeeByFriendly[entry.friendlyName] = entry.ieeeAddress
            }
        }
        // The bridge's own row leads the list: it is the permit-join target
        // and the only row that exists in an empty (never-paired) network.
        devicesFlow.value = listOf(self) + entries.map { it.device }
        // A device known to the inventory but with no state yet gets a
        // placeholder so it renders as "here, state pending" rather than not
        // at all; the first real publish merges onto it.
        val known = entries.map { it.device.deviceId } + self.deviceId
        var changed = false
        synchronized(lock) {
            for (deviceId in known) {
                if (states[deviceId] == null) {
                    states[deviceId] = BridgeDeviceState(
                        deviceId = deviceId,
                        online = if (deviceId == self.deviceId) bridgeOnlineState.value else true,
                    )
                    changed = true
                }
            }
        }
        if (changed) emitStates()
    }

    private fun onBridgeState(payload: String) {
        val online = Zigbee2Mqtt.parseBridgeState(payload) ?: return
        bridgeOnlineState.value = online
        log("zigbee2mqtt: bridge reports ${if (online) "online" else "offline"}")
        // The radio being down means every device behind it is unreachable —
        // say so rather than showing stale "on" lamps forever.
        synchronized(lock) {
            states.keys.toList().forEach { id ->
                states[id] = (states[id] ?: BridgeDeviceState(id)).copy(online = online)
            }
        }
        emitStates()
    }

    private fun onDeviceState(friendlyName: String, payload: String) {
        val ieee = synchronized(lock) { ieeeByFriendly[friendlyName] }
        if (ieee == null) {
            // A device we have not seen in `bridge/devices` yet (it is
            // republished on join, so this resolves itself) — or a foreign
            // topic under our base. Tolerated, never fatal.
            return
        }
        val deviceId = Zigbee2Mqtt.deviceId(ieee)
        val previous = synchronized(lock) { states[deviceId] }
        val merged = Zigbee2Mqtt.mergeState(deviceId, payload, previous)
        if (merged == null) {
            log("zigbee2mqtt: malformed state payload for $friendlyName ignored")
            return
        }
        synchronized(lock) { states[deviceId] = merged }
        emitStates()
    }

    private fun onAvailability(friendlyName: String, payload: String) {
        val online = Zigbee2Mqtt.parseAvailability(payload) ?: return
        val ieee = synchronized(lock) { ieeeByFriendly[friendlyName] } ?: return
        val deviceId = Zigbee2Mqtt.deviceId(ieee)
        synchronized(lock) {
            states[deviceId] = (states[deviceId] ?: BridgeDeviceState(deviceId)).copy(online = online)
        }
        emitStates()
    }

    private fun markAllOffline() {
        bridgeOnlineState.value = false
        synchronized(lock) {
            states.keys.toList().forEach { id ->
                states[id] = (states[id] ?: BridgeDeviceState(id)).copy(online = false)
            }
        }
        emitStates()
    }

    private fun emitStates() {
        statesFlow.value = synchronized(lock) { states.values.toList() }
    }

    /**
     * Exponential backoff with a ceiling — the sidecar restarting (or the Pi
     * booting mosquitto after us) must cost a couple of seconds, not a busy
     * loop, and a broker that is down for an hour must not be hammered.
     */
    data class Backoff(
        val initialMs: Long = 1_000L,
        val maxMs: Long = 60_000L,
        val factor: Int = 2,
    ) {
        fun delayMsFor(attempt: Int): Long {
            if (attempt <= 1) return initialMs
            var delay = initialMs
            repeat(attempt - 1) {
                delay = (delay * factor).coerceAtMost(maxMs)
            }
            return delay
        }
    }
}
