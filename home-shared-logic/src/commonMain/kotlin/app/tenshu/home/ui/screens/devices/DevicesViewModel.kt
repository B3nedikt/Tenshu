package app.tenshu.home.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.home.data.DeviceControlSource
import app.tenshu.home.data.DevicePairingSource
import app.tenshu.home.data.DeviceSource
import app.tenshu.home.data.HomeDevicesRepository
import app.tenshu.home.data.SyncedDeviceInfo
import app.tenshu.home.model.Device
import app.tenshu.home.model.DeviceKind
import app.tenshu.home.model.Room
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.generateId
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState as SyncedDeviceState
import org.tenshu.home.device.HomeDevice

data class DevicesUiState(
    val devices: List<Device> = emptyList(),
    val rooms: List<Room> = emptyList(),
    /** True once the first repository emission landed — gates the empty state vs. loading. */
    val loaded: Boolean = false,
    /** HD-2 "Add device" — see [PairingUiState]. */
    val pairing: PairingUiState = PairingUiState(),
    /**
     * Per-device actuation state, keyed by `Device.id`. A device **absent from
     * this map has no controls** — that is every mock row (the mock household
     * is a fallback, not a switchable house) and every real device that
     * advertises no actuable capability (a temperature sensor).
     */
    val controls: Map<String, DeviceControls> = emptyMap(),
)

/**
 * What the user may do to one real device, and how the last attempt is going.
 *
 * [on] / [levelPercent] are what the control should *render*: the synced state
 * normally, the requested value while a command is in flight — so a switch does
 * not snap back for the seconds it takes a hub to poll, act, and publish.
 */
data class DeviceControls(
    val canSetPower: Boolean = false,
    val canSetLevel: Boolean = false,
    val on: Boolean? = null,
    val levelPercent: Int? = null,
    val status: DeviceCommandStatus = DeviceCommandStatus.Idle,
) {
    val busy: Boolean get() = status == DeviceCommandStatus.Pending

    /** Whether a note about the last attempt should be shown under the row. */
    val hasNote: Boolean get() =
        status == DeviceCommandStatus.Failed || status == DeviceCommandStatus.Unconfirmed
}

/**
 * How the last command for a device ended. The distinction between [Failed] and
 * [Unconfirmed] is deliberate and load-bearing: one is something we know went
 * wrong, the other is us admitting we do not know.
 */
enum class DeviceCommandStatus {
    Idle,

    /** Issued; the hub has not reported an outcome for it yet. */
    Pending,

    /** Could not be issued at all, or the hub reported `failed`/`unsupported`. */
    Failed,

    /**
     * No outcome arrived within [DevicesViewModel.COMMAND_OUTCOME_TIMEOUT_MS].
     * The command row is durable and the hub may still act on it (within its
     * own 2-minute staleness window), so this is *not* a failure — it is "we
     * stopped waiting". Typically an offline or slow-polling hub.
     */
    Unconfirmed,
}

/**
 * The Add-device flow's state (`home-device-service.md` HD-2). A pairing
 * window is **explicit and time-bounded** (HD-D7), so this is a countdown,
 * not a mode: it starts when the user asks, it ends on its own, and while it
 * runs the newly-joined devices are named as they arrive.
 *
 * [bridgeId] is non-null exactly when a bridge advertising
 * [HomeDevice.CAP_PERMIT_JOIN] is present in the realm — i.e. when real
 * pairing is possible at all. With no hub (or a mock household) it stays
 * null and the tab keeps its plain mock add-a-device dialog: Home never
 * offers a pairing flow it cannot honour.
 */
data class PairingUiState(
    val bridgeId: String? = null,
    val bridgeName: String = "",
    /** True while the radio's admission window is (as far as we know) open. */
    val open: Boolean = false,
    val secondsRemaining: Int = 0,
    /** The command could not be issued at all (no proxy / no hub). */
    val failed: Boolean = false,
    /** Devices that appeared since the window opened, newest last. */
    val joinedDeviceNames: List<String> = emptyList(),
) {
    /** Whether the tab should offer real pairing rather than the mock dialog. */
    val available: Boolean get() = bridgeId != null
}

/**
 * Devices view model (docs/proposals/home-and-mia-apps.md §3, M1). Owns the Home tab's
 * device list: observes the repository and forwards add/remove/reorder. Rendering
 * (grouping, per-kind state lines) stays in the screen; per-user *persistence* of the
 * order arrives with the real repository (PreferenceService), so the VM stays thin.
 */
class DevicesViewModel(
    private val repository: HomeDevicesRepository,
    /**
     * The synced-device seam, present only in a Home build with a Tenshu
     * client (desktop today). Null = mock-only Home: no bridges, no pairing.
     */
    private val deviceSource: DeviceSource? = null,
    private val pairingSource: DevicePairingSource? = null,
    /** The actuation seam; null = a Home that can look but not touch. */
    private val controlSource: DeviceControlSource? = null,
    /** Command ids are injectable so tests can correlate outcomes deterministically. */
    private val newCommandId: () -> String = ::generateId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> get() = _uiState

    /** The latest synced inventory — the pairing flow's baseline + join signal. */
    private var syncedDevices: List<SyncedDeviceInfo> = emptyList()

    /** Synced device ids present when the current pairing window opened. */
    private var pairingBaseline: Set<String> = emptySet()
    private var countdownJob: Job? = null

    /** In-flight commands by device id — the outcome correlation table. */
    private val inFlight = mutableMapOf<String, InFlightCommand>()

    /**
     * Sticky per-device command status. Survives inventory re-emissions (the
     * controls map is rebuilt from scratch each time), so a failure note stays
     * on screen until the user acts again or dismisses it.
     */
    private val commandStatus = mutableMapOf<String, DeviceCommandStatus>()

    private data class InFlightCommand(
        val commandId: String,
        /** What the control should render until an outcome lands. */
        val on: Boolean?,
        val levelPercent: Int?,
        val timeoutJob: Job,
    )

    init {
        viewModelScope.launch {
            repository.devices().collect { v -> _uiState.update { it.copy(devices = v, loaded = true) } }
        }
        viewModelScope.launch {
            repository.rooms().collect { v -> _uiState.update { it.copy(rooms = v) } }
        }
        deviceSource?.let { source ->
            viewModelScope.launch { source.devices().collect(::onSyncedDevices) }
        }
    }

    // ---- HD-2 pairing -----------------------------------------------------------------

    /**
     * Track which bridge (if any) can pair, and — while a window is open —
     * which devices have joined since it did. The join signal is deliberately
     * *the ordinary synced inventory*: a device that appears is a device that
     * really joined, with no second reporting channel to keep in step.
     */
    private fun onSyncedDevices(devices: List<SyncedDeviceInfo>) {
        syncedDevices = devices
        resolveOutcomes(devices)
        val bridge = devices.firstOrNull { HomeDevice.CAP_PERMIT_JOIN in it.capabilities }
        _uiState.update { state ->
            val joined = if (state.pairing.open) {
                devices.filter { it.deviceId !in pairingBaseline }
                    .filter { HomeDevice.CAP_PERMIT_JOIN !in it.capabilities }
                    .map { it.name }
            } else {
                state.pairing.joinedDeviceNames
            }
            state.copy(
                pairing = state.pairing.copy(
                    bridgeId = bridge?.bridgeId?.takeIf { it.isNotBlank() },
                    bridgeName = bridge?.name.orEmpty(),
                    joinedDeviceNames = joined,
                ),
                controls = buildControls(devices),
            )
        }
    }

    // ---- actuation --------------------------------------------------------------------

    /**
     * Turn a real device on or off. Absolute, not a toggle: the switch carries
     * the state the user wants, so a state row landing between tap and command
     * cannot invert the intent (see [DeviceControlSource]).
     */
    fun setDevicePower(deviceId: String, on: Boolean) {
        val info = actuable(deviceId) ?: return
        if (!info.capabilities.contains(HomeDevice.CAP_POWER)) return
        issue(deviceId, renderedOn = on, renderedLevel = null) { source, commandId ->
            source.setPower(deviceId, on, commandId)
        }
    }

    /**
     * Set a real device's level. Callers must debounce a drag — a slider that
     * issued per pixel would write a command row per pixel; the screen issues
     * on release.
     */
    fun setDeviceLevel(deviceId: String, percent: Int) {
        val info = actuable(deviceId) ?: return
        if (!info.capabilities.contains(HomeDevice.CAP_LEVEL)) return
        val bounded = percent.coerceIn(0, 100)
        // A level above zero implies on (the service documents this), so render
        // the switch accordingly rather than waiting for the state row.
        issue(deviceId, renderedOn = bounded > 0, renderedLevel = bounded) { source, commandId ->
            source.setLevel(deviceId, bounded, commandId)
        }
    }

    /** Clear a failed / unconfirmed note (the user has read it). */
    fun dismissDeviceCommandNote(deviceId: String) {
        if (commandStatus.remove(deviceId) == null) return
        _uiState.update { it.copy(controls = buildControls(syncedDevices)) }
    }

    private fun issue(
        deviceId: String,
        renderedOn: Boolean?,
        renderedLevel: Int?,
        write: suspend (DeviceControlSource, String) -> Boolean,
    ) {
        val source = controlSource ?: return
        val commandId = newCommandId()
        // A newer command supersedes an older one for the same device: its own
        // outcome is the one worth waiting for (last-write-wins, and the hub's
        // journal makes each row at-most-once regardless).
        inFlight.remove(deviceId)?.timeoutJob?.cancel()
        commandStatus[deviceId] = DeviceCommandStatus.Pending
        val timeoutJob = viewModelScope.launch {
            delay(COMMAND_OUTCOME_TIMEOUT_MS)
            // Give up *waiting*, not on the command: the row is durable and the
            // hub may still consume it inside its own staleness window.
            if (inFlight[deviceId]?.commandId != commandId) return@launch
            inFlight.remove(deviceId)
            commandStatus[deviceId] = DeviceCommandStatus.Unconfirmed
            _uiState.update { it.copy(controls = buildControls(syncedDevices)) }
        }
        inFlight[deviceId] = InFlightCommand(commandId, renderedOn, renderedLevel, timeoutJob)
        _uiState.update { it.copy(controls = buildControls(syncedDevices)) }

        viewModelScope.launch {
            if (!write(source, commandId)) {
                if (inFlight[deviceId]?.commandId != commandId) return@launch
                inFlight.remove(deviceId)?.timeoutJob?.cancel()
                commandStatus[deviceId] = DeviceCommandStatus.Failed
                _uiState.update { it.copy(controls = buildControls(syncedDevices)) }
            }
        }
    }

    /**
     * Match arriving state rows against in-flight commands. The correlation is
     * the command id the hub echoes back on the row — not value equality, which
     * cannot tell "the hub did what I asked" from "someone flipped the wall
     * switch".
     */
    private fun resolveOutcomes(devices: List<SyncedDeviceInfo>) {
        if (inFlight.isEmpty()) return
        devices.forEach { info ->
            val pending = inFlight[info.deviceId] ?: return@forEach
            if (info.lastCommandId != pending.commandId) return@forEach
            pending.timeoutJob.cancel()
            inFlight.remove(info.deviceId)
            if (info.lastCommandOutcome == SyncedDeviceState.OUTCOME_OK) {
                commandStatus.remove(info.deviceId)
            } else {
                commandStatus[info.deviceId] = DeviceCommandStatus.Failed
            }
        }
    }

    /** The synced device behind [deviceId], or null for a mock row. */
    private fun actuable(deviceId: String): SyncedDeviceInfo? =
        syncedDevices.firstOrNull { it.deviceId == deviceId }

    /**
     * Controls for every real device that advertises an actuable capability.
     * Mock rows and read-only devices are simply absent from the map, so the
     * screen has nothing to render for them — Home never shows a control it
     * cannot honour (the same rule as the pairing FAB).
     *
     * Deliberately **not** gated on `online`: a bridge that does not publish
     * availability reports every device as offline, and disabling every switch
     * in that setup would be a dead end. The outcome on the state row is the
     * honest answer to "did it work", so let the user try.
     */
    private fun buildControls(devices: List<SyncedDeviceInfo>): Map<String, DeviceControls> {
        if (controlSource == null) return emptyMap()
        return devices.mapNotNull { info ->
            val canSetPower = HomeDevice.CAP_POWER in info.capabilities
            val canSetLevel = HomeDevice.CAP_LEVEL in info.capabilities
            if (!canSetPower && !canSetLevel) return@mapNotNull null
            val pending = inFlight[info.deviceId]
            info.deviceId to DeviceControls(
                canSetPower = canSetPower,
                canSetLevel = canSetLevel,
                on = pending?.on ?: info.powerOn,
                levelPercent = pending?.levelPercent ?: info.levelPercent,
                status = commandStatus[info.deviceId] ?: DeviceCommandStatus.Idle,
            )
        }.toMap()
    }

    /**
     * Open the bridge's pairing window and run the countdown. Idempotent
     * while a window is already open (re-asking would only reset the clock).
     */
    fun startPairing(seconds: Int = DeviceCommand.PERMIT_JOIN_DEFAULT_SECONDS) {
        val bridgeId = _uiState.value.pairing.bridgeId ?: return
        val source = pairingSource ?: return
        if (_uiState.value.pairing.open) return
        val bounded = seconds.coerceIn(1, DeviceCommand.PERMIT_JOIN_MAX_SECONDS)
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            pairingBaseline = syncedDevices.map { it.deviceId }.toSet()
            if (!source.openPairingWindow(bridgeId, bounded)) {
                _uiState.update {
                    it.copy(pairing = it.pairing.copy(open = false, failed = true))
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    pairing = it.pairing.copy(
                        open = true,
                        failed = false,
                        secondsRemaining = bounded,
                        joinedDeviceNames = emptyList(),
                    ),
                )
            }
            // The countdown is the *local* view of a window the hub owns; it
            // is honest about elapsed time, not authoritative about the radio.
            var remaining = bounded
            while (remaining > 0) {
                delay(1_000)
                remaining -= 1
                _uiState.update { it.copy(pairing = it.pairing.copy(secondsRemaining = remaining)) }
            }
            _uiState.update { it.copy(pairing = it.pairing.copy(open = false)) }
        }
    }

    /** Close the window early (the user is done, or gave up). */
    fun stopPairing() {
        val pairing = _uiState.value.pairing
        countdownJob?.cancel()
        countdownJob = null
        _uiState.update {
            it.copy(pairing = it.pairing.copy(open = false, secondsRemaining = 0))
        }
        // Only *close* a window we believe is open: dismissing the dialog
        // without ever opening one must not write a command row.
        val source = pairingSource ?: return
        if (pairing.open && pairing.bridgeId != null) {
            viewModelScope.launch { source.openPairingWindow(pairing.bridgeId, 0) }
        }
    }

    /** Clear the "could not start pairing" banner. */
    fun dismissPairingFailure() {
        _uiState.update { it.copy(pairing = it.pairing.copy(failed = false)) }
    }

    // ⚠⚠ **`addDevice` / `removeDevice` / `moveDeviceUp` / `moveDeviceDown` are gone
    // (2026-08-26).** They wrote to `MockHomeDevicesRepository`, an in-memory household that
    // survived nothing and was indistinguishable on screen from real devices. Adding a real device
    // is the HD-2 permit-join flow ([pairing]); there is no proxy-backed write path for removal or
    // reordering, so the tab no longer offers one.

    /** The room a device is assigned to, resolved for the row's subtitle. */
    fun roomName(device: Device): String? =
        device.roomId?.let { id -> _uiState.value.rooms.firstOrNull { it.id == id }?.name }

    companion object {
        /**
         * How long to wait for a hub to echo an outcome before saying so. Sized
         * against the hub's *slowest* honest path, not its fastest: the default
         * sync cadence is 30 s each way (a fast-polling hub answers in seconds),
         * so anything tighter would cry wolf on a perfectly healthy house.
         */
        const val COMMAND_OUTCOME_TIMEOUT_MS: Long = 75_000
    }
}
