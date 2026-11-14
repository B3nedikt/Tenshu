package app.tenshu.hub

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.toTString
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.client.homeDeviceService

/**
 * The hub side of `HomeDeviceService` (`home-device-service.md` HD-D1/HD-D6)
 * — [HubManagementAgent]'s shape generalised over a pluggable [DeviceBridge]:
 *
 *  - *Inventory*: publishes one [HomeDevice] row per bridged device.
 *    Change-gated on the **hub-authored** fields only (kind, manufacturer,
 *    model, capabilities, bridge id): a re-announce that changed nothing
 *    writes nothing, and a re-publish preserves the user-editable
 *    `name`/`room` from the existing row — so a user rename is never
 *    clobbered by state chatter (the two authors touch disjoint fields, and
 *    the hub only writes this row when identity actually changed).
 *  - *State*: publishes [DeviceState] rows through [DeviceStatePublishGate]
 *    — quantised change-gate + per-device minimum publish interval, so a
 *    drifting sensor cannot flood the realm with sync blocks. A change
 *    suppressed by the interval is remembered and flushed when the interval
 *    expires (never lost).
 *  - *Commands*: consumes [DeviceCommand] rows **at most once** — consumed
 *    ids persist in a durable [ConsumedCommandJournal] written *before*
 *    execution, and the [HubCommandGate] decision table is reused with the
 *    tight [DeviceCommand.STALE_WINDOW_MS] (2 min, HD-D7: physical actuation
 *    — a backlog-replayed toggle must never fire hours later). Outcomes ride
 *    the device's [DeviceState] row.
 */
class DeviceBridgeHost(
    private val runtime: HubRuntime,
    private val bridge: DeviceBridge,
    journalFile: File = File(runtime.dataRoot, "consumed-device-commands.log"),
    private val publishGate: DeviceStatePublishGate = DeviceStatePublishGate(),
    private val staleWindowMs: Long = DeviceCommand.STALE_WINDOW_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /**
     * The RE-2 routines fast path (`routines-engine.md` RE-D11).
     *
     * ⭐⭐ Called at the TOP of [onBridgeState] — **before** the publish gate and
     * therefore before the `DeviceState` row is written. Two consequences, both
     * intended: a device-event routine reacts in milliseconds rather than at the
     * 3–5 s poll cadence a remote observer sees, and it still reacts when the
     * publish gate *suppresses* the row (a rate-limited state change is still a
     * real event — the gate exists to brake sync traffic, not to hide events
     * from the engine that runs in the same process).
     */
    private val onDeviceEvent: (BridgeDeviceState, String) -> Unit = { _, _ -> },
) {
    private val journal = ConsumedCommandJournal(journalFile)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var inventoryJob: Job? = null
    private var statesJob: Job? = null
    private var commandsJob: Job? = null

    /** Bridge-owned devices — the command ownership check. */
    private val knownDevices = LinkedHashMap<String, BridgeDevice>()

    /** Latest bridge-reported state per device (outcome publishes reuse it). */
    private val lastStates = HashMap<String, BridgeDeviceState>()

    /** Last consumed command per device — rides every state publish. */
    private val lastCommandInfo = HashMap<String, Pair<String, String>>()

    /** States suppressed by the rate limit, awaiting their flush slot. */
    private val pendingStates = HashMap<String, BridgeDeviceState>()
    private val flushJobs = HashMap<String, Job>()

    private val lock = Any()

    /**
     * Serialises every write of a [DeviceState] row: the gate's decision, the
     * read of [lastCommandInfo] and the save happen as one step.
     *
     * ⚠⚠ Two coroutines author the same row — the bridge's state stream and
     * the command executor — and a bridge reports the state a command caused
     * *while* [handleCommand] is still recording its outcome. Unserialised,
     * the state publish can snapshot "no command yet", lose the CPU, and save
     * after the outcome publish: the row then carries the new state with the
     * command id erased, the gate calls every later report `NoChange`, and
     * the issuer waits for an echo that is never sent again.
     *
     * Always taken before [lock], never the other way round.
     */
    private val publishLock = Any()

    /** Executed commands this process — the system tests' exactly-once probe. */
    @Volatile
    var executedCommandCount: Long = 0L
        private set

    /** State rows actually written — the rate-limit tests' probe. */
    @Volatile
    var publishedStateCount: Long = 0L
        private set

    /** What [handleCommand] last decided for a command id, and for which issue of it. */
    data class HandledCommand(val issuedAtEpochMs: Long, val decision: HubCommandGate.Decision)

    private val handledCommands = java.util.concurrent.ConcurrentHashMap<String, HandledCommand>()

    /** The last state per device that went all the way through the gate (and its publish, if any). */
    private val handledStates = java.util.concurrent.ConcurrentHashMap<String, BridgeDeviceState>()

    /**
     * The system tests' "it arrived and was declined" probe: the gate's last decision for
     * [commandId], with the `issuedAt` it was made for — a re-issued command carries a newer one.
     * ⚠ A test that proves a command did NOT run must first prove the hub saw it; waiting a few
     * sync cycles proves neither.
     */
    fun handledCommand(commandId: String): HandledCommand? = handledCommands[commandId]

    /** The rate-limit tests' "this state has been decided on" probe (see [handledCommand]). */
    fun handledState(deviceId: String): BridgeDeviceState? = handledStates[deviceId]

    /** Whether a rate-limited state is still waiting for its flush slot. */
    fun hasPendingFlush(): Boolean = synchronized(lock) { flushJobs.values.any { it.isActive } }

    /** Start the publisher + executor. Idempotent; call after `runtime.start()`. */
    fun start() {
        if (inventoryJob?.isActive == true) return
        inventoryJob = scope.launch {
            bridge.devices().collect { devices ->
                try {
                    publishInventory(devices)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    println("tenshu-hub: device inventory publish failed (will retry on next change): $t")
                }
            }
        }
        statesJob = scope.launch {
            bridge.states().collect { states ->
                for (state in states) {
                    try {
                        onBridgeState(state)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        println("tenshu-hub: device state publish failed: $t")
                    }
                }
            }
        }
        commandsJob = scope.launch {
            runtime.client.homeDeviceService.deviceCommands.flow().collect { result ->
                val commands = (result as? TResult.Success)?.value?.toList().orEmpty()
                for (command in commands) {
                    try {
                        handleCommand(command)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        println("tenshu-hub: device command handling failed: $t")
                    }
                }
            }
        }
        bridge.start(scope)
    }

    suspend fun stop() {
        bridge.stop()
        inventoryJob?.cancelAndJoin()
        inventoryJob = null
        statesJob?.cancelAndJoin()
        statesJob = null
        commandsJob?.cancelAndJoin()
        commandsJob = null
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    /**
     * Publish/refresh the [HomeDevice] rows for [devices] — change-gated on
     * hub-authored fields, user fields preserved (class KDoc).
     */
    internal fun publishInventory(devices: List<BridgeDevice>) {
        if (!runtime.isPaired()) return
        synchronized(lock) {
            knownDevices.clear()
            devices.forEach { knownDevices[it.deviceId] = it }
        }
        val context = executionContext()
        for (device in devices) {
            val existing = context.database.getData(
                DataId.StringDataId.create(
                    context = context.context,
                    type = HomeDevice.ID,
                    keyValue = device.deviceId,
                )
            ) as? HomeDevice

            val capabilityIds = device.capabilityIds.joinToString(" ")
            val existingExtras = (existing?.extras as? TString)?.get().orEmpty()
            val unchanged = existing != null &&
                existing.bridgeId.get() == bridge.bridgeId &&
                existing.kind.get() == device.kind &&
                existing.manufacturer.get() == device.manufacturer &&
                existing.model.get() == device.model &&
                existing.capabilityIds.get() == capabilityIds &&
                (device.extras.isEmpty() || existingExtras == device.extras)
            if (unchanged) continue

            val entity = HomeDevice.create(
                context = context.context,
                deviceId = device.deviceId,
                bridgeId = bridge.bridgeId,
                kind = device.kind,
                manufacturer = device.manufacturer,
                model = device.model,
                capabilityIds = capabilityIds,
                // User-editable fields: preserved from the existing row; the
                // bridge default only seeds the very first publish.
                name = existing?.name?.get()?.takeIf { it.isNotBlank() } ?: device.defaultName,
                room = existing?.room?.get() ?: "",
                joinedAtEpochMs = existing?.joinedAtEpochMs?.get() ?: nowMs(),
                // Bridge-authored identity extras (HD-2: IEEE address, the
                // friendly name z2m addresses the device by, power source);
                // a bridge that says nothing leaves the existing slot alone.
                extras = if (device.extras.isNotEmpty()) {
                    device.extras.toTString(context.context)
                } else {
                    existing?.extras
                },
            )
            context.saveData(entity)
        }
    }

    /** Route one bridge state through the quantised change-gate + rate limit. */
    internal fun onBridgeState(state: BridgeDeviceState) {
        if (!runtime.isPaired()) return
        // ⚠ Before the gate, by design (see the constructor parameter). Failures
        // here must never stop the state from being published.
        // ⭐ The last command WE executed for this device rides along, so the
        // engine can recognise a change it caused itself (RE-D9 self-trigger
        // suppression). The bridge's own report carries no such handle.
        val causedBy = synchronized(lock) { lastCommandInfo[state.deviceId]?.first }.orEmpty()
        runCatching { onDeviceEvent(state, causedBy) }
            .onFailure { println("tenshu-hub: routine fast path failed: $it") }
        synchronized(publishLock) {
            synchronized(lock) {
                lastStates[state.deviceId] = state
            }
            when (publishGate.decide(state, nowMs())) {
                DeviceStatePublishGate.Decision.Publish -> {
                    cancelPendingFlush(state.deviceId)
                    publishState(state)
                }

                DeviceStatePublishGate.Decision.NoChange -> Unit

                DeviceStatePublishGate.Decision.RateLimited -> scheduleFlush(state)
            }
            // Last, so a reader that sees this state also sees whatever the gate did with it.
            handledStates[state.deviceId] = state
        }
    }

    private fun scheduleFlush(state: BridgeDeviceState) {
        val deviceId = state.deviceId
        synchronized(lock) {
            pendingStates[deviceId] = state
            if (flushJobs[deviceId]?.isActive == true) return
            flushJobs[deviceId] = scope.launch {
                delay((publishGate.dueAtMs(deviceId) - nowMs()).coerceAtLeast(1))
                val pending = synchronized(lock) { pendingStates.remove(deviceId) } ?: return@launch
                try {
                    onBridgeState(pending)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    println("tenshu-hub: deferred device state publish failed: $t")
                }
            }
        }
    }

    private fun cancelPendingFlush(deviceId: String) {
        synchronized(lock) {
            pendingStates.remove(deviceId)
            flushJobs.remove(deviceId)?.cancel()
        }
    }

    /** At-most-once command execution — see class KDoc. */
    internal suspend fun handleCommand(command: DeviceCommand) {
        if (!runtime.isPaired()) return
        val target = command.targetDeviceId.get()
        // The bridge itself is addressable (HD-2 permit-join): `bridgeId` is
        // a legitimate target even before any device has ever joined — which
        // is exactly the state a first pairing starts from.
        val owned = target == bridge.bridgeId ||
            synchronized(lock) { knownDevices.containsKey(target) }
        if (!owned) return // another hub's device (or not yet discovered) — not ours.

        val commandId = command.commandId.get()
        // Reuse the H4 decision table verbatim: ownership was checked above,
        // so target==own is passed pre-satisfied and the gate contributes the
        // journal + staleness legs (2-min window — HD-D7).
        val decision = HubCommandGate.decide(
            targetDeviceId = target,
            issuedAtEpochMs = command.issuedAtEpochMs.get(),
            ownDeviceId = target,
            nowEpochMs = nowMs(),
            alreadyConsumed = journal.isConsumed(commandId),
            staleWindowMs = staleWindowMs,
        )
        if (decision != HubCommandGate.Decision.Execute) {
            handledCommands[commandId] = HandledCommand(command.issuedAtEpochMs.get(), decision)
            return
        }

        // Persist BEFORE executing: at-MOST-once. A crash between here and
        // the radio loses the command (the issuer re-issues off the stale
        // state row) — never doubles a physical actuation.
        journal.markConsumed(commandId)
        executedCommandCount += 1
        handledCommands[commandId] = HandledCommand(command.issuedAtEpochMs.get(), decision)

        val verb = command.verb.get()
        val result = try {
            if (verb == DeviceCommand.VERB_PERMIT_JOIN) {
                // The admission gate (HD-D7). The window is clamped HERE, so
                // every bridge inherits the bound regardless of what the
                // caller asked for and regardless of what the radio would
                // have accepted; a request the hub cannot parse closes rather
                // than opens the window.
                val requested = command.argValue.get().trim().toIntOrNull()
                    ?: DeviceCommand.PERMIT_JOIN_DEFAULT_SECONDS
                bridge.setPermitJoin(
                    requested.coerceIn(0, DeviceCommand.PERMIT_JOIN_MAX_SECONDS),
                )
            } else {
                bridge.execute(target, verb, command.argValue.get())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            println("tenshu-hub: device command $commandId failed: $t")
            BridgeCommandResult(DeviceState.OUTCOME_FAILED)
        }

        // One step with the row write ([publishLock]): once the outcome is
        // recorded, no state publish that read the previous command may land
        // after it.
        synchronized(publishLock) {
            val outcomeState = result.newState
                ?: synchronized(lock) { lastStates[target] }
                ?: BridgeDeviceState(deviceId = target)
            synchronized(lock) {
                lastStates[target] = outcomeState
                lastCommandInfo[target] = commandId to result.outcome
            }
            // Outcome publishes bypass the rate limit (`force`) — the issuer is
            // waiting on this row for feedback.
            publishGate.decide(outcomeState, nowMs(), force = true)
            cancelPendingFlush(target)
            publishState(outcomeState)
        }
    }

    /** Callers hold [publishLock]. */
    private fun publishState(state: BridgeDeviceState) {
        val context = executionContext()
        val (lastCommandId, lastCommandOutcome) =
            synchronized(lock) { lastCommandInfo[state.deviceId] } ?: ("" to "")
        val entity = DeviceState.create(
            context = context.context,
            deviceId = state.deviceId,
            online = state.online,
            power = when (state.powerOn) {
                true -> DeviceState.POWER_ON
                false -> DeviceState.POWER_OFF
                null -> DeviceState.POWER_NOT_APPLICABLE
            },
            levelPercent = state.levelPercent?.toLong() ?: DeviceState.NO_PERCENT,
            temperatureDeciC = state.temperatureDeciC?.toLong()
                ?: DeviceState.NO_TEMPERATURE_DECI_C,
            humidityPercent = state.humidityPercent?.toLong() ?: DeviceState.NO_PERCENT,
            batteryPercent = state.batteryPercent?.toLong() ?: DeviceState.NO_PERCENT,
            colorTempMireds = state.colorTempMireds?.toLong() ?: DeviceState.NO_VALUE,
            powerDrawDeciW = state.powerDrawDeciW?.toLong() ?: DeviceState.NO_VALUE,
            lastCommandId = lastCommandId,
            lastCommandOutcome = lastCommandOutcome,
            freshAsOf = nowMs(),
            // Unmapped bridge attributes (HD-2: linkquality, voltage, …) —
            // carried, never dropped, and never a reason to publish (see
            // BridgeDeviceState.extras).
            extras = state.extras.takeIf { it.isNotEmpty() }?.toTString(context.context),
        )
        context.saveData(entity)
        publishedStateCount += 1
    }

    private fun executionContext() =
        runtime.koin.get<ClientRegistry>().getExecutionContext(HubRuntime.PACKAGE_NAME)

    companion object {
        /**
         * HD-D2 v1: the hub's fast-poll sync cadence whenever bridged devices
         * exist (a plugged-in node doesn't care about battery; empty polls
         * are trivial relay cost). The real fix is relay long-poll — HD-4.
         */
        const val DEVICE_FAST_POLL_INTERVAL_MS = 5_000L
    }
}

/**
 * Pure quantised change-gate + per-device rate limit for [DeviceState]
 * publishes — separated from the host so the decision table is
 * unit-testable without a runtime (the [HubCommandGate] pattern).
 *
 * Quantisation (HD-D1): analog values compare in coarse steps — temperature
 * in 0.5 °C, percent values in 5-point steps — so sensor jitter inside a
 * step publishes nothing; any publish still carries the *exact* figures
 * (quantisation gates the decision, never the data — the
 * `DISK_FREE_CHANGE_QUANTUM_BYTES` precedent).
 */
class DeviceStatePublishGate(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val heartbeatMs: Long = DEFAULT_HEARTBEAT_MS,
) {
    enum class Decision {
        /** Write the row now (the gate has recorded it as published). */
        Publish,

        /** Quantised-equal to the last published snapshot — drop. */
        NoChange,

        /** Changed, but inside the per-device minimum interval — hold + flush later. */
        RateLimited,
    }

    private class Entry(
        var lastQuantised: QuantisedState? = null,
        var lastPublishedAtMs: Long = 0L,
    )

    /** The comparison unit — every analog dimension already quantised. */
    data class QuantisedState(
        val online: Boolean,
        val powerOn: Boolean?,
        val levelStep: Int?,
        val temperatureStep: Int?,
        val humidityStep: Int?,
        val batteryStep: Int?,
        val colorTempStep: Int?,
        val powerDrawStep: Int?,
    )

    private val entries = HashMap<String, Entry>()

    fun quantise(state: BridgeDeviceState): QuantisedState = QuantisedState(
        online = state.online,
        powerOn = state.powerOn,
        levelStep = state.levelPercent?.floorDiv(PERCENT_QUANTUM),
        temperatureStep = state.temperatureDeciC?.floorDiv(TEMPERATURE_QUANTUM_DECI_C),
        humidityStep = state.humidityPercent?.floorDiv(PERCENT_QUANTUM),
        batteryStep = state.batteryPercent?.floorDiv(PERCENT_QUANTUM),
        colorTempStep = state.colorTempMireds?.floorDiv(COLOR_TEMP_QUANTUM_MIREDS),
        powerDrawStep = state.powerDrawDeciW?.floorDiv(POWER_DRAW_QUANTUM_DECI_W),
    )

    /**
     * Decide for one state snapshot at [nowMs]; a [Decision.Publish] records
     * the snapshot as published. [force] (command outcomes) always publishes
     * and re-bases the interval.
     */
    @Synchronized
    fun decide(state: BridgeDeviceState, nowMs: Long, force: Boolean = false): Decision {
        val quantised = quantise(state)
        val entry = entries.getOrPut(state.deviceId) { Entry() }
        if (!force) {
            val heartbeatDue = nowMs - entry.lastPublishedAtMs >= heartbeatMs
            if (quantised == entry.lastQuantised && !heartbeatDue) return Decision.NoChange
            if (entry.lastQuantised != null &&
                nowMs - entry.lastPublishedAtMs < minIntervalMs
            ) {
                return Decision.RateLimited
            }
        }
        entry.lastQuantised = quantised
        entry.lastPublishedAtMs = nowMs
        return Decision.Publish
    }

    /** Earliest epoch ms a rate-limited [deviceId] may publish again. */
    @Synchronized
    fun dueAtMs(deviceId: String): Long =
        (entries[deviceId]?.lastPublishedAtMs ?: 0L) + minIntervalMs

    companion object {
        /** Per-device minimum publish interval (10 s) — the flood brake. */
        const val DEFAULT_MIN_INTERVAL_MS = 10_000L

        /** Bounded staleness for an otherwise-unchanged state row (1 h). */
        const val DEFAULT_HEARTBEAT_MS = 60L * 60 * 1000

        /** Temperature compares in 0.5 °C steps. */
        const val TEMPERATURE_QUANTUM_DECI_C = 5

        /** Percent values (level, humidity, battery) compare in 5-point steps. */
        const val PERCENT_QUANTUM = 5

        /** Colour temperature compares in 10-mired steps. */
        const val COLOR_TEMP_QUANTUM_MIREDS = 10

        /** Power draw compares in 1 W steps. */
        const val POWER_DRAW_QUANTUM_DECI_W = 10
    }
}
