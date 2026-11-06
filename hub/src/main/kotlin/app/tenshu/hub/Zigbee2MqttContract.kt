package app.tenshu.hub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.HomeDevice

/**
 * The zigbee2mqtt wire contract (`home-device-service.md` HD-D3, HD-2) as
 * **pure functions**: topic algebra, payload parsing, capability/state
 * mapping, unit conversion, and the sanitisation of everything that arrives
 * over the air. [Zigbee2MqttBridge] is the I/O half; everything decidable
 * without a broker lives here so real recorded z2m payloads can be replayed
 * in unit tests.
 *
 * The contract we implement (zigbee2mqtt ≥ 1.30, MQTT topics under a
 * configurable base topic, default `zigbee2mqtt`):
 *
 *  - `<base>/bridge/devices` — **retained**, the full device list; our
 *    inventory source of truth (`ieee_address`, `friendly_name`,
 *    `definition.vendor/model/exposes`).
 *  - `<base>/bridge/state` — bridge `online`/`offline`.
 *  - `<base>/bridge/event` — `device_joined` / `device_interview` /
 *    `device_leave` (advisory: `bridge/devices` is republished anyway).
 *  - `<base>/<friendly_name>` — device state JSON.
 *  - `<base>/<friendly_name>/availability` — per-device reachability.
 *  - `<base>/<friendly_name>/set` — commands.
 *  - `<base>/bridge/request/permit_join` — `{"time": <seconds>}`.
 *
 * ⚠ **Identity vs. addressing.** State and command topics key off the
 * *friendly name*, which the user can rename at any time; our stable realm-wide
 * id keys off the **IEEE address** (`"zigbee2mqtt:0x00178801…"`). The mapping
 * is re-derived from every `bridge/devices` publish, so a rename moves the
 * topic without touching device identity (no row churn, no lost history).
 *
 * ⚠ **Everything here is untrusted input from the neighbourhood's airspace**
 * (threat model §8.20 / T-HD-04): names, vendor and model strings are
 * attacker-influenced and become realm rows rendered in every app. They are
 * length-capped and control-character-stripped by [sanitiseText] *before*
 * leaving this file, and unmapped attributes are bounded before entering the
 * `extras` slot.
 */
object Zigbee2Mqtt {

    /** The [HomeDevice.bridgeId] of every device this bridge reports. */
    const val BRIDGE_ID = HomeDevice.BRIDGE_ZIGBEE2MQTT

    /** zigbee2mqtt's own default base topic. */
    const val DEFAULT_BASE_TOPIC = "zigbee2mqtt"

    /** zigbee2mqtt's brightness scale — 0..254, NOT 0..255 (ZCL level control). */
    const val MAX_BRIGHTNESS = 254

    /** Hard cap on any single air-sourced text field (name / vendor / model). */
    const val MAX_TEXT_LENGTH = 64

    /** Hard cap on the serialised `extras` blob per row. */
    const val MAX_EXTRAS_LENGTH = 512

    /** Hard cap on how many unmapped attributes ride in `extras`. */
    const val MAX_EXTRAS_KEYS = 16

    /**
     * Hard cap on the ingested inventory size — a bounded realm-row budget
     * even if the airspace (or a malfunctioning sidecar) announces nonsense.
     */
    const val MAX_DEVICES = 512

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- topics ---------------------------------------------------------------------

    /** Bridge-scoped realm id for an IEEE address (`"zigbee2mqtt:0x0017…"`). */
    fun deviceId(ieeeAddress: String): String = "$BRIDGE_ID:$ieeeAddress"

    /** What an inbound topic means. Anything we do not model is [Inbound.Ignored]. */
    sealed interface Inbound {
        /** The retained full device list. */
        data object Devices : Inbound

        /** The bridge's own online/offline state. */
        data object BridgeState : Inbound

        /** A join/leave/interview event. */
        data object Event : Inbound

        /** A device state publish for [friendlyName]. */
        data class State(val friendlyName: String) : Inbound

        /** A per-device availability publish for [friendlyName]. */
        data class Availability(val friendlyName: String) : Inbound

        /** Our own `/set` echo, bridge logging, request/response, foreign topics. */
        data object Ignored : Inbound
    }

    /** Topic algebra for one configured [base] topic. */
    class Topics(val base: String = DEFAULT_BASE_TOPIC) {
        val devices: String = "$base/bridge/devices"
        val bridgeState: String = "$base/bridge/state"
        val event: String = "$base/bridge/event"
        val permitJoinRequest: String = "$base/bridge/request/permit_join"

        /** One wildcard subscription covers the whole contract (we filter). */
        val subscription: String = "$base/#"

        fun set(friendlyName: String): String = "$base/$friendlyName/set"

        /**
         * ⚠ Friendly names may themselves contain `/` (z2m allows nested
         * names), so the device-topic remainder is taken whole and resolved
         * against the known-name map by the caller — never split on `/`.
         */
        fun classify(topic: String): Inbound = when {
            topic == devices -> Inbound.Devices
            topic == bridgeState -> Inbound.BridgeState
            topic == event -> Inbound.Event
            topic.startsWith("$base/bridge/") -> Inbound.Ignored
            !topic.startsWith("$base/") -> Inbound.Ignored
            else -> {
                val rest = topic.removePrefix("$base/")
                when {
                    // Our own publishes come back to us (MQTT 3 has no no-local).
                    rest.endsWith("/set") || rest.endsWith("/get") -> Inbound.Ignored
                    rest.endsWith("/availability") ->
                        Inbound.Availability(rest.removeSuffix("/availability"))

                    rest.isBlank() -> Inbound.Ignored
                    else -> Inbound.State(rest)
                }
            }
        }
    }

    // ---- units ----------------------------------------------------------------------

    /**
     * z2m brightness (0..[MAX_BRIGHTNESS]) → our `levelPercent` (0..100).
     * Round-trips stably with [percentToBrightness] for every percent value
     * (254 > 100, so the mapping is injective) — pinned by test.
     */
    fun brightnessToPercent(raw: Int): Int {
        val clamped = raw.coerceIn(0, MAX_BRIGHTNESS)
        return (clamped * 100 + MAX_BRIGHTNESS / 2) / MAX_BRIGHTNESS
    }

    /** Our `levelPercent` (0..100) → z2m brightness (0..[MAX_BRIGHTNESS]). */
    fun percentToBrightness(percent: Int): Int {
        val clamped = percent.coerceIn(0, 100)
        return (clamped * MAX_BRIGHTNESS + 50) / 100
    }

    /** °C → tenths of a degree, the `DeviceState.temperatureDeciC` unit. */
    fun toDeciUnits(value: Double): Int = Math.round(value * 10).toInt()

    // ---- sanitisation (threat model §8.20 / T-HD-04) --------------------------------

    /**
     * Bound one air-sourced text field: control characters (including the
     * newlines and terminal escapes a hostile `friendly_name` would carry)
     * stripped, whitespace collapsed, trimmed, capped at [MAX_TEXT_LENGTH].
     * The result is safe to render as a row title in every app.
     */
    fun sanitiseText(raw: String?, maxLength: Int = MAX_TEXT_LENGTH): String {
        if (raw.isNullOrEmpty()) return ""
        val stripped = buildString(raw.length) {
            for (ch in raw) {
                // Category Cc/Cf plus anything below space: replaced with a
                // space rather than dropped, so "a\nb" cannot become "ab".
                // The two zero-width troublemakers (BOM, ZWSP) go the same way.
                val control = ch.isISOControl() || ch == '\uFEFF' || ch == '\u200B'
                append(if (control) ' ' else ch)
            }
        }
        val collapsed = stripped.split(' ').filter { it.isNotBlank() }.joinToString(" ")
        return if (collapsed.length <= maxLength) collapsed else collapsed.take(maxLength).trimEnd()
    }

    // ---- inventory ------------------------------------------------------------------

    /**
     * One device as `bridge/devices` reports it: the realm-facing
     * [BridgeDevice] plus the [friendlyName] its state/command topics use.
     */
    data class InventoryEntry(
        val ieeeAddress: String,
        val friendlyName: String,
        val device: BridgeDevice,
    )

    /**
     * Parse a `bridge/devices` payload. Never throws: a malformed payload
     * yields `null` (caller logs + keeps the previous inventory), a malformed
     * *entry* is skipped, and the coordinator entry is dropped (the bridge
     * announces itself as one synthetic row — see [bridgeSelfDevice]).
     */
    fun parseInventory(payload: String): List<InventoryEntry>? {
        val array = runCatching { json.parseToJsonElement(payload) as? JsonArray }.getOrNull()
            ?: return null
        return array.asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { parseInventoryEntry(it) }
            .take(MAX_DEVICES)
            .toList()
    }

    private fun parseInventoryEntry(obj: JsonObject): InventoryEntry? {
        val ieee = obj.string("ieee_address")?.takeIf { it.isNotBlank() } ?: return null
        val type = obj.string("type").orEmpty()
        // The coordinator is the radio, not a household device: it rides as
        // the bridge's own row so "Add device" has a stable target.
        if (type.equals("Coordinator", ignoreCase = true)) return null
        val friendlyRaw = obj.string("friendly_name")?.takeIf { it.isNotBlank() } ?: ieee
        val definition = obj["definition"] as? JsonObject
        val exposes = definition?.get("exposes") as? JsonArray
        val capabilities = capabilitiesOf(exposes)
        val extras = buildJsonObject {
            put("ieee_address", JsonPrimitive(sanitiseText(ieee, MAX_TEXT_LENGTH)))
            // The raw friendly name is the addressing key; keep it visible for
            // support ("which z2m entry is this row?") — sanitised like any
            // other air-sourced text.
            put("friendly_name", JsonPrimitive(sanitiseText(friendlyRaw)))
            obj.string("power_source")?.let { put("power_source", JsonPrimitive(sanitiseText(it))) }
            if (type.isNotBlank()) put("type", JsonPrimitive(sanitiseText(type, 32)))
            if (definition == null) put("supported", JsonPrimitive(false))
        }
        return InventoryEntry(
            ieeeAddress = ieee,
            // ⚠ NOT sanitised: this is the MQTT topic segment, and it must
            // match z2m byte for byte. It never reaches a realm row — the
            // sanitised copy in `extras` does.
            friendlyName = friendlyRaw,
            device = BridgeDevice(
                deviceId = deviceId(ieee),
                kind = kindOf(exposes, capabilities),
                manufacturer = sanitiseText(definition?.string("vendor")),
                model = sanitiseText(definition?.string("model")),
                capabilityIds = capabilities,
                defaultName = sanitiseText(friendlyRaw).ifBlank { ieee },
                extras = extras.toString().take(MAX_EXTRAS_LENGTH),
            ),
        )
    }

    /**
     * The bridge's own inventory row — id == [BRIDGE_ID] (a deliberate
     * exception to `"<bridgeId>:<local-id>"`: this row *is* the bridge). It
     * carries [HomeDevice.CAP_PERMIT_JOIN], which is how Home discovers that
     * a pairable bridge exists without hard-coding "zigbee2mqtt" in the UI,
     * and it is the `permitJoin` command's target.
     */
    fun bridgeSelfDevice(coordinatorModel: String = ""): BridgeDevice = BridgeDevice(
        deviceId = BRIDGE_ID,
        kind = HomeDevice.KIND_BRIDGE,
        manufacturer = "zigbee2mqtt",
        model = sanitiseText(coordinatorModel),
        capabilityIds = listOf(HomeDevice.CAP_PERMIT_JOIN),
        defaultName = "Zigbee bridge",
    )

    /** The coordinator's model string from a `bridge/devices` payload, if any. */
    fun coordinatorModel(payload: String): String {
        val array = runCatching { json.parseToJsonElement(payload) as? JsonArray }.getOrNull()
            ?: return ""
        val coordinator = array.asSequence()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("type").equals("Coordinator", ignoreCase = true) }
            ?: return ""
        return (coordinator["definition"] as? JsonObject)?.string("model")
            ?: coordinator.string("model_id")
            ?: ""
    }

    /**
     * z2m `exposes` → our `CAP_*` ids. Walks nested `features` (a `light`
     * expose carries `state`/`brightness`/`color_temp` as features).
     * Capabilities we do not model simply do not appear — the device is still
     * a device (its unmapped attributes ride `extras`).
     */
    fun capabilitiesOf(exposes: JsonArray?): List<String> {
        val names = LinkedHashSet<String>()
        collectExposeNames(exposes, names)
        val capabilities = LinkedHashSet<String>()
        if ("state" in names) capabilities += HomeDevice.CAP_POWER
        if ("brightness" in names) capabilities += HomeDevice.CAP_LEVEL
        if ("color_temp" in names) capabilities += HomeDevice.CAP_COLOR_TEMP
        if ("temperature" in names) capabilities += HomeDevice.CAP_TEMPERATURE
        if ("humidity" in names) capabilities += HomeDevice.CAP_HUMIDITY
        if ("battery" in names) capabilities += HomeDevice.CAP_BATTERY
        return capabilities.toList()
    }

    private fun collectExposeNames(exposes: JsonArray?, into: MutableSet<String>, depth: Int = 0) {
        if (exposes == null || depth > 4) return
        for (element in exposes) {
            val obj = element as? JsonObject ?: continue
            obj.string("name")?.let { into += it }
            obj.string("property")?.let { into += it }
            collectExposeNames(obj["features"] as? JsonArray, into, depth + 1)
        }
    }

    /** Device kind from the expose *types* (`light`/`switch`/`climate`), else the capabilities. */
    fun kindOf(exposes: JsonArray?, capabilities: List<String>): String {
        val types = (exposes ?: JsonArray(emptyList())).mapNotNull { (it as? JsonObject)?.string("type") }
        return when {
            "light" in types -> HomeDevice.KIND_LIGHT
            "switch" in types -> HomeDevice.KIND_PLUG
            "climate" in types -> HomeDevice.KIND_THERMOSTAT
            capabilities.any {
                it == HomeDevice.CAP_TEMPERATURE ||
                    it == HomeDevice.CAP_HUMIDITY ||
                    it == HomeDevice.CAP_BATTERY
            } -> HomeDevice.KIND_SENSOR

            exposes != null && exposes.isNotEmpty() -> HomeDevice.KIND_SENSOR
            else -> HomeDevice.KIND_OTHER
        }
    }

    // ---- state ----------------------------------------------------------------------

    /**
     * Merge a `<base>/<friendly_name>` state payload onto [previous]. z2m
     * normally publishes the full cached state, but a partial publish must not
     * *erase* a dimension, so present keys win and absent ones are inherited.
     *
     * Every attribute we do not model (linkquality, voltage, `update`,
     * `last_seen`, vendor-specific keys, …) lands in the bounded `extras`
     * JSON — never silently dropped. Returns `null` for a payload that is not
     * a JSON object (caller logs + skips; the flow survives).
     */
    fun mergeState(
        deviceId: String,
        payload: String,
        previous: BridgeDeviceState?,
    ): BridgeDeviceState? {
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return null
        val base = previous ?: BridgeDeviceState(deviceId = deviceId)
        var state = base.copy(deviceId = deviceId)
        val extras = LinkedHashMap<String, JsonPrimitive>()
        for ((key, value) in obj) {
            val primitive = value as? JsonPrimitive
            when (key) {
                "state" -> when (primitive?.content?.uppercase()) {
                    "ON" -> state = state.copy(powerOn = true)
                    "OFF" -> state = state.copy(powerOn = false)
                    else -> Unit // TOGGLE is a command word, never a report
                }

                "brightness" -> primitive?.number()?.let {
                    state = state.copy(levelPercent = brightnessToPercent(it.toInt()))
                }

                "color_temp" -> primitive?.number()?.let {
                    state = state.copy(colorTempMireds = it.toInt())
                }

                "temperature" -> primitive?.number()?.let {
                    state = state.copy(temperatureDeciC = toDeciUnits(it))
                }

                "humidity" -> primitive?.number()?.let {
                    state = state.copy(humidityPercent = it.toInt().coerceIn(0, 100))
                }

                "battery" -> primitive?.number()?.let {
                    state = state.copy(batteryPercent = it.toInt().coerceIn(0, 100))
                }

                "power" -> primitive?.number()?.let {
                    state = state.copy(powerDrawDeciW = toDeciUnits(it))
                }

                else -> if (primitive != null && extras.size < MAX_EXTRAS_KEYS) {
                    extras[sanitiseText(key, 32)] = JsonPrimitive(sanitiseText(primitive.content, 48))
                }
            }
        }
        return state.copy(extras = encodeExtras(extras, previous?.extras.orEmpty()))
    }

    /** `<friendly_name>/availability` payload → online. Accepts both z2m forms. */
    fun parseAvailability(payload: String): Boolean? {
        val trimmed = payload.trim().trim('"')
        if (trimmed.equals("online", ignoreCase = true)) return true
        if (trimmed.equals("offline", ignoreCase = true)) return false
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return null
        return when (obj.string("state")?.lowercase()) {
            "online" -> true
            "offline" -> false
            else -> null
        }
    }

    /** `bridge/state` payload → the bridge itself being up. Accepts both z2m forms. */
    fun parseBridgeState(payload: String): Boolean? = parseAvailability(payload)

    /**
     * `bridge/event` payload → a one-line log string, or `null` when the
     * payload is not an event we recognise. Advisory only: `bridge/devices`
     * is republished on every join/leave, so inventory never depends on this.
     */
    fun describeEvent(payload: String): String? {
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return null
        val type = obj.string("type") ?: return null
        val data = obj["data"] as? JsonObject
        val who = data?.string("friendly_name") ?: data?.string("ieee_address") ?: "?"
        val status = data?.string("status")?.let { " ($it)" }.orEmpty()
        return "${sanitiseText(type, 32)}: ${sanitiseText(who)}$status"
    }

    // ---- commands -------------------------------------------------------------------

    /**
     * Our capability verb → a zigbee2mqtt `/set` payload, or `null` when the
     * verb/argument pair is not expressible (the caller reports
     * `OUTCOME_UNSUPPORTED` / `OUTCOME_FAILED` — it never guesses).
     *
     * `setLevel 0` becomes an explicit `OFF`: a brightness of 0 is not a
     * reliable off across vendors, and our schema says level 0 means off.
     */
    fun setPayload(verb: String, argValue: String): String? = when (verb) {
        DeviceCommand.VERB_SET_POWER -> when (argValue) {
            DeviceCommand.ARG_ON -> """{"state":"ON"}"""
            DeviceCommand.ARG_OFF -> """{"state":"OFF"}"""
            else -> null
        }

        DeviceCommand.VERB_TOGGLE -> """{"state":"TOGGLE"}"""

        DeviceCommand.VERB_SET_LEVEL -> {
            val percent = argValue.trim().toIntOrNull()
            when {
                percent == null -> null
                percent <= 0 -> """{"state":"OFF"}"""
                else -> """{"state":"ON","brightness":${percentToBrightness(percent)}}"""
            }
        }

        else -> null
    }

    /** `bridge/request/permit_join` payload for a [seconds]-long window (0 = close). */
    fun permitJoinPayload(seconds: Int): String = """{"time":${seconds.coerceAtLeast(0)}}"""

    // ---- helpers --------------------------------------------------------------------

    private fun encodeExtras(
        extras: Map<String, JsonPrimitive>,
        previous: String,
    ): String {
        if (extras.isEmpty()) return previous
        val encoded = buildJsonObject { extras.forEach { (k, v) -> put(k, v) } }.toString()
        return if (encoded.length <= MAX_EXTRAS_LENGTH) encoded else previous
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content

    private fun JsonPrimitive.number(): Double? = doubleOrNull
}
