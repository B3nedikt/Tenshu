@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.HomePlugins
import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.JsRuntimeBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.Filter
import org.tenshu.core.collection.list.SearchAll
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.Take
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.IssueDeviceCommand
import org.tenshu.home.device.UpdateHomeDevice
import org.tenshu.home.device.client.homeDeviceService
import org.tenshu.home.device.issueDeviceCommand
import org.tenshu.home.device.updateHomeDevice
import org.tenshu.hub.management.HubStatus
import org.tenshu.hub.management.IssueHubCommand
import org.tenshu.hub.management.client.hubManagementService
import org.tenshu.hub.management.issueHubCommand
import org.tenshu.routine.DeleteRoutine
import org.tenshu.routine.EvaluateDueRoutines
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.RunRoutineNow
import org.tenshu.routine.SaveRoutine
import org.tenshu.routine.SetRoutineEnabled
import org.tenshu.routine.client.routineService
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.Condition
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.MatchOp
import org.tenshu.routine.common.program.Occupancy
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.StateMatch
import org.tenshu.routine.common.program.SunEvent
import org.tenshu.routine.common.program.Trigger
import org.tenshu.routine.deleteRoutine
import org.tenshu.routine.evaluateDueRoutines
import org.tenshu.routine.runRoutineNow
import org.tenshu.routine.saveRoutine
import org.tenshu.routine.setRoutineEnabled
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boundary-contract tests (`docs/testing.md` §9.3) for `Home` (`web-app-suite.md` Wave 4),
 * against the **real** in-process runtime. Rows are seeded the way a hub's rows reach a browser: a
 * store save with `WriteOrigin.SYNC_INGEST` (the sync-merge path) — there is no op to seed through,
 * because Home on web maps none.
 *
 * ⚠ `GlobalScope.promise`, not `runTest`: the service reads never complete on the test dispatcher.
 * ⚠ The jsTest suite shares ONE `JsRuntime`, and each relation is the whole realm, so every test seeds
 * its own ids (and, for the feed, its own far-future window) and asserts only on those.
 *
 * They assert the keys `web/sdk/src/home.ts` publishes and the values native `:home-shared-logic`
 * would read — not "returns something".
 */
/** [HomeWebContractTest.headMs]'s clock: top-level, because each test gets a fresh instance. */
private var headClock = 0L

class HomeWebContractTest : SdkRuntimeTest() {

    private val ctx get() = JsRuntime.tenshuClient.environment.tContext

    private fun stamp() = "${Date.now().toLong()}-${(0..999_999).random()}"

    private fun ingest(data: Data) = JsRuntime.executionContext.saveData(data, origin = WriteOrigin.SYNC_INGEST)

    private suspend fun ok(promise: Promise<dynamic>, what: String): dynamic {
        val result = promise.await()
        assertEquals(true, result.ok, "$what must succeed; got ${JSON.stringify(result)}")
        return result
    }

    private suspend fun failureKind(promise: Promise<dynamic>, what: String): String {
        val result = promise.await()
        assertEquals(false, result.ok, "$what must fail; got ${JSON.stringify(result)}")
        return result.error.kind as String
    }

    /** Re-reads until [found] holds (the relation maintainer runs inside the save; this is a bound). */
    private suspend fun <T> eventually(what: String, read: suspend () -> T, found: (T) -> Boolean): T {
        var last = read()
        var i = 0
        while (!found(last) && i < 100) {
            delay(20); i++
            last = read()
        }
        assertTrue(found(last), "$what never became visible")
        return last
    }

    /** The first push [subscribe] makes that satisfies [match], within a bound. */
    private suspend fun firstPush(
        what: String,
        subscribe: ((dynamic) -> Unit, (String) -> Unit) -> () -> Unit,
        afterSubscribe: () -> Unit,
        match: (dynamic) -> Boolean,
    ): dynamic {
        val seen = CompletableDeferred<dynamic>()
        val failed = CompletableDeferred<String>()
        val stop = subscribe({ r -> if (match(r)) seen.complete(r) }, { m -> failed.complete(m) })
        try {
            afterSubscribe()
            return withTimeoutOrNull(5_000) { seen.await() }
                ?: throw AssertionError("$what: no matching push (ended: ${failed.isCompleted})")
        } finally {
            stop()
        }
    }

    // ── devices ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun devices_carryRoomAndStateAsStored_andAStatelessDeviceHasNullState() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val lamp = "zigbee2mqtt:lamp-$s"
        val sensor = "zigbee2mqtt:sensor-$s"
        ingest(
            HomeDevice.create(
                ctx, deviceId = lamp, bridgeId = HomeDevice.BRIDGE_ZIGBEE2MQTT, kind = HomeDevice.KIND_LIGHT,
                manufacturer = "IKEA", model = "TRADFRI", capabilityIds = "power level  colorTemp",
                name = "Sofa lamp", room = "Living room $s", joinedAtEpochMs = 1_700_000_000_000L,
            ),
        )
        ingest(
            DeviceState.create(
                ctx, deviceId = lamp, online = true, power = DeviceState.POWER_ON, levelPercent = 70,
                colorTempMireds = 370, lastCommandId = "cmd-$s", lastCommandOutcome = DeviceState.OUTCOME_OK,
                freshAsOf = 1_700_000_100_000L,
            ),
        )
        // A sensor: no power dimension, battery only — every other sentinel must read as null.
        ingest(
            HomeDevice.create(
                ctx, deviceId = sensor, bridgeId = HomeDevice.BRIDGE_ZIGBEE2MQTT, kind = "doorbell-v9",
                name = "Front door", joinedAtEpochMs = 1_700_000_000_500L,
            ),
        )

        val read = eventually("the two devices", { ok(Home.devices(), "devices") }) { r ->
            r.devices.unsafeCast<Array<dynamic>>().count { it.deviceId == lamp || it.deviceId == sensor } == 2
        }
        val rows = read.devices.unsafeCast<Array<dynamic>>()
        val l = rows.first { it.deviceId == lamp }
        assertEquals(HomeDevice.BRIDGE_ZIGBEE2MQTT, l.bridgeId)
        assertEquals("light", l.kind)
        assertEquals("IKEA", l.manufacturer)
        assertEquals("TRADFRI", l.model)
        assertEquals(listOf("power", "level", "colorTemp"), l.capabilities.unsafeCast<Array<String>>().toList())
        assertEquals("Sofa lamp", l.name)
        assertEquals("Living room $s", l.room)
        assertEquals(1_700_000_000_000.0, l.joinedAtMs)
        val st = assertNotNull(l.state, "the lamp's state row synced")
        assertEquals(true, st.online)
        assertEquals("on", st.power)
        assertEquals(70, st.levelPercent)
        assertEquals(370, st.colorTempMireds)
        assertNull(st.temperatureDeciC, "NO_TEMPERATURE_DECI_C reads as null, never -100000.0°")
        assertNull(st.humidityPercent)
        assertNull(st.batteryPercent)
        assertNull(st.powerDrawDeciW)
        assertEquals("cmd-$s", st.lastCommandId)
        assertEquals("ok", st.lastCommandOutcome)
        assertEquals(1_700_000_100_000.0, st.freshAsOfMs)

        val d = rows.first { it.deviceId == sensor }
        assertEquals("doorbell-v9", d.kind, "an unknown kind passes through as stored")
        assertEquals("", d.room, "unassigned")
        assertEquals(0, d.capabilities.unsafeCast<Array<String>>().size)
        assertNull(d.state, "no state row yet: null, never plausible defaults")

        assertTrue("Living room $s" in read.rooms.unsafeCast<Array<String>>(), "rooms lists the lamp's room")
        assertTrue("" !in read.rooms.unsafeCast<Array<String>>(), "a blank room is no room")

        // The sentinel set, all at once: a battery-only reading.
        ingest(DeviceState.create(ctx, deviceId = sensor, online = false, batteryPercent = 12, freshAsOf = 5L))
        val after = eventually("the sensor's state", { ok(Home.devices(), "devices") }) { r ->
            r.devices.unsafeCast<Array<dynamic>>().first { it.deviceId == sensor }.state != null
        }
        val ss = after.devices.unsafeCast<Array<dynamic>>().first { it.deviceId == sensor }.state
        assertEquals(false, ss.online)
        assertNull(ss.power, "POWER_NOT_APPLICABLE is null, not \"off\"")
        assertNull(ss.levelPercent)
        assertEquals(12, ss.batteryPercent)
        assertNull(ss.colorTempMireds)
        assertEquals("", ss.lastCommandId)
    }

    @Test
    fun observeDevices_firesOnAWriteMadeThroughTheStore() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val id = "simulated:plug-${stamp()}"
        val pushed = firstPush(
            "observeDevices",
            { cb, err -> Home.observeDevices(cb, err) },
            afterSubscribe = {
                ingest(HomeDevice.create(ctx, deviceId = id, bridgeId = "simulated", kind = HomeDevice.KIND_PLUG, name = "Kettle", joinedAtEpochMs = 1L))
            },
        ) { r -> r.ok == true && r.devices.unsafeCast<Array<dynamic>>().any { it.deviceId == id } }
        assertEquals("Kettle", pushed.devices.unsafeCast<Array<dynamic>>().first { it.deviceId == id }.name)

        // And a state row alone (the hub's chatter) re-emits too.
        val state = firstPush(
            "observeDevices (state)",
            { cb, err -> Home.observeDevices(cb, err) },
            afterSubscribe = { ingest(DeviceState.create(ctx, deviceId = id, online = true, power = DeviceState.POWER_OFF, freshAsOf = 9L)) },
        ) { r -> r.ok == true && r.devices.unsafeCast<Array<dynamic>>().first { it.deviceId == id }.state?.power == "off" }
        assertEquals(true, state.devices.unsafeCast<Array<dynamic>>().first { it.deviceId == id }.state.online)
    }

    // ── the hub storage tile ──────────────────────────────────────────────────────────────────

    private fun hub(id: String, fresh: Long, total: Long, free: Long, backup: Long = 0) = HubStatusRow(
        deviceId = id, name = "Hub $id", version = "1.2.3", replicaFileCount = 4_200, replicaByteCount = 9_000_000_000,
        diskFreeBytes = free, diskTotalBytes = total, lastBackupPassAtMs = backup, freshAsOfMs = fresh,
    )

    @Test
    fun hubStorage_isTheFreshestHubWithDiskFigures_staleAfterTwoHeartbeats_andNullWithoutOne() {
        val now = 10_000_000_000L
        // The freshest row has no disk figures (a pre-v12 hub): it is skipped, never shown as 0 B.
        val r = hubStorageResult(
            HomeRead.Ok(listOf(hub("a", now - 1_000, 1_000, 400, backup = 77), hub("b", now - 10, 0, 0))),
            now,
        )
        assertEquals(true, r.ok)
        val h = r.hub
        assertEquals("a", h.deviceId)
        assertEquals("Hub a", h.name)
        assertEquals(1_000.0, h.diskTotalBytes)
        assertEquals(400.0, h.diskFreeBytes)
        assertEquals(600.0, h.usedBytes)
        assertEquals(4_200.0, h.replicaFileCount)
        assertEquals(9_000_000_000.0, h.replicaByteCount)
        assertEquals(77.0, h.lastBackupPassAtMs)
        assertEquals(false, h.stale)
        assertEquals(HUB_STALE_AFTER_MS.toDouble(), h.staleAfterMs)

        val stale = hubStorageResult(HomeRead.Ok(listOf(hub("a", now - HUB_STALE_AFTER_MS - 1, 10, 20))), now).hub
        assertEquals(true, stale.stale, "older than 2× the 1 h heartbeat")
        assertEquals(0.0, stale.usedBytes, "free > total never reads as negative usage")
        assertNull(stale.lastBackupPassAtMs, "0 = never backed up, not the epoch")
        assertEquals(false, hubStorageResult(HomeRead.Ok(listOf(hub("a", now - HUB_STALE_AFTER_MS, 10, 5))), now).hub.stale)

        val none = hubStorageResult(HomeRead.Ok(listOf(hub("c", now, 0, 0))), now)
        assertEquals(true, none.ok)
        assertNull(none.hub, "no usable figures: no tile — an answer, not a failure")
        val failed = hubStorageResult(HomeRead.Failed("error_x", "boom"), now)
        assertEquals(false, failed.ok, "a failed read is a failure, never `hub: null`")
        assertEquals("error_x", failed.error.kind)
    }

    @Test
    fun hubStorage_readsAndPushesASyncedHubStatus() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val id = "hub-${stamp()}"
        // Fresher than any other row this suite (or an earlier run) can have written, and not stale.
        val fresh = 4_000_000_000_000_000L + Date.now().toLong()
        val pushed = firstPush(
            "observeHubStorage",
            { cb, err -> Home.observeHubStorage(cb, err) },
            afterSubscribe = {
                ingest(
                    HubStatus.create(
                        ctx, deviceId = id, name = "Cellar", version = "0.9", startedAtEpochMs = 1,
                        replicaFileCount = 12, replicaByteCount = 3_000, diskFreeBytes = 250_000_000_000,
                        diskTotalBytes = 1_000_000_000_000, lastBackupPassAtEpochMs = 0, paired = true, freshAsOf = fresh,
                    ),
                )
            },
        ) { r -> r.ok == true && r.hub?.deviceId == id }
        assertEquals("Cellar", pushed.hub.name)
        assertEquals(750_000_000_000.0, pushed.hub.usedBytes)

        val read = ok(Home.hubStorage(), "hubStorage").hub
        assertEquals(id, read.deviceId)
        assertEquals("0.9", read.version)
        assertEquals(1_000_000_000_000.0, read.diskTotalBytes)
        assertEquals(12.0, read.replicaFileCount)
        assertNull(read.lastBackupPassAtMs)
        assertEquals(fresh.toDouble(), read.freshAsOfMs)
        assertEquals(false, read.stale)
    }

    // ── automations ───────────────────────────────────────────────────────────────────────────

    private val codec = JsonRoutineProgramCodec()

    private fun routine(id: String, name: String, program: RoutineProgram, enabled: Boolean = true): Routine {
        val encoded = codec.encode(program)
        return Routine.create(
            ctx, routineId = id, name = name, enabled = enabled, timeZoneId = "Europe/Berlin",
            createdAtEpochMs = 1, updatedAtEpochMs = 1, programJson = encoded.programJson, programVersion = encoded.programVersion,
        )
    }

    private fun run(firingId: String, routineId: String, claimedAt: Long, state: String, finishedAt: Long = claimedAt + 5) =
        RoutineRun.create(
            ctx, firingId = firingId, routineId = routineId, triggerInstantEpochMs = claimedAt,
            claimedAtEpochMs = claimedAt, finishedAtEpochMs = finishedAt, state = state,
        )

    @Test
    fun automations_describeTheEnginesProgram_andListWhatTheyCannotParse() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val full = "full-$s"
        val sun = "sun-$s"
        val event = "event-$s"
        val broken = "broken-$s"
        val newer = "newer-$s"
        ingest(
            routine(
                full, "Evening",
                RoutineProgram(
                    trigger = Trigger.TimeOfDay(minuteOfDay = 1_350, days = setOf(5, 1, 3)),
                    conditions = listOf(
                        Condition.TimeWindow(fromMinuteOfDay = 1_320, toMinuteOfDay = 60),
                        Condition.DeviceStateIs("zigbee2mqtt:lamp", StateMatch("power", MatchOp.EQ, "on")),
                        Condition.Presence(Occupancy.NOBODY_HOME),
                    ),
                    actions = listOf(
                        Action.SetPower("zigbee2mqtt:lamp", on = false),
                        Action.SetLevel("zigbee2mqtt:hall", percent = 30),
                        Action.Notify("Home", "Lights off"),
                        Action.RunRoutine(sun),
                    ),
                ),
                enabled = false,
            ),
        )
        ingest(routine(sun, "Dusk", RoutineProgram(Trigger.Sun(SunEvent.SUNSET, offsetMinutes = -30), actions = listOf(Action.RunRoutine(full)))))
        // A device-event trigger: native's editor model drops this routine from its list; the web lists it.
        ingest(
            routine(
                event, "Door",
                RoutineProgram(Trigger.DeviceEvent("zigbee2mqtt:door", StateMatch("contact", MatchOp.NEQ, "closed")), actions = listOf(Action.Notify("Door", ""))),
            ),
        )
        ingest(Routine.create(ctx, routineId = broken, name = "Broken", timeZoneId = "UTC", createdAtEpochMs = 1, updatedAtEpochMs = 1, programJson = "{", programVersion = 1))
        ingest(Routine.create(ctx, routineId = newer, name = "From the future", timeZoneId = "UTC", createdAtEpochMs = 1, updatedAtEpochMs = 1, programJson = "{}", programVersion = 99))
        // Two runs of `full`; `lastRunAtMs` is the newer claim, whatever its state (RE-D10).
        ingest(run("f1-$s", full, 1_000, RoutineRun.STATE_RAN))
        ingest(run("f2-$s", full, 2_000, RoutineRun.STATE_CLAIMED, finishedAt = RoutineRun.NOT_FINISHED))

        val ids = setOf(full, sun, event, broken, newer)
        val read = eventually("five routines", { ok(Home.automations(), "automations") }) { r ->
            val rows = r.automations.unsafeCast<Array<dynamic>>()
            rows.count { it.routineId in ids } == 5 && rows.first { it.routineId == full }.lastRunAtMs == 2_000.0
        }
        val rows = read.automations.unsafeCast<Array<dynamic>>().associateBy { it.routineId as String }

        val f = rows.getValue(full)
        assertEquals("Evening", f.name)
        assertEquals(false, f.enabled)
        assertEquals(Routine.STATE_ACTIVE, f.state)
        assertEquals("", f.suspendedReason)
        assertEquals("Europe/Berlin", f.timeZoneId)
        assertEquals(2_000.0, f.lastRunAtMs)
        assertEquals("parsed", f.programStatus)
        val t = f.program.trigger
        assertEquals("timeOfDay", t.type)
        assertEquals(1_350, t.minuteOfDay)
        assertEquals(listOf(1, 3, 5), t.days.unsafeCast<Array<Int>>().toList())
        val c = f.program.conditions.unsafeCast<Array<dynamic>>()
        assertEquals(listOf("timeWindow", "deviceStateIs", "presence"), c.map { it.type as String })
        assertEquals(1_320, c[0].fromMinuteOfDay)
        assertEquals(60, c[0].toMinuteOfDay)
        assertEquals("zigbee2mqtt:lamp", c[1].deviceId)
        assertEquals("power", c[1].match.field)
        assertEquals("eq", c[1].match.op)
        assertEquals("on", c[1].match.value)
        assertEquals("nobodyHome", c[2].expect)
        val a = f.program.actions.unsafeCast<Array<dynamic>>()
        assertEquals(listOf("setPower", "setLevel", "notify", "runRoutine"), a.map { it.type as String })
        assertEquals(false, a[0].on)
        assertEquals(30, a[1].percent)
        assertEquals("Home", a[2].title)
        assertEquals("Lights off", a[2].body)
        assertEquals(sun, a[3].routineId)

        val d = rows.getValue(sun)
        assertEquals("sun", d.program.trigger.type)
        assertEquals("sunset", d.program.trigger.event)
        assertEquals(-30, d.program.trigger.offsetMinutes)
        assertEquals(0, d.program.conditions.unsafeCast<Array<dynamic>>().size)
        assertNull(d.lastRunAtMs, "never ran: null, not the epoch")

        val e = rows.getValue(event)
        assertEquals("deviceEvent", e.program.trigger.type)
        assertEquals("neq", e.program.trigger.match.op)

        assertEquals("malformed", rows.getValue(broken).programStatus)
        assertNull(rows.getValue(broken).program)
        assertEquals("unsupportedVersion", rows.getValue(newer).programStatus)
        assertNull(rows.getValue(newer).program)
        assertEquals("From the future", rows.getValue(newer).name, "listed, never dropped")
    }

    @Test
    fun observeAutomations_firesOnARoutineAndOnARun() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val id = "obs-${stamp()}"
        // ⚠ The observe forms watch the newest runs (`RUN_SIGNAL_WINDOW`), so the run is a new one.
        val at = headMs()
        firstPush(
            "observeAutomations",
            { cb, err -> Home.observeAutomations(cb, err) },
            afterSubscribe = { ingest(routine(id, "Morning", RoutineProgram(Trigger.TimeOfDay(420), actions = listOf(Action.RunRoutine("x"))))) },
        ) { r -> r.ok == true && r.automations.unsafeCast<Array<dynamic>>().any { it.routineId == id } }
        val ran = firstPush(
            "observeAutomations (run)",
            { cb, err -> Home.observeAutomations(cb, err) },
            afterSubscribe = { ingest(run("obs-run-$id", id, at, RoutineRun.STATE_RAN)) },
        ) { r -> r.ok == true && r.automations.unsafeCast<Array<dynamic>>().first { it.routineId == id }.lastRunAtMs == at.toDouble() }
        assertEquals("Morning", ran.automations.unsafeCast<Array<dynamic>>().first { it.routineId == id }.name)
    }

    /**
     * Pins that `lastRunAtMs` is read one newest run per routine, never derived from every run.
     * Many runs over several routines, ingested out of order, a newest run still `claimed`,
     * runs of a routine that no longer exists, and a routine that never ran.
     */
    @Test
    fun lastRunAtMs_isEachRoutinesNewestRun_amongManyRunsOfSeveralRoutines() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val program = RoutineProgram(Trigger.TimeOfDay(60), actions = listOf(Action.RunRoutine("x")))
        val ids = (0 until 4).map { "many-$it-$s" }
        val never = "never-$s"
        (ids + never).forEach { ingest(routine(it, it, program)) }
        val base = farTop()
        val expected = mutableMapOf<String, Long>()
        // 4 routines × 40 runs, interleaved and ingested newest-first for half of them.
        for ((i, id) in ids.withIndex()) {
            val times = (0 until 40).map { base + it * 1_000L + i * 7 }
            val order = if (i % 2 == 0) times.reversed() else times.shuffled()
            for ((k, t) in order.withIndex()) {
                val state = if (t == times.last() && i == 1) RoutineRun.STATE_CLAIMED else RoutineRun.STATE_RAN
                ingest(run("many-run-$i-$k-$s", id, t, state, finishedAt = if (state == RoutineRun.STATE_RAN) t + 5 else RoutineRun.NOT_FINISHED))
            }
            expected[id] = times.max()
        }
        // A deleted routine's runs, newer than all of them: they belong to no listed routine.
        repeat(5) { ingest(run("many-gone-$it-$s", "gone-$s", base + 90_000 + it, RoutineRun.STATE_RAN)) }

        val rows = eventually("the seeded runs", { ok(Home.automations(), "automations").automations.unsafeCast<Array<dynamic>>() }) { all ->
            ids.all { id -> all.firstOrNull { it.routineId == id }?.lastRunAtMs == expected.getValue(id).toDouble() }
        }
        for (id in ids) assertEquals(expected.getValue(id).toDouble(), rows.first { it.routineId == id }.lastRunAtMs, id)
        assertEquals(base + 39_000.0 + 7, rows.first { it.routineId == ids[1] }.lastRunAtMs, "a newest run still `claimed` counts (RE-D10)")
        assertNull(rows.first { it.routineId == never }.lastRunAtMs, "never ran: null")
        assertTrue(rows.none { it.routineId == "gone-$s" }, "runs do not list a routine")
    }

    /** Op ids the unfused path dispatches and a fused top-N read dispatches none of. */
    private val unfusedOps = listOf(Filter.ID, SearchAll.ID, SortBy.ID, Drop.ID, Take.ID)

    /** Runs [body] with each of [unfusedOps] wrapped in a counting delegate; the dispatch counts. */
    private suspend fun dispatchesDuring(body: suspend () -> Unit): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (id in unfusedOps) {
            val counted: OperationImplementation = when (val delegate = implementationFactory(id)) {
                is SyncOperationImplementation -> object : SyncOperationImplementation {
                    override fun <T : Data?> execute(
                        executionContext: ExecutionContext,
                        operationContext: OperationContext?,
                        implementationFactory: OperationImplementationFactory,
                        caller: Any?,
                        params: Map<String, Data>?,
                    ): T {
                        counts[id] = (counts[id] ?: 0) + 1
                        return delegate.execute(executionContext, operationContext, implementationFactory, caller, params)
                    }
                }
                is AsyncOperationImplementation -> object : AsyncOperationImplementation {
                    override suspend fun <T : Data?> execute(
                        executionContext: ExecutionContext,
                        operationContext: OperationContext?,
                        implementationFactory: OperationImplementationFactory,
                        caller: Any?,
                        params: Map<String, Data>?,
                    ): T {
                        counts[id] = (counts[id] ?: 0) + 1
                        return delegate.execute(executionContext, operationContext, implementationFactory, caller, params)
                    }
                }
                else -> throw AssertionError("core implements $id as ${delegate?.let { it::class.simpleName }}")
            }
            JsRuntime.operationOverridesForTest[id] = counted
        }
        try {
            body()
        } finally {
            unfusedOps.forEach { JsRuntime.operationOverridesForTest.remove(it) }
        }
        return counts.filterValues { it > 0 }
    }

    /**
     * Pins that every run read is bounded. Every run read Home makes — each routine's
     * newest run, a feed page from the head, from inside a tied millisecond and below it, and the
     * observe forms' head on subscribe AND on a store write — must take the fused top-N path
     * (`core-ordered-window-fusion.md` F1/F4): one thin pass into a bounded heap, so nothing is
     * materialised whole and the relation is never fully sorted. ⚠ Asserted by dispatch count, the
     * `FilteredWindowFusionTest` recipe: a row assertion passes fused or not, but an unfused read
     * dispatches `Filter` / `SortBy` / `Take` over the materialised relation.
     */
    @Test
    fun everyRunRead_fusesAsTopN() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val top = farTop()
        val id = "fuse-$s"
        ingest(routine(id, "Fused", RoutineProgram(Trigger.TimeOfDay(0), actions = listOf(Action.RunRoutine("x")))))
        listOf("a", "b", "c").forEach { ingest(run("fuse-$s-$it", id, top, RoutineRun.STATE_RAN)) }
        ingest(run("fuse-$s-claimed", id, top - 10, RoutineRun.STATE_CLAIMED, finishedAt = RoutineRun.NOT_FINISHED))
        ingest(run("fuse-$s-older", id, top - 20, RoutineRun.STATE_FAILED))
        eventually("the seeded runs", { ok(Home.activityPage(ActivityCursor(top, null).encode(), 4), "page").events.unsafeCast<Array<dynamic>>().size }) { it == 4 }

        val reads = dispatchesDuring {
            val all = ok(Home.automations(), "automations").automations.unsafeCast<Array<dynamic>>()
            assertEquals(top.toDouble(), all.first { it.routineId == id }.lastRunAtMs)
            val head = ok(Home.activityPage(ActivityCursor(top, null).encode(), 2), "head page")
            assertEquals(listOf("fuse-$s-a", "fuse-$s-b"), head.events.unsafeCast<Array<dynamic>>().map { it.firingId as String })
            val next = ok(Home.activityPage(head.nextCursor as String, 2), "a page inside the tie")
            assertEquals(listOf("fuse-$s-c", "fuse-$s-older"), next.events.unsafeCast<Array<dynamic>>().map { it.firingId as String }, "the claimed run is filtered in the chain")
        }
        assertEquals(emptyMap(), reads, "every run read fuses as top-N")

        val pushes = dispatchesDuring {
            firstPush(
                "observeAutomations",
                { cb, err -> Home.observeAutomations(cb, err) },
                afterSubscribe = { ingest(run("fuse-$s-new", id, headMs(), RoutineRun.STATE_RAN)) },
            ) { r -> r.ok == true && r.automations.unsafeCast<Array<dynamic>>().first { it.routineId == id }.lastRunAtMs != top.toDouble() }
            var versions = 0
            val stop = Home.observeActivityChanges({ versions++ }, { })
            try {
                withTimeoutOrNull(5_000) { while (versions == 0) delay(10) }
                ingest(run("fuse-$s-newer", id, headMs(), RoutineRun.STATE_RAN))
                withTimeoutOrNull(5_000) { while (versions < 2) delay(10) }
                assertTrue(versions >= 2, "the change signal fired on the write")
            } finally {
                stop()
            }
        }
        assertEquals(emptyMap(), pushes, "the observe forms' head re-reads fuse too, on subscribe and on a write")
    }

    /**
     * The change signal: the feed's change counter watches a bounded head of runs, not the relation —
     * and a firing moving from `claimed` to its outcome inside that head (the row becoming a feed row)
     * must still bump it, as a full-relation subscription would.
     */
    @Test
    fun theChangeSignal_seesAnOutcomeLandingInTheHead() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val firing = "sig-$s"
        val at = headMs()
        ingest(run(firing, "r-$s", at, RoutineRun.STATE_CLAIMED, finishedAt = RoutineRun.NOT_FINISHED))
        var versions = 0
        val stop = Home.observeActivityChanges({ versions++ }, { })
        try {
            withTimeoutOrNull(5_000) { while (versions == 0) delay(10) }
            delay(100)
            val before = versions
            ingest(run(firing, "r-$s", at, RoutineRun.STATE_RAN))
            withTimeoutOrNull(5_000) { while (versions == before) delay(10) }
            assertTrue(versions > before, "an outcome landing on a head row signals a refetch")
        } finally {
            stop()
        }
    }

    // ── the activity feed ─────────────────────────────────────────────────────────────────────

    /** A window no other test (or real firing) can sit in: years 2300–2900. */
    private fun farTop(): Long = 10_500_000_000_000L + (0..1_000_000).random() * 10_000_000L

    /**
     * A claim time above every [farTop] and every earlier [headMs]: the NEWEST run in the shared
     * relation, so inside the observe forms' head window. Strictly increasing within a run.
     */
    private fun headMs(): Long {
        headClock = maxOf(headClock + 1_000, 30_000_000_000_000L + Date.now().toLong() * 1_000)
        return headClock
    }

    /** Every page from [top] down until a page reaches below [floor]: firing ids at or above it, per page. */
    private suspend fun pagesDownTo(top: Long, floor: Long, pageSize: Int): List<List<String>> {
        val pages = mutableListOf<List<String>>()
        var at: String? = ActivityCursor(top, null).encode()
        while (true) {
            // ⚠ Bounded: a cursor that re-serves its own row would otherwise page for ever.
            assertTrue(pages.size < 50, "paging did not terminate: ${pages.flatten().take(20)}")
            val page = ok(Home.activityPage(at, pageSize), "activityPage")
            val rows = page.events.unsafeCast<Array<dynamic>>()
            assertTrue(rows.size <= pageSize, "a page never exceeds its size")
            pages += rows.filter { (it.claimedAtMs as Double) >= floor }.map { it.firingId as String }
            val next = page.nextCursor as String?
            if (next == null || rows.any { (it.claimedAtMs as Double) < floor }) return pages
            at = next
        }
    }

    @Test
    fun activityPaging_neverRepeatsOrSkips_withTies_andShowsOnlyRunsWithAnOutcome() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val top = farTop()
        val floor = top - 10_000
        val live = "live-$s"
        val gone = "gone-$s" // never saved: a deleted routine's firings
        ingest(routine(live, "Porch light", RoutineProgram(Trigger.TimeOfDay(0), actions = listOf(Action.RunRoutine("x")))))
        // Four at the same millisecond (ids order them), a claimed one among them, then older rows.
        listOf("b", "d", "a", "c").forEach { ingest(run("tie-$s-$it", live, top, RoutineRun.STATE_RAN)) }
        ingest(run("tie-$s-bb0", live, top, RoutineRun.STATE_CLAIMED, finishedAt = RoutineRun.NOT_FINISHED))
        ingest(run("old-$s-x", gone, top - 1_000, RoutineRun.STATE_SKIPPED))
        ingest(run("old-$s-y", live, top - 1_000, RoutineRun.STATE_THROTTLED))
        ingest(run("old-$s-z", live, top - 1_000, "a-state-from-a-newer-engine"))
        ingest(run("older-$s", live, top - 5_000, RoutineRun.STATE_MISSED))
        ingest(run("oldest-$s", live, top - 9_000, RoutineRun.STATE_FAILED))

        val expected = listOf("a", "b", "c", "d").map { "tie-$s-$it" } +
            listOf("old-$s-x", "old-$s-y", "older-$s", "oldest-$s")
        eventually("the seeded runs", { pagesDownTo(top, floor, 50).flatten() }) { it == expected }
        for (size in listOf(1, 2, 3, 7)) {
            assertEquals(expected, pagesDownTo(top, floor, size).flatten(), "pageSize $size")
        }

        // The row shape.
        val head = ok(Home.activityPage(ActivityCursor(top, null).encode(), 5), "page").events.unsafeCast<Array<dynamic>>()
        val r = head.first()
        assertEquals("tie-$s-a", r.firingId)
        assertEquals("automation", r.kind)
        assertEquals(live, r.routineId)
        assertEquals("Porch light", r.routineName)
        assertEquals("ran", r.state)
        assertEquals(top.toDouble(), r.claimedAtMs)
        assertEquals((top + 5).toDouble(), r.finishedAtMs)
        assertEquals(0, r.coalescedCount)
        val deleted = head.first { it.firingId == "old-$s-x" }
        assertEquals("", deleted.routineName, "a deleted routine's firing stays, under no name")
        assertEquals("skipped", deleted.state)

        // A page boundary inside the tie, then a run arriving on each side of the cursor.
        val first = ok(Home.activityPage(ActivityCursor(top, null).encode(), 2), "first page")
        assertEquals(listOf("tie-$s-a", "tie-$s-b"), first.events.unsafeCast<Array<dynamic>>().map { it.firingId as String })
        ingest(run("tie-$s-0", live, top, RoutineRun.STATE_RAN)) // sorts before the cursor: not served again
        ingest(run("tie-$s-bb", live, top, RoutineRun.STATE_RAN)) // after the cursor, same millisecond: served
        val rest = mutableListOf<String>()
        var at = first.nextCursor as String?
        while (at != null) {
            val page = ok(Home.activityPage(at, 2), "rest")
            val rows = page.events.unsafeCast<Array<dynamic>>()
            rest += rows.filter { (it.claimedAtMs as Double) >= floor }.map { it.firingId as String }
            if (rows.any { (it.claimedAtMs as Double) < floor }) break
            at = page.nextCursor as String?
        }
        assertEquals(
            listOf("tie-$s-bb", "tie-$s-c", "tie-$s-d", "old-$s-x", "old-$s-y", "older-$s", "oldest-$s"),
            rest,
        )
    }

    @Test
    fun activityPage_anEmptyWindowIsAnEmptySuccess_andBadInputIsTyped() = GlobalScope.promise {
        JsRuntime.awaitReady()
        // Nothing is ever claimed before 1 ms: an empty feed, which is an ANSWER, not a failure.
        val empty = ok(Home.activityPage(ActivityCursor(0, null).encode(), 10), "empty window")
        assertEquals(0, empty.events.unsafeCast<Array<dynamic>>().size)
        assertNull(empty.nextCursor, "the end of the feed: no next page")

        assertEquals("error_home_bad_cursor", failureKind(Home.activityPage("w1.5.x", 10), "a Fit cursor"))
        assertEquals("error_home_bad_cursor", failureKind(Home.activityPage("a1.-1.x", 10), "negative"))
        assertEquals("error_home_bad_cursor", failureKind(Home.activityPage("a1.007.x", 10), "non-canonical"))
        assertEquals("error_home_bad_cursor", failureKind(Home.activityPage("a1.12", 10), "no separator"))
        assertEquals("error_home_bad_page_size", failureKind(Home.activityPage(null, 0), "0"))
        assertEquals("error_home_bad_page_size", failureKind(Home.activityPage(null, MAX_ACTIVITY_PAGE_SIZE + 1), "too big"))
        assertEquals(true, Home.activityPage(null, MAX_ACTIVITY_PAGE_SIZE).await().ok, "the max is allowed")
        assertEquals(true, Home.activityPage("", 1).await().ok, "'' is the head")
    }

    /**
     * A last page that is exactly full still carries a cursor — the read
     * cannot know nothing follows without reading it — and the page after it is `[]` + `null`. That is
     * the normal end of the feed (`HomeApi.activityPage` says so), never a failure.
     */
    @Test
    fun activityPage_anExactlyFullLastPage_isFollowedByAnEmptyPageWithNoCursor() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        // The feed's true end is shared by every test, so count what is already down there first.
        val window = ActivityCursor(4, null).encode()
        val already = ok(Home.activityPage(window, MAX_ACTIVITY_PAGE_SIZE), "the bottom").events.unsafeCast<Array<dynamic>>().size
        ingest(run("end-$s-a", "r-$s", 2, RoutineRun.STATE_RAN))
        ingest(run("end-$s-b", "r-$s", 3, RoutineRun.STATE_MISSED))
        val size = already + 2
        val last = eventually("the seeded runs", { ok(Home.activityPage(window, size), "the last page") }) {
            it.events.unsafeCast<Array<dynamic>>().size == size
        }
        val cursor = assertNotNull(last.nextCursor as String?, "an exactly-full page carries a cursor")
        val after = ok(Home.activityPage(cursor, size), "the page after the last")
        assertEquals(0, after.events.unsafeCast<Array<dynamic>>().size)
        assertNull(after.nextCursor, "empty + null: the normal end")
    }

    @Test
    fun activityCursor_roundTripsAnIdWithDots_andRefusesWhatNoReadMints() {
        val c = ActivityCursor(1_234, "a.b.c")
        assertEquals(c, ActivityCursor.decode(c.encode()))
        assertEquals(ActivityCursor.HEAD, ActivityCursor.decode(null))
        assertEquals(ActivityCursor.HEAD, ActivityCursor.decode(""))
        assertEquals(ActivityCursor(5, null), ActivityCursor.decode("a1.5."))
        for (bad in listOf("a1.+5.x", "a1.-0.x", "a1..x", "a1.x.y", "b1.5.x", "a1.99999999999999999999.x")) {
            assertNull(ActivityCursor.decode(bad), bad)
        }
    }

    @Test
    fun observeActivityChanges_firesOnARunSavedThroughTheStore() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val versions = mutableListOf<Double>()
        val bumped = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<String>()
        val stop = Home.observeActivityChanges({ v ->
            versions += v
            if (versions.size >= 2) bumped.complete(Unit)
        }, { m -> ended.complete(m) })
        try {
            withTimeoutOrNull(5_000) { while (versions.isEmpty()) delay(10) }
            assertTrue(versions.isNotEmpty(), "fires once on subscribe")
            ingest(run("chg-${stamp()}", "r", headMs(), RoutineRun.STATE_RAN))
            assertNotNull(withTimeoutOrNull(5_000) { bumped.await() }, "a run save bumps the version; ended=${ended.isCompleted}")
            assertTrue(versions.last() > versions.first(), "the version moves forward: $versions")
        } finally {
            stop()
        }
    }

    // ── empty vs failure ─────────────────────────────────────────────────────────────────────

    @Test
    fun anEmptyRealmIsAnEmptyList_butAFailedOrUnreachableReadIsAFailure() {
        val empty = devicesResult(HomeRead.Ok(emptyList()), HomeRead.Ok(emptyList()))
        assertEquals(true, empty.ok)
        assertEquals(0, empty.devices.unsafeCast<Array<dynamic>>().size)
        assertEquals(0, empty.rooms.unsafeCast<Array<dynamic>>().size)

        val failedStates = devicesResult(HomeRead.Ok(emptyList()), HomeRead.Failed("error_store", "x"))
        assertEquals(false, failedStates.ok, "either relation failing fails the read")
        assertEquals("error_store", failedStates.error.kind)

        val unreachable = automationsResult(unavailable("routines", IllegalStateException("no service")), HomeRead.Ok(emptyMap()))
        assertEquals(false, unreachable.ok)
        assertEquals("error_home_service_unavailable", unreachable.error.kind)
        assertTrue((unreachable.error.message as String).contains("routines"))

        assertEquals(true, automationsResult(HomeRead.Ok(emptyList()), HomeRead.Ok(emptyMap())).ok)
        val failedRuns = automationsResult(HomeRead.Ok(emptyList()), HomeRead.Failed("error_store", "runs"))
        assertEquals(false, failedRuns.ok, "a failed newest-run read fails the list, never `lastRunAtMs: null`")

        // An observe `onError` gets the promise reads' typed error, bare.
        val ended = homeError(HomeWebErrorKeys.STREAM_ENDED.value, HOME_OBSERVE_ENDED)
        assertEquals("error_home_stream_ended", ended.kind)
        assertEquals(HOME_OBSERVE_ENDED, ended.message)
        assertEquals(undefined, ended.ok, "the error itself, not a `{ ok: false }` envelope")
        assertEquals(JSON.stringify(failedRuns.error), JSON.stringify(homeError("error_store", "runs")), "the same shape as a read's `error`")
    }

    // ── read-only ─────────────────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ `web-app-suite.md` §8 items 2 and 9: Home on web is READ-ONLY. The exported surface is exactly
     * the eight reads; a ninth name fails here until the decision is revisited there.
     */
    @Test
    fun theFacadeExposesNoWrite() {
        val proto = js("Object.getPrototypeOf")(Home)
        val names = js("Object.getOwnPropertyNames")(proto).unsafeCast<Array<String>>()
            // Exported members keep their Kotlin name; private ones are mangled (`_…`, `…$`).
            .filter { it != "constructor" && '_' !in it && '$' !in it }
            .sorted()
        assertEquals(
            listOf(
                "activityPage", "automations", "devices", "hubStorage",
                "observeActivityChanges", "observeAutomations", "observeDevices", "observeHubStorage",
            ),
            names,
        )
    }

    private fun assertNoImplementation(why: String, result: TResult<*>) {
        assertTrue(result is TResult.Failure, "$why; got $result")
        assertTrue(result.error.toString().contains("No implementation found for operation"), "$why; got ${result.error}")
    }

    /**
     * Every Home write, and the routine executor, dispatched through the real client: each must fail
     * because NOTHING implements it — not because a half-wired implementation ran.
     */
    @Test
    fun everyHomeWriteAndTheExecutor_haveNoImplementationInATab() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val client = JsRuntime.tenshuClient
        val id = "ro-${stamp()}"
        assertNoImplementation("no device command", client.homeDeviceService.issueDeviceCommand(id, "x", DeviceCommand.VERB_SET_POWER, DeviceCommand.ARG_ON))
        assertNoImplementation("no rename / re-room", client.homeDeviceService.updateHomeDevice(id, name = "n", setName = true))
        assertNoImplementation("no hub command", client.hubManagementService.issueHubCommand(id, "x", "backupNow"))
        assertNoImplementation("no automation save", client.routineService.saveRoutine(id, "n", "UTC", "{}", 1))
        assertNoImplementation("no automation delete", client.routineService.deleteRoutine(id))
        assertNoImplementation("no automation toggle", client.routineService.setRoutineEnabled(id, false))
        assertNoImplementation("no run-now", client.routineService.runRoutineNow(id))
        assertNoImplementation("no routine evaluation", client.routineService.evaluateDueRoutines())

        val ids = listOf(
            IssueDeviceCommand.ID, UpdateHomeDevice.ID, IssueHubCommand.ID, SaveRoutine.ID, DeleteRoutine.ID,
            SetRoutineEnabled.ID, RunRoutineNow.ID, EvaluateDueRoutines.ID,
        )
        for (op in ids) {
            assertNull(implementationFactory(op), "core maps no $op")
            val mapped = JsRuntimeBuilder.orderedPlugins.filter { it.operationFactory(JsRuntime)?.invoke(op) != null }
            assertTrue(mapped.isEmpty(), "no plugin maps $op; got ${mapped.map { it.id }}")
        }
        for (plugin in HomePlugins) assertNull(plugin.operationFactory(JsRuntime), "${plugin.id} maps no operation at all")
    }
}
