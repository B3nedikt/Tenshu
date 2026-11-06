package org.tenshu.routine.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.routine.common.engine.CapabilityTier
import org.tenshu.routine.common.engine.DefaultTriggerObservability
import org.tenshu.routine.common.engine.RosterDevice
import org.tenshu.routine.common.engine.TierThenIdExecutorClaim
import org.tenshu.routine.common.program.GeofenceDirection
import org.tenshu.routine.common.program.StateMatch
import org.tenshu.routine.common.program.SunEvent
import org.tenshu.routine.common.program.Trigger

class ExecutorClaimTest {

    private val claim = TierThenIdExecutorClaim()

    private val hub = RosterDevice("hub-1", CapabilityTier.ALWAYS_ON)
    private val desktop = RosterDevice("desktop-1", CapabilityTier.RESIDENT)
    private val phone = RosterDevice("phone-1", CapabilityTier.MOBILE)

    @Test
    fun the_always_on_device_wins() {
        assertEquals("hub-1", claim.executorFor("r1", listOf(phone, desktop, hub)))
    }

    @Test
    fun a_resident_desktop_beats_a_phone() {
        assertEquals("desktop-1", claim.executorFor("r1", listOf(phone, desktop)))
    }

    @Test
    fun ties_break_stably_on_device_id() {
        val a = RosterDevice("aaa", CapabilityTier.RESIDENT)
        val b = RosterDevice("bbb", CapabilityTier.RESIDENT)

        assertEquals("aaa", claim.executorFor("r1", listOf(b, a)))
        assertEquals("aaa", claim.executorFor("r1", listOf(a, b)))
    }

    /**
     * ⭐ The property the whole of RE-D2 rests on: every converged device
     * computes the same answer, regardless of roster ordering, with no messages
     * exchanged.
     */
    @Test
    fun the_claim_is_identical_under_any_roster_ordering() {
        val roster = listOf(hub, desktop, phone)
        val permutations = listOf(
            listOf(hub, desktop, phone),
            listOf(phone, hub, desktop),
            listOf(desktop, phone, hub),
            roster.reversed(),
        )

        val answers = permutations.map { claim.executorFor("routine-x", it) }.toSet()

        assertEquals(1, answers.size, "claim must not depend on roster order")
    }

    @Test
    fun a_revoked_device_never_wins() {
        val revokedHub = RosterDevice("hub-1", CapabilityTier.ALWAYS_ON, revoked = true)

        assertEquals("desktop-1", claim.executorFor("r1", listOf(revokedHub, desktop)))
    }

    @Test
    fun an_empty_roster_yields_no_executor() {
        assertNull(claim.executorFor("r1", emptyList()))
        assertNull(claim.executorFor("r1", listOf(RosterDevice("x", CapabilityTier.MOBILE, revoked = true))))
    }
}

class TriggerObservabilityTest {

    private val hub = DefaultTriggerObservability(hostsDeviceBridge = true, hasLocationSource = false)
    private val phone = DefaultTriggerObservability(hostsDeviceBridge = false, hasLocationSource = true)
    private val desktop = DefaultTriggerObservability(hostsDeviceBridge = false, hasLocationSource = false)

    private val deviceEvent = Trigger.DeviceEvent("d1", StateMatch(field = "power", value = "on"))
    private val geofence = Trigger.Geofence("place-1", GeofenceDirection.ARRIVE)

    @Test
    fun everyone_can_observe_time_and_sun() {
        listOf(hub, phone, desktop).forEach {
            assertTrue(it.canObserve(Trigger.TimeOfDay(600)))
            assertTrue(it.canObserve(Trigger.Sun(SunEvent.SUNSET)))
        }
    }

    /** ⭐ Only the process hosting the bridge sees a device event in milliseconds. */
    @Test
    fun only_a_bridge_host_observes_device_events() {
        assertTrue(hub.canObserve(deviceEvent))
        assertFalse(phone.canObserve(deviceEvent))
        assertFalse(desktop.canObserve(deviceEvent))
    }

    /** ⚖️ A hub can never fire a geofence — only the phone that crossed knows. */
    @Test
    fun only_a_location_capable_device_observes_geofences() {
        assertTrue(phone.canObserve(geofence))
        assertFalse(hub.canObserve(geofence))
        assertFalse(desktop.canObserve(geofence))
    }

    /** A voice phrase arrives as an invocation; nothing arms it in the background. */
    @Test
    fun nobody_arms_a_voice_phrase() {
        listOf(hub, phone, desktop).forEach {
            assertFalse(it.canObserve(Trigger.VoicePhrase("goodnight")))
        }
    }
}
