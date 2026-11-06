package app.tenshu.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Apple pre-arming's two decisions (CA-8c).
 *
 * ⚠ Its consumer is Kotlin/Native calling `UNUserNotificationCenter`, which nothing here compiles
 * — so this file is the whole of the iOS reminder path that can be checked.
 */
class AppleAlertScheduleTest {

    private val berlin = TimeZone.of("Europe/Berlin")
    private val channel = AlertChannel("reminders", "Reminders", AlertImportance.HIGH)

    private fun alert(id: String, at: String) = ScheduledAlert(
        alert = AppAlert(id = id, channel = channel, title = id),
        deliverAt = LocalDateTime.parse(at).toInstant(berlin),
    )

    // --- the pending cap -----------------------------------------------------

    /**
     * ⚠⚠ The OS silently drops the 65th pending request. Taking a prefix of an *unsorted* list
     * would mean the survivors are whatever order the caller happened to produce — dropping
     * tomorrow morning's alarm in favour of one next week.
     */
    @Test
    fun the_soonest_alerts_survive_the_cap_whatever_order_they_arrive_in() {
        val alerts = listOf(
            alert("next-week", "2026-08-16T09:00"),
            alert("tomorrow", "2026-08-10T07:00"),
            alert("tonight", "2026-08-09T20:00"),
        )

        val kept = AppleAlertSchedule.toSchedule(alerts, limit = 2)

        assertEquals(listOf("tonight", "tomorrow"), kept.map { it.id })
    }

    @Test
    fun the_result_is_in_fire_order() {
        val alerts = listOf(
            alert("c", "2026-08-11T09:00"),
            alert("a", "2026-08-09T09:00"),
            alert("b", "2026-08-10T09:00"),
        )

        assertEquals(listOf("a", "b", "c"), AppleAlertSchedule.toSchedule(alerts).map { it.id })
    }

    /**
     * ⚠ Ties break on id so the surviving set does not shuffle between two runs over the same
     * data — a reschedule that reordered equal alerts would make `AlertReconciler` report churn
     * that is not there.
     */
    @Test
    fun alerts_at_the_same_instant_keep_a_stable_order() {
        val first = listOf(alert("beta", "2026-08-09T09:00"), alert("alpha", "2026-08-09T09:00"))

        assertEquals(
            listOf("alpha", "beta"),
            AppleAlertSchedule.toSchedule(first).map { it.id },
        )
        assertEquals(
            AppleAlertSchedule.toSchedule(first).map { it.id },
            AppleAlertSchedule.toSchedule(first.reversed()).map { it.id },
        )
    }

    @Test
    fun everything_fits_when_it_is_under_the_cap() {
        val alerts = listOf(alert("a", "2026-08-09T09:00"), alert("b", "2026-08-10T09:00"))

        assertEquals(2, AppleAlertSchedule.toSchedule(alerts).size)
        assertEquals(64, AppleAlertSchedule.PENDING_LIMIT)
    }

    /**
     * ⚠ The negative case is the one that matters — `take(0)` already yields empty, but
     * `take(-1)` throws, and a presenter that crashes while arming loses every reminder rather
     * than one.
     */
    @Test
    fun a_non_positive_cap_schedules_nothing_rather_than_throwing() {
        val alerts = listOf(alert("a", "2026-08-09T09:00"))

        assertEquals(emptyList(), AppleAlertSchedule.toSchedule(alerts, limit = 0))
        assertEquals(emptyList(), AppleAlertSchedule.toSchedule(alerts, limit = -1))
    }

    @Test
    fun nothing_to_schedule_is_an_empty_list() {
        assertEquals(emptyList(), AppleAlertSchedule.toSchedule(emptyList()))
    }

    // --- the trigger components ----------------------------------------------

    /**
     * ⚠⚠ **Seconds are carried.** `UNCalendarNotificationTrigger` matches components; omit the
     * second and it fires at the top of the minute, turning "five minutes before" into up to
     * fifty-nine seconds late.
     */
    @Test
    fun the_fire_components_carry_every_field_the_trigger_matches_on() {
        val at = LocalDateTime.parse("2026-08-09T14:35:20").toInstant(berlin)

        assertEquals(
            AlertFireComponents(year = 2026, month = 8, day = 9, hour = 14, minute = 35, second = 20),
            AppleAlertSchedule.fireComponents(at, berlin),
        )
    }

    /**
     * ⚠⚠ The zone is the *device's*, passed in rather than read — an alert is armed against the
     * wall clock the user reads. The same instant in two zones is two different wall times, and
     * that is the whole reason this takes a parameter.
     */
    @Test
    fun the_components_are_the_wall_clock_of_the_zone_it_is_given() {
        val at = LocalDateTime.parse("2026-08-09T14:35:00").toInstant(berlin)

        val utc = AppleAlertSchedule.fireComponents(at, TimeZone.UTC)

        assertEquals(12, utc.hour, "Berlin is UTC+2 in August")
        assertEquals(9, utc.day)
    }

    /** ⚠ Midnight must come back as hour 0, not as an absent field the OS would fill in. */
    @Test
    fun midnight_is_hour_zero() {
        val at = LocalDateTime.parse("2026-08-09T00:00:00").toInstant(berlin)

        val components = AppleAlertSchedule.fireComponents(at, berlin)

        assertEquals(0, components.hour)
        assertEquals(0, components.minute)
        assertEquals(0, components.second)
        assertEquals(9, components.day)
    }
}
