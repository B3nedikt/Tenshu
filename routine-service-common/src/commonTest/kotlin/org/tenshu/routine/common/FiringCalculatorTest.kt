package org.tenshu.routine.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.tenshu.routine.common.engine.DefaultFiringCalculator
import org.tenshu.routine.common.engine.RoutineLocale
import org.tenshu.routine.common.program.SunEvent
import org.tenshu.routine.common.program.Trigger

class FiringCalculatorTest {

    private val calculator = DefaultFiringCalculator()

    private val berlin = RoutineLocale(
        timeZoneId = "Europe/Berlin",
        homeLatE5 = 5_252_000,
        homeLonE5 = 1_340_500,
    )

    private fun at(iso: String): Instant = Instant.parse(iso)

    @Test
    fun next_time_of_day_is_today_when_still_ahead() {
        // 08:00 UTC = 10:00 Berlin (CEST); the routine fires at 22:30 local.
        val next = calculator.nextFiring(
            trigger = Trigger.TimeOfDay(minuteOfDay = 22 * 60 + 30),
            locale = berlin,
            after = at("2026-07-01T08:00:00Z"),
        )

        val local = assertNotNull(next).toLocalDateTime(TimeZone.of("Europe/Berlin"))
        assertEquals(22, local.hour)
        assertEquals(30, local.minute)
        assertEquals(1, local.date.day)
    }

    @Test
    fun next_time_of_day_rolls_to_tomorrow_when_already_past() {
        val next = calculator.nextFiring(
            trigger = Trigger.TimeOfDay(minuteOfDay = 6 * 60),
            locale = berlin,
            after = at("2026-07-01T08:00:00Z"),
        )

        val local = assertNotNull(next).toLocalDateTime(TimeZone.of("Europe/Berlin"))
        assertEquals(6, local.hour)
        assertEquals(2, local.date.day)
    }

    @Test
    fun day_filter_skips_to_the_next_matching_weekday() {
        // 2026-07-01 is a Wednesday (ISO 3). Ask for Saturday (6) only.
        val next = calculator.nextFiring(
            trigger = Trigger.TimeOfDay(minuteOfDay = 9 * 60, days = setOf(6)),
            locale = berlin,
            after = at("2026-07-01T08:00:00Z"),
        )

        val local = assertNotNull(next).toLocalDateTime(TimeZone.of("Europe/Berlin"))
        assertEquals(4, local.date.day)
        assertEquals(9, local.hour)
    }

    /**
     * ⚠⚠ RE-D3's sharpest trap, pinned: the instant depends only on the
     * **routine's** locale. The calculator has no device-zone parameter at all
     * (that is the point of `RoutineLocale`), so this asserts the observable
     * half — the same locale evaluated from wildly different "current" instants
     * in the same day yields the same firing.
     */
    @Test
    fun the_firing_instant_depends_only_on_the_routines_locale() {
        val trigger = Trigger.TimeOfDay(minuteOfDay = 22 * 60 + 30)

        val fromMorning = calculator.nextFiring(trigger, berlin, at("2026-07-01T04:00:00Z"))
        val fromNoon = calculator.nextFiring(trigger, berlin, at("2026-07-01T11:00:00Z"))

        assertEquals(fromMorning, fromNoon)
    }

    @Test
    fun a_routine_in_another_zone_fires_at_a_different_instant() {
        val trigger = Trigger.TimeOfDay(minuteOfDay = 22 * 60 + 30)
        val tokyo = RoutineLocale("Asia/Tokyo", berlin.homeLatE5, berlin.homeLonE5)

        val inBerlin = calculator.nextFiring(trigger, berlin, at("2026-07-01T00:00:00Z"))
        val inTokyo = calculator.nextFiring(trigger, tokyo, at("2026-07-01T00:00:00Z"))

        assertTrue(inBerlin != inTokyo, "22:30 is a different instant in Berlin and Tokyo")
    }

    @Test
    fun sunset_is_later_in_june_than_in_december() {
        val sunset = Trigger.Sun(SunEvent.SUNSET)
        val zone = TimeZone.of("Europe/Berlin")

        val june = assertNotNull(calculator.nextFiring(sunset, berlin, at("2026-06-21T00:00:00Z")))
            .toLocalDateTime(zone)
        val december = assertNotNull(calculator.nextFiring(sunset, berlin, at("2026-12-21T00:00:00Z")))
            .toLocalDateTime(zone)

        val juneMinutes = june.hour * 60 + june.minute
        val decemberMinutes = december.hour * 60 + december.minute
        assertTrue(
            juneMinutes > decemberMinutes,
            "June sunset $juneMinutes should be later than December $decemberMinutes",
        )
    }

    @Test
    fun berlin_midsummer_sunset_lands_in_the_expected_evening_window() {
        val next = calculator.nextFiring(
            trigger = Trigger.Sun(SunEvent.SUNSET),
            locale = berlin,
            after = at("2026-06-21T00:00:00Z"),
        )

        val local = assertNotNull(next).toLocalDateTime(TimeZone.of("Europe/Berlin"))
        val minutes = local.hour * 60 + local.minute
        // Real value is ~21:33 local; a generous window keeps this from being a
        // brittle assertion about an approximation's last digit.
        assertTrue(minutes in (21 * 60)..(22 * 60), "midsummer sunset at $local")
    }

    @Test
    fun the_sun_offset_shifts_the_firing() {
        val plain = calculator.nextFiring(
            Trigger.Sun(SunEvent.SUNSET), berlin, at("2026-06-21T00:00:00Z"),
        )
        val early = calculator.nextFiring(
            Trigger.Sun(SunEvent.SUNSET, offsetMinutes = -30), berlin, at("2026-06-21T00:00:00Z"),
        )

        assertNotNull(plain); assertNotNull(early)
        assertEquals(30 * 60 * 1000L, plain.toEpochMilliseconds() - early.toEpochMilliseconds())
    }

    @Test
    fun sunrise_precedes_sunset_on_the_same_day() {
        val after = at("2026-06-21T00:00:00Z")
        val sunrise = assertNotNull(calculator.nextFiring(Trigger.Sun(SunEvent.SUNRISE), berlin, after))
        val sunset = assertNotNull(calculator.nextFiring(Trigger.Sun(SunEvent.SUNSET), berlin, after))

        assertTrue(sunrise < sunset)
    }

    /** Polar day is real, and "no sunset for months" must be `null`, not a wrong instant. */
    @Test
    fun polar_day_yields_no_sunset_within_the_horizon() {
        val svalbard = RoutineLocale("Europe/Oslo", homeLatE5 = 7_822_000, homeLonE5 = 1_562_000)

        val next = calculator.nextFiring(
            trigger = Trigger.Sun(SunEvent.SUNSET),
            locale = svalbard,
            after = at("2026-06-01T00:00:00Z"),
        )

        // A sunset does return eventually (late August), so assert it is far
        // away rather than absent — the honest property is "not tonight".
        assertTrue(
            next == null || next > at("2026-08-01T00:00:00Z"),
            "Svalbard has no sunset in June; got $next",
        )
    }

    @Test
    fun a_sun_trigger_without_coordinates_never_fires() {
        val noCoords = RoutineLocale("Europe/Berlin", RoutineLocale.NO_COORDINATE, RoutineLocale.NO_COORDINATE)

        assertNull(calculator.nextFiring(Trigger.Sun(SunEvent.SUNSET), noCoords, at("2026-07-01T00:00:00Z")))
    }

    @Test
    fun an_unresolvable_timezone_never_fires() {
        val bogus = RoutineLocale("Mars/Olympus_Mons", berlin.homeLatE5, berlin.homeLonE5)

        assertNull(calculator.nextFiring(Trigger.TimeOfDay(600), bogus, at("2026-07-01T00:00:00Z")))
    }

    /** Event-driven classes are not scheduled: they arrive, they are not awaited. */
    @Test
    fun event_driven_triggers_are_never_scheduled() {
        val after = at("2026-07-01T00:00:00Z")

        assertNull(calculator.nextFiring(Trigger.DeviceEvent("d", match(  )), berlin, after))
        assertNull(calculator.nextFiring(Trigger.VoicePhrase("goodnight"), berlin, after))
    }

    private fun match() = org.tenshu.routine.common.program.StateMatch(field = "power", value = "on")
}
