package app.tenshu.fit.data

import app.tenshu.fit.data.xml.JvmXmlReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Google Takeout import (§9.6b) — the only route by which a user's real Google Fit *history* can
 * reach the app, because Google Fit never feeds Health Connect.
 *
 * Fixtures are synthetic: these are health rows, and a test is a bad place for anyone's real ones.
 * They mirror the *shape* Google exports — the column names, the quoting, the ISO dates — because
 * that shape is the whole risk surface. Google reorders and renames these columns between export
 * versions, and a reader that silently maps the wrong column into the wrong metric produces
 * plausible garbage rather than an error, which is the worst outcome available for health data.
 */
class TakeoutParserTest {

    // The real JDK XML reader, so the TCX assertions mean something (the parser tests next door
    // do the same). The CSV path is pure commonMain and needs no reader at all.
    private val parser = TakeoutParser(ActivityFileParser(xml = JvmXmlReader()))

    // ── which members are even worth extracting ───────────────────────────────────────

    @Test
    fun onlyTheUsableMembersOfAnArchiveAreExtracted() {
        // Takeout is whatever the user ticked — it can be gigabytes of photos and mail. Deciding by
        // name *before* reading the bytes is what keeps a huge export from being read into memory.
        assertTrue(parser.isRelevant("Takeout/Fit/Activities/2026-01-04T08_00_00Z_running.tcx"))
        assertTrue(parser.isRelevant("Takeout/Fit/Daily activity metrics/Daily activity metrics.csv"))
        assertTrue(parser.isRelevant("Takeout/Fit/Activities/ride.gpx"))

        assertFalse(parser.isRelevant("Takeout/Photos/IMG_0001.jpg"))
        assertFalse(parser.isRelevant("Takeout/Fit/All Data/derived_steps.json"))
        assertFalse(parser.isRelevant("Takeout/Fit/Activities/"))
        // The date-named per-day segment CSVs — a real 10-year export has thousands, and keeping them
        // once pushed the aggregate CSV past the member cap and silently dropped the whole history.
        assertFalse(parser.isRelevant("Takeout/Fit/Daily activity metrics/2016-04-26.csv"))
        assertFalse(parser.isRelevant("Takeout/Fit/Daily activity metrics/2026-07-13.csv"))
        // A zip built on a Mac carries a shadow tree of AppleDouble stubs mirroring every real name;
        // parsed, they contribute nothing but they do double the extraction work.
        assertFalse(parser.isRelevant("__MACOSX/Takeout/Fit/Activities/._ride.tcx"))
    }

    // ── the daily-metrics CSV: the long tail Health Connect can never give back ────────

    @Test
    fun theDailyMetricsCsvIsReadByColumnNameNotPosition() {
        // Deliberately *not* Google's column order, and with a column we do not know wedged in the
        // middle: a positional reader passes its own fixture and then mangles a real export.
        val csv = """
            Date,Heart Points,Step count,Some Future Column,Distance (m),Move Minutes count,Calories (kcal)
            2026-01-04,42,8000,ignored,6100.5,55,320.0
        """.trimIndent().encodeToByteArray()

        val health = parser.parse(listOf(ActivityFile("Daily activity metrics.csv", csv)))

        val day = health.dailyActivities.single()
        assertEquals("2026-01-04", day.date)
        assertEquals(8_000, day.steps)
        assertEquals(6_101, day.distanceM, "distance rounds to whole metres")
        assertEquals(55, day.activeMinutes)
        assertEquals(1_339, day.activeEnergyKj, "320 kcal → kJ")
    }

    @Test
    fun aDayGoogleExportedEmptyIsNotWrittenAsARealZero() {
        // A day with no numbers is a day the phone was not carried — not a day of zero steps. Writing
        // it would fabricate a fact, and the projection would render a real trough that never happened.
        val csv = """
            Date,Step count,Distance (m),Move Minutes count,Calories (kcal)
            2026-01-04,8000,6100,55,320
            2026-01-05,,,,
            2026-01-06,0,0,0,0
        """.trimIndent().encodeToByteArray()

        val health = parser.parse(listOf(ActivityFile("Daily activity metrics.csv", csv)))

        assertEquals(listOf("2026-01-04"), health.dailyActivities.map { it.date })
    }

    @Test
    fun theWeightColumnBecomesATimeSeriesNotJustTheLatestReading() {
        // §12 B2: weight is emitted as a series (one reading per day it was recorded) so the Body
        // detail can chart the trend — a day with no weight simply carries none.
        val csv = """
            Date,Step count,Average weight (kg)
            2026-01-04,8000,75.4
            2026-01-05,9100,
            2026-01-06,7200,74.9
            2026-01-07,8100,74.2
        """.trimIndent().encodeToByteArray()

        val health = parser.parse(listOf(ActivityFile("Daily activity metrics.csv", csv)))

        val weights = health.samples.filter { it.metric == "Weight" }
        assertEquals(3, weights.size, "one reading per day that carried a weight (2026-01-05 had none)")
        assertEquals(setOf("75.4", "74.9", "74.2"), weights.map { it.value }.toSet())
    }

    @Test
    fun aQuotedCellContainingACommaDoesNotShiftEveryColumnAfterIt() {
        val csv = """
            Date,Note,Step count
            2026-01-04,"walked, then ran",8000
        """.trimIndent().encodeToByteArray()

        val health = parser.parse(listOf(ActivityFile("Daily activity metrics.csv", csv)))

        assertEquals(8_000, health.dailyActivities.single().steps)
    }

    @Test
    fun aCsvWithoutADateColumnIsSkippedRatherThanMisread() {
        // Takeout also writes per-day segment files that restate the same totals; reading those as
        // well would double every day. They are told apart by the absence of a Date column.
        val segments = """
            Start time,End time,Step count
            2026-01-04T08:00:00Z,2026-01-04T09:00:00Z,2000
        """.trimIndent().encodeToByteArray()

        val health = parser.parse(listOf(ActivityFile("2026-01-04.csv", segments)))

        assertTrue(health.isEmpty, "a segment file must contribute nothing, not a bogus day")
    }

    // ── the whole archive ─────────────────────────────────────────────────────────────

    @Test
    fun anArchiveMergesItsActivitiesAndItsDailyMetrics() {
        val csv = """
            Date,Step count,Distance (m),Move Minutes count,Calories (kcal)
            2026-01-04,8000,6100,55,320
            2026-01-05,9100,6900,61,355
        """.trimIndent().encodeToByteArray()

        val health = parser.parse(
            listOf(
                ActivityFile("Takeout/Fit/Daily activity metrics/Daily activity metrics.csv", csv),
                ActivityFile("Takeout/Fit/Activities/run.tcx", TCX.encodeToByteArray()),
            ),
        )

        assertEquals(2, health.dailyActivities.size)
        assertEquals(1, health.workouts.size)
        assertEquals("Running", health.workouts.single().activityType)
        assertEquals(3, health.count)
    }

    @Test
    fun oneUnreadableMemberDoesNotSinkTheWholeImport() {
        val csv = """
            Date,Step count
            2026-01-04,8000
        """.trimIndent().encodeToByteArray()

        val health = parser.parse(
            listOf(
                ActivityFile("garbage.tcx", byteArrayOf(0, 1, 2, 3)),
                ActivityFile("Daily activity metrics.csv", csv),
            ),
        )

        assertEquals(1, health.dailyActivities.size, "the good member still imports")
    }

    private companion object {
        val TCX = """
            <?xml version="1.0"?>
            <TrainingCenterDatabase>
              <Activities>
                <Activity Sport="Running">
                  <Id>2026-01-04T08:00:00Z</Id>
                  <Lap StartTime="2026-01-04T08:00:00Z">
                    <TotalTimeSeconds>1800</TotalTimeSeconds>
                    <DistanceMeters>5000</DistanceMeters>
                    <Calories>300</Calories>
                  </Lap>
                </Activity>
              </Activities>
            </TrainingCenterDatabase>
        """.trimIndent()
    }
}
