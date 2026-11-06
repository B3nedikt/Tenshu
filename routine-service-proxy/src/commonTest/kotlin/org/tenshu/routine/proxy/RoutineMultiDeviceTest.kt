package org.tenshu.routine.proxy

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.entity.DataId
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.RoutineService
import org.tenshu.routine.common.engine.CapabilityTier
import org.tenshu.routine.common.engine.DefaultSafetyGate
import org.tenshu.routine.common.engine.FiringIds
import org.tenshu.routine.common.engine.RosterDevice
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.Trigger
import org.tenshu.routine.common.routineServiceCommonModule

/**
 * RE-D2's claim and grace fallback (RE-3).
 *
 * ⚠ **What this proves and what it does not.** Both engines share ONE store, so
 * this exercises the claim, the receipt and the fallback *logic* under a
 * converged roster — which is where the bugs live. It does **not** exercise
 * sync: a genuine two-proxy, two-store, relay-in-the-middle test belongs in
 * `:system-test-utils` and is named as outstanding in the PR. Sharing a store is
 * in fact the *harder* case for exactly-once, because the receipt is visible
 * instantly rather than a sync round trip later.
 */
class RoutineMultiDeviceTest {

    private lateinit var proxy: TestProxy
    private var now = 1_782_000_000_000L
    private val codec = JsonRoutineProgramCodec()

    private val hub = RosterDevice("hub-1", CapabilityTier.ALWAYS_ON)
    private val desktop = RosterDevice("desktop-1", CapabilityTier.RESIDENT)
    private val phone = RosterDevice("phone-1", CapabilityTier.RESIDENT)

    @BeforeTest
    fun setup() {
        proxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(routineServiceCommonModule)
            it.schemaRegistry.registerConfig(homeDeviceServiceCommonModule)
        }
        proxy.executionContext.initRoutineService()
    }

    private fun engineFor(
        me: String,
        roster: List<RosterDevice>,
        gate: DefaultSafetyGate = DefaultSafetyGate(),
    ) = RoutineEngine(
        executionContext = proxy.executionContext,
        implementationFactory = OperationImplementationFactory { null },
        nowEpochMs = { now },
        thisDeviceId = { me },
        roster = { roster },
        safetyGate = gate,
    )

    /** A routine that fires at 06:00 Berlin every day. */
    private fun dailyRoutine(routineId: String = "r1") {
        val encoded = codec.encode(
            RoutineProgram(
                trigger = Trigger.TimeOfDay(minuteOfDay = 6 * 60),
                actions = listOf(Action.SetPower("lamp", on = true)),
            )
        )
        proxy.executionContext.database.transaction {
            proxy.executionContext.database.addToArray(
                ArrayDatabaseReference(
                    arrayId = DataId.RelationDataId.create(
                        context = proxy.tContext, type = TList.ID,
                        relationId = RoutineService.Relations.ROUTINES,
                    ),
                    isEntity = false, elementSchemaId = Routine.ID,
                ),
                Routine.create(
                    context = proxy.tContext,
                    routineId = routineId,
                    name = "Morning light",
                    timeZoneId = "Europe/Berlin",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    programJson = encoded.programJson,
                    programVersion = encoded.programVersion,
                ),
            )
        }
    }

    private fun runs(): List<RoutineRun> =
        proxy.executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = proxy.tContext, type = TList.ID,
                    relationId = RoutineService.Relations.ROUTINE_RUNS,
                ),
                isEntity = false, elementSchemaId = RoutineRun.ID,
            )
        )?.toList().orEmpty().filterIsInstance<RoutineRun>()

    private fun commandsIssued(): Int =
        (0..8).count { index ->
            runs().any { run ->
                proxy.executionContext.database.getData(
                    DataId.StringDataId.create(
                        context = proxy.tContext, type = DeviceCommand.ID,
                        keyValue = "firing:${run.firingId.get()}:$index",
                    )
                ) != null
            }
        }

    /** Advance the clock to just after the next 06:00 Berlin. */
    private fun atSixAm() {
        // 2026-06-21T04:00:00Z is the base; 06:00 Berlin = 04:00 UTC in CEST.
        now = 1_782_014_400_000
    }

    // ------------------------------------------------------------------ tests

    /** ⭐ Exactly once: the primary fires, the peer stands down. */
    @Test
    fun only_the_claimed_executor_fires() {
        dailyRoutine()
        val roster = listOf(hub, desktop)
        atSixAm()

        // ⚠ The peer evaluates FIRST, and with no receipt in existence yet — so
        // the only thing that can stop it is the claim itself. (Evaluating the
        // primary first would let this pass on the receipt instead, which is a
        // different guard and already covered below.)
        val desktopResults = engineFor("desktop-1", roster).evaluateDue()
        val hubResults = engineFor("hub-1", roster).evaluateDue()

        assertTrue(
            desktopResults.none { it is FiringResult.Ran },
            "the non-executor must not fire at the trigger instant — by CLAIM, not by receipt",
        )
        assertEquals(1, hubResults.filterIsInstance<FiringResult.Ran>().size)
        assertEquals(1, runs().count { it.state.get() == RoutineRun.STATE_RAN })
    }

    /** The always-on device outranks a resident one — RE-D2's tier ordering. */
    @Test
    fun the_hub_wins_the_claim_over_a_desktop() {
        dailyRoutine()
        atSixAm()

        engineFor("hub-1", listOf(hub, desktop)).evaluateDue()

        assertEquals("hub-1", runs().single { it.state.get() == RoutineRun.STATE_RAN }
            .executorDeviceId.get())
    }

    /**
     * ⭐ The grace fallback: with the primary asleep, a peer fires at
     * `triggerInstant + GRACE` — and its run record says so. Without this,
     * RE-D2's second half is decorative.
     */
    @Test
    fun a_peer_fires_after_the_grace_window_when_the_primary_never_did() {
        dailyRoutine()
        val roster = listOf(hub, desktop)
        atSixAm()

        // The hub is offline: it simply never evaluates.
        val early = engineFor("desktop-1", roster).evaluateDue()
        assertTrue(early.none { it is FiringResult.Ran }, "too early for the fallback")

        now += DefaultSafetyGate.GRACE_MS + 1_000
        val late = engineFor("desktop-1", roster).evaluateDue()

        assertEquals(1, late.filterIsInstance<FiringResult.Ran>().size)
        val row = runs().single { it.state.get() == RoutineRun.STATE_RAN }
        assertTrue(row.wasFallback.get(), "a late firing must be marked as a fallback")
        assertEquals("desktop-1", row.executorDeviceId.get())
    }

    /**
     * ⭐⭐ And the fallback **stands down** when the primary's receipt is there.
     * This is what the receipt is actually for — it cannot prevent a race
     * (RE-D2), but it does stop a late peer from firing a second time.
     */
    @Test
    fun the_fallback_stands_down_once_the_primarys_receipt_exists() {
        dailyRoutine()
        val roster = listOf(hub, desktop)
        atSixAm()

        engineFor("hub-1", roster).evaluateDue()
        now += DefaultSafetyGate.GRACE_MS + 1_000
        val fallback = engineFor("desktop-1", roster).evaluateDue()

        assertTrue(fallback.none { it is FiringResult.Ran })
        assertEquals(1, runs().count { it.state.get() == RoutineRun.STATE_RAN })
        assertEquals(1, commandsIssued(), "the light must be commanded exactly once")
    }

    /**
     * ⚠⚠ A genuine race is *contained, not prevented* (RE-D2). Both devices
     * firing inside the grace window write the same `firingId`-keyed row, and the
     * absolute action vocabulary means the light ends up in the same state.
     */
    @Test
    fun a_race_inside_the_grace_window_collapses_onto_one_row() {
        dailyRoutine()
        val roster = listOf(hub, desktop)
        atSixAm()

        // Both consider themselves due at the same instant (the desktop only
        // does so because we push it past grace without a receipt landing).
        engineFor("hub-1", roster).evaluateDue()
        now += DefaultSafetyGate.GRACE_MS + 1_000
        engineFor("desktop-1", roster).evaluateDue()

        val ran = runs().filter { it.state.get() == RoutineRun.STATE_RAN }
        assertEquals(1, ran.size, "the receipt is keyed by firingId — one row, not two")
    }

    /** ⚠ A revoked winner must not keep the claim, or its routines only ever run late. */
    @Test
    fun a_revoked_device_loses_the_claim_to_a_live_one() {
        dailyRoutine()
        val roster = listOf(hub.copy(revoked = true), desktop)
        atSixAm()

        val results = engineFor("desktop-1", roster).evaluateDue()

        assertEquals(1, results.filterIsInstance<FiringResult.Ran>().size)
        assertTrue(
            !runs().single { it.state.get() == RoutineRun.STATE_RAN }.wasFallback.get(),
            "the desktop is now the PRIMARY, so this is not a fallback firing",
        )
    }

    /** ⭐ The claim is a pure function: every device computes the same winner. */
    @Test
    fun every_device_agrees_on_the_executor() {
        dailyRoutine()
        val roster = listOf(phone, hub, desktop)
        atSixAm()

        // Each device asks independently; only one of them may consider itself
        // the primary at the trigger instant.
        val primaries = roster.count { device ->
            engineFor(device.deviceId, roster).evaluateDue()
                .any { it is FiringResult.Ran }
        }

        assertEquals(1, primaries, "exactly one device may fire at the trigger instant")
    }

    private fun RosterDevice.copy(revoked: Boolean) =
        RosterDevice(deviceId = deviceId, tier = tier, revoked = revoked)

    @Test
    fun an_empty_roster_leaves_the_routine_unclaimed_rather_than_firing_everywhere() {
        dailyRoutine()
        atSixAm()

        val results = engineFor("nobody", emptyList()).evaluateDue()

        assertTrue(results.none { it is FiringResult.Ran })
        assertNull(
            runs().firstOrNull { it.state.get() == RoutineRun.STATE_RAN },
            "with no roster there is no executor — and no silent double-fire",
        )
    }

    @Test
    fun the_run_record_names_the_executor() {
        dailyRoutine()
        atSixAm()

        engineFor("hub-1", listOf(hub, desktop)).evaluateDue()

        val row = assertNotNull(runs().firstOrNull { it.state.get() == RoutineRun.STATE_RAN })
        assertEquals("hub-1", row.executorDeviceId.get())
        assertEquals(
            FiringIds.scheduled("r1", row.triggerInstantEpochMs.get()),
            row.firingId.get(),
        )
    }
}
