package app.tenshu.home.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Home domain model, M1 (docs/proposals/home-and-mia-apps.md §7). Plain classes — these
 * graduate to synced schema types when `:home-device-service` becomes real; per-user layout
 * (sort order) then rides the PreferenceService, not the shared record.
 */
data class Room(
    val id: String,
    val name: String,
)

/**
 * What kind of thing a device is. Drives the icon, the state rendering, and (later) the
 * per-category detail screen. [Hub] is the Tenshu Hub itself; [Storage] is a connected
 * storage surface (a Hub disk / expansion module — proposal §3, reading the
 * `HubManagementService` stubs once those are live).
 */
enum class DeviceKind {
    Hub,
    Storage,
    Speaker,
    Light,
    Camera,
    Thermostat,
    Sensor,
}

/**
 * Per-kind live state. A sealed hierarchy rather than fields-on-[Device] so each kind
 * carries exactly the state it has — the row renderer switches on this.
 */
sealed interface DeviceState {

    /** Speaker / Hub / camera style connectivity. */
    data class Connectivity(val online: Boolean) : DeviceState

    /** A lamp: on/off plus brightness when on. */
    data class Light(val on: Boolean, val brightnessPercent: Int) : DeviceState

    /**
     * Connected storage: usage against capacity. [live] is non-null exactly
     * when the figures come from a real hub-authored `HubStatus` row (the
     * `:hub-management-service-client` surface) — a null [live] means the
     * mock/default figures, which the UI must not dress up as real.
     */
    data class Storage(
        val usedBytes: Long,
        val totalBytes: Long,
        val live: LiveHubStorage? = null,
    ) : DeviceState {
        val freeBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)

        /** 0f..1f for a linear usage indicator. */
        val usedFraction: Float get() =
            if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    /**
     * The real-hub half of a [Storage] tile (hub proposal §13 H4): the hub's
     * name, the H3 replica figures, and the freshness signal the "as of" /
     * offline rendering derives from. [stale] flips when the hub has not
     * refreshed its row within the expected heartbeat window — the figures
     * are still shown (they were real), but marked as from an offline hub.
     */
    data class LiveHubStorage(
        val hubName: String,
        val replicaFileCount: Long,
        val replicaByteCount: Long,
        /** Epoch ms of the hub's last completed backup pass; `0` = none. */
        val lastBackupPassAtEpochMs: Long,
        /** Epoch ms (hub clock) at which the hub authored the snapshot. */
        val freshAsOfEpochMs: Long,
        val stale: Boolean,
    )

    /** A thermostat: current reading plus the target. Tenths of a degree Celsius. */
    data class Thermostat(val currentDeciC: Int, val targetDeciC: Int) : DeviceState

    /** A sensor's latest reading, already human-shaped by the (mock) source. */
    data class SensorReading(val summary: String) : DeviceState

    companion object {
        /** A plausible initial state for a freshly added (mock) device of [kind]. */
        fun defaultFor(kind: DeviceKind): DeviceState = when (kind) {
            DeviceKind.Hub, DeviceKind.Speaker, DeviceKind.Camera -> Connectivity(online = true)
            DeviceKind.Light -> Light(on = false, brightnessPercent = 60)
            DeviceKind.Storage -> Storage(usedBytes = 0, totalBytes = 1_000_000_000_000)
            DeviceKind.Thermostat -> Thermostat(currentDeciC = 210, targetDeciC = 210)
            DeviceKind.Sensor -> SensorReading(summary = "")
        }
    }
}

data class Device(
    val id: String,
    val name: String,
    /** Rooms are first-class in the model from day one (proposal D5); null = unassigned. */
    val roomId: String?,
    val kind: DeviceKind,
    val state: DeviceState,
    /** Position in the user's list; the repository keeps this contiguous from 0. */
    val sortIndex: Int,
)

/**
 * Formats a byte count for the storage row — "618 GB", "1 TB", "412 MB". Decimal units
 * (matching how storage products are sold), one decimal only when it adds signal.
 * Unit suffixes stay code-side like Timeline's "km" (punctuation-grade, ADR 0016 n=1).
 */
fun formatByteSize(bytes: Long): String {
    if (bytes < 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val whole = value.toInt()
    val tenth = ((value - whole) * 10).toInt()
    return if (unit == 0 || tenth == 0 || whole >= 10) "$whole ${units[unit]}"
    else "$whole.$tenth ${units[unit]}"
}

/**
 * Formats an "as of" instant for the storage tile — "14:05" when it falls on
 * today (local time), "2026-07-23 14:05" otherwise. Numeric-only on purpose:
 * locale-neutral punctuation-grade output, no month names to translate
 * (ADR 0016 n=1, same line as the code-side unit suffixes above).
 */
fun formatAsOfTime(
    epochMs: Long,
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(timeZone).date
    val time = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    return if (local.date == today) time else "${local.date} $time"
}

/** Formats tenths-of-°C as "21.5°" — the thermostat row's compact reading. */
fun formatDeciC(deciC: Int): String {
    val whole = deciC / 10
    val tenth = (if (deciC < 0) -deciC else deciC) % 10
    return if (tenth == 0) "$whole°" else "$whole.$tenth°"
}
