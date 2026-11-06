package org.tenshu.routine.common.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.tenshu.routine.common.program.SunEvent
import org.tenshu.routine.common.program.Trigger

/**
 * Where a routine's schedule is anchored (`routines-engine.md` §4.5).
 *
 * ⭐⭐ **This is passed in, and the executing device's own zone and coordinates
 * are not parameters of [FiringCalculator] at all.** RE-D3's sharpest trap — a
 * laptop travelling in another timezone computing a different instant for
 * "22:30", or a desktop 400 km north computing a different sunset — becomes
 * *unrepresentable* rather than merely documented. It is the single most likely
 * way to build a subtly broken engine, so the type system carries it.
 */
class RoutineLocale(
    val timeZoneId: String,
    val homeLatE5: Long,
    val homeLonE5: Long,
) {
    val hasCoordinates: Boolean
        get() = homeLatE5 != NO_COORDINATE && homeLonE5 != NO_COORDINATE

    val latitude: Double get() = homeLatE5 / 1e5
    val longitude: Double get() = homeLonE5 / 1e5

    companion object {
        const val NO_COORDINATE = -1_000_000_000L
    }
}

fun interface FiringCalculator {
    /**
     * The next instant at or after [after] at which [trigger] fires, or `null`
     * when it never will (a non-scheduled trigger class, an unresolvable
     * timezone, or a sun event that does not occur at this latitude on any
     * candidate day — polar summer and winter are real).
     */
    fun nextFiring(trigger: Trigger, locale: RoutineLocale, after: Instant): Instant?
}

class DefaultFiringCalculator : FiringCalculator {

    override fun nextFiring(trigger: Trigger, locale: RoutineLocale, after: Instant): Instant? {
        val zone = runCatching { TimeZone.of(locale.timeZoneId) }.getOrNull() ?: return null
        return when (trigger) {
            is Trigger.TimeOfDay -> nextTimeOfDay(trigger, zone, after)
            is Trigger.Sun -> nextSun(trigger, locale, zone, after)
            // Event-driven classes are not scheduled: they arrive, they are not awaited.
            is Trigger.DeviceEvent, is Trigger.Geofence, is Trigger.VoicePhrase -> null
        }
    }

    private fun nextTimeOfDay(
        trigger: Trigger.TimeOfDay,
        zone: TimeZone,
        after: Instant,
    ): Instant? {
        val startDate = after.toLocalDateTime(zone).date
        // 8 days covers a full week of day-filtering plus the current day, and
        // absorbs a DST transition landing on the boundary.
        for (dayOffset in 0..8) {
            val date = startDate.plus(dayOffset, kotlinx.datetime.DateTimeUnit.DAY)
            if (!matchesDays(date, trigger.days)) continue
            val local = LocalDateTime(
                date = date,
                time = LocalTime(trigger.minuteOfDay / 60, trigger.minuteOfDay % 60),
            )
            // ⚠ A local time that does not exist (spring-forward gap) or exists
            // twice (autumn fall-back) is resolved by kotlinx-datetime's own
            // rule. Deliberately not hand-rolled: a routine firing once at a
            // slightly shifted instant beats an engine with its own DST theory.
            val instant = local.toInstant(zone)
            if (instant > after) return instant
        }
        return null
    }

    private fun matchesDays(date: LocalDate, days: Set<Int>): Boolean =
        days.isEmpty() || date.dayOfWeek.isoDayNumber in days

    private fun nextSun(
        trigger: Trigger.Sun,
        locale: RoutineLocale,
        zone: TimeZone,
        after: Instant,
    ): Instant? {
        if (!locale.hasCoordinates) return null
        val startDate = after.toLocalDateTime(zone).date
        // A generous horizon: at high latitudes a sun event can be absent for
        // months, and returning null after a bounded search is the honest answer.
        for (dayOffset in 0..370) {
            val date = startDate.plus(dayOffset, kotlinx.datetime.DateTimeUnit.DAY)
            val event = sunEventUtc(date, zone, locale.latitude, locale.longitude, trigger.event)
                ?: continue
            val instant = event + (trigger.offsetMinutes * 60_000L).milliseconds
            if (instant > after) return instant
        }
        return null
    }

    /**
     * NOAA's low-precision sunrise/sunset algorithm — accurate to well under a
     * minute at temperate latitudes, which is far finer than a smart-home
     * routine can act on anyway.
     *
     * @return the event's UTC instant, or `null` when the sun neither rises nor
     *   sets on that date at that latitude (polar day / polar night).
     */
    private fun sunEventUtc(
        date: LocalDate,
        zone: TimeZone,
        latitude: Double,
        longitude: Double,
        event: SunEvent,
    ): Instant? {
        val dayStart = date.atStartOfDayIn(zone)
        val julianDay = dayStart.toEpochMilliseconds() / 86_400_000.0 + 2_440_587.5
        // ⚠ `n` is the integer *day number* since J2000, not a fractional Julian
        // day. Feeding the fraction through here silently shifts the result by
        // hours — it computed a Berlin midsummer "sunset" of 07:34 before this
        // ceil, which is the failure mode to watch for if this is ever touched.
        val n = ceil(julianDay - 2_451_545.0 + 0.0008)

        val meanSolarNoon = n - longitude / 360.0
        val meanAnomaly = (357.5291 + 0.98560028 * meanSolarNoon).mod(360.0)
        val center = 1.9148 * sinDeg(meanAnomaly) +
            0.0200 * sinDeg(2 * meanAnomaly) +
            0.0003 * sinDeg(3 * meanAnomaly)
        val eclipticLongitude = (meanAnomaly + center + 180.0 + 102.9372).mod(360.0)
        val solarTransit = 2_451_545.0 + meanSolarNoon +
            0.0053 * sinDeg(meanAnomaly) - 0.0069 * sinDeg(2 * eclipticLongitude)
        val declination = asin(sinDeg(eclipticLongitude) * sinDeg(23.44)) * RAD_TO_DEG

        // -0.833° accounts for atmospheric refraction plus the solar disc radius.
        val cosHourAngle = (sinDeg(-0.833) - sinDeg(latitude) * sinDeg(declination)) /
            (cosDeg(latitude) * cosDeg(declination))
        if (abs(cosHourAngle) > 1.0) return null

        val hourAngle = acos(cosHourAngle) * RAD_TO_DEG
        val julianEvent = when (event) {
            SunEvent.SUNSET -> solarTransit + hourAngle / 360.0
            SunEvent.SUNRISE -> solarTransit - hourAngle / 360.0
        }
        val epochMs = ((julianEvent - 2_440_587.5) * 86_400_000.0).toLong()
        return Instant.fromEpochMilliseconds(epochMs)
    }

    private fun sinDeg(degrees: Double) = sin(degrees * DEG_TO_RAD)
    private fun cosDeg(degrees: Double) = cos(degrees * DEG_TO_RAD)

    private companion object {
        const val DEG_TO_RAD = 0.017453292519943295
        const val RAD_TO_DEG = 57.29577951308232
    }
}
